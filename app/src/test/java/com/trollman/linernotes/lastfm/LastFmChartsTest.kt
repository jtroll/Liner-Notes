package com.trollman.linernotes.lastfm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException

class LastFmChartsTest {

    @Test
    fun readsChartList() {
        val root = LastFm.parseRoot("""{"weeklychartlist":{"chart":[{"from":"100","to":"200"},{"from":"200","to":"300"}]}}""")
        assertEquals(listOf(100L to 200L, 200L to 300L), LastFm.parseChartList(root))
    }

    @Test
    fun readsWeeklyAlbumsIncludingASingleObject() {
        val many = LastFm.parseRoot(
            """{"weeklyalbumchart":{"album":[
                {"artist":{"#text":"The Smile","mbid":""},"name":"Cutouts","playcount":"12","mbid":""},
                {"artist":{"#text":"WILLOW"},"name":"empathogen","playcount":3,"mbid":"m1"}]}}"""
        )
        val albums = LastFm.parseWeeklyAlbums(many)
        assertEquals(2, albums.size)
        assertEquals(12, albums[0].plays)
        assertNull(albums[0].mbid)
        assertEquals("m1", albums[1].mbid)
        val one = LastFm.parseRoot("""{"weeklyalbumchart":{"album":{"artist":{"#text":"A"},"name":"B","playcount":"1"}}}""")
        assertEquals(1, LastFm.parseWeeklyAlbums(one).size)
    }

    @Test
    fun readsWeeklyTracks() {
        val root = LastFm.parseRoot("""{"weeklytrackchart":{"track":[{"artist":{"#text":"The Smile"},"name":"Zero Sum","playcount":"5"}]}}""")
        assertEquals("Zero Sum", LastFm.parseWeeklyTracks(root).single().title)
    }

    @Test
    fun readsAlbumInfo() {
        val root = LastFm.parseRoot(
            """{"album":{"name":"Cutouts","url":"https://www.last.fm/music/x",
                "image":[{"size":"small","#text":"https://img/s.png"},{"size":"mega","#text":"https://img/m.png"}],
                "tracks":{"track":[{"name":"Foreign Spies","artist":{"name":"The Smile"}},{"name":"Zero Sum"}]}}}"""
        )
        val info = LastFm.parseAlbumInfo(root)
        assertEquals("https://img/m.png", info.imageUrl)
        assertEquals(listOf("Foreign Spies", "Zero Sum"), info.tracks)
    }

    @Test(expected = IOException::class)
    fun errorsCarryLastFmsMessage() {
        LastFm.parseRoot("""{"error":6,"message":"User not found"}""")
    }
}
