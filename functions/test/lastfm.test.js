"use strict";
const test = require("node:test");
const assert = require("node:assert");
const crypto = require("node:crypto");
const { signature } = require("../lib/lastfm");

test("signs sorted params plus secret, skipping format and callback", () => {
  const params = { token: "t0k", method: "auth.getSession", api_key: "key", format: "json", callback: "x" };
  const expected = crypto.createHash("md5").update("api_keykeymethodauth.getSessiontokent0ksecret").digest("hex");
  assert.strictEqual(signature(params, "secret"), expected);
});
