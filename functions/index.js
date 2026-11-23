"use strict";
// Liner Notes' Cloud Functions. Everything that needs a secret runs here, so the APK carries only
// public values. Phase 0: the Last.fm and Spotify sign-in swaps, finding an album on Spotify, and
// the test playlist that proves POST /playlists/{id}/items works for this app. Group send: drafting
// notes with Claude (draftNote, draftFrame), the "fit for them" scores and the set's playlist.
//
// Where things live in Firestore:
//   users/{uid}                 name, lastSeenAt (the app writes these);
//                               lastfm {username, connectedAt}, spotify {displayName, connectedAt,
//                               expiresAt, expired, remindedSoonFor, remindedExpiredFor} (only these
//                               Functions write them; the rules stop the app)
//   users/{uid}/private/tokens  Last.fm session key, Spotify refresh token, and runningPlaylists
//                               {senderUid: {id, url}}. No client can read it.
//   users/{uid}/sets/{setId}    a group send, as {json, updatedAt} (the app owns these)
//   users/{uid}/settings/{doc}  "writing": style examples, tone notes, last year's frame notes
//   users/{uid}.fcmTokens       the phone's push tokens (the app adds them; these Functions prune)
//   invites/{token}             inviterUid, inviterName, forName, status (open, accepted),
//                               expiresAt, acceptedBy, pairId. Only these Functions read them.
//   pairs/{uid1_uid2}           members [uid, uid], names {uid: name}, createdAt, and the pair's
//                               settings: nickname, monthlyOn, draftDay (1 to 28), decemberPause
//   sends/{uid_setId[_test]}    one scheduled or delivered set: senderUid, fromName, recipientUid
//                               (null until the invite is accepted), inviteToken, pairId, status
//                               (scheduled, delivered), deliverAt, deliveredAt, booklet (the
//                               recipient's copy, as JSON). The sender reads their own; the
//                               recipient reads it only once delivered. Only these Functions write.
//                               Monthly sends (kind "monthly", month "yyyy-mm") have the id
//                               uid_myyyymm_<pair hash>, one per person, pair and month.
//                               reactions {index: {reaction, comment, at}} come from the recipient.
//                               playlist {state, url, missing, reason, at}: how adding the albums
//                               to the recipient's Spotify went (done, failed, notConnected).

const { onCall, HttpsError } = require("firebase-functions/v2/https");
const { onSchedule } = require("firebase-functions/v2/scheduler");
const { setGlobalOptions } = require("firebase-functions/v2");
const { defineSecret, defineString } = require("firebase-functions/params");
const logger = require("firebase-functions/logger");
const { initializeApp } = require("firebase-admin/app");
const { getFirestore, FieldValue, Timestamp } = require("firebase-admin/firestore");
const { getMessaging } = require("firebase-admin/messaging");
const { getAuth } = require("firebase-admin/auth");

const lastfm = require("./lib/lastfm");
const spotify = require("./lib/spotify");
const facts = require("./lib/facts");
const notes = require("./lib/notes");
const sends = require("./lib/sends");
const monthly = require("./lib/monthly");
const influence = require("./lib/influence");
const polish = require("./lib/polish");
const crypto = require("node:crypto");
const Anthropic = require("@anthropic-ai/sdk");

initializeApp();
const db = getFirestore();

setGlobalOptions({ maxInstances: 5 });

// Public values come from functions/.env, which CI writes from the repo's Actions variables.
const LASTFM_API_KEY = defineString("LASTFM_API_KEY");
const SPOTIFY_CLIENT_ID = defineString("SPOTIFY_CLIENT_ID");
// Secrets live in Secret Manager only (firebase functions:secrets:set NAME).
const LASTFM_SHARED_SECRET = defineSecret("LASTFM_SHARED_SECRET");
const SPOTIFY_CLIENT_SECRET = defineSecret("SPOTIFY_CLIENT_SECRET");
const ANTHROPIC_API_KEY = defineSecret("ANTHROPIC_API_KEY");

const SPOTIFY_REDIRECT_URI = "linernotes://spotify-callback";
const SPOTIFY_TOKEN_DAYS = polish.SPOTIFY_TOKEN_DAYS; // refresh tokens stop working 6 months after the first sign-in

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
  if (err instanceof notes.NoteError) {
    return new HttpsError("failed-precondition", err.message);
  }
  if (err instanceof Anthropic.APIError) {
    if (err.status === 429 || err.status === 529) {
      return new HttpsError("resource-exhausted", "Claude is busy right now. Try again in a minute.");
    }
    if (err.status === 401 || err.status === 403) {
      return new HttpsError("failed-precondition", "The Claude API key isn't set up yet.");
    }
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

exports.spotifyConnect = onCall({ secrets: [SPOTIFY_CLIENT_SECRET], timeoutSeconds: 180 }, async (request) => {
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
    await userDoc(uid).set({ spotify: { displayName, connectedAt: now, expiresAt, expired: false } }, { merge: true });
    const caughtUp = await catchUpPlaylists(uid);
    return { displayName, caughtUp };
  } catch (err) {
    throw toHttpsError("spotifyConnect", err);
  }
});

