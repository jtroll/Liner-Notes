package com.trollman.linernotes

import android.content.Context
import com.trollman.linernotes.auth.AuthManager
import com.trollman.linernotes.cloud.Cloud
import com.trollman.linernotes.data.Prefs
import com.trollman.linernotes.lastfm.LastFm
import com.trollman.linernotes.spotify.AppRemotePlayer
import com.trollman.linernotes.spotify.Player

/** The app's few long-lived objects. Small enough that a DI framework would be more than it saves. */
class AppGraph(context: Context, val firebaseReady: Boolean) {
    val setup = Setup.fromBuild(firebaseReady)
    val prefs = Prefs(context)
    val lastFm = LastFm(BuildConfig.LASTFM_API_KEY, BuildConfig.LASTFM_CALLBACK_URL)
    val auth: AuthManager? = if (firebaseReady) AuthManager() else null
    val cloud: Cloud? = if (firebaseReady) Cloud() else null
    val player: Player = AppRemotePlayer(
        clientId = BuildConfig.SPOTIFY_CLIENT_ID,
        redirectUri = BuildConfig.SPOTIFY_REDIRECT_URI,
    )
}
