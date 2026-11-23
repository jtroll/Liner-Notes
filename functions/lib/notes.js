"use strict";
// Claude's part of a note (SPEC.md, "Writeup pipeline" and "Group send"):
//   draftNote   Opus writes one album's paragraph in the sender's voice, from the gathered facts,
//               2 to 4 reviews it finds with web search, and the sender's own style examples.
//   checkNote   a second, cheap call (Haiku) checks the draft's claims against those sources and
//               returns flags. Nothing is ever deleted; flags show beside the note.
//   draftFrame  the set's intro or closing note, starting from last year's.
//   fitScores   "fit for them": how well each shortlisted album suits the recipient's taste.
//
// The prompt builders and parsers are pure, so test/notes.test.js covers them without the API.

const Anthropic = require("@anthropic-ai/sdk");

const DRAFT_MODEL = "claude-opus-5-5";
const CHECK_MODEL = "claude-haiku-5-5";
const FALLBACK_BETA = "server-side-fallback-2026-07-01";
const MAX_PAUSES = 3;

class NoteError extends Error {
  constructor(message, kind) {
    super(message);
    this.kind = kind; // "refusal" | "empty"
  }
}

// ---- Calling Claude ------------------------------------------------------------------------

/**
 * One Opus request with server-side fallbacks. A long web-search turn can pause; the paused
 * content goes back as the assistant turn and the server carries on.
 */
async function askOpus(client, { system, user, effort = "medium", tools, maxTokens = 16000 }) {
  const messages = [{ role: "user", content: user }];
  let response;
  for (let i = 0; i <= MAX_PAUSES; i++) {
    response = await client.beta.messages.create({
      model: DRAFT_MODEL,
      max_tokens: maxTokens,
      betas: [FALLBACK_BETA],
      fallbacks: "default",
      output_config: { effort },
      system,
      messages,
      ...(tools ? { tools } : {}),
    });
    if (response.stop_reason !== "pause_turn") break;
    messages.splice(1, messages.length - 1, { role: "assistant", content: response.content });
  }
  if (response.stop_reason === "refusal") throw new NoteError("Claude declined to write this one.", "refusal");
  return response;
}

/** One Haiku request (Haiku has no server-side fallback, so no fallbacks field). */
async function askHaiku(client, { system, user, effort = "low", maxTokens = 8000 }) {
  const response = await client.messages.create({
    model: CHECK_MODEL,
    max_tokens: maxTokens,
    output_config: { effort },
    system,
    messages: [{ role: "user", content: user }],
  });
  if (response.stop_reason === "refusal") throw new NoteError("Claude declined this check.", "refusal");
  return response;
}

const textOf = (response) =>
  response.content.filter((b) => b.type === "text").map((b) => b.text).join("");

/** The text between <tag> and </tag>, trimmed, or null. */
function extractTag(text, tag) {
  const m = new RegExp(`<${tag}>([\\s\\S]*?)</${tag}>`).exec(text || "");
  return m ? m[1].trim() : null;
}

/** Web pages Claude cited (or, failing that, found), deduplicated by URL, in order. */
function collectSources(content) {
  const seen = new Map();
  const add = (url, title, quote) => {
    if (!url || seen.has(url)) {
      if (url && quote && !seen.get(url).quotes.includes(quote)) seen.get(url).quotes.push(quote);
      return;
    }
    seen.set(url, { url, title: title || url, quotes: quote ? [quote] : [] });
  };
  for (const block of content || []) {
    if (block.type === "text" && Array.isArray(block.citations)) {
      for (const c of block.citations) {
        if (c.type === "web_search_result_location") add(c.url, c.title, c.cited_text);
      }
    }
  }
  if (!seen.size) {
    for (const block of content || []) {
      if (block.type === "web_search_tool_result" && Array.isArray(block.content)) {
        for (const r of block.content) if (r.type === "web_search_result") add(r.url, r.title, null);
      }
    }
  }
  return [...seen.values()];
}

// ---- Prompts -------------------------------------------------------------------------------

const BEATS = [
  "Who they are, with a hook: where they're from, a former band, a real name or a family tie.",
  "The sender's personal link: how they found it, the artist's history on the sender's earlier lists (with the year), or a candid admission about their own bias or surprise.",
  "What the album is: the genre blend and its themes, in plain words.",
  "Two or three tracks named in quotes, each with a vivid short description.",
  "A comparison to a familiar artist, only where it genuinely helps.",
  "Sometimes one striking fact: an award, a chart moment or a short quote from the artist.",
  "A closing line on how or when to listen.",
];

