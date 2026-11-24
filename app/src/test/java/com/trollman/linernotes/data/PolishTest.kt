package com.trollman.linernotes.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PolishTest {
    private val day = 86_400_000L

    @Test
    fun spotifyExpiry() {
        val now = 1_000 * day
        val fine = Profile(spotifyName = "jt", spotifyExpiresAt = now + 60 * day)
        assertFalse(fine.spotifyRunningOut(now))
        assertFalse(fine.spotifyRunOut(now))
        assertEquals(60, fine.spotifyDaysLeft(now))

        val soon = fine.copy(spotifyExpiresAt = now + 5 * day)
        assertTrue(soon.spotifyRunningOut(now))
        assertFalse(soon.spotifyRunOut(now))

        val gone = fine.copy(spotifyExpiresAt = now - day)
        assertTrue(gone.spotifyRunOut(now))
        assertFalse(gone.spotifyRunningOut(now))

        // Spotify refusing the sign-in counts, whatever the date says.
        assertTrue(fine.copy(spotifyExpired = true).spotifyRunOut(now))
        // Not connected: nothing to warn about.
        assertFalse(Profile(spotifyExpiresAt = now - day).spotifyRunOut(now))
        assertNull(Profile().spotifyDaysLeft(now))
    }

    @Test
    fun playlistStatusParses() {
        val p = PlaylistStatus.parse(
            mapOf("state" to "done", "url" to "https://open.spotify.com/playlist/abc", "missing" to listOf("Gone", 3)),
        )!!
        assertTrue(p.done)
        assertEquals("https://open.spotify.com/playlist/abc", p.url)
        assertEquals(listOf("Gone"), p.missing)

        val failed = PlaylistStatus.parse(mapOf("state" to "failed", "reason" to "Their Spotify sign-in has run out.", "url" to "javascript:x"))!!
        assertTrue(failed.failed)
        assertNull(failed.url)
        assertNull(PlaylistStatus.parse(null))
        assertNull(PlaylistStatus.parse(mapOf("url" to "https://x")))
    }
}
