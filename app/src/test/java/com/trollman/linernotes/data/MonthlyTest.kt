package com.trollman.linernotes.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale

class MonthlyTest {
    private fun a(title: String, plays: Int, weeks: Int = 1) = SetAlbum("Artist", title, plays = plays, weeks = weeks)

    @Test fun monthKeysAndTheListeningMonth() {
        assertEquals("2027-01", Monthly.monthKey(LocalDate.of(2027, 1, 9)))
        assertEquals(YearMonth.of(2026, 12), Monthly.listeningMonth("2027-01"))
        assertEquals("January", Monthly.monthName("2027-01", Locale.US))
    }

    @Test fun heavyPlayMeansFifteenTrackPlaysOrThreeTimesThrough() {
        assertTrue(Monthly.qualifies(15, null))
        assertFalse(Monthly.qualifies(14, null))
        assertTrue(Monthly.qualifies(12, 4))
        assertFalse(Monthly.qualifies(11, 4))
        assertFalse(Monthly.qualifies(5, 0))
    }

    @Test fun lastMonthsHeavyAlbumsComeFirstByPlays() {
        val c = Monthly.candidates(listOf(a("Light", 3), a("Big", 40), a("Mid", 20), a("Short", 9)), null, mapOf(a("Short", 0).key to 3))
        assertEquals(listOf("Big", "Mid", "Short"), c.map { it.album.title })
        assertTrue(c.none { it.fromEarlier })
    }

    @Test fun widensToThreeMonthsWhenFewerThanThreeQualify() {
        val last = listOf(a("Only", 30), a("Light", 2))
        val quarter = listOf(a("Only", 50), a("Older", 25), a("Faint", 4))
        val c = Monthly.candidates(last, quarter, emptyMap())
        assertEquals(listOf("Only", "Older"), c.map { it.album.title })
        assertFalse(c[0].fromEarlier)
        assertTrue(c[1].fromEarlier)
    }

    @Test fun decemberPauseOnlyWithAYearEndSetToTheSamePair() {
        val pair = PairInfo("p1", "u2", "Sam")
        val dec = java.time.ZonedDateTime.of(2026, 12, 25, 8, 0, 0, 0, java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val set = SendInfo("s", "set", "scheduled", dec, null, true, false, pairId = "p1", kind = "group")
        assertTrue(Monthly.pausedForDecember("2026-12", pair, listOf(set)))
        assertFalse(Monthly.pausedForDecember("2026-12", pair.copy(decemberPause = false), listOf(set)))
        assertFalse(Monthly.pausedForDecember("2026-11", pair, listOf(set)))
        assertFalse(Monthly.pausedForDecember("2026-12", pair, listOf(set.copy(pairId = "p2"))))
    }

    @Test fun theRecipientsCopyDropsTheMonthlyWorkings() {
        val set = GroupSet(
            title = "January pick", forName = "Sam", fromDay = "2026-12-01", toDay = "2026-12-31",
            kind = "monthly", pairId = "p1", month = "2027-01",
            picks = listOf(Pick(a("Big", 40), "ties to X", 4)),
            parked = listOf(SetEntry(a("Old", 20), note = "old note")),
            entries = listOf(SetEntry(a("Big", 40), note = "Hi", connection = "ties to X")),
        )
        val out = set.forRecipient()
        assertTrue(out.picks.isEmpty())
        assertTrue(out.parked.isEmpty())
        assertEquals("", out.entries[0].connection)
        assertEquals(0, out.entries[0].album.plays)
        assertEquals("monthly", out.kind)
        assertEquals("2027-01", out.month)
    }
}
