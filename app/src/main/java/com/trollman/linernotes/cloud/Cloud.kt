package com.trollman.linernotes.cloud

import com.google.android.gms.tasks.Task
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.functions.FirebaseFunctions
import com.trollman.linernotes.data.Fit
import com.trollman.linernotes.data.Profile
import com.trollman.linernotes.data.Source
import com.trollman.linernotes.data.SpotifyAlbum
import com.trollman.linernotes.data.Writing
import java.util.concurrent.TimeUnit
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

    private suspend fun call(name: String, data: Map<String, Any?>, timeoutSeconds: Long = 70): Map<*, *> =
        functions.getHttpsCallable(name).apply { setTimeout(timeoutSeconds, TimeUnit.SECONDS) }
            .call(data).await().getData() as? Map<*, *> ?: emptyMap<String, Any?>()

    private fun style(w: Writing) = mapOf(
        "examples" to w.styleExamples.filter { it.isNotBlank() },
        "toneNotes" to w.toneNotes,
        "minWords" to w.minWords,
        "maxWords" to w.maxWords,
    )

    /** What Claude's draft of one note came back with. */
    data class Draft(val note: String, val sources: List<Source>, val flags: List<String>)

    /**
     * Drafts one album's note in the sender's voice (functions/lib/notes.js). Takes up to a couple
     * of minutes: it reads Wikipedia, searches for reviews, writes, then fact-checks.
     */
    suspend fun draftNote(
        artist: String,
        album: String,
        writing: Writing,
        recipientName: String,
        taste: String,
        plays: Int,
        weeks: Int,
        rangeLabel: String,
        favoriteTracks: List<String>,
        isCloser: Boolean,
        instruction: String? = null,
        previous: String? = null,
    ): Draft {
        val r = call(
            "draftNote",
            mapOf(
                "artist" to artist, "album" to album,
                "senderName" to writing.senderName, "recipientName" to recipientName, "taste" to taste,
                "plays" to plays, "weeks" to weeks, "rangeLabel" to rangeLabel,
                "favoriteTracks" to favoriteTracks, "isCloser" to isCloser,
                "style" to style(writing), "instruction" to instruction, "previous" to previous,
            ),
            timeoutSeconds = 300,
        )
        val sources = (r["sources"] as? List<*>).orEmpty().mapNotNull { s ->
            val m = s as? Map<*, *> ?: return@mapNotNull null
            val url = m["url"] as? String ?: return@mapNotNull null
            Source(title = m["title"] as? String ?: url, url = url)
        }
        val flags = (r["flags"] as? List<*>).orEmpty().mapNotNull { it as? String }
        return Draft(note = r["note"] as? String ?: "", sources = sources, flags = flags)
    }

    /** Drafts the set's intro or closing note, starting from last year's. */
    suspend fun draftFrame(
        kind: String,
        writing: Writing,
        recipientName: String,
        year: Int,
        title: String,
        albums: List<String>,
        lastText: String,
        instruction: String? = null,
        previous: String? = null,
    ): String = call(
        "draftFrame",
        mapOf(
            "kind" to kind, "senderName" to writing.senderName, "recipientName" to recipientName,
            "year" to year.toString(), "title" to title, "albums" to albums, "lastText" to lastText,
            "style" to style(writing), "instruction" to instruction, "previous" to previous,
        ),
        timeoutSeconds = 120,
    )["note"] as? String ?: ""

    /** "Fit for them" scores, one per album in order (null where Claude gave none). */
    suspend fun scoreFit(taste: String, albums: List<Pair<String, String>>): List<Fit?> {
        val r = call(
            "scoreFit",
            mapOf("taste" to taste, "albums" to albums.map { (artist, title) -> mapOf("artist" to artist, "title" to title) }),
            timeoutSeconds = 120,
        )
        return (r["scores"] as? List<*>).orEmpty().map { s ->
            val m = s as? Map<*, *> ?: return@map null
            val score = (m["score"] as? Number)?.toInt() ?: return@map null
            Fit(score, m["reason"] as? String ?: "")
        }
    }

    /** Makes a private playlist of the set: every track of each album, in order. Returns its link. */
    suspend fun makeSetPlaylist(name: String, description: String, albumUris: List<String>): String =
        call(
            "spotifySetPlaylist",
            mapOf("name" to name, "description" to description, "albumUris" to albumUris),
            timeoutSeconds = 120,
        )["url"] as? String ?: ""

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
