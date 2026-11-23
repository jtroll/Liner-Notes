package com.trollman.linernotes.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SendingTest {
    private val token = "AbCdEfGhIjKlMnOpQrStUv"

    @Test fun readsTokensFromEveryFormOfTheLink() {
        assertEquals(token, InviteLinks.token("https://liner-notes.web.app/invite/$token"))
        assertEquals(token, InviteLinks.token("  linernotes://invite/$token  "))
        assertEquals(token, InviteLinks.token("Join me! https://x.web.app/invite/$token?ref=1"))
        assertEquals(token, InviteLinks.token(token))
        assertNull(InviteLinks.token("https://example.com/"))
        assertNull(InviteLinks.token("short"))
    }

    @Test fun theRecipientsCopyLeavesThePrivatePartsBehind() {
        val set = GroupSet(
            title = "T", forName = "Sam", taste = "likes quiet records", fromDay = "2026-01-01", toDay = "2026-11-22",
            entries = listOf(
                SetEntry(
                    album = SetAlbum("A", "B", plays = 40, weeks = 6),
                    note = "Note", aiDraft = "Draft", flags = listOf("flag"), sources = listOf(Source("s", "https://s")),
                    favoriteTracks = listOf("One"),
                )
            ),
            shortlist = listOf(SetAlbum("C", "D")),
            fit = mapOf("k" to Fit(8, "r")),
            inviteToken = token,
        )
        val copy = set.forRecipient()
        assertEquals("", copy.taste)
        assertTrue(copy.shortlist.isEmpty())
        assertTrue(copy.fit.isEmpty())
        assertNull(copy.inviteToken)
        val e = copy.entries.single()
        assertEquals("Note", e.note)
        assertEquals(listOf("One"), e.favoriteTracks)
        assertEquals("", e.aiDraft)
        assertTrue(e.flags.isEmpty() && e.sources.isEmpty())
        assertEquals(0, e.album.plays)
        assertEquals(0, e.album.weeks)
    }

    @Test fun knowsWhenTheSetChangedAfterScheduling() {
        val set = GroupSet(title = "T", forName = "S", fromDay = "2026-01-01", toDay = "2026-02-01", updatedAt = 100)
        assertFalse(set.changedSinceScheduled)
        assertFalse(set.copy(scheduledVersion = 100).changedSinceScheduled)
        assertTrue(set.copy(scheduledVersion = 50).changedSinceScheduled)
    }
}
