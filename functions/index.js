"use strict";
// Liner Notes' Cloud Functions. Everything that needs a secret runs here, so the APK carries only
// public values. Phase 0: the Last.fm and Spotify sign-in swaps, finding an album on Spotify, and
// the test playlist that proves POST /playlists/{id}/items works for this app. Group send: drafting
// notes with Claude (draftNote, draftFrame), the "fit for them" scores and the set's playlist.
//
// Where things live in Firestore:
//   users/{uid}                 name, lastSeenAt (the app writes these);
//                               lastfm {username, connectedAt}, spotify {displayName, connectedAt,
//                               expiresAt} (only these Functions write them; the rules stop the app)
//   users/{uid}/private/tokens  Last.fm session key, Spotify refresh token. No client can read it.
//   users/{uid}/sets/{setId}    a group send, as {json, updatedAt} (the app owns these)
//   users/{uid}/settings/{doc}  "writing": style examples, tone notes, last year's frame notes
//   users/{uid}.fcmTokens       the phone's push tokens (the app adds them; these Functions prune)
//   invites/{token}             inviterUid, inviterName, forName, status (open, accepted),
//                               expiresAt, acceptedBy, pairId. Only these Functions read them.
//   pairs/{uid1_uid2}           members [uid, uid], names {uid: name}, createdAt
//   sends/{uid_setId[_test]}    one scheduled or delivered set: senderUid, fromName, recipientUid
//                               (null until the invite is accepted), inviteToken, pairId, status
//                               (scheduled, delivered), deliverAt, deliveredAt, booklet (the
//                               recipient's copy, as JSON). The sender reads their own; the
//                               recipient reads it only once delivered. Only these Functions write.

const { onCall, HttpsError } = require("firebase-functions/v2/https");
const { onSchedule } = require("firebase-functions/v2/scheduler");
const { setGlobalOptions } = require("firebase-functions/v2");
const { defineSecret, defineString } = require("firebase-functions/params");
const logger = require("firebase-functions/logger");
const { initializeApp } = require("firebase-admin/app");
const { getFirestore, FieldValue, Timestamp } = require("firebase-admin/firestore");
const { getMessaging } = require("firebase-admin/messaging");

const lastfm = require("./lib/lastfm");
const spotify = require("./lib/spotify");
const facts = require("./lib/facts");
const notes = require("./lib/notes");
const sends = require("./lib/sends");
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
      facts: found,
    });
  } catch (err) {
    throw toHttpsError("draftNote", err);
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

/** Pushes the "you've got a set" notification to the recipient's phones. Never throws. */
async function notifyDelivered(sendId, data) {
  try {
    const user = await userDoc(data.recipientUid).get();
    const tokens = (user.exists && user.get("fcmTokens")) || [];
    if (!Array.isArray(tokens) || !tokens.length) return;
    const booklet = JSON.parse(data.booklet);
    const n = sends.notification(data.fromName, booklet);
    const result = await getMessaging().sendEachForMulticast({
      tokens: tokens.slice(0, 20),
      notification: n,
      data: { sendId },
      android: { priority: "high", notification: { channelId: "sends" } },
    });
    const dead = [];
    result.responses.forEach((r, i) => {
      const code = r.error && r.error.code;
      if (code === "messaging/registration-token-not-registered" || code === "messaging/invalid-registration-token") {
        dead.push(tokens[i]);
      }
    });
    if (dead.length) await userDoc(data.recipientUid).update({ fcmTokens: FieldValue.arrayRemove(...dead) });
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
  if (delivered) await notifyDelivered(ref.id, delivered);
  return !!delivered;
}

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

exports.acceptInvite = onCall(async (request) => {
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
exports.scheduleSend = onCall(async (request) => {
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
exports.deliverDueSends = onSchedule({ schedule: "every 15 minutes", timeoutSeconds: 300 }, async () => {
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
