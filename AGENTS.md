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
- Everything that needs a secret is a Cloud Function. The app reads Last.fm's public
  `user.getTopAlbums` itself with the public API key.
- Spotify after February 2026: `POST /me/playlists`, `POST /playlists/{id}/items`, search
  `limit` at most 10, refresh tokens last 6 months, Development Mode allows 5 users. Check the
  live docs before adding an endpoint.
- Firestore: only Functions write `users/{uid}.lastfm`, `.spotify` and `.role`; tokens live in
  `users/{uid}/private/*`, which no client can read.

## Design system

`app/src/main/java/com/trollman/linernotes/ui/system/`: `PhysicalButton`, `HardwareSwitch`,
`Led`, `DotMatrixText` (Doto, 32sp and up only), `DisplayWindow`, `Record`, `VinylSleeve`,
`PlayerDeck`. Tokens in `ui/theme/Theme.kt` (`liner.casing`, `liner.signal`, ...), springs in
`ui/theme/Motion.kt`. Body text is always Allo Crisp. Respect `reduceMotion()`.
