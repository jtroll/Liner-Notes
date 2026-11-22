"use strict";
// Facts for a note: the album's and artist's Wikipedia articles (summary plus Background,
// Recording and Reception, which the plain-text extract includes) and Last.fm's album wiki as a
// fallback (SPEC.md, "Writeup pipeline"). Every step is best effort: a note still gets written
// from whatever was found, and the gaps become flags.

const UA = "LinerNotes/0.1 (private app; Cloud Functions)";
const WIKI = "https://en.wikipedia.org/w/api.php";
const MAX_EXTRACT = 9000;

async function getJson(url) {
  const res = await fetch(url, { headers: { "User-Agent": UA, Accept: "application/json" } });
  if (!res.ok) throw new Error(`${new URL(url).host} said ${res.status}`);
  return res.json();
}

function wikiUrl(params) {
  const url = new URL(WIKI);
  for (const [k, v] of Object.entries({ format: "json", formatversion: "2", ...params })) url.searchParams.set(k, v);
  return url;
}

/** Plain text of a Wikipedia article by exact title, or null. */
async function wikiExtract(title) {
  const body = await getJson(wikiUrl({ action: "query", prop: "extracts|info", explaintext: "1", inprop: "url", redirects: "1", titles: title }));
  const page = body.query && body.query.pages && body.query.pages[0];
  if (!page || page.missing || !page.extract) return null;
  return { title: page.title, url: page.fullurl, text: trimExtract(page.extract) };
}

/** Wikipedia's search, first few titles. */
async function wikiSearch(query, limit = 5) {
  const body = await getJson(wikiUrl({ action: "query", list: "search", srsearch: query, srlimit: String(limit) }));
  return ((body.query && body.query.search) || []).map((r) => r.title);
}

/** Drops the reference-style tail sections and keeps the article to a sensible length. */
function trimExtract(text) {
  const cut = text.search(/\n==+ (Track listing|Personnel|Charts|Certifications|References|External links|Notes|See also) ==+/);
  const kept = (cut > 0 ? text.slice(0, cut) : text).trim();
  return kept.length > MAX_EXTRACT ? kept.slice(0, MAX_EXTRACT) + "…" : kept;
}

/**
 * Picks the search result that is the album's own article: one whose title starts with the album
 * name, preferring "(album)" or "(X album)" disambiguations.
 */
function pickAlbumTitle(titles, artist, album) {
  const norm = (s) => String(s).toLowerCase().replace(/[^a-z0-9]+/g, " ").trim();
  const a = norm(album);
  const ar = norm(artist);
  const scored = titles.map((t) => {
    const n = norm(t);
    let s = 0;
    if (n === a) s = 60;
    if (n.startsWith(a)) s = Math.max(s, 50);
    if (/\((.* )?album\)$/i.test(t)) s += 30;
    if (n.includes(ar) && n.includes("album")) s += 20;
    if (!n.startsWith(a)) s = 0;
    return { t, s };
  });
  scored.sort((x, y) => y.s - x.s);
  return scored.length && scored[0].s > 0 ? scored[0].t : null;
}

async function albumArticle(artist, album) {
  const titles = await wikiSearch(`${album} ${artist} album`);
  const title = pickAlbumTitle(titles, artist, album);
  return title ? wikiExtract(title) : null;
}

async function artistArticle(artist) {
  const titles = await wikiSearch(`${artist} musician band singer`, 3);
  const exact = titles.find((t) => t.toLowerCase().startsWith(artist.toLowerCase()));
  return exact ? wikiExtract(exact) : null;
}

/** Last.fm's album wiki (Creative Commons text written by its users). */
async function lastfmWiki(apiKey, artist, album) {
  if (!apiKey) return null;
  const url = new URL("https://ws.audioscrobbler.com/2.0/");
  for (const [k, v] of Object.entries({ method: "album.getinfo", artist, album, api_key: apiKey, format: "json", autocorrect: "1" })) {
    url.searchParams.set(k, v);
  }
  const body = await getJson(url);
  const wiki = body.album && body.album.wiki;
  if (!wiki || !wiki.content) return null;
  const text = wiki.content.replace(/<a [^>]*>.*?<\/a>\.?/g, "").replace(/<[^>]+>/g, "").trim();
  return text ? { title: "Last.fm", url: body.album.url, text: trimExtract(text) } : null;
}

/** Everything found for one album, and what was missing. */
async function gather(apiKey, artist, album) {
  const settle = async (fn) => { try { return await fn(); } catch { return null; } };
  const [albumWiki, artistWiki, lastfm] = await Promise.all([
    settle(() => albumArticle(artist, album)),
    settle(() => artistArticle(artist)),
    settle(() => lastfmWiki(apiKey, artist, album)),
  ]);
  const missing = [];
  if (!albumWiki) missing.push("No Wikipedia article for the album was found.");
  if (!artistWiki) missing.push("No Wikipedia article for the artist was found.");
  return { albumWiki, artistWiki, lastfm, missing };
}

module.exports = { gather, pickAlbumTitle, trimExtract };