/** A fresh user access token. Saves the new refresh token when Spotify rotates it. */
async function userToken(uid) {
  const snap = await tokensDoc(uid).get();
  const refreshToken = snap.exists ? snap.get("spotifyRefreshToken") : null;
  if (!refreshToken) throw new HttpsError("failed-precondition", "Connect Spotify first.");
  let body;
  try {
    body = await spotify.refresh(SPOTIFY_CLIENT_ID.value(), SPOTIFY_CLIENT_SECRET.value(), refreshToken);
  } catch (err) {
    // The six months are up: the app shows "reconnect" from this, and reminders stop.
    if (err.body && err.body.error === "invalid_grant") {
      await userDoc(uid).set({ spotify: { expired: true } }, { merge: true }).catch(() => {});
    }
    throw err;
  }
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

// Builds the set's playlist: every track of each album, in the set's order.
exports.spotifySetPlaylist = onCall({ secrets: [SPOTIFY_CLIENT_SECRET], timeoutSeconds: 120 }, async (request) => {
  const uid = requireUid(request);
  const name = requireString(request.data, "name", 100);
  const description = typeof request.data.description === "string" ? request.data.description.slice(0, 300) : "";
  const albumUris = Array.isArray(request.data.albumUris) ? request.data.albumUris : [];
  const ids = albumUris.map((u) => /^spotify:album:([A-Za-z0-9]+)$/.exec(String(u))).filter(Boolean).map((m) => m[1]);
  if (!ids.length || ids.length > 30) throw new HttpsError("invalid-argument", "Pick between 1 and 30 albums on Spotify.");
  try {
    const token = await userToken(uid);
    const uris = [];
    for (const id of ids) uris.push(...(await spotify.albumTrackUris(token, id)));
    const playlist = await spotify.createPlaylist(token, name, description);
    await spotify.addItems(token, playlist.id, uris);
    return { url: playlist.external_urls ? playlist.external_urls.spotify : null, id: playlist.id, tracks: uris.length };
  } catch (err) {
    throw toHttpsError("spotifySetPlaylist", err);
  }
});

// ---- Claude --------------------------------------------------------------------------------

const optString = (data, key, max) => (typeof data[key] === "string" ? data[key].trim().slice(0, max) : "");
const optStrings = (data, key, maxItems, maxLen) =>
  (Array.isArray(data[key]) ? data[key] : []).filter((s) => typeof s === "string" && s.trim()).slice(0, maxItems).map((s) => s.trim().slice(0, maxLen));
const optInt = (data, key) => (Number.isFinite(Number(data[key])) ? Math.max(0, Math.round(Number(data[key]))) : 0);

function styleFrom(data) {
  const s = (data && typeof data.style === "object" && data.style) || {};
  return {
    examples: optStrings(s, "examples", 6, 3000),
    toneNotes: optString(s, "toneNotes", 1000),
    minWords: optInt(s, "minWords") || 90,
    maxWords: optInt(s, "maxWords") || 120,
  };
}

// Where this album or artist showed up on the sender's earlier lists: [{year, artistOnly}].
function earlierListsFrom(data) {
  const raw = Array.isArray(data && data.earlierLists) ? data.earlierLists : [];
  return raw
    .filter((e) => e && /^\d{4}$/.test(String(e.year)))
    .slice(0, 10)
    .map((e) => ({ year: String(e.year), artistOnly: e.artistOnly === true }));
}

// One album's note: facts, reviews, Claude's draft in the sender's voice, then the fact check.
exports.draftNote = onCall({ secrets: [ANTHROPIC_API_KEY], timeoutSeconds: 300, memory: "512MiB" }, async (request) => {
  requireUid(request);
  const d = request.data || {};
  const artist = requireString(d, "artist", 300);
  const album = requireString(d, "album", 300);
  try {
    const found = await facts.gather(LASTFM_API_KEY.value(), artist, album);
    return await notes.draftNote(ANTHROPIC_API_KEY.value(), {
      artist,
      album,
      senderName: optString(d, "senderName", 80),
      recipientName: optString(d, "recipientName", 80),
      taste: optString(d, "taste", 1500),
      plays: optInt(d, "plays"),
      weeks: optInt(d, "weeks"),
      rangeLabel: optString(d, "rangeLabel", 80),
      favoriteTracks: optStrings(d, "favoriteTracks", 9, 200),
      isCloser: d.isCloser === true,
      isMonthly: d.isMonthly === true,
      style: styleFrom(d),
      instruction: optString(d, "instruction", 500),
      previous: optString(d, "previous", 4000),
      connection: optString(d, "connection", 400),
      earlierLists: earlierListsFrom(d),
      facts: found,
    });
  } catch (err) {
    throw toHttpsError("draftNote", err);
  }
});

// A spoken note, transcribed on the phone, turned into the written note (SPEC.md, "Dictating a note").
exports.formatDictation = onCall({ secrets: [ANTHROPIC_API_KEY], timeoutSeconds: 180 }, async (request) => {
  requireUid(request);
  const d = request.data || {};
  const artist = requireString(d, "artist", 300);
  const album = requireString(d, "album", 300);
  const transcript = requireString(d, "transcript", 8000);
  try {
    const found = await facts.gather(LASTFM_API_KEY.value(), artist, album);
    return await notes.formatDictation(ANTHROPIC_API_KEY.value(), {
      artist,
      album,
      transcript,
      senderName: optString(d, "senderName", 80),
      recipientName: optString(d, "recipientName", 80),
      current: optString(d, "current", 4000),
      mode: d.mode === "add" ? "add" : "replace",
      favoriteTracks: optStrings(d, "favoriteTracks", 9, 200),
      style: styleFrom(d),
      facts: found,
    });
  } catch (err) {
    throw toHttpsError("formatDictation", err);
  }
});

// The set's intro or closing note, from last year's as a starting point.
exports.draftFrame = onCall({ secrets: [ANTHROPIC_API_KEY], timeoutSeconds: 120 }, async (request) => {
  requireUid(request);
  const d = request.data || {};
  const kind = d.kind === "closing" ? "closing" : "intro";
  try {
    return await notes.draftFrame(ANTHROPIC_API_KEY.value(), {
      kind,
      senderName: optString(d, "senderName", 80),
      recipientName: optString(d, "recipientName", 80),
      year: optString(d, "year", 10),
      title: optString(d, "title", 120),
      albums: optStrings(d, "albums", 30, 300),
      lastText: optString(d, "lastText", 3000),
      style: styleFrom(d),
      instruction: optString(d, "instruction", 500),
      previous: optString(d, "previous", 3000),
    });
  } catch (err) {
    throw toHttpsError("draftFrame", err);
  }
});

// "Fit for them": scores the shortlist against the recipient's taste.
exports.scoreFit = onCall({ secrets: [ANTHROPIC_API_KEY], timeoutSeconds: 120 }, async (request) => {
  requireUid(request);
  const d = request.data || {};
  const taste = requireString(d, "taste", 1500);
  const albums = (Array.isArray(d.albums) ? d.albums : [])
    .filter((a) => a && typeof a.artist === "string" && typeof a.title === "string")
    .slice(0, 60)
    .map((a) => ({ artist: a.artist.slice(0, 200), title: a.title.slice(0, 200) }));
  if (!albums.length) throw new HttpsError("invalid-argument", "No albums to score.");
  try {
    return { scores: await notes.fitScores(ANTHROPIC_API_KEY.value(), { taste, albums }) };
  } catch (err) {
    throw toHttpsError("scoreFit", err);
  }
});

// ---- Sending ------------------------------------------------------------------------------
//
// A set goes to someone through an invite: the sender makes a link, schedules the set against it,
// and shares the link whenever they like (on the day itself, if it's a present). Accepting the
// invite forms the pair and hands over any set whose delivery time has already come; the rest go
// out from deliverDueSends at their time.

const inviteDoc = (token) => db.collection("invites").doc(token);
const sendDoc = (id) => db.collection("sends").doc(id);

function hostingBase() {
  const project = process.env.GCLOUD_PROJECT || process.env.GCP_PROJECT || "";
  return project ? `https://${project}.web.app` : "";
}

async function displayName(uid, fallback) {
  const snap = await userDoc(uid).get();
  const name = snap.exists ? snap.get("name") : null;
  return (typeof name === "string" && name.trim()) || fallback || "";
}

/** Pushes a notification to every phone a person has registered, pruning dead tokens. Never throws. */
async function pushTo(uid, notification, data) {
  try {
    const user = await userDoc(uid).get();
    const tokens = (user.exists && user.get("fcmTokens")) || [];
    if (!Array.isArray(tokens) || !tokens.length) return;
    const strings = Object.fromEntries(Object.entries(data || {}).map(([k, v]) => [k, String(v)]));
    const result = await getMessaging().sendEachForMulticast({
      tokens: tokens.slice(0, 20),
      notification,
      data: strings,
      android: { priority: "high", notification: { channelId: "sends" } },
    });
    const dead = [];
    result.responses.forEach((r, i) => {
      const code = r.error && r.error.code;
      if (code === "messaging/registration-token-not-registered" || code === "messaging/invalid-registration-token") {
        dead.push(tokens[i]);
      }
    });
    if (dead.length) await userDoc(uid).update({ fcmTokens: FieldValue.arrayRemove(...dead) });
  } catch (err) {
    logger.warn("push failed", { uid, message: err.message });
  }
}

/** Pushes the "you've got a set" notification to the recipient's phones. Never throws. */
async function notifyDelivered(sendId, data) {
  try {
    const booklet = JSON.parse(data.booklet);
    await pushTo(data.recipientUid, sends.notification(data.fromName, booklet), { sendId });
  } catch (err) {
    logger.warn("notifyDelivered failed", { sendId, message: err.message });
  }
}

/** Marks a send delivered (if it still needs it) and notifies the recipient. */
async function deliver(ref) {
  const delivered = await db.runTransaction(async (tx) => {
    const snap = await tx.get(ref);
    if (!snap.exists) return null;
    const d = snap.data();
    const due = sends.isDue({ status: d.status, recipientUid: d.recipientUid, deliverAtMs: d.deliverAt.toMillis() }, Date.now());
    if (!due) return null;
    tx.update(ref, { status: "delivered", deliveredAt: FieldValue.serverTimestamp() });
    return d;
  });
  if (delivered) {
    await notifyDelivered(ref.id, delivered);
    await toRecipientSpotify(ref);
  }
  return !!delivered;
}

// ---- Playlists in the recipient's Spotify ---------------------------------------------------
//
// When a send is delivered and the recipient has connected Spotify, its albums go into their own
// account too (SPEC.md, "Delivery and the recipient's side"): each month's pick is added to a
// running playlist named for the sender ("From J.T."), and a group send gets a playlist of its own
// in the booklet's order. The send records how it went in `playlist`, so the sender sees a failure
// with Retry. A failure never holds a send back: it has already arrived with its notes and links.

/** The Spotify album id for a booklet album: the one the sender's app found, or a search. */
async function albumIdFor(token, album) {
  const m = /^spotify:album:([A-Za-z0-9]+)$/.exec(album.spotifyUri || "");
  if (m) return m[1];
  const hit = await spotify.findAlbum(token, album.artist, album.title);
  return hit ? hit.id : null;
}

/**
 * Adds tracks to the recipient's running playlist for this sender, making it the first time (or
 * again, if they deleted it). The playlist's id is kept in the recipient's private tokens doc.
 */
async function addToRunning(token, recipientUid, senderUid, fromName, uris) {
  const snap = await tokensDoc(recipientUid).get();
  const saved = snap.exists ? (snap.get("runningPlaylists") || {})[senderUid] : null;
  if (saved && saved.id) {
    try {
      await spotify.addItems(token, saved.id, uris);
      return saved.url || null;
    } catch (err) {
      if (!polish.playlistGone(err)) throw err;
      logger.info("running playlist gone, making a new one", { recipientUid });
    }
  }
  const p = await spotify.createPlaylist(token, polish.runningName(fromName), polish.runningDescription(fromName));
  const url = p.external_urls ? p.external_urls.spotify : null;
  await tokensDoc(recipientUid).set({ runningPlaylists: { [senderUid]: { id: p.id, url } } }, { merge: true });
  await spotify.addItems(token, p.id, uris);
  return url;
}

/** Puts a delivered send's albums in the recipient's Spotify and records how it went. Never throws. */
async function toRecipientSpotify(ref) {
  let record;
  try {
    const snap = await ref.get();
    if (!snap.exists) return null;
    const d = snap.data();
    if (d.status !== "delivered" || !d.recipientUid || d.test === true) return null;
    const tokens = await tokensDoc(d.recipientUid).get();
    if (!tokens.exists || !tokens.get("spotifyRefreshToken")) {
      record = polish.playlistRecord("notConnected");
    } else {
      const token = await userToken(d.recipientUid);
      const booklet = JSON.parse(d.booklet);
      const uris = [];
      const missing = [];
      for (const e of booklet.entries || []) {
        const id = await albumIdFor(token, e.album);
        if (!id) {
          missing.push(e.album.title);
          continue;
        }
        uris.push(...(await spotify.albumTrackUris(token, id)));
      }
      if (!uris.length) throw Object.assign(new Error("no albums found"), { notFound: true });
      let url;
      if (d.kind === "monthly") {
        url = await addToRunning(token, d.recipientUid, d.senderUid, d.fromName, uris);
      } else {
        const count = (booklet.entries || []).length;
        const p = await spotify.createPlaylist(token, polish.setName(booklet.title), polish.setDescription(d.fromName, count));
        await spotify.addItems(token, p.id, uris);
        url = p.external_urls ? p.external_urls.spotify : null;
      }
      record = polish.playlistRecord("done", { url, missing });
    }
  } catch (err) {
    logger.warn("toRecipientSpotify", { sendId: ref.id, message: err.message, status: err.status });
    record = polish.playlistRecord("failed", { reason: polish.failureReason(err) });
  }
  await ref.update({ playlist: record }).catch((err) => logger.warn("playlist record", { message: err.message }));
  return record;
}

/** After someone connects (or reconnects) Spotify: the sends that missed their playlist, newest first. */
async function catchUpPlaylists(uid) {
  try {
    const snap = await db.collection("sends").where("recipientUid", "==", uid).get();
    const behind = snap.docs
      .filter((doc) => doc.get("status") === "delivered" && doc.get("test") !== true)
      .filter((doc) => {
        const p = doc.get("playlist");
        return !p || p.state === "notConnected" || p.state === "failed";
      })
      .sort((a, b) => (b.get("deliveredAt")?.toMillis() || 0) - (a.get("deliveredAt")?.toMillis() || 0))
      .slice(0, 12)
      .reverse(); // oldest first, so the running playlist keeps the order they arrived in
    let done = 0;
    for (const doc of behind) {
      const r = await toRecipientSpotify(doc.ref);
      if (r && r.state === "done") done++;
    }
    return done;
  } catch (err) {
    logger.warn("catchUpPlaylists", { message: err.message });
    return 0;
  }
}

// Retry from either side of a send, after a failure.
exports.retryPlaylist = onCall({ secrets: [SPOTIFY_CLIENT_SECRET], timeoutSeconds: 180 }, async (request) => {
  const uid = requireUid(request);
  const ref = sendDoc(requireString(request.data, "sendId", 200));
  const snap = await ref.get();
  if (!snap.exists || (snap.get("senderUid") !== uid && snap.get("recipientUid") !== uid)) {
    throw new HttpsError("not-found", "That send isn't one of yours.");
  }
  if (snap.get("status") !== "delivered") throw new HttpsError("failed-precondition", "It hasn't been delivered yet.");
  if ((snap.get("playlist") || {}).state === "done") return snap.get("playlist");
  return await toRecipientSpotify(ref);
});

exports.createInvite = onCall(async (request) => {
  const uid = requireUid(request);
  const d = request.data || {};
  const forName = optString(d, "forName", 80);
  const inviterName = optString(d, "fromName", 80) || (await displayName(uid, request.auth.token.name));
  const token = sends.newToken();
  await inviteDoc(token).set({
    inviterUid: uid,
    inviterName,
    forName,
    status: "open",
    createdAt: FieldValue.serverTimestamp(),
    expiresAt: Timestamp.fromMillis(Date.now() + sends.INVITE_DAYS * 86_400_000),
  });
  const base = hostingBase();
  return { token, url: base ? `${base}/invite/${token}` : `linernotes://invite/${token}` };
});

// Who an invite is from, so the app can ask "Join?" before anything happens.
exports.inviteInfo = onCall(async (request) => {
  const uid = requireUid(request);
  const token = request.data && request.data.token;
  if (!sends.isToken(token)) throw new HttpsError("invalid-argument", "That isn't an invite link.");
  const snap = await inviteDoc(token).get();
  const invite = snap.exists ? snap.data() : null;
  const problem = sends.inviteProblem(
    invite && { ...invite, expiresAtMs: invite.expiresAt ? invite.expiresAt.toMillis() : 0 },
    uid,
    Date.now(),
  );
  return {
    inviterName: (invite && invite.inviterName) || "",
    forName: (invite && invite.forName) || "",
    problem,
    alreadyJoined: !!invite && invite.status === "accepted" && invite.acceptedBy === uid,
  };
});

exports.acceptInvite = onCall({ secrets: [SPOTIFY_CLIENT_SECRET], timeoutSeconds: 180 }, async (request) => {
  const uid = requireUid(request);
  const token = request.data && request.data.token;
  if (!sends.isToken(token)) throw new HttpsError("invalid-argument", "That isn't an invite link.");
  const myName = optString(request.data, "name", 80) || (await displayName(uid, request.auth.token.name));
  const result = await db.runTransaction(async (tx) => {
    const ref = inviteDoc(token);
    const snap = await tx.get(ref);
    const invite = snap.exists ? snap.data() : null;
    const problem = sends.inviteProblem(
      invite && { ...invite, expiresAtMs: invite.expiresAt ? invite.expiresAt.toMillis() : 0 },
      uid,
      Date.now(),
    );
    if (problem) throw new HttpsError("failed-precondition", problem);
    const pair = sends.pairId(invite.inviterUid, uid);
    if (invite.status === "open") {
      tx.set(
        db.collection("pairs").doc(pair),
        {
          members: [invite.inviterUid, uid],
          names: { [invite.inviterUid]: invite.inviterName || "", [uid]: myName },
          monthlyOn: true,
          draftDay: monthly.DEFAULT_DRAFT_DAY,
          decemberPause: true,
          createdAt: FieldValue.serverTimestamp(),
        },
        { merge: true },
      );
      tx.update(ref, { status: "accepted", acceptedBy: uid, acceptedAt: FieldValue.serverTimestamp(), pairId: pair });
    }
    return { pairId: pair, inviterName: invite.inviterName || "" };
  });

  // Hand over everything scheduled against this invite; deliver what's already due.
  const waiting = await db.collection("sends").where("inviteToken", "==", token).get();
  let delivered = 0;
  for (const doc of waiting.docs) {
    if (doc.get("recipientUid")) continue;
    await doc.ref.update({ recipientUid: uid, pairId: result.pairId });
    if (await deliver(doc.ref)) delivered++;
  }
  return { ...result, delivered };
});

// Schedules (or reschedules, with the latest version of the set) a set for delivery.
exports.scheduleSend = onCall({ secrets: [SPOTIFY_CLIENT_SECRET], timeoutSeconds: 180 }, async (request) => {
  const uid = requireUid(request);
  const d = request.data || {};
  const setId = requireString(d, "setId", 64);
  const test = d.test === true;
  let booklet;
  try {
    booklet = sends.cleanBooklet(requireString(d, "booklet", 400_000));
  } catch (err) {
    if (err instanceof HttpsError) throw err;
    throw new HttpsError("invalid-argument", "That set isn't ready to send: it needs a title and at least one album.");
  }
  const now = Date.now();
  let deliverAtMs;
  try {
    deliverAtMs = test ? now : sends.deliveryTime(d.deliverAt, now);
  } catch (_) {
    throw new HttpsError("invalid-argument", "Pick a delivery date within the next year.");
  }
  const fromName = optString(d, "fromName", 80) || (await displayName(uid, request.auth.token.name));
  if (!test && d.kind === "monthly") return sendMonthly(uid, d, booklet, fromName);

  let recipientUid = null;
  let pairId = null;
  let inviteToken = null;
  if (test) {
    recipientUid = uid;
  } else {
    inviteToken = d.inviteToken;
    if (!sends.isToken(inviteToken)) throw new HttpsError("failed-precondition", "Make the invite link first.");
    const invite = await inviteDoc(inviteToken).get();
    if (!invite.exists || invite.get("inviterUid") !== uid) {
      throw new HttpsError("failed-precondition", "That invite isn't yours. Make a new link.");
    }
    if (invite.get("status") === "accepted") {
      recipientUid = invite.get("acceptedBy");
      pairId = invite.get("pairId") || null;
    }
  }

  const ref = sendDoc(sends.sendId(uid, setId, test));
  await db.runTransaction(async (tx) => {
    const snap = await tx.get(ref);
    if (!test && snap.exists && snap.get("status") === "delivered") {
      throw new HttpsError("failed-precondition", "This set has already been delivered.");
    }
    tx.set(ref, {
      senderUid: uid,
      fromName,
      recipientUid,
      inviteToken,
      pairId,
      setId,
      kind: "group",
      test,
      status: "scheduled",
      deliverAt: Timestamp.fromMillis(deliverAtMs),
      deliveredAt: null,
      booklet: JSON.stringify(booklet),
      createdAt: snap.exists ? snap.get("createdAt") : FieldValue.serverTimestamp(),
      updatedAt: FieldValue.serverTimestamp(),
    });
  });
  const deliveredNow = await deliver(ref);
  return {
    sendId: ref.id,
    status: deliveredNow ? "delivered" : "scheduled",
    deliverAt: deliverAtMs,
    waitingForJoin: !recipientUid,
  };
});

exports.cancelSend = onCall(async (request) => {
  const uid = requireUid(request);
  const setId = requireString(request.data, "setId", 64);
  const ref = sendDoc(sends.sendId(uid, setId, false));
  await db.runTransaction(async (tx) => {
    const snap = await tx.get(ref);
    if (!snap.exists) return;
    if (snap.get("status") === "delivered") {
      throw new HttpsError("failed-precondition", "It's already been delivered, so it can't be called back.");
    }
    tx.delete(ref);
  });
  return { cancelled: true };
});

// Every 15 minutes: deliver whatever has come due. Needs the (status, deliverAt) index in
// firestore.indexes.json.
exports.deliverDueSends = onSchedule({ schedule: "every 15 minutes", timeoutSeconds: 540, secrets: [SPOTIFY_CLIENT_SECRET] }, async () => {
  const due = await db
    .collection("sends")
    .where("status", "==", "scheduled")
    .where("deliverAt", "<=", Timestamp.now())
    .limit(200)
    .get();
  let count = 0;
  for (const doc of due.docs) {
    if (!doc.get("recipientUid")) continue; // waiting for the invite to be accepted
    if (await deliver(doc.ref)) count++;
  }
  if (count) logger.info("deliverDueSends", { delivered: count });
});

// ---- Monthly send -------------------------------------------------------------------------
//
// Each month each person sends the other one album they loved last month (SPEC.md, "Monthly
// send"). The app gathers its own candidates from its Last.fm charts; monthlySuggest adds what
// only the backend should see (the recipient's listening, what's been sent in the pair, how past
// picks landed) and asks Claude for one pick and two alternates. Sending goes through
// scheduleSend with kind "monthly", which delivers straight away.

/** The pair, if the caller is one of its two members. */
async function memberPair(uid, pairId) {
  if (typeof pairId !== "string" || !pairId || pairId.length > 200) throw new HttpsError("invalid-argument", "Missing pairId.");
  const snap = await db.collection("pairs").doc(pairId).get();
  const members = (snap.exists && snap.get("members")) || [];
  if (!members.includes(uid)) throw new HttpsError("permission-denied", "That isn't one of your pairs.");
  const data = snap.data();
  return { ref: snap.ref, data, partnerUid: members.find((m) => m !== uid), names: data.names || {} };
}

const heading = (album) => `${album.artist} — ${album.title}`;

exports.monthlySuggest = onCall({ secrets: [ANTHROPIC_API_KEY], timeoutSeconds: 180 }, async (request) => {
  const uid = requireUid(request);
  const d = request.data || {};
  const { partnerUid, names } = await memberPair(uid, d.pairId);
  const candidates = monthly.cleanCandidates(d.candidates);
  if (!candidates.length) throw new HttpsError("failed-precondition", "No albums to choose from yet.");

  // Everything already sent within the pair, both ways, and how this sender's picks landed.
  const sent = new Set();
  const reactions = { loved: [], liked: [], notForMe: [] };
  const recent = [];
  const results = [];
  const pairSends = await db.collection("sends").where("pairId", "==", d.pairId).get();
  for (const doc of pairSends.docs) {
    for (const k of monthly.bookletKeys(doc.get("booklet"))) sent.add(k);
    if (doc.get("senderUid") !== uid || doc.get("test")) continue;
    let entries = [];
    try {
      entries = JSON.parse(doc.get("booklet")).entries || [];
    } catch (_) {
      continue;
    }
    for (const [i, r] of Object.entries(doc.get("reactions") || {})) {
      const e = entries[Number(i)];
      if (e && r && reactions[r.reaction]) reactions[r.reaction].push(heading(e.album));
    }
    if (doc.get("kind") === "monthly" && entries[0]) {
      recent.push({ at: doc.get("deliverAt") ? doc.get("deliverAt").toMillis() : 0, h: heading(entries[0].album) });
    }
    const inf = doc.get("influence") || {};
    const look = inf.d90 || inf.d30 || inf.latest;
    if (look && look.items) {
      for (const [i, r] of Object.entries(look.items)) {
        const e = entries[Number(i)];
        if (e && r) results.push({ heading: heading(e.album), points: Number(r.points) || 0, measured: true });
      }
    }
  }

  // The recipient's taste, from their Last.fm. Never returned to the sender as such.
  const taste = { coreArtists: [], recentArtists: [], recentAlbums: [], typed: optString(d, "taste", 1500) };
  let known = new Set();
  const partner = await userDoc(partnerUid).get();
  const lfm = partner.exists ? partner.get("lastfm.username") : null;
  if (lfm) {
    const key = LASTFM_API_KEY.value();
    const safe = (p) => p.catch((err) => {
      logger.warn("recipient taste read failed", { message: err.message });
      return [];
    });
    const [year, ever, recentArtists, recentAlbums, allAlbums] = await Promise.all([
      safe(lastfm.topArtists(key, lfm, "12month", 30)),
      safe(lastfm.topArtists(key, lfm, "overall", 30)),
      safe(lastfm.topArtists(key, lfm, "3month", 15)),
      safe(lastfm.topAlbums(key, lfm, "3month", 15)),
      safe(lastfm.topAlbums(key, lfm, "overall", 500)),
    ]);
    taste.coreArtists = [...new Set([...year, ...ever].map((a) => a.name))];
    taste.recentArtists = recentArtists.map((a) => a.name);
    taste.recentAlbums = recentAlbums.map(heading);
    known = monthly.knownAlbums(allAlbums);
  }

  const fresh = monthly.filterCandidates(candidates, known, sent);
  if (!fresh.length) {
    return { picks: [], stretch: false, excluded: candidates.length };
  }
  try {
    const picks = await notes.suggestMonthly(ANTHROPIC_API_KEY.value(), {
      senderName: optString(d, "senderName", 80) || names[uid] || "",
      recipientName: names[partnerUid] || "",
      month: optString(d, "rangeLabel", 60),
      candidates: fresh,
      taste,
      reactions,
      landed: influence.landed(results),
      recentPicks: recent.sort((a, b) => b.at - a.at).slice(0, 6).map((r) => r.h),
    });
    const byId = new Map(fresh.map((c) => [c.id, c]));
    return {
      picks: picks.map((p) => {
        const c = byId.get(p.id);
        return { artist: c.artist, title: c.title, confidence: p.confidence, connection: p.connection, fromEarlier: c.fromEarlier };
      }),
      stretch: monthly.isStretch(picks),
      excluded: candidates.length - fresh.length,
    };
  } catch (err) {
    throw toHttpsError("monthlySuggest", err);
  }
});

/** scheduleSend for kind "monthly": one album to the other member of the pair, delivered now. */
async function sendMonthly(uid, d, booklet, fromName) {
  const month = d.month;
  if (!monthly.isMonth(month)) throw new HttpsError("invalid-argument", "Missing month.");
  const { partnerUid } = await memberPair(uid, d.pairId);
  if (booklet.entries.length !== 1) throw new HttpsError("invalid-argument", "A monthly send is one album.");
  const ref = sendDoc(monthly.monthlySendId(uid, d.pairId, month));
  const now = Date.now();
  await db.runTransaction(async (tx) => {
    const snap = await tx.get(ref);
    if (snap.exists) throw new HttpsError("already-exists", `You've already sent your ${monthly.monthName(month)} pick.`);
    tx.create(ref, {
      senderUid: uid,
      fromName,
      recipientUid: partnerUid,
      inviteToken: null,
      pairId: d.pairId,
      setId: requireString(d, "setId", 64),
      kind: "monthly",
      month,
      test: false,
      status: "scheduled",
      deliverAt: Timestamp.fromMillis(now),
      deliveredAt: null,
      booklet: JSON.stringify({ ...booklet, kind: "monthly", month }),
      createdAt: FieldValue.serverTimestamp(),
      updatedAt: FieldValue.serverTimestamp(),
    });
  });
  const deliveredNow = await deliver(ref);
  return { sendId: ref.id, status: deliveredNow ? "delivered" : "scheduled", deliverAt: now, waitingForJoin: false };
}

// The recipient's reaction to one album of a send: loved, liked or not for me, and a comment.
exports.reactToSend = onCall(async (request) => {
  const uid = requireUid(request);
  const d = request.data || {};
  const ref = sendDoc(requireString(d, "sendId", 200));
  const result = await db.runTransaction(async (tx) => {
    const snap = await tx.get(ref);
    if (!snap.exists || snap.get("recipientUid") !== uid || snap.get("status") !== "delivered") {
      throw new HttpsError("not-found", "That send isn't in your inbox.");
    }
    let entries;
    try {
      entries = JSON.parse(snap.get("booklet")).entries || [];
    } catch (_) {
      throw new HttpsError("failed-precondition", "That send can't be read.");
    }
    let r;
    try {
      r = monthly.cleanReaction(d, entries.length);
    } catch (err) {
      throw new HttpsError("invalid-argument", err.message);
    }
    const before = (snap.get("reactions") || {})[String(r.index)] || null;
    const field = `reactions.${r.index}`;
    if (r.reaction === null && !r.comment) tx.update(ref, { [field]: FieldValue.delete() });
    else tx.update(ref, { [field]: { reaction: r.reaction, comment: r.comment, at: Timestamp.now() } });
    const changed = !before || before.reaction !== r.reaction || (r.comment && before.comment !== r.comment);
    return { ...r, changed, senderUid: snap.get("senderUid"), test: snap.get("test") === true, album: entries[r.index].album };
  });
  if (result.changed && !result.test && (result.reaction || result.comment)) {
    const name = await displayName(uid, request.auth.token.name);
    await pushTo(
      result.senderUid,
      {
        title: `${name || "They"} ${monthly.reactionWord(result.reaction)} ${result.album.title}`,
        body: result.comment || heading(result.album),
      },
      { sendId: ref.id, kind: "reaction" },
    );
  }
  return { saved: true };
});

// The pair's settings: nickname, monthly on or off, the draft day, the December pause.
exports.updatePair = onCall(async (request) => {
  const uid = requireUid(request);
  const d = request.data || {};
  const { ref } = await memberPair(uid, d.pairId);
  let changes;
  try {
    changes = monthly.cleanPairSettings(d);
  } catch (err) {
    throw new HttpsError("invalid-argument", err.message);
  }
  // Influence sharing is each member's own choice; it runs only when both have it on.
  if (typeof d.influence === "boolean") changes[`influence.${uid}`] = d.influence;
  if (Object.keys(changes).length) await ref.update({ ...changes, updatedAt: FieldValue.serverTimestamp() });
  return { saved: true };
});

/** Whether the member has a group send to the pair delivering in this December. */
async function hasDecemberSet(uid, pairId, now) {
  const year = Number(monthly.monthKey(now).slice(0, 4));
  const snap = await db.collection("sends").where("senderUid", "==", uid).where("pairId", "==", pairId).get();
  return snap.docs.some((doc) => {
    if (doc.get("kind") === "monthly" || doc.get("test")) return false;
    const at = doc.get("deliverAt");
    return !!at && monthly.monthKey(at.toDate()) === `${year}-12`;
  });
}

// Each morning: "time for your pick" on the pair's draft day, and a nudge a week later and on the
// 25th to anyone whose month's send hasn't gone. Also the Spotify sign-in reminders, below.
exports.monthlyReminders = onSchedule(
  { schedule: "0 10 * * *", timeZone: monthly.TIME_ZONE, timeoutSeconds: 300 },
  async () => {
    const now = new Date();
    const month = monthly.monthKey(now);
    const day = monthly.dayOfMonth(now);
    const pairs = await db.collection("pairs").get();
    let count = 0;
    for (const p of pairs.docs) {
      const data = p.data();
      const members = data.members || [];
      if (members.length !== 2) continue;
      for (const uid of members) {
        const partner = members.find((m) => m !== uid);
        const sent = (await sendDoc(monthly.monthlySendId(uid, p.id, month)).get()).exists;
        const paused = month.endsWith("-12") && data.decemberPause !== false && (await hasDecemberSet(uid, p.id, now));
        const kind = monthly.reminderFor({ day, draftDay: data.draftDay, sent, monthlyOn: data.monthlyOn, paused });
        if (!kind) continue;
        await pushTo(uid, monthly.reminderText(kind, (data.names || {})[partner], month), { kind: "monthly", pairId: p.id });
        count++;
      }
    }
    if (count) logger.info("monthlyReminders", { sent: count });
    await spotifyExpiryReminders(now);
  },
);

/**
 * Spotify sign-ins run out six months after the first sign-in, whatever happens in between, so
 * each person gets one reminder two weeks before and one on the day (SPEC.md, "First run").
 * Runs inside the daily reminder job rather than as a job of its own.
 */
async function spotifyExpiryReminders(now) {
  const nowMs = now.getTime();
  const soon = Timestamp.fromMillis(nowMs + polish.REMIND_DAYS * polish.DAY);
  const users = await db.collection("users").where("spotify.expiresAt", "<=", soon).get();
  let count = 0;
  for (const u of users.docs) {
    const s = u.get("spotify") || {};
    const expiresAtMs = s.expiresAt && s.expiresAt.toMillis ? s.expiresAt.toMillis() : NaN;
    const kind = polish.expiryReminder(
      { expiresAtMs, remindedSoonFor: s.remindedSoonFor, remindedExpiredFor: s.remindedExpiredFor },
      nowMs,
    );
    if (!kind) continue;
    await pushTo(u.id, polish.expiryText(kind, expiresAtMs, monthly.TIME_ZONE), { kind: "spotifyExpiry" });
    const field = kind === "soon" ? "remindedSoonFor" : "remindedExpiredFor";
    await u.ref.set({ spotify: { [field]: expiresAtMs } }, { merge: true });
    count++;
  }
  if (count) logger.info("spotifyExpiryReminders", { sent: count });
}

// ---- Delete my data -------------------------------------------------------------------------
//
// Settings' "Delete my data" (SPEC.md, "Data model"): the person's own documents, their sends,
// their pairs and invites, and their sign-in. Sends they received keep the sender's booklet, so the
// other person's archive survives, minus everything that came from the deleted person. Playlists
// already in their own Spotify are theirs and stay there. Album and artist caches hold nothing
// personal and stay.
exports.deleteMyData = onCall({ timeoutSeconds: 540, memory: "512MiB" }, async (request) => {
  const uid = requireUid(request);
  if (!request.data || request.data.confirm !== "DELETE") {
    throw new HttpsError("invalid-argument", "Confirm the deletion first.");
  }
  const writer = db.bulkWriter();
  let sendsDeleted = 0;
  let sendsKept = 0;
  for (const field of ["senderUid", "recipientUid"]) {
    const snap = await db.collection("sends").where(field, "==", uid).get();
    for (const doc of snap.docs) {
      const plan = polish.deletionPlan(doc.data(), uid);
      if (plan.action === "delete") {
        writer.delete(doc.ref);
        sendsDeleted++;
      } else if (plan.action === "strip") {
        const changes = { recipientDeleted: true, updatedAt: FieldValue.serverTimestamp() };
        for (const f of plan.fields) changes[f] = FieldValue.delete();
        writer.update(doc.ref, changes);
        sendsKept++;
      }
    }
  }
  const invites = await db.collection("invites").where("inviterUid", "==", uid).get();
  invites.docs.forEach((doc) => writer.delete(doc.ref));
  const accepted = await db.collection("invites").where("acceptedBy", "==", uid).get();
  accepted.docs.forEach((doc) => writer.update(doc.ref, { acceptedBy: FieldValue.delete() }));
  const pairs = await db.collection("pairs").where("members", "array-contains", uid).get();
  pairs.docs.forEach((doc) => writer.delete(doc.ref));
  await writer.close();

  // users/{uid} and everything under it: tokens, sets, settings, cached weekly charts.
  await db.recursiveDelete(userDoc(uid));
  try {
    await getAuth().deleteUser(uid);
  } catch (err) {
    logger.warn("deleteUser", { message: err.message });
  }
  logger.info("deleteMyData", { sendsDeleted, sendsKept, pairs: pairs.size });
  return { deleted: true, sendsDeleted, sendsKept, pairs: pairs.size };
});

// ---- Influence ------------------------------------------------------------------------------
//
// How much each person's sends showed up in the other's listening (SPEC.md, "Influence"), from
// the recipient's Last.fm weekly charts: the 90 days before a send as the baseline, then a running
// look from a week after delivery, and the 30 and 90 day marks. Only for pairs where both people
// turned sharing on. The weekly charts are cached in the recipient's own users/{uid}/weeks, and
// only the per-album summaries are written to the send, where the sender can read them.

const sha = (s) => crypto.createHash("sha256").update(s).digest("hex").slice(0, 32);

/** Per-run caches, so a job reading many sends asks Last.fm for each thing once. */
function influenceRun() {
  return { pairs: new Map(), users: new Map(), lists: new Map(), tracks: new Map(), similar: new Map() };
}

async function cached(map, key, load) {
  if (!map.has(key)) map.set(key, load());
  return map.get(key);
}

/** The recipient's charts for the weeks in [startMs, endMs), from the cache or Last.fm. */
async function weeksFor(run, uid, user, startMs, endMs, slackDays) {
  const key = LASTFM_API_KEY.value();
  const list = await cached(run.lists, user, () => lastfm.chartList(key, user));
  const wanted = influence.weeksBetween(list, startMs, endMs, slackDays);
  const col = userDoc(uid).collection("weeks");
  const snaps = await Promise.all(wanted.map((w) => col.doc(String(w.from)).get()));
  const out = [];
  const missing = [];
  snaps.forEach((snap, i) => {
    if (snap.exists && snap.get("user") === user) out.push(JSON.parse(snap.get("json")));
    else missing.push(wanted[i]);
  });
  for (let i = 0; i < missing.length; i += 3) {
    const got = await Promise.all(missing.slice(i, i + 3).map(async (w) => {
      const data = await lastfm.week(key, user, w.from, w.to);
      await col.doc(String(w.from)).set({ user, from: w.from, to: w.to, json: JSON.stringify(data), at: FieldValue.serverTimestamp() });
      return data;
    }));
    out.push(...got);
  }
  return out;
}

/** An album's track count, cached in albums/{hash} (shared album facts). */
async function trackCountFor(run, album) {
  const k = influence.albumKey(album.artist, album.title);
  return cached(run.tracks, k, async () => {
    const ref = db.collection("albums").doc(sha(k));
    const snap = await ref.get();
    if (snap.exists && typeof snap.get("trackCount") === "number") return snap.get("trackCount");
    const n = await lastfm.trackCount(LASTFM_API_KEY.value(), album.artist, album.title);
    if (n > 0) await ref.set({ artist: album.artist, title: album.title, trackCount: n }, { merge: true });
    return n;
  });
}

/** Last.fm's similar artists, cached in artists/{hash} for 90 days. */
async function similarFor(run, artist) {
  const k = influence.norm(artist);
  return cached(run.similar, k, async () => {
    const ref = db.collection("artists").doc(sha(k));
    const snap = await ref.get();
    const at = snap.exists ? snap.get("similarAt") : null;
    if (at && Date.now() - at.toMillis() < 90 * influence.DAY) return snap.get("similar") || [];
    const similar = await lastfm.similarArtists(LASTFM_API_KEY.value(), artist);
    await ref.set({ name: artist, similar, similarAt: FieldValue.serverTimestamp() }, { merge: true });
    return similar;
  });
}

/**
 * Measures one delivered send, for the looks that are due (or a running look now, with `force`).
 * Returns what it wrote, or null when there was nothing to do.
 */
async function measureSend(run, doc, { force = false } = {}) {
  const d = doc.data();
  if (d.status !== "delivered" || d.test || !d.pairId || !d.deliveredAt || !d.recipientUid) return null;
  const pair = await cached(run.pairs, d.pairId, async () => {
    const p = await db.collection("pairs").doc(d.pairId).get();
    return p.exists ? p.data() : null;
  });
  if (!influence.sharing(pair)) return null;
  const user = await cached(run.users, d.recipientUid, async () => {
    const u = await userDoc(d.recipientUid).get();
    return u.exists ? u.get("lastfm.username") : null;
  });
  if (!user) return null;

  const now = Date.now();
  const deliveredMs = d.deliveredAt.toMillis();
  const age = (now - deliveredMs) / influence.DAY;
  const looks = influence.due(age, d.influence);
  if (force && age >= 1 && !looks.includes("latest")) looks.unshift("latest");
  if (!looks.length) return null;

  let entries;
  try {
    entries = JSON.parse(d.booklet).entries || [];
  } catch (_) {
    return null;
  }
  if (!entries.length) return null;
  const name = (pair.names && pair.names[d.recipientUid]) || "";
  const baseline = influence.sumWeeks(
    await weeksFor(run, d.recipientUid, user, deliveredMs - influence.BASELINE_DAYS * influence.DAY, deliveredMs, 0),
  );
  const facts = await Promise.all(entries.map(async (e) => ({
    trackCount: await trackCountFor(run, e.album),
    similar: await similarFor(run, e.album.artist),
  })));

  const writes = {};
  for (const look of looks) {
    const days = look === "latest" ? Math.min(90, age) : Number(look.slice(1));
    const after = influence.sumWeeks(
      await weeksFor(run, d.recipientUid, user, deliveredMs, Math.min(now, deliveredMs + days * influence.DAY), 3),
    );
    const items = {};
    let total = 0;
    entries.forEach((e, i) => {
      const r = influence.measure({ album: e.album, before: baseline, after, ...facts[i] });
      items[i] = { ...r, line: influence.line(name, e.album, r) };
      total += r.points;
    });
    const best = Object.entries(items)
      .filter(([, r]) => r.points > 0)
      .sort((a, b) => b[1].points - a[1].points)
      .slice(0, 3)
      .map(([i]) => Number(i));
    writes[`influence.${look}`] = {
      at: Timestamp.fromMillis(now),
      days: Math.floor(days),
      items,
      total,
      max: influence.maxPoints() * entries.length,
      best,
    };
  }
  await doc.ref.update(writes);
  return writes;
}

// Each morning, the running look and any 30 or 90 day marks that are due.
exports.measureInfluence = onSchedule(
  { schedule: "30 5 * * *", timeZone: monthly.TIME_ZONE, timeoutSeconds: 540, memory: "512MiB" },
  async () => {
    const since = Timestamp.fromMillis(Date.now() - 95 * influence.DAY);
    const snap = await db.collection("sends").where("deliveredAt", ">=", since).limit(400).get();
    const run = influenceRun();
    let measured = 0;
    for (const doc of snap.docs) {
      try {
        if (await measureSend(run, doc)) measured++;
      } catch (err) {
        logger.warn("measureInfluence", { send: doc.id, message: err.message });
      }
    }
    if (measured) logger.info("measureInfluence", { measured });
  },
);

// "Check now" on the pair page: a running look at every send in the pair that's at least a day old.
exports.influenceNow = onCall({ timeoutSeconds: 300, memory: "512MiB" }, async (request) => {
  const uid = requireUid(request);
  const d = request.data || {};
  const { data } = await memberPair(uid, d.pairId);
  if (!influence.sharing(data)) {
    throw new HttpsError("failed-precondition", "Influence runs once you both turn sharing on in the pair's settings.");
  }
  const snap = await db.collection("sends").where("pairId", "==", d.pairId).get();
  const run = influenceRun();
  let measured = 0;
  for (const doc of snap.docs) {
    const latest = doc.get("influence.latest.at");
    if (latest && Date.now() - latest.toMillis() < 60 * 60 * 1000) continue; // looked within the hour
    try {
      if (await measureSend(run, doc, { force: true })) measured++;
    } catch (err) {
      throw toHttpsError("influenceNow", err);
    }
  }
  return { measured };
});