const STYLE = [
  "First person, in the sender's own voice: warm and conversational, like a friend recommending something they love. Addressed to the recipient without being sappy.",
  "Candid about the sender's own biases and surprises; light, self-aware humour is welcome.",
  "Enthusiastic but grounded. A little critic vocabulary is fine; no pile-ups of superlatives.",
  "One paragraph. No headings, bullets or sign-off. Don't repeat the heading or the favorite tracks list.",
  "Never copy more than a short phrase from a review or from Wikipedia. Summarise in your own words.",
  "Only state facts you found in the sources below or in a review you read. Never invent the sender's feelings, history or listening habits beyond what you're told.",
];

function wordRange(style) {
  const min = Number(style && style.minWords) || 90;
  const max = Number(style && style.maxWords) || 120;
  return [Math.min(min, max), Math.max(min, max)];
}

function examplesBlock(style) {
  const examples = ((style && style.examples) || []).filter((e) => e && e.trim()).slice(0, 6);
  if (!examples.length) return "";
  return (
    "Here are some of the sender's own past write-ups. Match their voice, rhythm and level of detail (but not their facts):\n" +
    examples.map((e, i) => `<example index="${i + 1}">\n${e.trim()}\n</example>`).join("\n") +
    "\n\n"
  );
}

function factsBlock(facts) {
  const parts = [];
  for (const [label, item] of [["album article", facts && facts.albumWiki], ["artist article", facts && facts.artistWiki], ["Last.fm wiki", facts && facts.lastfm]]) {
    if (item && item.text) parts.push(`<source kind="${label}" title="${item.title}" url="${item.url}">\n${item.text}\n</source>`);
  }
  return parts.length ? parts.join("\n") : "(No reference articles were found. Rely on the reviews you find.)";
}

/**
 * The prompt for one album's note. `input`:
 *   artist, album, senderName, recipientName, taste, plays, weeks, rangeLabel, year,
 *   favoriteTracks [string], earlierLists [{year, artistOnly}], isCloser, isMonthly,
 *   style {examples, toneNotes, minWords, maxWords}, facts (from facts.gather),
 *   instruction (re-draft request), previous (the note being re-drafted),
 *   connection (monthly picks: how the album ties to what the recipient loves)
 */
function draftPrompt(input) {
  const [min, max] = wordRange(input.style);
  const sender = input.senderName || "the sender";
  const recipient = input.recipientName || "the recipient";
  const system =
    `You write the short album notes in a personal music gift: ${sender} is sharing albums they loved with ${recipient}. ` +
    `Each note is one paragraph of ${min} to ${max} words, written as ${sender}.\n\n` +
    "The paragraph usually moves through these beats, in this order, though not every note uses all of them:\n" +
    BEATS.map((b) => `- ${b}`).join("\n") +
    "\n\nStyle:\n" +
    STYLE.map((s) => `- ${s}`).join("\n") +
    (input.style && input.style.toneNotes ? `\n- The sender's own tone notes: ${input.style.toneNotes.trim()}` : "") +
    (input.isMonthly ? `\n- Add one short clause tying the album to something ${recipient} already loves.` : "") +
    (input.isCloser ? `\n- This album closes the list, so open the paragraph by introducing it as the closer, for example "We'll close the list this year with…".` : "") +
    "\n\nUse web search to find 2 to 4 reviews of the album from established music outlets, and use them for what the album is and how it was received. " +
    "Reply with the finished paragraph inside <note></note> tags, then inside <reviews></reviews> one line per review you used: Outlet | URL | its key point in your own words.";

  const context = [];
  context.push(`Album: ${input.artist} — ${input.album}`);
  if (input.plays) {
    context.push(`${sender} played it ${input.plays} times${input.weeks ? ` across ${input.weeks} separate weeks` : ""}${input.rangeLabel ? ` (${input.rangeLabel})` : ""}.`);
  }
  if (input.favoriteTracks && input.favoriteTracks.length) {
    context.push(`${sender}'s most-played tracks on it: ${input.favoriteTracks.map((t) => `"${t}"`).join(", ")}. Prefer these when naming tracks.`);
  }
  for (const e of input.earlierLists || []) {
    context.push(e.artistOnly ? `${input.artist} was on ${sender}'s ${e.year} list with a different album.` : `This album was on ${sender}'s ${e.year} list.`);
  }
  if (input.taste) context.push(`What ${recipient} likes, in ${sender}'s words: ${input.taste}`);
  if (input.connection) context.push(`How it connects to what ${recipient} already loves: ${input.connection}`);

  let user =
    examplesBlock(input.style) +
    "Reference material:\n" +
    factsBlock(input.facts) +
    "\n\nAbout this pick:\n" +
    context.map((c) => `- ${c}`).join("\n");
  if (input.previous) {
    user += `\n\nThe current note, which ${sender} wants re-drafted:\n<current>\n${input.previous}\n</current>`;
  }
  if (input.instruction) user += `\n\n${sender}'s instruction for this draft: ${input.instruction}`;
  return { system, user };
}

