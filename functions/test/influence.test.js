"use strict";
const test = require("node:test");
const assert = require("node:assert");
const inf = require("../lib/influence");

const before = inf.sumWeeks([
  { albums: [["Radiohead", "In Rainbows", 20]], artists: [["Radiohead", 20], ["Portishead", 4]] },
]);
const after = inf.sumWeeks([
  { albums: [["The Smile", "Cutouts", 20], ["The Smile", "Wall of Eyes", 6]], artists: [["The Smile", 26], ["Radiohead", 3]] },
  { albums: [["The Smile", "Cutouts (Deluxe Edition)", 8], ["The Smile", "A Light for Attracting Attention", 4]], artists: [["The Smile", 12], ["Thom Yorke", 5], ["Portishead", 9]] },
]);

test("sumWeeks adds plays across weeks and folds edition tags", () => {
  assert.equal(after.albums.get(inf.albumKey("The Smile", "Cutouts")).plays, 28);
  assert.equal(after.artists.get(inf.norm("the smile")).plays, 38);
});

test("measure scores listening, coming back, going deeper, top 20 and similar artists", () => {
  const r = inf.measure({
    album: { artist: "The Smile", title: "Cutouts" },
    before,
    after,
    trackCount: 10,
    similar: ["Thom Yorke", "Portishead", "Atoms for Peace"],
  });
  assert.equal(r.plays, 28);
  assert.equal(r.fullPlays, 3);
  assert.equal(r.listened, true);
  assert.equal(r.cameBack, true);
  assert.deepEqual(r.deeper.sort(), ["A Light for Attracting Attention", "Wall of Eyes"]);
  assert.equal(r.top20, true);
  assert.equal(r.spilled, 1); // Portishead was already played before the send
  assert.equal(r.points, 1 + 2 + 2 + 3 + 1);
  assert.equal(inf.maxPoints(), 9);
});

test("line reads warmly and handles nothing played", () => {
  const album = { artist: "The Smile", title: "Cutouts" };
  assert.equal(inf.line("Sam", album, { plays: 0, deeper: [], top20: false, spilled: 0 }), "Sam hasn't played Cutouts yet.");
  assert.equal(
    inf.line("Sam", album, { plays: 14, deeper: ["a", "b"], top20: false, spilled: 0 }),
    "Sam has played Cutouts 14 times and tried two more The Smile albums.",
  );
  assert.equal(
    inf.line("Sam", album, { plays: 1, deeper: ["a"], top20: true, spilled: 2 }),
    "Sam has played Cutouts once, tried one more The Smile album, The Smile made their top 20 and found two similar artists.",
  );
});

test("weeksBetween keeps the baseline and the after window apart", () => {
  const day = 86400;
  const list = [0, 7, 14, 21].map((n) => ({ from: n * day, to: (n + 7) * day }));
  const sent = 10 * day * 1000;
  const base = inf.weeksBetween(list, sent - 90 * day * 1000, sent, 0).map((w) => w.from / day);
  const aft = inf.weeksBetween(list, sent, sent + 20 * day * 1000, 3).map((w) => w.from / day);
  assert.deepEqual(base, [0]);
  assert.deepEqual(aft, [7, 14, 21]);
});

test("due: a running look after a week, then each mark once; sharing needs both", () => {
  assert.deepEqual(inf.due(3, {}), []);
  assert.deepEqual(inf.due(8, {}), ["latest"]);
  assert.deepEqual(inf.due(31, {}), ["latest", "d30"]);
  assert.deepEqual(inf.due(31, { d30: {} }), ["latest"]);
  assert.deepEqual(inf.due(95, { d30: {} }), ["d90"]);
  assert.equal(inf.sharing({ members: ["a", "b"], influence: { a: true } }), false);
  assert.equal(inf.sharing({ members: ["a", "b"], influence: { a: true, b: true } }), true);
});
