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
import com.trollman.linernotes.data.Candidate
import com.trollman.linernotes.data.Fit
import com.trollman.linernotes.data.History
import com.trollman.linernotes.data.Influence
import com.trollman.linernotes.data.PairInfo
import com.trollman.linernotes.data.PlaylistStatus
import com.trollman.linernotes.data.Pick
import com.trollman.linernotes.data.Reaction
import com.trollman.linernotes.data.SetAlbum
import com.trollman.linernotes.data.Suggestions
import com.trollman.linernotes.data.albumKey
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
    data class Dictated(val note: String, val fixes: List<String>)

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
        isMonthly: Boolean = false,
        connection: String? = null,
        earlierLists: List<History.Mention> = emptyList(),
    ): Draft {
        val r = call(
            "draftNote",
            mapOf(
                "artist" to artist, "album" to album,
                "senderName" to writing.senderName, "recipientName" to recipientName, "taste" to taste,
                "plays" to plays, "weeks" to weeks, "rangeLabel" to rangeLabel,
                "favoriteTracks" to favoriteTracks, "isCloser" to isCloser,
                "style" to style(writing), "instruction" to instruction, "previous" to previous,
                "isMonthly" to isMonthly, "connection" to connection,
                "earlierLists" to earlierLists.map { mapOf("year" to it.year.toString(), "artistOnly" to it.artistOnly) },
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

    /** A spoken note: Claude writes up the transcript in the sender's own words. */
    suspend fun formatDictation(
        artist: String,
        album: String,
        writing: Writing,
        recipientName: String,
        transcript: String,
        current: String,
        add: Boolean,
        favoriteTracks: List<String>,
    ): Dictated {
        val r = call(
            "formatDictation",
            mapOf(
                "artist" to artist, "album" to album, "transcript" to transcript,
                "senderName" to writing.senderName, "recipientName" to recipientName,
                "current" to current, "mode" to if (add) "add" else "replace",
                "favoriteTracks" to favoriteTracks, "style" to style(writing),
            ),
            timeoutSeconds = 180,
        )
        return Dictated(
            note = r["note"] as? String ?: "",
            fixes = (r["fixes"] as? List<*>).orEmpty().mapNotNull { it as? String },
        )
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
    /** Connects (or reconnects) Spotify. Returns the Spotify name and how many earlier sends were caught up. */
    suspend fun connectSpotify(code: String, redirectUri: String): Pair<String, Int> {
        val r = call("spotifyConnect", mapOf("code" to code, "redirectUri" to redirectUri), timeoutSeconds = 190)
        return (r["displayName"] as? String ?: "") to ((r["caughtUp"] as? Number)?.toInt() ?: 0)
    }

    /** Tries again to put a delivered send's albums in the recipient's Spotify. */
    suspend fun retryPlaylist(sendId: String): PlaylistStatus? =
        PlaylistStatus.parse(call("retryPlaylist", mapOf("sendId" to sendId), timeoutSeconds = 190))

    /** Settings' "Delete my data": everything of the signed-in person's, and their sign-in. */
    suspend fun deleteMyData() {
        call("deleteMyData", mapOf("confirm" to "DELETE"), timeoutSeconds = 550)
    }

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
        val r = call("acceptInvite", mapOf("token" to token, "name" to name), timeoutSeconds = 190)
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
        pairId: String? = null,
        kind: String = "group",
        month: String? = null,
    ): Scheduled {
        val r = call(
            "scheduleSend",
            mapOf(
                "setId" to setId, "booklet" to bookletJson, "deliverAt" to deliverAt,
                "inviteToken" to inviteToken, "fromName" to fromName, "test" to test,
                "pairId" to pairId, "kind" to kind, "month" to month,
            ),
            timeoutSeconds = 190, // delivering now also fills the recipient's Spotify
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
                        pairId = d.getString("pairId"),
                        kind = d.getString("kind") ?: "group",
                        month = d.getString("month"),
                        fromName = d.getString("fromName") ?: "",
                        set = d.getString("booklet")?.let { booklet(it) },
                        reactions = reactions(d.get("reactions")),
                        influence = Influence.parse(d.get("influence")),
                        playlist = PlaylistStatus.parse(d.get("playlist")),
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
                        val set = d.getString("booklet")?.let { booklet(it) } ?: return@mapNotNull null
                        Received(
                            id = d.id,
                            fromName = d.getString("fromName") ?: "",
                            deliveredAt = d.getTimestamp("deliveredAt")?.toDate()?.time
                                ?: d.getTimestamp("deliverAt")?.toDate()?.time ?: 0L,
                            set = set,
                            test = d.getBoolean("test") ?: false,
                            pairId = d.getString("pairId"),
                            kind = d.getString("kind") ?: "group",
                            reactions = reactions(d.get("reactions")),
                            influence = Influence.parse(d.get("influence")),
                            playlist = PlaylistStatus.parse(d.get("playlist")),
                        )
                    }.sortedByDescending { it.deliveredAt }
                )
            }
        awaitClose { reg.remove() }
    }

    private fun booklet(json: String): GroupSet? =
        runCatching { storeJson.decodeFromString<GroupSet>(json) }
            .onFailure { Log.w(TAG, "Unreadable booklet", it) }
            .getOrNull()

    private fun reactions(raw: Any?): Map<Int, Reaction> =
        (raw as? Map<*, *>).orEmpty().mapNotNull { (k, v) ->
            val i = (k as? String)?.toIntOrNull() ?: return@mapNotNull null
            val m = v as? Map<*, *> ?: return@mapNotNull null
            i to Reaction(m["reaction"] as? String, m["comment"] as? String ?: "")
        }.toMap()

    // ---- Monthly send (functions/index.js, "Monthly send") -------------------------------------

    /** The signed-in person's pairs, live. */
    fun pairs(uid: String): Flow<List<PairInfo>> = callbackFlow {
        val reg = db.collection("pairs").whereArrayContains("members", uid).addSnapshotListener { snap, err ->
            if (err != null) Log.w(TAG, "Watching pairs failed", err)
            if (snap == null) return@addSnapshotListener
            trySend(
                snap.documents.mapNotNull { d ->
                    val members = (d.get("members") as? List<*>).orEmpty().filterIsInstance<String>()
                    val partner = members.firstOrNull { it != uid } ?: return@mapNotNull null
                    val names = d.get("names") as? Map<*, *>
                    PairInfo(
                        id = d.id,
                        partnerUid = partner,
                        partnerName = names?.get(partner) as? String ?: "",
                        nickname = d.getString("nickname") ?: "",
                        monthlyOn = d.getBoolean("monthlyOn") ?: true,
                        draftDay = d.getLong("draftDay")?.toInt() ?: 1,
                        decemberPause = d.getBoolean("decemberPause") ?: true,
                        influenceMine = (d.get("influence") as? Map<*, *>)?.get(uid) == true,
                        influencePartner = (d.get("influence") as? Map<*, *>)?.get(partner) == true,
                    )
                }.sortedBy { it.label.lowercase() }
            )
        }
        awaitClose { reg.remove() }
    }

    /**
     * Claude's pick and two alternates from [candidates], with the recipient's taste and the pair's
     * history added on the server (the app never sees the recipient's listening).
     */
    suspend fun monthlySuggest(
        pairId: String,
        candidates: List<Candidate>,
        senderName: String,
        rangeLabel: String,
    ): Suggestions {
        val r = call(
            "monthlySuggest",
            mapOf(
                "pairId" to pairId,
                "senderName" to senderName,
                "rangeLabel" to rangeLabel,
                "candidates" to candidates.map {
                    mapOf(
                        "artist" to it.album.artist, "title" to it.album.title,
                        "plays" to it.album.plays, "weeks" to it.album.weeks, "fromEarlier" to it.fromEarlier,
                    )
                },
            ),
            timeoutSeconds = 180,
        )
        val byKey = candidates.associateBy { it.album.key }
        val picks = (r["picks"] as? List<*>).orEmpty().mapNotNull { p ->
            val m = p as? Map<*, *> ?: return@mapNotNull null
            val artist = m["artist"] as? String ?: return@mapNotNull null
            val title = m["title"] as? String ?: return@mapNotNull null
            val album = byKey[albumKey(artist, title)]?.album ?: SetAlbum(artist, title)
            Pick(
                album = album,
                connection = m["connection"] as? String ?: "",
                confidence = (m["confidence"] as? Number)?.toInt() ?: 3,
                fromEarlier = m["fromEarlier"] as? Boolean ?: false,
            )
        }
        return Suggestions(picks, r["stretch"] as? Boolean ?: false, (r["excluded"] as? Number)?.toInt() ?: 0)
    }

    /** The recipient's reaction to one album of a send (null clears it). */
    suspend fun react(sendId: String, index: Int, reaction: String?, comment: String) {
        call("reactToSend", mapOf("sendId" to sendId, "index" to index, "reaction" to reaction, "comment" to comment))
    }

    /** Changes the pair's settings: any of nickname, monthlyOn, draftDay, decemberPause. */
    suspend fun updatePair(pairId: String, changes: Map<String, Any>) {
        call("updatePair", changes + ("pairId" to pairId))
    }

    /** A fresh look at what every send in the pair did to the other's listening; returns how many it measured. */
    suspend fun influenceNow(pairId: String): Int {
        val r = call("influenceNow", mapOf("pairId" to pairId), timeoutSeconds = 300)
        return (r["measured"] as? Number)?.toInt() ?: 0
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
                    spotifyExpiresAt = snap.getTimestamp("spotify.expiresAt")?.toDate()?.time,
                    spotifyExpired = snap.getBoolean("spotify.expired") == true,
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