/** Reads the draft reply: the note, review lines, and every source behind it. */
function parseDraft(response, facts) {
  const text = textOf(response);
  const note = extractTag(text, "note");
  if (!note) throw new NoteError("The draft came back empty.", "empty");
  const reviews = (extractTag(text, "reviews") || "")
    .split("\n")
    .map((l) => l.replace(/^[-*]\s*/, "").split("|").map((s) => s.trim()))
    .filter((p) => p.length >= 3 && /^https?:\/\//.test(p[1]))
    .map(([outlet, url, point]) => ({ outlet, url, point }));
  const web = collectSources(response.content);
  const sources = [];
  const push = (title, url) => { if (url && !sources.some((s) => s.url === url)) sources.push({ title, url }); };
  for (const r of reviews) push(r.outlet, r.url);
  for (const w of web) push(w.title, w.url);
  for (const f of [facts && facts.albumWiki, facts && facts.artistWiki, facts && facts.lastfm]) if (f) push(f.title, f.url);
  return { note, reviews, web, sources };
}

function checkPrompt({ note, facts, reviews, web }) {
  const system =
    "You fact-check a short album note against its sources. List every factual claim in the note (names, dates, places, " +
    "awards, chart positions, quotes, who played or produced what, genre claims stated as fact) that the sources below do not support. " +
    "Opinions and the writer's own feelings or listening habits are not claims. Reply with one line per unsupported claim inside " +
    "<flags></flags>, each a short sentence naming the claim and why, or the single word NONE inside the tags if everything is supported.";
  const parts = [factsBlock(facts)];
  for (const r of reviews || []) parts.push(`<source kind="review" title="${r.outlet}" url="${r.url}">\n${r.point}\n</source>`);
  for (const w of web || []) if (w.quotes.length) parts.push(`<source kind="web" title="${w.title}" url="${w.url}">\n${w.quotes.join("\n")}\n</source>`);
  const user = `Sources:\n${parts.join("\n")}\n\nThe note:\n<note>\n${note}\n</note>`;
  return { system, user };
}

function parseFlags(text) {
  const body = extractTag(text, "flags");
  if (body == null) return [];
  if (/^none\.?$/i.test(body.trim())) return [];
  return body.split("\n").map((l) => l.replace(/^[-*\d.)\s]+/, "").trim()).filter(Boolean);
}

/**
 * Intro or closing note for a set. `input`: kind ("intro" | "closing"), senderName,
 * recipientName, year, title, albums ["Artist — Album"], lastText, style, instruction, previous.
 */
function framePrompt(input) {
  const sender = input.senderName || "the sender";
  const recipient = input.recipientName || "the recipient";
  const what = input.kind === "closing"
    ? `the closing note that ends the list: two to four sentences wishing ${recipient} well with the music, then a short sign-off line`
    : `the short introduction at the front of the list: two to four sentences setting up this year's albums`;
  const system =
    `You write ${what}, as ${sender}, for "${input.title || "Top Album Discoveries"}", a personal list of albums ${sender} is giving ${recipient}. ` +
    "Warm and conversational, in the sender's own voice, never sappy. Keep it short. Reply with the text inside <note></note> tags.";
  let user = examplesBlock(input.style);
  if (input.lastText) user += `Last time, ${sender} wrote this. Keep its spirit and shape, but make it fresh for this year:\n<last>\n${input.lastText}\n</last>\n\n`;
  if (input.albums && input.albums.length) user += `This year's albums:\n${input.albums.map((a, i) => `${i + 1}. ${a}`).join("\n")}\n\n`;
  if (input.year) user += `Year: ${input.year}\n`;
  if (input.previous) user += `\nThe current text, to re-draft:\n<current>\n${input.previous}\n</current>\n`;
  if (input.instruction) user += `\n${sender}'s instruction: ${input.instruction}\n`;
  return { system, user: user.trim() || "Write it." };
}

