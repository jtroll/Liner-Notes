"use strict";
// Sending a set (SPEC.md, "Delivery and the recipient's side"): the pure parts, kept apart from
// Firestore so they can be tested. index.js does the reading and writing.
//
// A send carries a copy of the booklet as it was when the sender scheduled it, cut down to what
// the recipient sees. Everything private to the sender stays behind: Claude's drafts, fact-check
// flags, sources, the shortlist, the fit scores, the sender's description of the recipient's
// taste, and the play counts from their Last.fm.

const crypto = require("crypto");

const MAX_ENTRIES = 30;
const INVITE_DAYS = 120;

/** An unguessable invite token, safe in a URL path. */
function newToken() {
  return crypto.randomBytes(16).toString("base64url");
}

function isToken(s) {
  return typeof s === "string" && /^[A-Za-z0-9_-]{16,64}$/.test(s);
}

function str(v, max) {
  return typeof v === "string" ? v.slice(0, max) : "";
}

function optStr(v, max) {
  return typeof v === "string" && v.trim() ? v.trim().slice(0, max) : null;
}

function isDay(v) {
  return typeof v === "string" && /^\d{4}-\d{2}-\d{2}$/.test(v);
}

function httpsUrl(v) {
  if (typeof v !== "string" || v.length > 1000) return null;
  try {
    const u = new URL(v);
    return u.protocol === "https:" ? u.toString() : null;
  } catch (_) {
    return null;
  }
}

function spotifyAlbumUri(v) {
  return typeof v === "string" && /^spotify:album:[A-Za-z0-9]{10,40}$/.test(v) ? v : null;
}

/**
 * The booklet the recipient gets: the set's JSON (the app's GroupSet) reduced to the fields the
 * booklet shows. Throws on anything that isn't a usable set.
 */
function cleanBooklet(raw) {
  const set = typeof raw === "string" ? JSON.parse(raw) : raw;
  if (!set || typeof set !== "object") throw new Error("not a set");
  const entries = (Array.isArray(set.entries) ? set.entries : [])
    .filter((e) => e && e.album && typeof e.album.artist === "string" && typeof e.album.title === "string")
    .slice(0, MAX_ENTRIES)
    .map((e) => ({
      album: {
        artist: str(e.album.artist, 300),
        title: str(e.album.title, 300),
        imageUrl: httpsUrl(e.album.imageUrl),
        mbid: optStr(e.album.mbid, 64),
        spotifyUri: spotifyAlbumUri(e.album.spotifyUri),
      },
      note: str(e.note, 6000),
      favoriteTracks: (Array.isArray(e.favoriteTracks) ? e.favoriteTracks : [])
        .filter((t) => typeof t === "string" && t.trim())
        .slice(0, 9)
        .map((t) => t.trim().slice(0, 200)),
    }));
  if (!entries.length) throw new Error("no albums");
  const title = str(set.title, 120).trim();
  if (!title || !isDay(set.fromDay) || !isDay(set.toDay)) throw new Error("missing title or dates");
  return {
    id: str(set.id, 64),
    title,
    forName: str(set.forName, 80),
    fromDay: set.fromDay,
    toDay: set.toDay,
    count: entries.length,
    entries,
    intro: str(set.intro, 6000),
    closing: str(set.closing, 6000),
    yearList: set.yearList === true,
    playlistUrl: httpsUrl(set.playlistUrl),
    kind: set.kind === "monthly" ? "monthly" : "group",
    month: typeof set.month === "string" && /^\d{4}-\d{2}$/.test(set.month) ? set.month : null,
  };
}

/** The send's document id: one live send per set, plus a separate test send to oneself. */
function sendId(uid, setId, test) {
  return `${uid}_${setId}${test ? "_test" : ""}`;
}

/** Checks the delivery time the app asked for. Past times mean "now". */
function deliveryTime(ms, now) {
  const n = Number(ms);
  if (!Number.isFinite(n)) return now;
  if (n > now + 400 * 86_400_000) throw new Error("too far ahead");
  return Math.max(n, now);
}

/** Whether a scheduled send should go out now. */
function isDue(send, now) {
  return send.status === "scheduled" && !!send.recipientUid && send.deliverAtMs <= now;
}

/** Why an invite can't be accepted by this person, or null when it can. */
function inviteProblem(invite, uid, now) {
  if (!invite) return "That invite doesn't exist. Ask for a new link.";
  if (invite.inviterUid === uid) return "That's your own invite. Send the link to the person it's for.";
  if (invite.status === "accepted") return invite.acceptedBy === uid ? null : "That invite has already been used.";
  if (invite.status !== "open") return "That invite was cancelled. Ask for a new link.";
  if (invite.expiresAtMs && invite.expiresAtMs < now) return "That invite has expired. Ask for a new link.";
  return null;
}

/** The pair's id: the same two people always get the same pair, whoever invited whom. */
function pairId(a, b) {
  return [a, b].sort().join("_");
}

/** The push notification for a delivered send. */
function notification(fromName, booklet) {
  const from = fromName || "Someone";
  if (booklet.kind === "monthly" && booklet.entries.length) {
    const a = booklet.entries[0].album;
    return { title: `${from} sent you an album`, body: `${a.artist} — ${a.title}, with a note. Open Liner Notes to read it.` };
  }
  const n = booklet.entries.length;
  return {
    title: `${from} sent you ${booklet.title}`,
    body: `${n} album${n === 1 ? "" : "s"}, with notes. Open Liner Notes to read the booklet.`,
  };
}

module.exports = {
  INVITE_DAYS,
  newToken,
  isToken,
  cleanBooklet,
  sendId,
  deliveryTime,
  isDue,
  inviteProblem,
  pairId,
  notification,
};
