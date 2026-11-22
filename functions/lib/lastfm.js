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

module.exports = { signature, getSession };
