"use strict";
// Influence (SPEC.md, "Influence"): how much one person's sends showed up in the other person's
// listening, from the recipient's Last.fm weekly charts after each send against the 90 days
// before it. Pure functions here; functions/index.js fetches the charts and stores the results.
//
// Only pairs where both people turned influence sharing on are measured, and only these
// summaries leave the recipient's own documents.

const DEFAULT_WEIGHTS = { listened: 1, cameBack: 2, deeper: 2, top20: 3, spilled: 1 };
const DAY = 24 * 60 * 60 * 1000;
const BASELINE_DAYS = 90;
const MARKS = [30, 90];
/** The first look waits this long after delivery, so there's a week of charts to read. */
const FIRST_LOOK_DAYS = 7;

/** Folds case, accents, punctuation and edition tags, so names from different places match. */
function norm(s) {
  return String(s || "")
    .normalize("NFKD")
    .replace(/[̀-ͯ]/g, "")
    .toLowerCase()
    .replace(/&/g, " and ")
    .replace(/[([][^)\]]*(deluxe|expanded|remaster|anniversary|edition|bonus|version)[^)\]]*[)\]]/g, "")
    .replace(/[^a-z0-9]+/g, " ")
    .trim();
}

const albumKey = (artist, title) => `${norm(artist)}|${norm(title)}`;

/**
 * Sums weekly charts into { albums: Map albumKey -> {artist, title, plays}, artists: Map norm -> {name, plays} }.
 * A week is { albums: [[artist, title, plays]], artists: [[name, plays]] }.
 */
function sumWeeks(weeks) {
  const albums = new Map();
  const artists = new Map();
  for (const w of weeks || []) {
    for (const [artist, title, plays] of w.albums || []) {
      const k = albumKey(artist, title);
      const before = albums.get(k);
      albums.set(k, { artist, title, plays: (before ? before.plays : 0) + (Number(plays) || 0) });
    }
    for (const [name, plays] of w.artists || []) {
      const k = norm(name);
      const before = artists.get(k);
      artists.set(k, { name, plays: (before ? before.plays : 0) + (Number(plays) || 0) });
    }
  }
  return { albums, artists };
}

/**
 * The [from, to] weeks (unix seconds) inside [startMs, endMs), allowing a week to start or end up
 * to `slackDays` outside it. The baseline uses no slack, so no week counts both before and after.
 */
function weeksBetween(chartList, startMs, endMs, slackDays = 0) {
  const s = Math.floor(startMs / 1000);
  const e = Math.floor(endMs / 1000);
  const slack = slackDays * 86400;
  return (chartList || []).filter((w) => w.from >= s - slack && w.to <= e + slack && w.to > s && w.from < e);
}

/** How many times the album was played through: its plays over about 80% of its track count. */
function nearFullPlays(plays, trackCount) {
  const per = trackCount > 0 ? Math.max(1, Math.round(trackCount * 0.8)) : 8;
  return Math.floor((plays || 0) / per);
}

function topN(artists, n) {
  return new Set([...artists.entries()].sort((a, b) => b[1].plays - a[1].plays).slice(0, n).map(([k]) => k));
}

/**
 * One album's influence. `input`:
 *   album {artist, title}, before and after (sumWeeks results), trackCount,
 *   similar [artist names from Last.fm], weights.
 */
