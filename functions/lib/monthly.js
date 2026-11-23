"use strict";
// The monthly send (SPEC.md, "Monthly send"): the pure parts, kept apart from Firestore and the
// APIs so they can be tested. index.js does the reading, writing and calling.
//
// Each month, each person in a pair sends the other one album they loved last month. The app
// gathers the sender's candidates from their own Last.fm charts; these Functions add what the app
// may not see (the recipient's taste, what's already been sent in the pair, how past picks
// landed), and Claude picks one album and two alternates from the candidates, each with the
// connection to something the recipient already loves.

const crypto = require("crypto");

const MAX_CANDIDATES = 30;
const DEFAULT_DRAFT_DAY = 1;
const TIME_ZONE = "America/Los_Angeles";

function str(v, max) {
  return typeof v === "string" ? v.trim().slice(0, max) : "";
}

function int(v) {
  const n = Number(v);
  return Number.isFinite(n) ? Math.max(0, Math.round(n)) : 0;
}

/** Artist and title folded the same way everywhere, so the same album matches across sources. */
function albumKey(artist, title) {
  const fold = (s) =>
    String(s || "")
      .normalize("NFKD")
      .replace(/[̀-ͯ]/g, "")
      .toLowerCase()
      .replace(/\s*[([].*?(deluxe|remaster|edition|expanded|anniversary|bonus).*?[)\]]/g, "")
      .replace(/[^a-z0-9]+/g, " ")
      .trim();
  return `${fold(artist)}|${fold(title)}`;
}

/** "2026-11" for a date in the pair's time zone. */
function monthKey(date, timeZone = TIME_ZONE) {
  const parts = new Intl.DateTimeFormat("en-CA", { timeZone, year: "numeric", month: "2-digit" }).formatToParts(date);
  const y = parts.find((p) => p.type === "year").value;
  const m = parts.find((p) => p.type === "month").value;
  return `${y}-${m}`;
}

/** Day of the month (1 to 31) for a date in the pair's time zone. */
function dayOfMonth(date, timeZone = TIME_ZONE) {
  return Number(new Intl.DateTimeFormat("en-CA", { timeZone, day: "numeric" }).format(date));
}

function isMonth(v) {
  return typeof v === "string" && /^\d{4}-(0[1-9]|1[0-2])$/.test(v);
}

/** "November" for "2026-11". */
function monthName(month) {
  const [y, m] = month.split("-").map(Number);
  return new Date(Date.UTC(y, m - 1, 15)).toLocaleString("en-US", { month: "long", timeZone: "UTC" });
}

/**
 * The send's document id: one monthly send per person, per pair, per month. The pair id is two
 * Firebase uids, so it's shortened to keep the id readable.
 */
function monthlySendId(uid, pairId, month) {
  const pair = crypto.createHash("sha256").update(pairId).digest("hex").slice(0, 12);
  return `${uid}_m${month.replace("-", "")}_${pair}`;
}

/** The app's candidate list, checked and trimmed. */
function cleanCandidates(raw) {
  const seen = new Set();
  return (Array.isArray(raw) ? raw : [])
    .filter((c) => c && typeof c.artist === "string" && typeof c.title === "string" && c.artist.trim() && c.title.trim())
    .map((c) => ({
      artist: str(c.artist, 300),
      title: str(c.title, 300),
      plays: int(c.plays),
      weeks: int(c.weeks),
      fromEarlier: c.fromEarlier === true,
    }))
    .filter((c) => {
      const k = albumKey(c.artist, c.title);
      if (seen.has(k)) return false;
      seen.add(k);
      return true;
    })
    .slice(0, MAX_CANDIDATES)
    .map((c, i) => ({ ...c, id: String(i + 1) }));
}

/** The recipient's "known set" of albums: anything they've played more than 3 times. */
function knownAlbums(topAlbums) {
  return new Set((topAlbums || []).filter((a) => int(a.plays) > 3).map((a) => albumKey(a.artist, a.title)));
}

/** Candidates the recipient doesn't already know and nobody has sent within the pair. */
function filterCandidates(candidates, known, sent) {
  return candidates.filter((c) => {
    const k = albumKey(c.artist, c.title);
    return !known.has(k) && !sent.has(k);
  });
}

/** Every album in a send's booklet, as keys. */
function bookletKeys(bookletJson) {
  try {
    const b = typeof bookletJson === "string" ? JSON.parse(bookletJson) : bookletJson;
    return (b.entries || []).filter((e) => e && e.album).map((e) => albumKey(e.album.artist, e.album.title));
  } catch (_) {
    return [];
  }
}

const list = (items, max) => (items || []).filter(Boolean).slice(0, max).join("; ") || "none known";

/**
 * The suggestion prompt. `input`:
 *   senderName, recipientName, month (the listening month, "October"),
 *   candidates [{id, artist, title, plays, weeks, fromEarlier}],
 *   taste {coreArtists [string], recentArtists [string], recentAlbums [string], typed},
 *   reactions {loved [string], liked [string], notForMe [string]},
 *   recentPicks [string] (the sender's last few monthly picks for this person)
 */
