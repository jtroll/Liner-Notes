"use strict";
// Phase 6 (SPEC.md, "Build order"): the pure parts of running playlists, the Spotify sign-in
// reminders and "delete my data", kept apart from Firestore and the APIs so they can be tested.
// index.js does the reading, writing and calling.

const DAY = 86_400_000;
/** Spotify refresh tokens stop working this long after the first sign-in (SPEC.md). */
const SPOTIFY_TOKEN_DAYS = 182;
/** The reminder comes this long before the sign-in runs out (SPEC.md, "First run"). */
const REMIND_DAYS = 14;

// ---- Playlists in the recipient's Spotify -------------------------------------------------

/** The running playlist each month's pick goes into: "From J.T.". */
function runningName(fromName) {
  const who = String(fromName || "").trim().slice(0, 60);
  return who ? `From ${who}` : "From Liner Notes";
}

function runningDescription(fromName) {
  const who = String(fromName || "").trim().slice(0, 60) || "your pair";
  return `Every album ${who} sent you in Liner Notes, newest at the bottom.`;
}

/** A group send's own playlist, in the recipient's Spotify. */
function setName(title) {
  return String(title || "").trim().slice(0, 100) || "Liner Notes";
}

function setDescription(fromName, count) {
  const who = String(fromName || "").trim().slice(0, 60) || "Someone";
  return `${count} albums from ${who}, in the order of the booklet. Made by Liner Notes.`;
}

/**
 * What the send records about the recipient's playlist, for both people to see:
 * { state: "done" | "failed" | "notConnected", url, missing: [titles], reason, at }.
 * The sender sees a failure with Retry; nobody is told off for not connecting Spotify.
 */
function playlistRecord(state, { url = null, missing = [], reason = null, at = Date.now() } = {}) {
  const out = { state, at };
  if (url) out.url = url;
  if (missing.length) out.missing = missing.slice(0, 30).map((t) => String(t).slice(0, 200));
  if (reason) out.reason = String(reason).slice(0, 200);
  return out;
}

/** Plain words for why adding to the recipient's Spotify failed, safe to show the sender. */
function failureReason(err) {
  const grant = err && err.body && err.body.error === "invalid_grant";
  if (grant || (err && err.expired)) return "Their Spotify sign-in has run out.";
  if (err && err.status === 403) return "Spotify refused. They may not be on the app's Spotify user list yet.";
  if (err && err.status === 429) return "Spotify asked us to slow down.";
  if (err && err.notFound) return "None of the albums could be found on Spotify.";
  return "Spotify didn't answer properly.";
}

/** Whether a stored running playlist should be replaced with a new one after this error. */
function playlistGone(err) {
  return !!err && (err.status === 404 || err.status === 403) && !(err.body && err.body.error === "invalid_grant");
}

// ---- Spotify sign-in reminders ------------------------------------------------------------

/**
 * Which reminder, if any, a person's Spotify sign-in needs today: "soon" once, two weeks out, and
 * "expired" once, on the day it runs out. remindedFor says which expiry each reminder went for, so
 * reconnecting (a new expiry) starts afresh.
 */
function expiryReminder({ expiresAtMs, remindedSoonFor, remindedExpiredFor }, nowMs) {
  if (!Number.isFinite(expiresAtMs)) return null;
  const left = expiresAtMs - nowMs;
  if (left <= 0) return remindedExpiredFor === expiresAtMs ? null : "expired";
  if (left <= REMIND_DAYS * DAY) return remindedSoonFor === expiresAtMs ? null : "soon";
  return null;
}

function expiryText(kind, expiresAtMs, timeZone) {
  const day = new Date(expiresAtMs).toLocaleDateString("en-US", { month: "long", day: "numeric", timeZone });
  return kind === "soon"
    ? {
        title: "Your Spotify sign-in runs out soon",
        body: `Spotify asks everyone to sign in again every six months. Yours runs out on ${day}. Tap to reconnect; it takes one tap.`,
      }
    : {
        title: "Your Spotify sign-in has run out",
        body: "Tap to reconnect Spotify, so albums and playlists keep arriving in your account.",
      };
}

// ---- Delete my data -----------------------------------------------------------------------

/**
 * What "delete my data" does with each send the person is part of. Their own sends go (their
 * words); sends they received keep the sender's booklet, so the other person's archive survives,
 * but lose everything that came from the deleted person: reactions, influence (from their
 * listening) and the playlist record from their Spotify.
 */
function deletionPlan(send, uid) {
  if (send.senderUid === uid) return { action: "delete" };
  if (send.recipientUid === uid) {
    return { action: "strip", fields: ["reactions", "influence", "playlist"] };
  }
  return { action: "keep" };
}

module.exports = {
  DAY,
  SPOTIFY_TOKEN_DAYS,
  REMIND_DAYS,
  runningName,
  runningDescription,
  setName,
  setDescription,
  playlistRecord,
  failureReason,
  playlistGone,
  expiryReminder,
  expiryText,
  deletionPlan,
};
