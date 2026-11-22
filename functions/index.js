"use strict";
// Liner Notes' Cloud Functions. Everything that needs a secret runs here, so the APK carries only
// public values. Phase 0: the Last.fm and Spotify sign-in swaps, finding an album on Spotify, and
// the test playlist that proves POST /playlists/{id}/items works for this app.
//
// Where things live in Firestore:
//   users/{uid}                 name, lastSeenAt (the app writes these);
//                               lastfm {username, connectedAt}, spotify {displayName, connectedAt,
//                               expiresAt} (only these Functions write them; the rules stop the app)
//   users/{uid}/private/tokens  Last.fm session key, Spotify refresh token. No client can read it.

const { onCall, HttpsError } = require("firebase-functions/v2/https");
const { setGlobalOptions } = require("firebase-functions/v2");
const { defineSecret, defineString } = require("firebase-functions/params");
const logger = require("firebase-functions/logger");
const { initializeApp } = require("firebase-admin/app");
const { getFirestore, FieldValue, Timestamp } = require("firebase-admin/firestore");

const lastfm = require("./lib/lastfm");
const spotify = require("./lib/spotify");

initializeApp();
const db = getFirestore();

setGlobalOptions({ maxInstances: 5 });

// Public values come from functions/.env, which CI writes from the repo's Actions variables.
const LASTFM_API_KEY = defineString("LASTFM_API_KEY");
const SPOTIFY_CLIENT_ID = defineString("SPOTIFY_CLIENT_ID");
// Secrets live in Secret Manager only (firebase functions:secrets:set NAME).
const LASTFM_SHARED_SECRET = defineSecret("LASTFM_SHARED_SECRET");
const SPOTIFY_CLIENT_SECRET = defineSecret("SPOTIFY_CLIENT_SECRET");

const SPOTIFY_REDIRECT_URI = "linernotes://spotify-callback";
const SPOTIFY_TOKEN_DAYS = 182; // refresh tokens stop working 6 months after the first sign-in

function requireUid(request) {
  const uid = request.auth && request.auth.uid;
  if (!uid) throw new HttpsError("unauthenticated", "Sign in with Google first.");
  return uid;
}

function requireString(data, key, max = 2000) {
  const v = data && data[key];
  if (typeof v !== "string" || !v.trim() || v.length > max) {
    throw new HttpsError("invalid-argument", `Missing ${key}.`);
  }
  return v.trim();
}

const userDoc = (uid) => db.collection("users").doc(uid);
const tokensDoc = (uid) => userDoc(uid).collection("private").doc("tokens");

/** Turns a library error into something the app can show, and logs the detail. */
function toHttpsError(where, err) {
  if (err instanceof HttpsError) return err;
  logger.error(where, { message: err.message, status: err.status, lastfmCode: err.lastfmCode });
  if (err.lastfmCode === 4 || err.lastfmCode === 14 || err.lastfmCode === 15) {
    return new HttpsError("failed-precondition", "Last.fm didn't accept that approval. Try connecting again.");
  }
  if (err instanceof spotify.SpotifyError) {
    if (err.body && err.body.error === "invalid_grant") {
      return new HttpsError("failed-precondition", "Spotify needs you to connect again.");
    }
    if (err.status === 403) {
      return new HttpsError("permission-denied", "Spotify refused. Is this account on the app's user list?");
    }
    if (err.status === 429) return new HttpsError("resource-exhausted", "Spotify asked us to slow down.");
  }
  return new HttpsError("internal", "Something went wrong talking to the music services.");
}

// ---- Last.fm -------------------------------------------------------------------------------

exports.lastfmConnect = onCall({ secrets: [LASTFM_SHARED_SECRET] }, async (request) => {
  const uid = requireUid(request);
  const token = requireString(request.data, "token", 200);
  try {
    const session = await lastfm.getSession(LASTFM_API_KEY.value(), LASTFM_SHARED_SECRET.value(), token);
    const now = FieldValue.serverTimestamp();
    await tokensDoc(uid).set({ lastfmSessionKey: session.key, lastfmConnectedAt: now }, { merge: true });
    await userDoc(uid).set({ lastfm: { username: session.name, connectedAt: now } }, { merge: true });
    return { username: session.name };
  } catch (err) {
    throw toHttpsError("lastfmConnect", err);
  }
});

