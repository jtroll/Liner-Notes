"use strict";
// Last.fm's signed calls (https://www.last.fm/api/authspec). The shared secret only ever lives in
// Secret Manager, read here.
const crypto = require("node:crypto");

const API = "https://ws.audioscrobbler.com/2.0/";
const UA = "LinerNotes/0.1 (private app)";

/** api_sig: every parameter except format and callback, sorted by name, name+value, then the secret, md5. */
function signature(params, secret) {
  const base = Object.keys(params)
    .filter((k) => k !== "format" && k !== "callback")
    .sort()
    .map((k) => k + params[k])
    .join("");
  return crypto.createHash("md5").update(base + secret, "utf8").digest("hex");
}

/** Swaps the token from Last.fm's web approval for a session: { name, key }. */
async function getSession(apiKey, secret, token) {
  const params = { api_key: apiKey, method: "auth.getSession", token };
  const url = new URL(API);
  for (const [k, v] of Object.entries(params)) url.searchParams.set(k, v);
  url.searchParams.set("api_sig", signature(params, secret));
  url.searchParams.set("format", "json");
  const res = await fetch(url, { headers: { "User-Agent": UA } });
  const body = await res.json().catch(() => ({}));
  if (body.error || !body.session) {
    const err = new Error(body.message || `Last.fm said ${res.status}`);
    err.lastfmCode = body.error;
    throw err;
  }
  return { name: body.session.name, key: body.session.key };
}

/** One unsigned call to Last.fm's public methods. Throws with Last.fm's own message on an error. */
async function publicCall(apiKey, method, params) {
  const url = new URL(API);
  for (const [k, v] of Object.entries({ method, api_key: apiKey, format: "json", ...params })) url.searchParams.set(k, String(v));
  const res = await fetch(url, { headers: { "User-Agent": UA } });
  const body = await res.json().catch(() => ({}));
  if (body.error) {
    const err = new Error(body.message || `Last.fm said ${res.status}`);
    err.lastfmCode = body.error;
    throw err;
  }
  return body;
}

const asList = (v) => (Array.isArray(v) ? v : v ? [v] : []);

/** user.getTopArtists, as [{name, plays}]. */
function parseTopArtists(body) {
  return asList(body && body.topartists && body.topartists.artist)
    .filter((a) => a && a.name)
    .map((a) => ({ name: String(a.name), plays: Number(a.playcount) || 0 }));
}

/** user.getTopAlbums, as [{artist, title, plays}]. */
function parseTopAlbums(body) {
  return asList(body && body.topalbums && body.topalbums.album)
    .filter((a) => a && a.name && a.artist)
    .map((a) => ({ artist: String(a.artist.name || a.artist["#text"] || ""), title: String(a.name), plays: Number(a.playcount) || 0 }))
    .filter((a) => a.artist);
}

async function topArtists(apiKey, user, period, limit) {
  return parseTopArtists(await publicCall(apiKey, "user.gettopartists", { user, period, limit }));
}

async function topAlbums(apiKey, user, period, limit) {
  return parseTopAlbums(await publicCall(apiKey, "user.gettopalbums", { user, period, limit }));
}

module.exports = { signature, getSession, publicCall, parseTopArtists, parseTopAlbums, topArtists, topAlbums };
