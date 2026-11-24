package com.trollman.linernotes.data

import com.trollman.linernotes.ui.system.dialValueAt
import org.junit.Assert.assertEquals
import org.junit.Test

class DialTest {
    @Test
    fun anglesMapToMonths() {
        assertEquals(1, dialValueAt(135f, 1, 12)) // bottom left: the start
        assertEquals(12, dialValueAt(45f, 1, 12)) // bottom right: the end
        assertEquals(7, dialValueAt(-90f, 1, 12)) // straight up: the middle (6.5 rounds up)
        assertEquals(1, dialValueAt(100f, 1, 12)) // dead zone, nearer the start
        assertEquals(12, dialValueAt(80f, 1, 12)) // dead zone, nearer the end
        assertEquals(4, dialValueAt(-148.6f, 1, 12))
    }
}
