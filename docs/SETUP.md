# One-sitting setup

About 45 minutes at a computer. Until it's done, every build still installs and runs: the app
shows what's missing and lets you look around a sample shelf.

Values that are public (client IDs, API keys) go in GitHub **Actions variables**. Secrets go in
**GitHub secrets** (for CI) or **Google Secret Manager** (for the Functions). Nothing secret is
ever committed.

## 0. Before Firebase: install from GitHub

Every push builds `LinerNotes.apk` and puts it on the repo's **latest-build** prerelease
(Releases on the repo page). Open that on the phone and install it (allow installs from your
browser when Android asks).

## 1. Firebase project

1. [Firebase console](https://console.firebase.google.com): create a project and move it to the
   **Blaze** plan (Functions and Secret Manager need it).
2. Add an **Android app**, package `com.trollman.linernotes`, with the debug key's fingerprints:
   - SHA-1 `AD:82:9E:3D:5F:8D:01:4B:AF:E1:97:A6:10:51:5E:96:7A:62:A0:21` (the same key as Kerasma)
   - SHA-256: printed on the summary page of any **Android** workflow run once step 2 is done
     (or `keytool -list -v -keystore ~/.android/debug.keystore -storepass android`).
3. **Authentication** > Sign-in method > turn on **Google**.
4. **Firestore Database** > create (production mode, any region).
5. **App Distribution** > Testers & groups > make a group called `testers` and add yourself.
6. Download `google-services.json` and commit it as `app/google-services.json` (or send it to
   Claude in the project and it will commit it). It holds no secrets, only the project's public
   identifiers.

## 2. GitHub secrets and variables

Repo > Settings > Secrets and variables > Actions.

**Secrets**

- `DEBUG_KEYSTORE_BASE64`: the same debug keystore as Kerasma, so Google sign-in and Spotify
  accept the builds. On the PC that holds it:
  ```bash
  gh secret set DEBUG_KEYSTORE_BASE64 -R jtroll/Liner-Notes < <(base64 -w0 ~/.android/debug.keystore)
  ```
- `FIREBASE_SERVICE_ACCOUNT`: the JSON key of a Google Cloud service account for CI.
  Google Cloud console > IAM & Admin > Service accounts > create `ci-deploy` with these roles:
  Firebase Admin, Firebase App Distribution Admin, Cloud Functions Admin, Service Account User,
  Secret Manager Admin, Service Usage Admin, Cloud Build Editor, Artifact Registry Administrator,
  Cloud Scheduler Admin (for the delivery and monthly reminder jobs).
  Keys > Add key > JSON, and paste the whole file as the secret's value.

**Variables**

- `SPOTIFY_CLIENT_ID` (step 3)
- `LASTFM_API_KEY` (step 4)
- Optional: `FIREBASE_PROJECT_ID` (otherwise read from google-services.json),
  `LASTFM_CALLBACK_URL` (otherwise `https://<project-id>.web.app/lastfm-callback`),
  `APP_DISTRIBUTION_GROUP` (otherwise `testers`).

## 3. Spotify

[Developer dashboard](https://developer.spotify.com/dashboard): create the app, or reuse the one
you have (Development Mode allows one per developer).

- APIs used: **Android** and **Web API**.
- Android package: `com.trollman.linernotes` with the SHA-1 above.
- Redirect URI: `linernotes://spotify-callback`
- User Management: add your own Spotify account (and later anyone else who connects; the cap
  is 5).
- Client ID goes in the `SPOTIFY_CLIENT_ID` variable; client secret goes in Secret Manager
  (step 5).

Playing in the app needs Spotify Premium and the Spotify app installed and signed in.

## 4. Last.fm

[Create an API account](https://www.last.fm/api/account/create) with callback URL
`https://<project-id>.web.app/lastfm-callback`. The API key goes in the `LASTFM_API_KEY`
variable; the shared secret goes in Secret Manager.

## 5. Secret Manager

Google Cloud console > Security > Secret Manager, same project. Create three secrets:

- `SPOTIFY_CLIENT_SECRET`
- `LASTFM_SHARED_SECRET`
- `ANTHROPIC_API_KEY`: a Claude API key for drafting the notes. Make it at
  [platform.claude.com](https://platform.claude.com) under API keys, and set a monthly spend
  limit there. A full set of 12 notes with reviews costs a few dollars at most.

(Or on the PC: `firebase functions:secrets:set SPOTIFY_CLIENT_SECRET`, and the same for the
others.) The deploy fails until all three exist, because the Functions name them.

## 6. Deploy and install

1. Merge the open pull request into `main` (or re-run the **Backend** workflow on main). It
   deploys the Functions (including the 15-minute delivery job and the daily monthly-reminder job, which turn on Cloud Scheduler),
   the Firestore rules and indexes, and the Last.fm callback and invite pages.
2. On the phone: accept the App Distribution email invite and install **Firebase App Tester**.
   Each push to any branch then arrives there as an update.
3. Open Liner Notes, sign in with Google, connect Last.fm and Spotify, open an album and press
   play.
4. To try sending: in a set's Booklet tab, press **Send a test copy to my inbox**, then open
   Inbox on the Shelf. For the real thing, the person you invite needs the app too: add them as a
   tester in App Distribution (and under Spotify's User Management if they'll play in the app),
   then share the invite link from the set.

## If something fails

- **Google sign-in fails**: the APK's signature doesn't match the SHA-1 in Firebase. The Android
  workflow's "Check the signature" step says which key it used.
- **Spotify says the app isn't allowed**: your account isn't under User Management, or the
  package or SHA-1 differs on the dashboard.
- **Last.fm opens but never comes back**: the callback URL on the Last.fm API account doesn't
  match the Hosting page, or Hosting hasn't deployed yet.
- **Backend deploy names a missing permission**: add that role to `ci-deploy` and re-run.
