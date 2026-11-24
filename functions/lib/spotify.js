"use strict";
// Spotify Web API, as it stands after the February 2026 Development Mode changes: playlists are
// created with POST /me/playlists and filled with POST /playlists/{id}/items (the /tracks routes
// were renamed), search returns at most 10 results, and refresh tokens last 6 months from the
// original sign-in. SPEC.md, "Spotify integration and its limits".

const ACCOUNTS = "https://accounts.spotify.com/api/token";
const API = "https://api.spotify.com/v1";

class SpotifyError extends Error {
  constructor(message, status, body) {
    super(message);
    this.status = status;
    this.body = body;
  }
}

async function tokenRequest(clientId, clientSecret, form) {
  const res = await fetch(ACCOUNTS, {
    method: "POST",
    headers: {
      "Content-Type": "application/x-www-form-urlencoded",
      Authorization: "Basic " + Buffer.from(`${clientId}:${clientSecret}`).toString("base64"),
    },
    body: new URLSearchParams(form).toString(),
  });
  const body = await res.json().catch(() => ({}));
  if (!res.ok) {
    throw new SpotifyError(body.error_description || body.error || `Spotify accounts said ${res.status}`, res.status, body);
  }
  return body;
}

/** The authorization code from the app's sign-in, swapped for tokens. */
function exchangeCode(clientId, clientSecret, code, redirectUri) {
  return tokenRequest(clientId, clientSecret, { grant_type: "authorization_code", code, redirect_uri: redirectUri });
}

/** A fresh access token from a stored refresh token. invalid_grant means the 6 months are up. */
function refresh(clientId, clientSecret, refreshToken) {
  return tokenRequest(clientId, clientSecret, { grant_type: "refresh_token", refresh_token: refreshToken });
}

let appToken = null; // { token, expiresAt }
/** An app-only token (no user), for search. Cached until a minute before it expires. */
async function clientToken(clientId, clientSecret) {
  if (appToken && appToken.expiresAt > Date.now() + 60_000) return appToken.token;
  const body = await tokenRequest(clientId, clientSecret, { grant_type: "client_credentials" });
  appToken = { token: body.access_token, expiresAt: Date.now() + body.expires_in * 1000 };
  return appToken.token;
}

async function api(token, method, path, body) {
  const res = await fetch(API + path, {
    method,
    headers: {
      Authorization: `Bearer ${token}`,
      ...(body ? { "Content-Type": "application/json" } : {}),
    },
    body: body ? JSON.stringify(body) : undefined,
  });
  const text = await res.text();
  let parsed = {};
  try { parsed = text ? JSON.parse(text) : {}; } catch { parsed = { raw: text }; }
  if (!res.ok) {
    const msg = (parsed.error && parsed.error.message) || `Spotify said ${res.status}`;
    throw new SpotifyError(`${method} ${path.split("?")[0]}: ${msg}`, res.status, parsed);
  }
  return parsed;
}

const me = (token) => api(token, "GET", "/me");

// ---- Finding an album ----------------------------------------------------------------------

/** Lowercase, no accents, no punctuation, single spaces. */
function norm(s) {
  return String(s || "")
    .normalize("NFKD")
    .replace(/[̀-ͯ]/g, "")
    .toLowerCase()
    .replace(/&/g, " and ")
    .replace(/[^a-z0-9]+/g, " ")
    .trim();
}

const EDITION_WORDS = /\b(deluxe|live|remaster(ed)?|edition|anniversary|expanded|bonus|instrumental|karaoke|acoustic|sped up|slowed)\b/;

/**
 * Scores one search result against what Last.fm called the album. Higher is better; below 50 is
 * not a match. Prefers the original release over deluxe or live editions unless the name asked
 * for one (SPEC.md, "Find the album on Spotify").
 */
function scoreAlbum(item, artist, title) {
  const wantTitle = norm(title);
  const wantArtist = norm(artist);
  const gotTitle = norm(item.name);
  const artists = (item.artists || []).map((a) => norm(a.name));
  if (!artists.some((a) => a === wantArtist || a.includes(wantArtist) || wantArtist.includes(a))) return 0;
  let score = 0;
  if (gotTitle === wantTitle) score = 100;
  else if (gotTitle.startsWith(wantTitle) || wantTitle.startsWith(gotTitle)) score = 75;
  else if (gotTitle.includes(wantTitle) || wantTitle.includes(gotTitle)) score = 60;
  else return 0;
  if (artists[0] === wantArtist) score += 5;
  const wantsEdition = EDITION_WORDS.test(wantTitle);
  if (!wantsEdition && EDITION_WORDS.test(gotTitle)) score -= 20;
  if (item.album_type === "album") score += 3;
  if (item.album_type === "compilation") score -= 10;
  return score;
}

function pickAlbum(items, artist, title) {
  let best = null;
  let bestScore = 0;
  for (const item of items || []) {
    if (!item) continue;
    const s = scoreAlbum(item, artist, title);
    if (s > bestScore) { best = item; bestScore = s; }
  }
  return bestScore >= 50 ? best : null;
}

/** Finds an album by artist and title. Two tries: field filters first, then plain words. */
async function findAlbum(token, artist, title) {
  const queries = [
    `album:"${title.replace(/"/g, "")}" artist:"${artist.replace(/"/g, "")}"`,
    `${artist} ${title}`,
  ];
  for (const q of queries) {
    const params = new URLSearchParams({ q, type: "album", limit: "10" });
    const body = await api(token, "GET", `/search?${params}`);
    const hit = pickAlbum(body.albums && body.albums.items, artist, title);
    if (hit) return hit;
  }
  return null;
}

/** Every track URI on an album, in order. */
async function albumTrackUris(token, albumId) {
  const uris = [];
  let path = `/albums/${encodeURIComponent(albumId)}/tracks?limit=50`;
  while (path) {
    const page = await api(token, "GET", path);
    for (const t of page.items || []) if (t && t.uri) uris.push(t.uri);
    path = page.next ? page.next.replace(API, "") : null;
  }
  return uris;
}

/** POST /me/playlists, the February 2026 route. */
function createPlaylist(token, name, description) {
  return api(token, "POST", "/me/playlists", { name, description, public: false });
}

/** POST /playlists/{id}/items, at most 100 a request. */
async function addItems(token, playlistId, uris) {
  for (let i = 0; i < uris.length; i += 100) {
    await api(token, "POST", `/playlists/${encodeURIComponent(playlistId)}/items`, { uris: uris.slice(i, i + 100) });
  }
}

module.exports = {
  SpotifyError, exchangeCode, refresh, clientToken, me, norm, scoreAlbum, pickAlbum, findAlbum,
  albumTrackUris, createPlaylist, addItems,
};
