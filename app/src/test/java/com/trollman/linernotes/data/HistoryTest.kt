package com.trollman.linernotes.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryTest {
    @Test fun readsAPastedListInTheUsualShapes() {
        val text = """
            1. Big Thief — Dragon New Warm Mountain I Believe in You
            2) Wet Leg - Wet Leg
            • "Cutouts" by The Smile
            Fontaines D.C.: Romance

            just a heading
        """.trimIndent()
        val albums = History.parseList(text)
        assertEquals(4, albums.size)
        assertEquals(SetAlbum("Big Thief", "Dragon New Warm Mountain I Believe in You"), albums[0])
        assertEquals(SetAlbum("Wet Leg", "Wet Leg"), albums[1])
        assertEquals(SetAlbum("The Smile", "Cutouts"), albums[2])
        assertEquals(SetAlbum("Fontaines D.C.", "Romance"), albums[3])
    }

    @Test fun matchesIgnoreCaseAccentsAndEditionTags() {
        assertEquals(
            History.matchKey("Beyoncé", "Renaissance"),
            History.matchKey("beyonce", "RENAISSANCE (Deluxe Edition)"),
        )
    }

    private fun send(setId: String, forName: String, albums: List<SetAlbum>, kind: String = "group", month: String? = null, pairId: String? = null, test: Boolean = false) =
        SendInfo(
            id = "u_$setId", setId = setId, status = "delivered", deliverAt = 1, deliveredAt = 1,
            recipientJoined = true, test = test, pairId = pairId, kind = kind, month = month,
            set = GroupSet(title = "Albums", forName = forName, fromDay = "2026-01-01", toDay = "2026-12-01", entries = albums.map { SetEntry(it) }),
        )

    @Test fun marksSendsAndEarlierListsForTheSamePersonOnly() {
        val smile = SetAlbum("The Smile", "Cutouts")
        val wetLeg = SetAlbum("Wet Leg", "Moisturizer")
        val other = SetAlbum("Someone", "Else")
        val lists = listOf(PastList(2024, "", listOf(wetLeg)), PastList(2023, "Pat", listOf(other)))
        val sends = listOf(
            send("m1", "Sam", listOf(smile), kind = "monthly", month = "2026-10", pairId = "p1"),
            send("t1", "Sam", listOf(other), test = true),
            send("x1", "Alex", listOf(other)),
        )
        val given = History.given(lists, sends, forName = "Sam", pairId = "p1", exceptSetId = null) { "October" }
        assertEquals("SENT OCTOBER", given[History.matchKey(smile)])
        assertEquals("ON YOUR 2024 LIST", given[History.matchKey(wetLeg)])
        assertNull(given[History.matchKey(other)])
    }

    @Test fun earlierMentionsSayWhetherItWasTheAlbumOrJustTheArtist() {
        val lists = listOf(
            PastList(2024, "", listOf(SetAlbum("The Smile", "Wall of Eyes"))),
            PastList(2023, "", listOf(SetAlbum("The Smile", "Cutouts"))),
        )
        val m = History.earlierMentions(lists, "Sam", SetAlbum("The Smile", "Cutouts"))
        assertEquals(listOf(History.Mention(2023, false), History.Mention(2024, true)), m)
        assertTrue(History.earlierMentions(lists, "Sam", SetAlbum("Nobody", "Nothing")).isEmpty())
    }
}
