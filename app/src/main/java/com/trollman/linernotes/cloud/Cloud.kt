package com.trollman.linernotes.cloud

import com.google.android.gms.tasks.Task
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.functions.FirebaseFunctions
import com.trollman.linernotes.data.Profile
import com.trollman.linernotes.data.SpotifyAlbum
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The app's side of the backend. Every call that needs a secret (Last.fm's shared secret,
 * Spotify's client secret, later the Anthropic key) is a Cloud Function in functions/, so no
 * secret ships in the APK. The app only reads its own user document.
 */
class Cloud {
    private val functions = FirebaseFunctions.getInstance()
    private val db = FirebaseFirestore.getInstance()

    private suspend fun call(name: String, data: Map<String, Any?>): Map<*, *> =
        functions.getHttpsCallable(name).call(data).await().getData() as? Map<*, *> ?: emptyMap<String, Any?>()

    /** Swaps the token Last.fm handed back for a session key, kept server-side. Returns the username. */
    suspend fun connectLastFm(token: String): String =
        call("lastfmConnect", mapOf("token" to token))["username"] as? String ?: ""

    /** Swaps Spotify's authorization code for tokens, kept server-side. Returns the display name. */
    suspend fun connectSpotify(code: String, redirectUri: String): String =
        call("spotifyConnect", mapOf("code" to code, "redirectUri" to redirectUri))["displayName"] as? String ?: ""

    /** Finds the Spotify release for an album from Last.fm, or null when nothing matches well. */
    suspend fun findSpotifyAlbum(artist: String, title: String): SpotifyAlbum? {
        val r = call("spotifyFindAlbum", mapOf("artist" to artist, "album" to title))
        val uri = r["uri"] as? String ?: return null
        return SpotifyAlbum(
            uri = uri,
            name = r["name"] as? String ?: title,
            artist = r["artist"] as? String ?: artist,
            imageUrl = r["imageUrl"] as? String,
            webUrl = r["url"] as? String,
        )
    }

    /** Phase 0 proof: makes a private playlist in the person's Spotify with the album's tracks. */
    suspend fun makeTestPlaylist(albumUri: String): String =
        call("spotifyTestPlaylist", mapOf("albumUri" to albumUri))["url"] as? String ?: ""

    /** Keeps the signed-in person's name on their user document. Best effort. */
    fun touchUser(name: String?) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        db.collection("users").document(uid)
            .set(mapOf("name" to (name ?: ""), "lastSeenAt" to Timestamp.now()), SetOptions.merge())
    }

    /** The signed-in person's connections, as the Functions record them. */
    fun profile(uid: String): Flow<Profile> = callbackFlow {
        val reg = db.collection("users").document(uid).addSnapshotListener { snap, _ ->
            if (snap == null) return@addSnapshotListener
            trySend(
                Profile(
                    lastfmUsername = snap.getString("lastfm.username"),
                    spotifyName = snap.getString("spotify.displayName"),
                    spotifyConnectedAt = snap.getTimestamp("spotify.connectedAt")?.toDate()?.time,
                )
            )
        }
        awaitClose { reg.remove() }
    }
}

/** Awaits a Play-services Task without pulling in another coroutines artifact (as Kerasma). */
suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}
