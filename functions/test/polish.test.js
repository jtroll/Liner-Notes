"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const polish = require("../lib/polish");

const DAY = polish.DAY;

test("running playlist is named for the sender", () => {
  assert.equal(polish.runningName("J.T."), "From J.T.");
  assert.equal(polish.runningName("  "), "From Liner Notes");
  assert.match(polish.runningDescription("Sam"), /Every album Sam sent you/);
});

test("group playlist takes the set's title", () => {
  assert.equal(polish.setName("Twelve for 2027"), "Twelve for 2027");
  assert.equal(polish.setName(""), "Liner Notes");
  assert.match(polish.setDescription("Sam", 12), /^12 albums from Sam/);
});

test("playlist record keeps only what it needs", () => {
  const r = polish.playlistRecord("done", { url: "https://open.spotify.com/playlist/x", missing: ["A"], at: 5 });
  assert.deepEqual(r, { state: "done", at: 5, url: "https://open.spotify.com/playlist/x", missing: ["A"] });
  assert.deepEqual(polish.playlistRecord("notConnected", { at: 1 }), { state: "notConnected", at: 1 });
});

test("failure reasons are plain words", () => {
  assert.match(polish.failureReason({ body: { error: "invalid_grant" } }), /run out/);
  assert.match(polish.failureReason({ status: 403 }), /user list/);
  assert.match(polish.failureReason({ notFound: true }), /found/);
  assert.match(polish.failureReason(new Error("x")), /didn't answer/);
});

test("a deleted running playlist is replaced, an expired sign-in isn't", () => {
  assert.equal(polish.playlistGone({ status: 404 }), true);
  assert.equal(polish.playlistGone({ status: 403 }), true);
  assert.equal(polish.playlistGone({ status: 400, body: { error: "invalid_grant" } }), false);
  assert.equal(polish.playlistGone({ status: 500 }), false);
});

test("expiry reminders: once two weeks out, once on the day", () => {
  const exp = 100 * DAY;
  assert.equal(polish.expiryReminder({ expiresAtMs: exp }, exp - 20 * DAY), null);
  assert.equal(polish.expiryReminder({ expiresAtMs: exp }, exp - 14 * DAY), "soon");
  assert.equal(polish.expiryReminder({ expiresAtMs: exp, remindedSoonFor: exp }, exp - 3 * DAY), null);
  assert.equal(polish.expiryReminder({ expiresAtMs: exp, remindedSoonFor: exp }, exp + 1), "expired");
  assert.equal(polish.expiryReminder({ expiresAtMs: exp, remindedSoonFor: exp, remindedExpiredFor: exp }, exp + DAY), null);
  // Reconnecting gives a new expiry, so the old marks don't count.
  const next = exp + 182 * DAY;
  assert.equal(polish.expiryReminder({ expiresAtMs: next, remindedSoonFor: exp, remindedExpiredFor: exp }, next - DAY), "soon");
  assert.equal(polish.expiryReminder({ expiresAtMs: NaN }, 0), null);
});

test("expiry text names the day", () => {
  const t = polish.expiryText("soon", Date.UTC(2027, 5, 10, 20), "America/Los_Angeles");
  assert.match(t.body, /June 10/);
  assert.match(polish.expiryText("expired", 0, "UTC").title, /run out/);
});

test("delete my data: own sends go, received ones are stripped", () => {
  assert.deepEqual(polish.deletionPlan({ senderUid: "a", recipientUid: "b" }, "a"), { action: "delete" });
  const strip = polish.deletionPlan({ senderUid: "b", recipientUid: "a" }, "a");
  assert.equal(strip.action, "strip");
  assert.deepEqual(strip.fields.sort(), ["influence", "playlist", "reactions"]);
  assert.deepEqual(polish.deletionPlan({ senderUid: "b", recipientUid: "c" }, "a"), { action: "keep" });
});
