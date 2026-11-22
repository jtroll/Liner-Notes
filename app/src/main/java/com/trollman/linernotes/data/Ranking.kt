package com.trollman.linernotes.data

import java.text.Normalizer

/** One album on one of Last.fm's weekly charts. */
data class ChartAlbum(val artist: String, val title: String, val mbid: String?, val plays: Int)

/** One track on one of Last.fm's weekly charts. */
data class ChartTrack(val artist: String, val title: String, val plays: Int)

/**
 * The shortlist's ranking (SPEC.md, "Group send"): albums from the range, by total plays and by
 * how many separate weeks the sender came back to them. Coming back matters more than one binge.
 */
object Ranking {
    const val WEEK_WEIGHT = 12

    /** Sums each album's plays across the weekly charts and counts the weeks it appeared in. */
    fun aggregate(weeks: List<List<ChartAlbum>>): List<SetAlbum> {
        val totals = LinkedHashMap<String, SetAlbum>()
        for (week in weeks) {
            val seen = HashSet<String>()
            for (a in week) {
                if (a.plays <= 0) continue
                val k = albumKey(a.artist, a.title)
                val before = totals[k]
                val firstThisWeek = seen.add(k)
                totals[k] = if (before == null) {
                    SetAlbum(a.artist, a.title, mbid = a.mbid, plays = a.plays, weeks = 1)
                } else {
                    before.copy(
                        plays = before.plays + a.plays,
                        weeks = before.weeks + if (firstThisWeek) 1 else 0,
                        mbid = before.mbid ?: a.mbid,
                    )
                }
            }
        }
        return totals.values.toList()
    }

    fun score(a: SetAlbum): Int = a.weeks * WEEK_WEIGHT + a.plays

    /** Best first. Albums played in only one week with a couple of plays are left out. */
    fun rank(albums: List<SetAlbum>, limit: Int = 40): List<SetAlbum> =
        albums
            .filter { it.weeks >= 2 || it.plays >= 5 }
            .sortedWith(compareByDescending<SetAlbum> { score(it) }.thenByDescending { it.plays }.thenBy { it.title.lowercase() })
            .take(limit)

    /** Track plays across the range, by normalised artist then normalised track title. */
    fun trackPlays(weeks: List<List<ChartTrack>>): Map<String, Map<String, Int>> {
        val out = HashMap<String, HashMap<String, Int>>()
        for (week in weeks) for (t in week) {
            val byTrack = out.getOrPut(norm(t.artist)) { HashMap() }
            val k = norm(t.title)
            byTrack[k] = (byTrack[k] ?: 0) + t.plays
        }
        return out
    }

    /**
     * The sender's favourite tracks on an album: its tracklist, ordered by their plays in the
     * range, most first, keeping only tracks they played. Titles keep the album's own spelling.
     */
    fun favoriteTracks(artist: String, tracklist: List<String>, plays: Map<String, Map<String, Int>>, max: Int = 6): List<String> {
        val byTrack = plays[norm(artist)] ?: return emptyList()
        return tracklist
            .distinct()
            .map { it to (byTrack[norm(it)] ?: byTrack[norm(stripVersion(it))] ?: 0) }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(max)
            .map { it.first }
    }

    /** "Song - 2019 Remaster" and "Song (Remastered)" count as "Song". */
    fun stripVersion(title: String): String =
        title.replace(Regex("""\s+-\s+.*(remaster|version|edit|mix|live|mono|stereo).*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*[(\[][^)\]]*(remaster|version|edit|mix|live|mono|stereo)[^)\]]*[)\]]""", RegexOption.IGNORE_CASE), "")
            .trim()

    fun norm(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .lowercase()
            .replace("&", " and ")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
}