function measure(input) {
  const w = { ...DEFAULT_WEIGHTS, ...(input.weights || {}) };
  const { before, after } = input;
  const artistKey = norm(input.album.artist);
  const key = albumKey(input.album.artist, input.album.title);
  const playsBefore = (before.albums.get(key) || {}).plays || 0;
  const plays = (after.albums.get(key) || {}).plays || 0;
  const full = nearFullPlays(plays, input.trackCount);

  // Other albums by the artist they hadn't played before.
  const deeper = [];
  for (const [k, a] of after.albums) {
    if (k === key || !k.startsWith(`${artistKey}|`)) continue;
    if (!before.albums.has(k) && a.plays >= 3) deeper.push(a.title);
  }

  // The artist in their top 20 after the send, when it wasn't before.
  const top20 = topN(after.artists, 20).has(artistKey) && !topN(before.artists, 20).has(artistKey);

  // Similar artists they hadn't played before.
  const spilled = [];
  for (const name of input.similar || []) {
    const k = norm(name);
    if (k === artistKey || before.artists.has(k)) continue;
    const a = after.artists.get(k);
    if (a && a.plays >= 3) spilled.push(a.name);
  }

  const listened = full >= 1;
  const cameBack = full >= 3;
  const points =
    (listened ? w.listened : 0) +
    (cameBack ? w.cameBack : 0) +
    (deeper.length ? w.deeper : 0) +
    (top20 ? w.top20 : 0) +
    (spilled.length ? w.spilled : 0);
  return {
    plays,
    playsBefore,
    fullPlays: full,
    listened,
    cameBack,
    deeper: deeper.slice(0, 5),
    top20,
    // A count only: which similar artists they found stays in their own listening.
    spilled: spilled.length,
    points,
  };
}

function maxPoints(weights) {
  const w = { ...DEFAULT_WEIGHTS, ...(weights || {}) };
  return w.listened + w.cameBack + w.deeper + w.top20 + w.spilled;
}

const words = ["no", "one", "two", "three", "four", "five"];
const count = (n, one, many) => `${n < words.length ? words[n] : n} ${n === 1 ? one : many}`;

/** A short warm line, like "Sam has played Cutouts 14 times and tried two more The Smile albums". */
function line(name, album, r) {
  const who = name || "They";
  if (!r.plays) return `${who} hasn't played ${album.title} yet.`;
  const parts = [`${who} has played ${album.title} ${r.plays === 1 ? "once" : `${r.plays} times`}`];
  const extra = [];
  if (r.deeper.length) extra.push(`tried ${count(r.deeper.length, `more ${album.artist} album`, `more ${album.artist} albums`)}`);
  if (r.top20) extra.push(`${album.artist} made their top 20`);
  if (r.spilled) extra.push(`found ${count(r.spilled, "similar artist", "similar artists")}`);
  if (extra.length === 1) parts.push(`and ${extra[0]}`);
  else if (extra.length > 1) parts.push(`, ${extra.slice(0, -1).join(", ")} and ${extra[extra.length - 1]}`);
  return parts.join(extra.length > 1 ? "" : " ") + ".";
}

/**
 * Which measurements a send is due, `ageDays` after delivery: "latest" (a running look, once a
 * week of charts exists, until 90 days), and the 30 and 90 day marks once each.
 */
function due(ageDays, influence) {
  const have = influence || {};
  const out = [];
  if (ageDays >= FIRST_LOOK_DAYS && ageDays < 92) out.push("latest");
  for (const m of MARKS) if (ageDays >= m && !have[`d${m}`]) out.push(`d${m}`);
  return out;
}

/** Whether both members of a pair turned sharing on: pairs/{id}.influence = { uid: true }. */
function sharing(pair) {
  const members = (pair && pair.members) || [];
  const flags = (pair && pair.influence) || {};
  return members.length === 2 && members.every((m) => flags[m] === true);
}

/** For suggestions: the sender's past picks, by how they landed. */
function landed(results) {
  const well = [];
  const not = [];
  for (const r of results) {
    if (r.points >= 4) well.push(r.heading);
    else if (r.measured && r.points === 0) not.push(r.heading);
  }
  return { well, not };
}

module.exports = {
  DEFAULT_WEIGHTS,
  DAY,
  BASELINE_DAYS,
  MARKS,
  FIRST_LOOK_DAYS,
  norm,
  albumKey,
  sumWeeks,
  weeksBetween,
  nearFullPlays,
  measure,
  maxPoints,
  line,
  due,
  sharing,
  landed,
};
