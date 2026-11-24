package com.trollman.linernotes.data

import org.junit.Assert.assertEquals
import org.junit.Test

class RankingTest {

    @Test
    fun sumsPlaysAndCountsWeeks() {
        val weeks = listOf(
            listOf(ChartAlbum("The Smile", "Cutouts", null, 10), ChartAlbum("WILLOW", "empathogen", "m", 3)),
            listOf(ChartAlbum("the smile", "CUTOUTS", "x", 4)),
            listOf(ChartAlbum("The Smile", "Cutouts", null, 0)),
        )
        val a = Ranking.aggregate(weeks)
        val cutouts = a.first { it.title == "Cutouts" }
        assertEquals(14, cutouts.plays)
        assertEquals(2, cutouts.weeks)
        assertEquals("x", cutouts.mbid)
        assertEquals(1, a.first { it.title == "empathogen" }.weeks)
    }

    @Test
    fun comingBackBeatsOneBinge() {
        val binge = SetAlbum("A", "Binge", plays = 30, weeks = 1)
        val steady = SetAlbum("B", "Steady", plays = 20, weeks = 6)
        val stray = SetAlbum("C", "Stray", plays = 2, weeks = 1)
        assertEquals(listOf("Steady", "Binge"), Ranking.rank(listOf(binge, stray, steady)).map { it.title })
    }

    @Test
    fun favoriteTracksFollowPlays() {
        val plays = Ranking.trackPlays(
            listOf(
                listOf(ChartTrack("The Smile", "Zero Sum", 5), ChartTrack("The Smile", "Foreign Spies", 2)),
                listOf(ChartTrack("The Smile", "Foreign Spies", 6), ChartTrack("Other", "Zero Sum", 50)),
            )
        )
        val tracks = listOf("Foreign Spies", "Instant Psalm", "Zero Sum - 2024 Remaster")
        assertEquals(listOf("Foreign Spies", "Zero Sum - 2024 Remaster"), Ranking.favoriteTracks("The Smile", tracks, plays))
    }

    @Test
    fun normalisesNames() {
        assertEquals("beyonce cowboy carter", Ranking.norm("Beyoncé — COWBOY CARTER!"))
        assertEquals("simon and garfunkel", Ranking.norm("Simon & Garfunkel"))
        assertEquals("Song", Ranking.stripVersion("Song (Remastered 2011)"))
    }
}
