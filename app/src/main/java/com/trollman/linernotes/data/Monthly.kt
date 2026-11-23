package com.trollman.linernotes.data

import androidx.compose.runtime.Immutable
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

/**
 * The monthly send (SPEC.md, "Monthly send"): each month, each person in a pair sends the other one
 * album they loved the month before. The pairs come from pairs/{id} (functions/index.js).
 */

/** One of the signed-in person's pairs, from their side. */
@Immutable
data class PairInfo(
    val id: String,
    val partnerUid: String,
    val partnerName: String,
    val nickname: String = "",
    val monthlyOn: Boolean = true,
    /** The day each month the pick is ready, 1 to 28. */
    val draftDay: Int = 1,
    /** Hold the monthly send in December when a year-end set is going to the same person. */
    val decemberPause: Boolean = true,
) {
    val label: String get() = nickname.ifBlank { partnerName.ifBlank { "Your pair" } }
    val who: String get() = partnerName.ifBlank { "them" }
}

/** An album the sender could send this month. */
@Immutable
data class Candidate(val album: SetAlbum, val fromEarlier: Boolean)

/** What monthlySuggest answered. */
data class Suggestions(val picks: List<Pick>, val stretch: Boolean, val excluded: Int)

object Monthly {
    /** Track plays that make an album "played heavily" in a month. */
    const val MIN_TRACK_PLAYS = 15
    /** Or this many near-full plays, counted as track plays over the track count. */
    const val NEAR_FULL_PLAYS = 3
    /** Fewer qualifying albums than this widens the search to the past 3 months. */
    const val MIN_CANDIDATES = 3
    const val MAX_CANDIDATES = 25

    /** "2026-11". */
    fun monthKey(date: LocalDate = LocalDate.now()): String = YearMonth.from(date).toString()

    /** The month whose listening a send for [month] draws on: the month before. */
    fun listeningMonth(month: String): YearMonth = YearMonth.parse(month).minusMonths(1)

    fun monthName(month: YearMonth, locale: Locale = Locale.getDefault()): String =
        month.month.getDisplayName(TextStyle.FULL, locale)

    fun monthName(month: String, locale: Locale = Locale.getDefault()): String = monthName(YearMonth.parse(month), locale)

    /** Played heavily: [MIN_TRACK_PLAYS] track plays, or [NEAR_FULL_PLAYS] times through when the track count is known. */
    fun qualifies(plays: Int, tracks: Int?): Boolean =
        plays >= MIN_TRACK_PLAYS || (tracks != null && tracks > 0 && plays >= NEAR_FULL_PLAYS * tracks)

    /**
     * The candidates (step 3): the albums played heavily last month, most played first. When fewer
     * than [MIN_CANDIDATES] qualify, the ones played heavily over the past 3 months join them,
     * marked "from earlier". [tracks] is each album's track count by key, where known.
     */
    fun candidates(lastMonth: List<SetAlbum>, pastQuarter: List<SetAlbum>?, tracks: Map<String, Int>): List<Candidate> {
        fun heavy(list: List<SetAlbum>) = list
            .filter { qualifies(it.plays, tracks[it.key]) }
            .sortedWith(compareByDescending<SetAlbum> { it.plays }.thenByDescending { it.weeks })
        val recent = heavy(lastMonth).map { Candidate(it, fromEarlier = false) }
        if (recent.size >= MIN_CANDIDATES || pastQuarter == null) return recent.take(MAX_CANDIDATES)
        val have = recent.map { it.album.key }.toSet()
        val earlier = heavy(pastQuarter).filter { it.key !in have }.map { Candidate(it, fromEarlier = true) }
        return (recent + earlier).take(MAX_CANDIDATES)
    }

    /** Whether the pick for [month] is held for December because a year-end set goes to the same pair. */
    fun pausedForDecember(month: String, pair: PairInfo, sends: Collection<SendInfo>): Boolean =
        month.endsWith("-12") && pair.decemberPause && sends.any {
            it.pairId == pair.id && it.kind != "monthly" && !it.test &&
                YearMonth.from(java.time.Instant.ofEpochMilli(it.deliverAt).atZone(java.time.ZoneId.systemDefault())).toString() == month
        }
}
