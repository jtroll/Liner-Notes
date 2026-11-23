package com.trollman.linernotes.data

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

/** One of the sender's earlier lists, typed or pasted in (SPEC.md, "Data model": pastLists). */
@Immutable
@Serializable
data class PastList(
    val year: Int,
    /** Who it was for, as the sender calls them; blank counts for anyone. */
    val forName: String = "",
    val albums: List<SetAlbum> = emptyList(),
)

/** An album held back from monthly sends for a group send (SPEC.md, "Composing and editing"). */
@Immutable
@Serializable
data class SavedAlbum(
    val album: SetAlbum,
    /** Who it's saved for; blank for anyone. */
    val forName: String = "",
    val savedAt: Long = System.currentTimeMillis(),
)

/**
 * What the sender has already given someone: albums on their earlier lists and in their sends,
 * so the shortlist can mark them and suggestions can leave them out (SPEC.md, "Group send" and
 * "Monthly send", step 3).
 */
object History {
    private val numbering = Regex("""^\s*(?:\d{1,2}\s*[.):]|[-*•])\s*""")
    private val separators = listOf(" — ", " – ", " - ", " : ", ": ", " / ")
    private val byPattern = Regex("""^(.+?)\s+by\s+(.+)$""", RegexOption.IGNORE_CASE)
    private val edition = Regex(
        """\s*[(\[][^)\]]*(deluxe|expanded|remaster|anniversary|edition|bonus|version)[^)\]]*[)\]]""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Reads a pasted list, one album to a line: "Artist — Album", "Artist - Album", "Album by
     * Artist", numbered or bulleted or not. Lines it can't read are skipped.
     */
    fun parseList(text: String): List<SetAlbum> =
        text.lines()
            .map { it.replace(numbering, "").trim().trim('"', '“', '”') }
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val sep = separators.firstOrNull { line.contains(it) }
                if (sep != null) {
                    val artist = line.substringBefore(sep).trim()
                    val title = line.substringAfter(sep).trim().trim('"', '“', '”')
                    if (artist.isNotBlank() && title.isNotBlank()) SetAlbum(artist, title) else null
                } else {
                    byPattern.matchEntire(line)?.let { m -> SetAlbum(m.groupValues[2].trim(), m.groupValues[1].trim().trim('"', '“', '”')) }
                }
            }
            .distinctBy { matchKey(it) }

    /** Loose key for "is this the same album": accents, case, punctuation and edition tags ignored. */
    fun matchKey(a: SetAlbum): String = matchKey(a.artist, a.title)
    fun matchKey(artist: String, title: String): String =
        Ranking.norm(artist) + "|" + Ranking.norm(Ranking.stripVersion(title.replace(edition, "")))

    fun sameName(a: String, b: String): Boolean = a.isNotBlank() && b.isNotBlank() && Ranking.norm(a) == Ranking.norm(b)

    private fun listFor(list: PastList, forName: String) = list.forName.isBlank() || sameName(list.forName, forName)

    /**
     * Albums already given to this person, by [matchKey], with a short label for the mark.
     * [forName] is who a set is for; [pairId] the pair, once they've joined. [exceptSetId] leaves
     * out the set being made.
     */
    fun given(
        pastLists: List<PastList>,
        sends: Collection<SendInfo>,
        forName: String,
        pairId: String?,
        exceptSetId: String?,
        monthName: (String) -> String = { it },
    ): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (s in sends.sortedByDescending { it.deliveredAt ?: it.deliverAt }) {
            if (s.test || s.setId == exceptSetId) continue
            val set = s.set ?: continue
            val samePerson = (pairId != null && s.pairId == pairId) || sameName(set.forName, forName)
            if (!samePerson) continue
            val label = when {
                s.kind == "monthly" && s.month != null -> "SENT ${monthName(s.month).uppercase()}"
                !s.delivered -> "IN A SCHEDULED SET"
                else -> "SENT IN ${set.title.uppercase()}".take(40)
            }
            for (e in set.entries) out.putIfAbsent(matchKey(e.album), label)
        }
        for (list in pastLists.sortedByDescending { it.year }) {
            if (!listFor(list, forName)) continue
            for (a in list.albums) out.putIfAbsent(matchKey(a), "ON YOUR ${list.year} LIST")
        }
        return out
    }

    /** Where [album] or its artist was on the sender's earlier lists, for the note's prompt. */
    fun earlierMentions(pastLists: List<PastList>, forName: String, album: SetAlbum): List<Mention> {
        val key = matchKey(album)
        val artist = Ranking.norm(album.artist)
        return pastLists.filter { listFor(it, forName) }.mapNotNull { list ->
            when {
                list.albums.any { matchKey(it) == key } -> Mention(list.year, artistOnly = false)
                list.albums.any { Ranking.norm(it.artist) == artist } -> Mention(list.year, artistOnly = true)
                else -> null
            }
        }.distinctBy { it.year }.sortedBy { it.year }
    }

    data class Mention(val year: Int, val artistOnly: Boolean)
}
