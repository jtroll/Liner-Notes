package com.trollman.linernotes.lastfm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class LastFmParseTest {

    @Test
    fun readsTopAlbums() {
        val body = """
            {"topalbums":{"album":[
              {"name":"Cutouts","playcount":"42","mbid":"","url":"https://www.last.fm/music/The+Smile/Cutouts",
               "artist":{"name":"The Smile","mbid":"x","url":"u"},
               "image":[{"size":"small","#text":"https://img/s.png"},{"size":"extralarge","#text":"https://img/xl.png"}],
               "@attr":{"rank":"1"}},
              {"name":"empathogen","playcount":7,"artist":{"name":"WILLOW"},
               "image":[{"size":"extralarge","#text":"https://lastfm.freetls.fastly.net/i/u/300x300/2a96cbd8b46e442fc41c2b86b821562f.png"}]}
            ],"@attr":{"user":"jtroll"}}}
        """.trimIndent()
        val albums = LastFm.parseTopAlbums(body)
        assertEquals(2, albums.size)
        assertEquals("The Smile — Cutouts", albums[0].heading)
        assertEquals(42, albums[0].playCount)
        assertEquals("https://img/xl.png", albums[0].imageUrl)
        assertNull(albums[0].mbid)
        assertEquals(7, albums[1].playCount)
        // Last.fm's grey-star placeholder is dropped so the app draws its own sleeve.
        assertNull(albums[1].imageUrl)
    }

    @Test
    fun singleAlbumComesBackAsAnObject() {
        val body = """{"topalbums":{"album":{"name":"A","playcount":"1","artist":{"name":"B"}}}}"""
        assertEquals(1, LastFm.parseTopAlbums(body).size)
    }

    @Test
    fun lastFmErrorsKeepTheirMessage() {
        val e = runCatching { LastFm.parseTopAlbums("""{"error":6,"message":"User not found"}""") }.exceptionOrNull()
        assertTrue(e is IOException)
        assertTrue(e!!.message!!.contains("User not found"))
    }
}
