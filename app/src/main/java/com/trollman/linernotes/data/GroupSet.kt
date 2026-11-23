package com.trollman.linernotes.data

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.util.UUID

/**
 * Group send (SPEC.md, "Group send"): a set of albums from a stretch of the sender's listening,
 * each with a note, plus an intro and a closing note, laid out as a booklet.
 *
 * Kept as one JSON document per set, so the whole thing saves and loads in one go and the shape
 * can grow without a migration.
 */
@Immutable
@Serializable
data class SetAlbum(
    val artist: String,
    val title: String,
    val imageUrl: String? = null,
    val mbid: String? = null,
    /** Plays in the set's range, from Last.fm's weekly charts. */
    val plays: Int = 0,
    /** How many separate weeks in the range it was played. */
    val weeks: Int = 0,
    val spotifyUri: String? = null,
) {
    val key: String get() = albumKey(artist, title)
    val heading: String get() = "$artist — $title"
    fun toAlbum() = Album(artist = artist, title = title, playCount = plays, imageUrl = imageUrl, mbid = mbid)
}

fun albumKey(artist: String, title: String) = "${artist.lowercase()}\u0000${title.lowercase()}"

@Immutable
@Serializable
data class Source(val title: String, val url: String)

@Serializable
enum class NoteState { Empty, Drafted, Edited }

@Immutable
@Serializable
data class SetEntry(
    val album: SetAlbum,
    val note: String = "",
    /** Claude's last draft, kept so the sender can compare it with their edit or go back to it. */
    val aiDraft: String = "",
    val flags: List<String> = emptyList(),
    val sources: List<Source> = emptyList(),
    /** The sender's most-played tracks on it, for the Favorite Tracks sidebar. */
    val favoriteTracks: List<String> = emptyList(),
    val state: NoteState = NoteState.Empty,
    /** Goes up each time the note is replaced from outside the editor (a draft, a restore). */
    val rev: Int = 0,
    /** Monthly picks: how the album ties to what the recipient already loves (from the suggestion). */
    val connection: String = "",
) {
    val key: String get() = album.key
}

/** One of Claude's monthly suggestions (SPEC.md, "Monthly send", step 4). */
@Immutable
@Serializable
data class Pick(
    val album: SetAlbum,
    val connection: String = "",
    /** How sure Claude is that it lands, 1 to 5. */
    val confidence: Int = 3,
    /** Played before last month: the candidates were widened to the past 3 months. */
    val fromEarlier: Boolean = false,
)

/** Claude's "fit for them" score for one album against the recipient's taste. */
@Immutable
@Serializable
data class Fit(val score: Int, val reason: String)

@Immutable
@Serializable
data class GroupSet(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    /** Who it's for, as the sender calls them. */
    val forName: String,
    /** The recipient's taste in the sender's words, until the recipient joins. */
    val taste: String = "",
    val fromDay: String,
    val toDay: String,
    val count: Int = 12,
    val entries: List<SetEntry> = emptyList(),
    val shortlist: List<SetAlbum> = emptyList(),
    val fit: Map<String, Fit> = emptyMap(),
    val intro: String = "",
    val closing: String = "",
    val deliverOn: String? = null,
    /** The year-end list: the closer framing on the last album and a dated booklet cover. */
    val yearList: Boolean = false,
    val playlistUrl: String? = null,
    /** Goes up each time Claude replaces the intro or closing, so open editors pick it up. */
    val frameRev: Int = 0,
    /** The invite this set goes out on (createInvite in functions/index.js) and its link. */
    val inviteToken: String? = null,
    val inviteUrl: String? = null,
    /** When the sender asked for it to be delivered, in epoch millis. */
    val deliverAt: Long? = null,
    /** [updatedAt] of the version last scheduled, so the app can say it's changed since. */
    val scheduledVersion: Long? = null,
    /** "group", or "monthly" for one album to a pair (SPEC.md, "Monthly send"). */
    val kind: String = "group",
    /** The pair a monthly send goes to. */
    val pairId: String? = null,
    /** The month a monthly send is for, "yyyy-mm"; its albums come from the month before. */
    val month: String? = null,
    /** Claude's suggestion and two alternates, best first. */
    val picks: List<Pick> = emptyList(),
    /** Nothing reached confidence 3, so the suggestion is "a stretch pick". */
    val stretch: Boolean = false,
    /** Albums the sender flipped away from, kept with their notes so flipping back loses nothing. */
    val parked: List<SetEntry> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) {
    val isMonthly: Boolean get() = kind == "monthly"
    val from: LocalDate get() = LocalDate.parse(fromDay)
    val to: LocalDate get() = LocalDate.parse(toDay)
    val year: Int get() = to.year
    fun picked(key: String) = entries.any { it.key == key }

    companion object {
        fun yearListTitle(year: Int) = "Top Album Discoveries of $year"
    }
}

/** The sender's writing settings (SPEC.md, "Template settings"). */
@Immutable
@Serializable
data class Writing(
    /** How the sender signs their notes. */
    val senderName: String = "",
    val styleExamples: List<String> = emptyList(),
    val toneNotes: String = "",
    val minWords: Int = 90,
    val maxWords: Int = 120,
    val lastIntro: String = "",
    val lastClosing: String = "",
)
