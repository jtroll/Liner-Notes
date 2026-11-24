"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const monthly = require("../lib/monthly");
const lastfm = require("../lib/lastfm");

test("albumKey folds case, accents and edition tags", () => {
  assert.equal(monthly.albumKey("Beyoncé", "COWBOY CARTER"), monthly.albumKey("beyonce", "Cowboy Carter"));
  assert.equal(monthly.albumKey("The Smile", "Cutouts (Deluxe Edition)"), monthly.albumKey("The Smile", "Cutouts"));
  assert.notEqual(monthly.albumKey("A", "B"), monthly.albumKey("A", "C"));
});

test("monthKey and dayOfMonth use the pair's time zone", () => {
  // 03:00 UTC on Dec 1 is still Nov 30 in California.
  const d = new Date(Date.UTC(2026, 11, 1, 3, 0));
  assert.equal(monthly.monthKey(d), "2026-11");
  assert.equal(monthly.dayOfMonth(d), 30);
  assert.equal(monthly.monthKey(d, "UTC"), "2026-12");
});

test("isMonth and monthName", () => {
  assert.ok(monthly.isMonth("2027-01"));
  assert.ok(!monthly.isMonth("2027-13"));
  assert.ok(!monthly.isMonth("2027-1"));
  assert.equal(monthly.monthName("2027-01"), "January");
});

test("monthlySendId is one per person, pair and month", () => {
  const a = monthly.monthlySendId("u1", "u1_u2", "2027-01");
  assert.match(a, /^u1_m202701_[0-9a-f]{12}$/);
  assert.equal(a, monthly.monthlySendId("u1", "u1_u2", "2027-01"));
  assert.notEqual(a, monthly.monthlySendId("u1", "u1_u3", "2027-01"));
  assert.notEqual(a, monthly.monthlySendId("u2", "u1_u2", "2027-01"));
});

test("cleanCandidates drops junk and duplicates, numbers the rest", () => {
  const c = monthly.cleanCandidates([
    { artist: "A", title: "One", plays: "20", weeks: 3 },
    { artist: "a", title: "one" },
    { artist: "", title: "x" },
    null,
    { artist: "B", title: "Two", plays: -4, fromEarlier: true },
  ]);
  assert.deepEqual(c, [
    { artist: "A", title: "One", plays: 20, weeks: 3, fromEarlier: false, id: "1" },
    { artist: "B", title: "Two", plays: 0, weeks: 0, fromEarlier: true, id: "2" },
  ]);
});

test("filterCandidates leaves out known and already-sent albums", () => {
  const c = monthly.cleanCandidates([
    { artist: "A", title: "One" },
    { artist: "B", title: "Two" },
    { artist: "C", title: "Three" },
  ]);
  const known = monthly.knownAlbums([
    { artist: "A", title: "One", plays: 9 },
    { artist: "C", title: "Three", plays: 2 },
  ]);
  const sent = new Set(monthly.bookletKeys(JSON.stringify({ entries: [{ album: { artist: "B", title: "Two" } }] })));
  assert.deepEqual(monthly.filterCandidates(c, known, sent).map((x) => x.title), ["Three"]);
  assert.deepEqual(monthly.bookletKeys("not json"), []);
});

test("suggestPrompt lists the candidates by id and asks for the pipe format", () => {
  const { system, user } = monthly.suggestPrompt({
    senderName: "Sam",
    recipientName: "Alex",
    month: "October",
    candidates: monthly.cleanCandidates([{ artist: "A", title: "One", plays: 30, weeks: 3 }, { artist: "B", title: "Two", fromEarlier: true }]),
    taste: { coreArtists: ["X"], typed: "loves folk" },
    reactions: { loved: ["Q — R"] },
  });
  assert.match(system, /id\|confidence\|connection/);
  assert.match(user, /1\. A — One, played 30 times over 3 weeks/);
  assert.match(user, /2\. B — Two \(from earlier\)/);
  assert.match(user, /loves folk/);
  assert.match(user, /Past picks Alex loved: Q — R/);
});

test("parseSuggestions keeps real ids once, best first, at most three", () => {
  const c = monthly.cleanCandidates([1, 2, 3, 4].map((n) => ({ artist: `A${n}`, title: `T${n}` })));
  const text = "<picks>\n2|4|Ties to X\n9|5|not a candidate\n2|3|dup\n1|2|meh\n3|5|great\n4|5|fourth\n</picks>";
  assert.deepEqual(monthly.parseSuggestions(text, c), [
    { id: "2", confidence: 4, connection: "Ties to X" },
    { id: "1", confidence: 2, connection: "meh" },
    { id: "3", confidence: 5, connection: "great" },
  ]);
  assert.deepEqual(monthly.parseSuggestions("no tags", c), []);
  assert.ok(monthly.isStretch([{ confidence: 2 }, { confidence: 1 }]));
  assert.ok(!monthly.isStretch([{ confidence: 3 }]));
});

test("reminderFor: draft day, a week later, the 25th, never once sent", () => {
  const base = { draftDay: 1, sent: false, monthlyOn: true, paused: false };
  assert.equal(monthly.reminderFor({ ...base, day: 1 }), "ready");
  assert.equal(monthly.reminderFor({ ...base, day: 8 }), "nudge");
  assert.equal(monthly.reminderFor({ ...base, day: 25 }), "nudge");
  assert.equal(monthly.reminderFor({ ...base, day: 12 }), null);
  assert.equal(monthly.reminderFor({ ...base, day: 8, sent: true }), null);
  assert.equal(monthly.reminderFor({ ...base, day: 1, monthlyOn: false }), null);
  assert.equal(monthly.reminderFor({ ...base, day: 1, paused: true }), null);
  assert.equal(monthly.reminderFor({ ...base, draftDay: 26, day: 25 }), null);
  assert.equal(monthly.reminderText("ready", "Alex", "2027-01").title, "Time for your January pick");
});

test("cleanReaction and cleanPairSettings", () => {
  assert.deepEqual(monthly.cleanReaction({ index: 0, reaction: "loved", comment: " yes " }, 1), { index: 0, reaction: "loved", comment: "yes" });
  assert.deepEqual(monthly.cleanReaction({ index: 0, reaction: null }, 1), { index: 0, reaction: null, comment: "" });
  assert.throws(() => monthly.cleanReaction({ index: 1, reaction: "loved" }, 1));
  assert.throws(() => monthly.cleanReaction({ index: 0, reaction: "meh" }, 1));
  assert.deepEqual(monthly.cleanPairSettings({ nickname: " Us ", monthlyOn: false, draftDay: 3, other: 1 }), {
    nickname: "Us",
    monthlyOn: false,
    draftDay: 3,
  });
  assert.throws(() => monthly.cleanPairSettings({ draftDay: 30 }));
});

test("Last.fm top lists parse singles and lists", () => {
  assert.deepEqual(lastfm.parseTopArtists({ topartists: { artist: { name: "X", playcount: "12" } } }), [{ name: "X", plays: 12 }]);
  assert.deepEqual(
    lastfm.parseTopAlbums({ topalbums: { album: [{ name: "One", artist: { name: "A" }, playcount: "5" }, { name: "Bad" }] } }),
    [{ artist: "A", title: "One", plays: 5 }],
  );
});
