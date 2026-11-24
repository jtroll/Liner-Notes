"use strict";
const test = require("node:test");
const assert = require("node:assert");
const { norm, scoreAlbum, pickAlbum } = require("../lib/spotify");

const item = (name, artist, type = "album") => ({ name, album_type: type, artists: [{ name: artist }] });

test("norm strips accents, case and punctuation", () => {
  assert.strictEqual(norm("Beyoncé — COWBOY CARTER!"), "beyonce cowboy carter");
  assert.strictEqual(norm("Simon & Garfunkel"), "simon and garfunkel");
});

test("an exact title by the right artist scores highest", () => {
  assert.ok(scoreAlbum(item("Cutouts", "The Smile"), "The Smile", "Cutouts") >= 100);
  assert.strictEqual(scoreAlbum(item("Cutouts", "Someone Else"), "The Smile", "Cutouts"), 0);
});

test("prefers the original over a deluxe edition", () => {
  const picked = pickAlbum(
    [item("What Now (Deluxe Edition)", "Brittany Howard"), item("What Now", "Brittany Howard")],
    "Brittany Howard",
    "What Now",
  );
  assert.strictEqual(picked.name, "What Now");
});

test("keeps a deluxe edition when that's what was asked for", () => {
  const picked = pickAlbum([item("Big Ideas (Deluxe)", "Remi Wolf")], "Remi Wolf", "Big Ideas (Deluxe)");
  assert.strictEqual(picked.name, "Big Ideas (Deluxe)");
});

test("nothing close enough is no match", () => {
  assert.strictEqual(pickAlbum([item("Another Album", "WILLOW")], "WILLOW", "empathogen"), null);
});
