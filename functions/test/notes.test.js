"use strict";
const test = require("node:test");
const assert = require("node:assert");
const n = require("../lib/notes");
const { pickAlbumTitle, trimExtract } = require("../lib/facts");

const facts = {
  albumWiki: { title: "Cutouts (album)", url: "https://en.wikipedia.org/wiki/Cutouts_(album)", text: "Cutouts is the third album by the Smile." },
  artistWiki: null,
  lastfm: null,
  missing: ["No Wikipedia article for the artist was found."],
};

test("the draft prompt carries the facts, the pick and the sender's style", () => {
  const { system, user } = n.draftPrompt({
    artist: "The Smile", album: "Cutouts", senderName: "Sam", recipientName: "Alex",
    plays: 40, weeks: 6, rangeLabel: "this year", favoriteTracks: ["Zero Sum", "Foreign Spies"],
    isCloser: true, style: { examples: ["An old note."], toneNotes: "dry", minWords: 80, maxWords: 110 }, facts,
  });
  assert.match(system, /80 to 110 words/);
  assert.match(system, /close the list/);
  assert.match(system, /dry/);
  assert.match(user, /Cutouts is the third album/);
  assert.match(user, /played it 40 times across 6 separate weeks \(this year\)/);
  assert.match(user, /"Zero Sum", "Foreign Spies"/);
  assert.match(user, /<example index="1">\nAn old note\.\n<\/example>/);
});

test("a re-draft includes the current note and the instruction", () => {
  const { user } = n.draftPrompt({ artist: "A", album: "B", previous: "Old text", instruction: "shorter", facts: {} });
  assert.match(user, /<current>\nOld text\n<\/current>/);
  assert.match(user, /instruction for this draft: shorter/);
  assert.match(user, /No reference articles were found/);
});

test("parseDraft reads the note, the reviews and the cited pages", () => {
  const response = {
    content: [
      { type: "web_search_tool_result", content: [{ type: "web_search_result", url: "https://p.example/r", title: "Review" }] },
      {
        type: "text",
        text: "<note>A fine record.</note>\n<reviews>\n- Pitchfork | https://p.example/r | Spiky and tender.\nnot a line\n</reviews>",
        citations: [{ type: "web_search_result_location", url: "https://p.example/r", title: "Review", cited_text: "spiky" }],
      },
    ],
  };
  const d = n.parseDraft(response, facts);
  assert.strictEqual(d.note, "A fine record.");
  assert.deepStrictEqual(d.reviews, [{ outlet: "Pitchfork", url: "https://p.example/r", point: "Spiky and tender." }]);
  assert.deepStrictEqual(d.web, [{ url: "https://p.example/r", title: "Review", quotes: ["spiky"] }]);
  assert.deepStrictEqual(d.sources.map((s) => s.url), ["https://p.example/r", facts.albumWiki.url]);
});

test("parseDraft refuses an empty draft", () => {
  assert.throws(() => n.parseDraft({ content: [{ type: "text", text: "no tags" }] }, {}), n.NoteError);
});

test("collectSources falls back to search results when nothing was cited", () => {
  const s = n.collectSources([{ type: "web_search_tool_result", content: [{ type: "web_search_result", url: "https://x.example", title: "X" }] }]);
  assert.deepStrictEqual(s, [{ url: "https://x.example", title: "X", quotes: [] }]);
});

test("flags parse to a list, and NONE to nothing", () => {
  assert.deepStrictEqual(n.parseFlags("<flags>NONE</flags>"), []);
  assert.deepStrictEqual(n.parseFlags("<flags>\n- Won a Grammy: not in sources\n2. Born in Ohio: unsupported\n</flags>"), [
    "Won a Grammy: not in sources",
    "Born in Ohio: unsupported",
  ]);
  assert.deepStrictEqual(n.parseFlags("nothing"), []);
});

test("the check prompt includes review points and cited text", () => {
  const { user } = n.checkPrompt({ note: "N", facts, reviews: [{ outlet: "O", url: "https://o", point: "P" }], web: [{ url: "https://w", title: "W", quotes: ["Q"] }] });
  assert.match(user, /kind="review"[^>]*>\nP/);
  assert.match(user, /kind="web"[^>]*>\nQ/);
  assert.match(user, /<note>\nN\n<\/note>/);
});

test("fit scores parse by index and clamp", () => {
  const scores = n.parseFit("<scores>\n0|85|Right up their street\n2|150|Too high\nbad\n9|50|out of range\n</scores>", 3);
  assert.deepStrictEqual(scores, [{ score: 85, reason: "Right up their street" }, null, { score: 100, reason: "Too high" }]);
});

test("the frame prompt starts from last year's text", () => {
  const { system, user } = n.framePrompt({ kind: "closing", lastText: "See you next year", albums: ["A — B"], year: "2026" });
  assert.match(system, /closing note/);
  assert.match(user, /<last>\nSee you next year\n<\/last>/);
  assert.match(user, /1\. A — B/);
});

test("pickAlbumTitle prefers the album's own article", () => {
  assert.strictEqual(pickAlbumTitle(["Cutouts", "Cutouts (album)", "The Smile (band)"], "The Smile", "Cutouts"), "Cutouts (album)");
  assert.strictEqual(pickAlbumTitle(["Something else"], "The Smile", "Cutouts"), null);
});

test("trimExtract drops the reference sections", () => {
  assert.strictEqual(trimExtract("Intro.\n\n== Reception ==\nGood.\n\n== Track listing ==\n1. A"), "Intro.\n\n== Reception ==\nGood.");
});
