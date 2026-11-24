package com.trollman.linernotes.data

import org.junit.Assert.assertEquals
import org.junit.Test

class InfluenceTest {
    private fun look(total: Int, at: Long = 1) = InfluenceLook(at = at, days = 30, items = emptyMap(), total = total, max = 9, best = emptyList())

    @Test fun theCurrentLookPrefersTheNinetyDayMarkThenTheRunningLook() {
        assertEquals(7, Influence(mapOf("latest" to look(3), "d90" to look(7), "d30" to look(2))).current?.total)
        assertEquals(3, Influence(mapOf("latest" to look(3), "d30" to look(2))).current?.total)
    }

    @Test fun theMeterSumsMeasuredSendsAndScalesTheNeedle() {
        val m = Meter.of(
            listOf(
                Triple(100L, Influence(mapOf("latest" to look(4))), 1),
                Triple(200L, Influence(mapOf("d30" to look(6))), 1),
                Triple(300L, Influence(), 12),
            )
        )
        assertEquals(10, m.allTime)
        assertEquals(2, m.allTimeAlbums)
        assertEquals(6, m.latest)
        assertEquals(2, m.measured)
        assertEquals(1f, m.needle(m.allTime, m.allTimeAlbums), 0.001f)
        assertEquals(0.4f, m.needle(2, 1), 0.001f)
        assertEquals(0f, m.needle(0, 0), 0.001f)
    }
}