function suggestPrompt(input) {
  const sender = input.senderName || "the sender";
  const recipient = input.recipientName || "the recipient";
  const system =
    `You help ${sender} choose this month's album to send ${recipient}: one album ${sender} loved recently, ` +
    `picked so it lands with ${recipient}.\n\n` +
    "Rules:\n" +
    "- Choose only from the candidate list, by its id.\n" +
    `- Prefer an album that bridges to an artist or album ${recipient} already loves over one that is merely popular.\n` +
    `- Weigh how much ${sender} played it: a real favourite makes a better gift than a passing listen.\n` +
    "- Vary the picks from month to month (genre, era, mood), so recent picks shouldn't be repeated in spirit.\n" +
    `- Learn from how past picks landed with ${recipient}: lean towards what they loved and away from what wasn't for them.\n` +
    "- Candidates marked (from earlier) were played before last month; use them only if they're clearly better.\n\n" +
    "Rank one suggestion and two alternates. For each, give a confidence from 1 to 5 that it will land, and the connection: " +
    `which of ${recipient}'s artists or albums it relates to and how, in at most 20 words, written to ${sender}.\n` +
    "Reply inside <picks></picks> with one line per album, best first: id|confidence|connection";

  const t = input.taste || {};
  const r = input.reactions || {};
  const lines = [
    `${recipient}'s core artists (the past year and all time): ${list(t.coreArtists, 30)}`,
    `${recipient}'s recent listening (the past 3 months): ${list(t.recentArtists, 15)}`,
    `${recipient}'s recent albums: ${list(t.recentAlbums, 15)}`,
  ];
  if (t.typed) lines.push(`${recipient}'s taste, in ${sender}'s words: ${t.typed}`);
  lines.push(
    `Past picks ${recipient} loved: ${list(r.loved, 10)}`,
    `Past picks ${recipient} liked: ${list(r.liked, 10)}`,
    `Past picks that weren't for ${recipient}: ${list(r.notForMe, 10)}`,
    `${sender}'s recent monthly picks for ${recipient}: ${list(input.recentPicks, 6)}`,
  );
  const candidates = input.candidates
    .map((c) => {
      const plays = c.plays ? `, played ${c.plays} times${c.weeks > 1 ? ` over ${c.weeks} weeks` : ""}` : "";
      return `${c.id}. ${c.artist} — ${c.title}${plays}${c.fromEarlier ? " (from earlier)" : ""}`;
    })
    .join("\n");
  const user = `About ${recipient}:\n${lines.map((l) => `- ${l}`).join("\n")}\n\n${sender}'s candidates from ${input.month || "last month"}:\n${candidates}`;
  return { system, user };
}

/** The picks Claude returned, keeping only real candidate ids, each once, best first, at most 3. */
function parseSuggestions(text, candidates) {
  const m = /<picks>([\s\S]*?)<\/picks>/.exec(text || "");
  const byId = new Map(candidates.map((c) => [c.id, c]));
  const out = [];
  for (const line of (m ? m[1] : "").split("\n")) {
    const p = /^\s*(\d+)\s*\|\s*([1-5])\s*\|\s*(.*)$/.exec(line);
    if (!p || !byId.has(p[1]) || out.some((o) => o.id === p[1])) continue;
    out.push({ id: p[1], confidence: Number(p[2]), connection: p[3].trim().slice(0, 300) });
    if (out.length === 3) break;
  }
  return out;
}

/** "A stretch pick" when nothing reached confidence 3. */
function isStretch(picks) {
  return picks.length > 0 && picks.every((p) => p.confidence < 3);
}

/**
 * Which reminder, if any, goes to a member today: "ready" on the pair's draft day, "nudge" a week
 * later and on the 25th while the month's send hasn't gone.
 */
function reminderFor({ day, draftDay, sent, monthlyOn, paused }) {
  if (sent || monthlyOn === false || paused) return null;
  const start = Math.min(28, Math.max(1, int(draftDay) || DEFAULT_DRAFT_DAY));
  if (day === start) return "ready";
  if (day === start + 7 || (day === 25 && day > start)) return "nudge";
  return null;
}

function reminderText(kind, partnerName, month) {
  const who = partnerName || "your pair";
  const name = monthName(month);
  return kind === "ready"
    ? { title: `Time for your ${name} pick`, body: `Open Liner Notes to pick an album for ${who}. A suggestion and a drafted note are waiting.` }
    : { title: `${who} hasn't had your ${name} pick yet`, body: "Still time. The suggestion and note take a couple of minutes." };
}

const REACTIONS = ["loved", "liked", "notForMe"];

/** A reaction from the recipient, checked. */
function cleanReaction(data, entryCount) {
  const index = Number(data && data.index);
  if (!Number.isInteger(index) || index < 0 || index >= entryCount) throw new Error("no such album");
  const reaction = data.reaction;
  if (reaction !== null && !REACTIONS.includes(reaction)) throw new Error("unknown reaction");
  return { index, reaction, comment: str(data.comment, 1000) };
}

function reactionWord(reaction) {
  return reaction === "loved" ? "loved" : reaction === "liked" ? "liked" : reaction === "notForMe" ? "wasn't into" : "reacted to";
}

/** Pair settings the members may change. */
function cleanPairSettings(data) {
  const out = {};
  if (typeof data.nickname === "string") out.nickname = data.nickname.trim().slice(0, 60);
  if (typeof data.monthlyOn === "boolean") out.monthlyOn = data.monthlyOn;
  if (typeof data.decemberPause === "boolean") out.decemberPause = data.decemberPause;
  if (data.draftDay !== undefined) {
    const d = Number(data.draftDay);
    if (!Number.isInteger(d) || d < 1 || d > 28) throw new Error("draft day must be 1 to 28");
    out.draftDay = d;
  }
  return out;
}

module.exports = {
  MAX_CANDIDATES,
  DEFAULT_DRAFT_DAY,
  TIME_ZONE,
  albumKey,
  monthKey,
  dayOfMonth,
  isMonth,
  monthName,
  monthlySendId,
  cleanCandidates,
  knownAlbums,
  filterCandidates,
  bookletKeys,
  suggestPrompt,
  parseSuggestions,
  isStretch,
  reminderFor,
  reminderText,
  cleanReaction,
  reactionWord,
  cleanPairSettings,
};
