package com.trollman.linernotes

/**
 * Which of the one-time setup steps this build was made with (docs/SETUP.md).
 *
 * A build made before J.T.'s computer session still installs and opens; it says plainly what is
 * missing instead of failing somewhere deep inside a sign-in.
 */
data class Setup(
    val firebase: Boolean,
    val spotifyClientId: Boolean,
    val lastfmApiKey: Boolean,
    val lastfmCallback: Boolean,
) {
    val canSignIn: Boolean get() = firebase
    val canConnectLastFm: Boolean get() = firebase && lastfmApiKey && lastfmCallback
    val canConnectSpotify: Boolean get() = firebase && spotifyClientId
    val canPlay: Boolean get() = spotifyClientId
    val complete: Boolean get() = firebase && spotifyClientId && lastfmApiKey && lastfmCallback

    /** One line per missing piece, in the words of docs/SETUP.md. */
    val missing: List<String>
        get() = buildList {
            if (!firebase) add("Firebase config (app/google-services.json)")
            if (!spotifyClientId) add("Spotify client ID (SPOTIFY_CLIENT_ID variable)")
            if (!lastfmApiKey) add("Last.fm API key (LASTFM_API_KEY variable)")
            if (firebase && !lastfmCallback) add("Last.fm callback address")
        }

    companion object {
        fun fromBuild(firebaseReady: Boolean) = Setup(
            firebase = firebaseReady,
            spotifyClientId = BuildConfig.SPOTIFY_CLIENT_ID.isNotBlank(),
            lastfmApiKey = BuildConfig.LASTFM_API_KEY.isNotBlank(),
            lastfmCallback = BuildConfig.LASTFM_CALLBACK_URL.isNotBlank(),
        )
    }
}
