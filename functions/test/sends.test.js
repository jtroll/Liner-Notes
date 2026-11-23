"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const sends = require("../lib/sends");

const set = {
  id: "abc",
  title: "Top Album Discoveries of 2026",
  forName: "Sam",
  taste: "private description",
  fromDay: "2026-01-01",
  toDay: "2026-11-22",
  count: 12,
  intro: "Hi",
  closing: "Bye",
  yearList: true,
  playlistUrl: "https://open.spotify.com/playlist/x",
  shortlist: [{ artist: "A", title: "B" }],
  fit: { k: { score: 9, reason: "r" } },
  entries: [
    {
      album: { artist: "The Smile", title: "Cutouts", plays: 80, weeks: 9, imageUrl: "https://img/x.jpg", spotifyUri: "spotify:album:1234567890abcdef" },
      note: "A note",
      aiDraft: "draft",
      flags: ["flag"],
      sources: [{ title: "t", url: "https://x" }],
      favoriteTracks: ["Foreign Spies", "  ", 3],
    },
    { album: { artist: "No title" } },
  ],
};

test("cleanBooklet keeps what the recipient sees and drops the sender's private parts", () => {
  const b = sends.cleanBooklet(JSON.stringify(set));
  assert.equal(b.title, set.title);
  assert.equal(b.entries.length, 1);
  assert.equal(b.count, 1);
  assert.deepEqual(b.entries[0].favoriteTracks, ["Foreign Spies"]);
  assert.equal(b.entries[0].album.spotifyUri, "spotify:album:1234567890abcdef");
  const json = JSON.stringify(b);
  for (const secret of ["private description", "draft", "flag", "plays", "weeks", "shortlist", "fit", "sources"]) {
    assert.ok(!json.includes(secret), `leaked ${secret}`);
  }
});

test("cleanBooklet refuses a set with no albums or no dates", () => {
  assert.throws(() => sends.cleanBooklet({ ...set, entries: [] }));
  assert.throws(() => sends.cleanBooklet({ ...set, fromDay: "soon" }));
  assert.throws(() => sends.cleanBooklet("not json"));
});

test("cleanBooklet drops links that aren't https and odd Spotify ids", () => {
  const b = sends.cleanBooklet({
    ...set,
    playlistUrl: "javascript:alert(1)",
    entries: [{ album: { artist: "A", title: "B", imageUrl: "http://x", spotifyUri: "spotify:track:1234567890" }, note: "" }],
  });
  assert.equal(b.playlistUrl, null);
  assert.equal(b.entries[0].album.imageUrl, null);
  assert.equal(b.entries[0].album.spotifyUri, null);
});

test("tokens are long, URL-safe and recognised", () => {
  const t = sends.newToken();
  assert.ok(sends.isToken(t));
  assert.ok(t.length >= 20);
  assert.ok(!sends.isToken("short"));
  assert.ok(!sends.isToken("../../etc/passwd/../../"));
});

test("delivery times: past means now, far future is refused", () => {
  const now = 1_000_000;
  assert.equal(sends.deliveryTime(now - 5, now), now);
  assert.equal(sends.deliveryTime(now + 60_000, now), now + 60_000);
  assert.equal(sends.deliveryTime("nope", now), now);
  assert.throws(() => sends.deliveryTime(now + 500 * 86_400_000, now));
});

test("a send is due only once it has a recipient and its time has come", () => {
  assert.ok(sends.isDue({ status: "scheduled", recipientUid: "r", deliverAtMs: 5 }, 10));
  assert.ok(!sends.isDue({ status: "scheduled", recipientUid: null, deliverAtMs: 5 }, 10));
  assert.ok(!sends.isDue({ status: "scheduled", recipientUid: "r", deliverAtMs: 50 }, 10));
  assert.ok(!sends.isDue({ status: "delivered", recipientUid: "r", deliverAtMs: 5 }, 10));
});

test("invite problems", () => {
  const open = { inviterUid: "a", status: "open", expiresAtMs: 100 };
  assert.equal(sends.inviteProblem(open, "b", 50), null);
  assert.match(sends.inviteProblem(open, "a", 50), /own invite/);
  assert.match(sends.inviteProblem(open, "b", 150), /expired/);
  assert.match(sends.inviteProblem(null, "b", 50), /doesn't exist/);
  assert.equal(sends.inviteProblem({ ...open, status: "accepted", acceptedBy: "b" }, "b", 50), null);
  assert.match(sends.inviteProblem({ ...open, status: "accepted", acceptedBy: "c" }, "b", 50), /already been used/);
});

test("the pair id doesn't depend on who invited whom", () => {
  assert.equal(sends.pairId("x", "y"), sends.pairId("y", "x"));
});

test("send ids keep the test send apart", () => {
  assert.equal(sends.sendId("u", "s", false), "u_s");
  assert.equal(sends.sendId("u", "s", true), "u_s_test");
});