// ---- Spotify -------------------------------------------------------------------------------

exports.spotifyConnect = onCall({ secrets: [SPOTIFY_CLIENT_SECRET] }, async (request) => {
  const uid = requireUid(request);
  const code = requireString(request.data, "code");
  const redirectUri = (request.data && request.data.redirectUri) || SPOTIFY_REDIRECT_URI;
  if (redirectUri !== SPOTIFY_REDIRECT_URI) throw new HttpsError("invalid-argument", "Unexpected redirect.");
  try {
    const tokens = await spotify.exchangeCode(SPOTIFY_CLIENT_ID.value(), SPOTIFY_CLIENT_SECRET.value(), code, redirectUri);
    const profile = await spotify.me(tokens.access_token);
    const now = FieldValue.serverTimestamp();
    const expiresAt = Timestamp.fromMillis(Date.now() + SPOTIFY_TOKEN_DAYS * 86_400_000);
    await tokensDoc(uid).set(
      { spotifyRefreshToken: tokens.refresh_token, spotifyUserId: profile.id, spotifyConnectedAt: now },
      { merge: true },
    );
    const displayName = profile.display_name || profile.id;
    await userDoc(uid).set({ spotify: { displayName, connectedAt: now, expiresAt } }, { merge: true });
    return { displayName };
  } catch (err) {
    throw toHttpsError("spotifyConnect", err);
  }
});

/** A fresh user access token. Saves the new refresh token when Spotify rotates it. */
async function userToken(uid) {
  const snap = await tokensDoc(uid).get();
  const refreshToken = snap.exists ? snap.get("spotifyRefreshToken") : null;
  if (!refreshToken) throw new HttpsError("failed-precondition", "Connect Spotify first.");
  const body = await spotify.refresh(SPOTIFY_CLIENT_ID.value(), SPOTIFY_CLIENT_SECRET.value(), refreshToken);
  if (body.refresh_token && body.refresh_token !== refreshToken) {
    await tokensDoc(uid).set({ spotifyRefreshToken: body.refresh_token }, { merge: true });
  }
  return body.access_token;
}

exports.spotifyFindAlbum = onCall({ secrets: [SPOTIFY_CLIENT_SECRET] }, async (request) => {
  const uid = requireUid(request);
  const artist = requireString(request.data, "artist", 300);
  const album = requireString(request.data, "album", 300);
  try {
    let hit;
    try {
      const token = await spotify.clientToken(SPOTIFY_CLIENT_ID.value(), SPOTIFY_CLIENT_SECRET.value());
      hit = await spotify.findAlbum(token, artist, album);
    } catch (appErr) {
      // Development Mode can refuse app-only tokens; the person's own token still searches.
      logger.warn("app-token search failed, trying the user's token", { message: appErr.message });
      hit = await spotify.findAlbum(await userToken(uid), artist, album);
    }
    if (!hit) return { notFound: true };
    return {
      uri: hit.uri,
      id: hit.id,
      name: hit.name,
      artist: (hit.artists || []).map((a) => a.name).join(", "),
      imageUrl: hit.images && hit.images[0] ? hit.images[0].url : null,
      url: hit.external_urls ? hit.external_urls.spotify : null,
    };
  } catch (err) {
    throw toHttpsError("spotifyFindAlbum", err);
  }
});

exports.spotifyTestPlaylist = onCall({ secrets: [SPOTIFY_CLIENT_SECRET] }, async (request) => {
  const uid = requireUid(request);
  const albumUri = requireString(request.data, "albumUri", 200);
  const match = /^spotify:album:([A-Za-z0-9]+)$/.exec(albumUri);
  if (!match) throw new HttpsError("invalid-argument", "That isn't a Spotify album.");
  try {
    const token = await userToken(uid);
    const uris = await spotify.albumTrackUris(token, match[1]);
    if (!uris.length) throw new HttpsError("not-found", "That album has no tracks Spotify can add.");
    const playlist = await spotify.createPlaylist(
      token,
      "Liner Notes test",
      "Made by Liner Notes to check it can build playlists. Safe to delete.",
    );
    await spotify.addItems(token, playlist.id, uris);
    return { url: playlist.external_urls ? playlist.external_urls.spotify : null, id: playlist.id };
  } catch (err) {
    throw toHttpsError("spotifyTestPlaylist", err);
  }
});
