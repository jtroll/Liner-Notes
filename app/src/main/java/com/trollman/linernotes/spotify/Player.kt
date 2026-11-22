package com.trollman.linernotes.spotify

import android.content.Context
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.StateFlow

/**
 * Playback, behind one interface (SPEC.md, "In-app playback"): Spotify labels its Android SDK
 * beta, so nothing outside this package knows App Remote exists.
 */
interface Player {
    val state: StateFlow<PlayerState>

    /** Connects to the Spotify app on the phone. [context] should be an Activity (Spotify may ask to approve). */
    fun connect(context: Context)

    /** Plays a Spotify URI (an album: spotify:album:...), connecting first if needed. */
    fun play(context: Context, uri: String)
    fun pause()
    fun resume()
    fun next()
    fun previous()
    fun disconnect()
}

enum class Connection { NotSetUp, NotInstalled, Disconnected, Connecting, Connected, Failed }

@Immutable
data class PlayerState(
    val connection: Connection = Connection.Disconnected,
    val track: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val albumUri: String? = null,
    val isPaused: Boolean = true,
    /** Why the last connect failed, in a sentence, or null. */
    val problem: String? = null,
) {
    val isPlaying: Boolean get() = connection == Connection.Connected && !isPaused && track != null
}
