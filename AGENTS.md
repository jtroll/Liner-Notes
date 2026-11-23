# Agents' manual

Liner Notes is a native Android app (package `com.trollman.linernotes`) modelled on
[jtroll/kerasma](https://github.com/jtroll/kerasma): same Gradle and AGP versions, Firebase
Google sign-in, debug-key signing in CI, Allo Crisp body type. The build spec is the Claude Doc
"Liner Notes: build spec" in J.T.'s project; code comments cite its sections as SPEC.md. It
stays out of this repository while the repository is public.

## Building

- **No local Android build in cloud sessions.** Google's Maven repo and the Android SDK host are
  blocked there. Push the branch; the **Android** workflow compiles, runs unit tests, and
  delivers the APK. Read its log for compile errors.
- Functions are plain CommonJS on Node 22: `cd functions && npm install && npm test`.
- Public client values (`SPOTIFY_CLIENT_ID`, `LASTFM_API_KEY`) come from a Gradle property, an
  environment variable (CI reads repo variables) or the gitignored `linernotes.local.properties`.
  Without `app/google-services.json` the Google Services plugin is skipped and the app runs in
  "setup needed" mode, so every build installs.

## Rules

- Never commit a secret. Secrets live in GitHub secrets (CI) and Secret Manager (Functions).
- Everything that needs a secret is a Cloud Function. The app reads Last.fm's public methods
  (`user.getTopAlbums`, the weekly charts, `album.getInfo`) itself with the public API key.
- Spotify after February 2026: `POST /me/playlists`, `POST /playlists/{id}/items`, search
  `limit` at most 10, refresh tokens last 6 months, Development Mode allows 5 users. Check the
  live docs before adding an endpoint.
- Firestore: only Functions write `users/{uid}.lastfm`, `.spotify` and `.role`; tokens live in
  `users/{uid}/private/*`, which no client can read. Group sets (`users/{uid}/sets/{id}`) and
  writing settings (`users/{uid}/settings/writing`) are the owner's, stored as one `json` field.
- Claude calls live in `functions/lib/notes.js`: Opus 5.5 (`claude-opus-5-5`, effort medium,
  server-side `fallbacks: "default"`, web search) drafts; Haiku 5.5 (`claude-haiku-5-5`) does the
  cheap fact check and fit scores. Keep the prompts generic (no names of real people) and keep
  their builders pure so `functions/test/notes.test.js` covers them.
- The app reads Last.fm's weekly charts itself for the group-send shortlist and favorite tracks
  (`LastFm.rangeCharts`, ranked in `data/Ranking.kt`).
- Sending (`functions/index.js`, "Sending"; pure parts in `functions/lib/sends.js`): a set goes out
  against an invite (`invites/{token}`). `scheduleSend` stores the recipient's copy of the booklet
  in `sends/{uid_setId}`, cut down by `cleanBooklet` (and first by `GroupSet.forRecipient()` in
  the app) so the sender's taste notes, Claude's drafts, flags, sources and play counts never
  leave their account. `acceptInvite` forms `pairs/{uid_uid}` and delivers anything already due;
  `deliverDueSends` runs every 15 minutes for the rest. Only Functions write invites, pairs and
  sends; a recipient can read a send only once it's delivered. Push goes to `users/{uid}.fcmTokens`.
- Invite links are `https://<project>.web.app/invite/<token>` (`hosting/invite`), which hands
  over to `linernotes://invite/<token>`; the app keeps the token through sign-in (`Prefs`) and asks
  "Join?" before accepting.
- Monthly send (`functions/index.js`, "Monthly send"; pure parts in `functions/lib/monthly.js`):
  the app gathers candidates from its own Last.fm charts for last month (`data/Monthly.kt`,
  widened to 3 months when fewer than 3 qualify) and calls `monthlySuggest`, which adds the
  recipient's Last.fm taste, the pair's past sends and reactions on the server and asks Claude for
  one pick and two alternates. The recipient's listening never reaches the sender's phone. A
  monthly draft is a `GroupSet` with `kind = "monthly"`, `pairId` and `month`; it goes out through
  `scheduleSend` with `kind: "monthly"` (delivered at once, one per person, pair and month).
  `reactToSend` stores the recipient's reactions on the send; `updatePair` changes pair settings;
  `monthlyReminders` runs each morning (Pacific) for the draft day, a week later and the 25th.
- The printable booklet is drawn on the phone (`print/BookletPdf.kt`): half-letter pages, or two
  to a letter sheet in fold order (`print/Imposition.kt`).

## Design system

`app/src/main/java/com/trollman/linernotes/ui/system/`: `PhysicalButton`, `HardwareSwitch`,
`Led`, `DotMatrixText` (Doto, 32sp and up only), `DisplayWindow`, `Record`, `VinylSleeve`,
`PlayerDeck`. Tokens in `ui/theme/Theme.kt` (`liner.casing`, `liner.signal`, ...), springs in
`ui/theme/Motion.kt`. Body text is always Allo Crisp. Respect `reduceMotion()`.
