# Liner Notes

An Android app for trading albums with the people you love: one album a month each way, or a
hand-picked set from the last few months, each with a liner note you can edit or dictate over.
It reads your listening from Last.fm, plays albums through Spotify, and shows how much your
picks have changed the other person's listening.

Early days: this is Phase 0, the skeleton. It signs in with Google, connects Last.fm and Spotify,
shows your top albums as records on a shelf, plays them through the Spotify app, and can make a
test playlist.

- **Setup:** [docs/SETUP.md](docs/SETUP.md)
- **For agents:** [AGENTS.md](AGENTS.md)

## Layout

- `app/`: the Android app (Kotlin, Jetpack Compose).
- `functions/`: Cloud Functions, everything that needs a secret.
- `hosting/`: Firebase Hosting, the page Last.fm returns to.
- `firestore.rules`: who can read and write what.
- `.github/workflows/`: builds, App Tester delivery and backend deploys.

## Credits

Allo Crisp, Doto and IBM Plex Mono are under the SIL Open Font License; the Spotify App Remote
SDK is under Apache 2.0. Texts in `licences/`.
