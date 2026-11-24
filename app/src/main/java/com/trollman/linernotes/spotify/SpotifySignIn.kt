package com.trollman.linernotes.spotify

import android.app.Activity
import android.content.Intent
import com.spotify.sdk.android.auth.AuthorizationClient
import com.spotify.sdk.android.auth.AuthorizationRequest
import com.spotify.sdk.android.auth.AuthorizationResponse
import java.security.SecureRandom

/**
 * Spotify sign-in for the Web API (playlists). Uses the Spotify app's own sheet when it's
 * installed, otherwise a Custom Tab. The app only receives an authorization code; the
 * spotifyConnect function swaps it for tokens with the client secret, so the secret never ships.
 */
object SpotifySignIn {

    /** SPEC.md, "Spotify scopes to request". user-top-read waits for the Spotify-only mode. */
    private val SCOPES = arrayOf(
        "playlist-modify-private",
        "playlist-modify-public",
        "app-remote-control",
    )

    private var expectedState: String? = null

    fun intent(activity: Activity, clientId: String, redirectUri: String): Intent {
        val state = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        expectedState = state
        val request = AuthorizationRequest.Builder(clientId, AuthorizationResponse.Type.CODE, redirectUri)
            .setScopes(SCOPES)
            .setState(state)
            .build()
        return AuthorizationClient.createLoginActivityIntent(activity, request)
    }

    sealed interface Result {
        data class Code(val code: String) : Result
        data object Cancelled : Result
        data class Failed(val message: String) : Result
    }

    fun read(resultCode: Int, data: Intent?): Result {
        val response = AuthorizationClient.getResponse(resultCode, data)
        return when (response.type) {
            AuthorizationResponse.Type.CODE -> {
                val code = response.code
                if (code.isNullOrBlank()) Result.Failed("Spotify sent no code")
                else if (expectedState != null && response.state != expectedState) Result.Failed("Spotify's answer didn't match the request")
                else Result.Code(code)
            }
            AuthorizationResponse.Type.CANCELLED, AuthorizationResponse.Type.EMPTY -> Result.Cancelled
            AuthorizationResponse.Type.ERROR -> Result.Failed(response.error ?: "Spotify said no")
            else -> Result.Failed("Spotify sent an unexpected answer")
        }
    }
}