/** `albums` is [{artist, title}]; returns the prompt for scoring them against `taste`. */
function fitPrompt({ taste, albums }) {
  const system =
    "You judge how well albums suit a listener, from a short description of their taste. Score each album 0 to 100 " +
    "(100: right in their wheelhouse; 50: a reasonable stretch; under 30: probably not for them) with a reason of at most 12 words. " +
    "Reply inside <scores></scores> with one line per album: index|score|reason.";
  const user = `Their taste: ${taste}\n\nAlbums:\n${albums.map((a, i) => `${i}. ${a.artist} — ${a.title}`).join("\n")}`;
  return { system, user };
}

function parseFit(text, count) {
  const out = new Array(count).fill(null);
  for (const line of (extractTag(text, "scores") || "").split("\n")) {
    const m = /^\s*(\d+)\s*\|\s*(\d{1,3})\s*\|\s*(.*)$/.exec(line);
    if (!m) continue;
    const i = Number(m[1]);
    if (i >= 0 && i < count) out[i] = { score: Math.max(0, Math.min(100, Number(m[2]))), reason: m[3].trim() };
  }
  return out;
}

// ---- The calls ---------------------------------------------------------------------------

async function draftNote(apiKey, input) {
  const client = new Anthropic({ apiKey });
  const { system, user } = draftPrompt(input);
  const response = await askOpus(client, {
    system,
    user,
    effort: "medium",
    tools: [{ type: "web_search_20260209", name: "web_search", max_uses: 4 }],
  });
  const draft = parseDraft(response, input.facts);
  let flags = [];
  try {
    const check = checkPrompt({ note: draft.note, facts: input.facts, reviews: draft.reviews, web: draft.web });
    flags = parseFlags(textOf(await askHaiku(client, check)));
  } catch (err) {
    flags = ["The fact check couldn't run this time, so nothing in this draft has been checked."];
  }
  if (!draft.reviews.length && !draft.web.length) flags.push("No reviews were found, so this draft leans on reference articles only.");
  return { note: draft.note, sources: draft.sources, flags: [...((input.facts && input.facts.missing) || []), ...flags] };
}

async function draftFrame(apiKey, input) {
  const client = new Anthropic({ apiKey });
  const response = await askOpus(client, { ...framePrompt(input), effort: "low", maxTokens: 8000 });
  const note = extractTag(textOf(response), "note");
  if (!note) throw new NoteError("The draft came back empty.", "empty");
  return { note };
}

/**
 * The monthly suggestion: one album and two alternates from the sender's candidates
 * (lib/monthly.js builds the prompt and checks the ids). Retries once if no valid id came back.
 */
async function suggestMonthly(apiKey, input) {
  const monthly = require("./monthly");
  const client = new Anthropic({ apiKey });
  const prompt = monthly.suggestPrompt(input);
  for (let attempt = 0; attempt < 2; attempt++) {
    const user = attempt === 0 ? prompt.user : `${prompt.user}\n\nUse only ids from the candidate list above.`;
    const response = await askOpus(client, { system: prompt.system, user, effort: "medium", maxTokens: 8000 });
    const picks = monthly.parseSuggestions(textOf(response), input.candidates);
    if (picks.length) return picks;
  }
  throw new NoteError("Claude didn't pick from the candidates. Try again.", "empty");
}

async function fitScores(apiKey, input) {
  const client = new Anthropic({ apiKey });
  const response = await askHaiku(client, { ...fitPrompt(input), effort: "low" });
  return parseFit(textOf(response), input.albums.length);
}

module.exports = {
  NoteError,
  draftNote,
  draftFrame,
  fitScores,
  suggestMonthly,
  // pure parts, for tests
  draftPrompt,
  parseDraft,
  checkPrompt,
  parseFlags,
  framePrompt,
  fitPrompt,
  parseFit,
  extractTag,
  collectSources,
};
