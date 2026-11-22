package com.trollman.linernotes.spotify

import android.content.Context
import android.util.Log
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.SpotifyAppRemote
import com.spotify.android.appremote.api.error.AuthenticationFailedException
import com.spotify.android.appremote.api.error.CouldNotFindSpotifyApp
import com.spotify.android.appremote.api.error.NotLoggedInException
import com.spotify.android.appremote.api.error.UserNotAuthorizedException
import com.spotify.protocol.client.Subscription
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import com.spotify.protocol.types.PlayerState as SpotifyPlayerState

/**
 * [Player] through Spotify's App Remote SDK, which drives the Spotify app on the same phone: the
 * app draws its own turntable, Spotify does the streaming. Needs the Spotify app installed and
 * signed in, Premium for on-demand albums, and the listener on the Spotify app's user list
 * (Development Mode, docs/SETUP.md).
 */
class AppRemotePlayer(
    private val clientId: String,
    private val redirectUri: String,
) : Player {

    private val _state = MutableStateFlow(
        PlayerState(connection = if (clientId.isBlank()) Connection.NotSetUp else Connection.Disconnected)
    )
    override val state: StateFlow<PlayerState> = _state.asStateFlow()

    private var remote: SpotifyAppRemote? = null
    private var subscription: Subscription<SpotifyPlayerState>? = null
    private var pendingUri: String? = null

    override fun connect(context: Context) {
        if (clientId.isBlank()) return
        if (remote?.isConnected == true) return
        if (_state.value.connection == Connection.Connecting) return
        if (!SpotifyAppRemote.isSpotifyInstalled(context)) {
            _state.update { it.copy(connection = Connection.NotInstalled, problem = "The Spotify app isn't installed on this phone.") }
            return
        }
        _state.update { it.copy(connection = Connection.Connecting, problem = null) }
        val params = ConnectionParams.Builder(clientId)
            .setRedirectUri(redirectUri)
            .showAuthView(true)
            .build()
        SpotifyAppRemote.connect(context, params, object : Connector.ConnectionListener {
            override fun onConnected(appRemote: SpotifyAppRemote) {
                remote = appRemote
                _state.update { it.copy(connection = Connection.Connected, problem = null) }
                subscription = appRemote.playerApi.subscribeToPlayerState()
                    .setEventCallback { ps -> onPlayerState(ps) }
                pendingUri?.let { uri ->
                    pendingUri = null
                    playNow(appRemote, uri)
                }
            }

            override fun onFailure(error: Throwable) {
                Log.w("AppRemotePlayer", "Couldn't connect to Spotify", error)
                remote = null
                pendingUri = null
                _state.update {
                    it.copy(
                        connection = if (error is CouldNotFindSpotifyApp) Connection.NotInstalled else Connection.Failed,
                        problem = describe(error),
                    )
                }
            }
        })
    }

    override fun play(context: Context, uri: String) {
        val r = remote
        if (r != null && r.isConnected) {
            playNow(r, uri)
        } else {
            pendingUri = uri
            connect(context)
        }
    }

    private fun playNow(r: SpotifyAppRemote, uri: String) {
        r.playerApi.play(uri)
            .setResultCallback { }
            .setErrorCallback { e ->
                Log.w("AppRemotePlayer", "Play failed for $uri", e)
                _state.update { it.copy(problem = "Spotify wouldn't play that. Playing an album needs Premium.") }
            }
    }

    private fun onPlayerState(ps: SpotifyPlayerState) {
        val t = ps.track
        _state.update {
            it.copy(
                track = t?.name,
                artist = t?.artist?.name,
                album = t?.album?.name,
                albumUri = t?.album?.uri,
                isPaused = ps.isPaused,
            )
        }
    }

    override fun pause() { remote?.playerApi?.pause() }
    override fun resume() { remote?.playerApi?.resume() }
    override fun next() { remote?.playerApi?.skipNext() }
    override fun previous() { remote?.playerApi?.skipPrevious() }

    override fun disconnect() {
        subscription?.cancel()
        subscription = null
        remote?.let { SpotifyAppRemote.disconnect(it) }
        remote = null
        _state.update { PlayerState(connection = if (clientId.isBlank()) Connection.NotSetUp else Connection.Disconnected) }
    }

    private fun describe(error: Throwable): String = when (error) {
        is CouldNotFindSpotifyApp -> "The Spotify app isn't installed on this phone."
        is NotLoggedInException -> "Sign in to the Spotify app first, then try again."
        is UserNotAuthorizedException ->
            "Spotify didn't allow it. Your Spotify account may need adding to the app's user list (docs/SETUP.md)."
        is AuthenticationFailedException ->
            "Spotify didn't recognise this build. Check the package name and fingerprint in the Spotify dashboard."
        else -> "Couldn't reach the Spotify app (${error::class.simpleName ?: "unknown"})."
    }
}
