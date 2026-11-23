package com.trollman.linernotes.cloud

import com.google.android.gms.tasks.Task
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.messaging.FirebaseMessaging
import android.util.Log
import com.trollman.linernotes.data.Fit
import com.trollman.linernotes.data.GroupSet
import com.trollman.linernotes.data.Invite
import com.trollman.linernotes.data.InviteInfo
import com.trollman.linernotes.data.Profile
import com.trollman.linernotes.data.Received
import com.trollman.linernotes.data.Scheduled
import com.trollman.linernotes.data.SendInfo
import com.trollman.linernotes.data.storeJson
import com.trollman.linernotes.data.Source
import com.trollman.linernotes.data.SpotifyAlbum
import com.trollman.linernotes.data.Writing
import java.util.concurrent.TimeUnit
import kotlinx.serialization.decodeFromString
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

    // ---- Sending (functions/index.js, "Sending") ---------------------------------------------

    /** Makes an invite link for the person a set is for. */
    suspend fun createInvite(forName: String, fromName: String): Invite {
        val r = call("createInvite", mapOf("forName" to forName, "fromName" to fromName))
        return Invite(token = r["token"] as? String ?: "", url = r["url"] as? String ?: "")
    }

    /** Who an invite is from, without joining. */
    suspend fun inviteInfo(token: String): InviteInfo {
        val r = call("inviteInfo", mapOf("token" to token))
        return InviteInfo(
            inviterName = r["inviterName"] as? String ?: "",
            forName = r["forName"] as? String ?: "",
            problem = r["problem"] as? String,
            alreadyJoined = r["alreadyJoined"] as? Boolean ?: false,
        )
    }

    /** Joins: forms the pair and receives anything already due. Returns (inviter's name, sets delivered). */
    suspend fun acceptInvite(token: String, name: String): Pair<String, Int> {
        val r = call("acceptInvite", mapOf("token" to token, "name" to name))
        return (r["inviterName"] as? String ?: "") to ((r["delivered"] as? Number)?.toInt() ?: 0)
    }

    /**
     * Schedules [bookletJson] (the recipient's copy of the set) for [deliverAt] against the invite,
     * or, with [test], delivers it to the sender's own inbox now.
     */
    suspend fun scheduleSend(
        setId: String,
        bookletJson: String,
        deliverAt: Long,
        inviteToken: String?,
        fromName: String,
        test: Boolean,
    ): Scheduled {
        val r = call(
            "scheduleSend",
            mapOf(
                "setId" to setId, "booklet" to bookletJson, "deliverAt" to deliverAt,
                "inviteToken" to inviteToken, "fromName" to fromName, "test" to test,
            ),
        )
        return Scheduled(
            status = r["status"] as? String ?: "scheduled",
            deliverAt = (r["deliverAt"] as? Number)?.toLong() ?: deliverAt,
            waitingForJoin = r["waitingForJoin"] as? Boolean ?: false,
        )
    }

    suspend fun cancelSend(setId: String) {
        call("cancelSend", mapOf("setId" to setId))
    }

    /** The sets this person has scheduled or delivered, live. */
    fun sendsFrom(uid: String): Flow<List<SendInfo>> = callbackFlow {
        val reg = db.collection("sends").whereEqualTo("senderUid", uid).addSnapshotListener { snap, err ->
            if (err != null) Log.w(TAG, "Watching sends failed", err)
            if (snap == null) return@addSnapshotListener
            trySend(
                snap.documents.mapNotNull { d ->
                    SendInfo(
                        id = d.id,
                        setId = d.getString("setId") ?: return@mapNotNull null,
                        status = d.getString("status") ?: "scheduled",
                        deliverAt = d.getTimestamp("deliverAt")?.toDate()?.time ?: 0L,
                        deliveredAt = d.getTimestamp("deliveredAt")?.toDate()?.time,
                        recipientJoined = d.getString("recipientUid") != null,
                        test = d.getBoolean("test") ?: false,
                    )
                }
            )
        }
        awaitClose { reg.remove() }
    }

    /** Sets delivered to this person, newest first, live. */
    fun inbox(uid: String): Flow<List<Received>> = callbackFlow {
        val reg = db.collection("sends")
            .whereEqualTo("recipientUid", uid)
            .whereEqualTo("status", "delivered")
            .addSnapshotListener { snap, err ->
                if (err != null) Log.w(TAG, "Watching the inbox failed", err)
                if (snap == null) return@addSnapshotListener
                trySend(
                    snap.documents.mapNotNull { d ->
                        val json = d.getString("booklet") ?: return@mapNotNull null
                        val set = runCatching { storeJson.decodeFromString<GroupSet>(json) }
                            .onFailure { Log.w(TAG, "Unreadable booklet ${d.id}", it) }
                            .getOrNull() ?: return@mapNotNull null
                        Received(
                            id = d.id,
                            fromName = d.getString("fromName") ?: "",
                            deliveredAt = d.getTimestamp("deliveredAt")?.toDate()?.time
                                ?: d.getTimestamp("deliverAt")?.toDate()?.time ?: 0L,
                            set = set,
                            test = d.getBoolean("test") ?: false,
                        )
                    }.sortedByDescending { it.deliveredAt }
                )
            }
        awaitClose { reg.remove() }
    }

    /** Saves this phone's push token on the user document, so delivered sets can ping it. */
    suspend fun registerPush(uid: String) {
        val token = FirebaseMessaging.getInstance().token.await()
        saveToken(uid, token)
    }

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

private const val TAG = "Cloud"

/** Adds a push token to the person's user document (PushService calls this for new tokens). */
fun saveToken(uid: String, token: String) {
    FirebaseFirestore.getInstance().collection("users").document(uid)
        .set(mapOf("fcmTokens" to FieldValue.arrayUnion(token)), SetOptions.merge())
}

/** Awaits a Play-services Task without pulling in another coroutines artifact (as Kerasma). */
suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}
