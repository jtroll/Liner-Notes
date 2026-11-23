package com.trollman.linernotes.print

import org.junit.Assert.assertEquals
import org.junit.Test

class ImpositionTest {
    @Test fun padsToWholeSheets() {
        assertEquals(16, Imposition.padded(16))
        assertEquals(16, Imposition.padded(13))
        assertEquals(4, Imposition.padded(1))
    }

    @Test fun fourPagesFoldIntoOneSheet() {
        // Front: back cover | cover. Back: page 2 | page 3.
        assertEquals(listOf(3 to 0, 1 to 2), Imposition.sides(4))
    }

    @Test fun sixteenPagesUseEverySideOnce() {
        val sides = Imposition.sides(16)
        assertEquals(8, sides.size)
        assertEquals((0 until 16).toList(), sides.flatMap { listOf(it.first, it.second) }.sorted())
        assertEquals(15 to 0, sides.first())
        assertEquals(7 to 8, sides.last())
        // Facing pages on each side add up to n - 1, which is what makes the fold work.
        sides.forEach { (l, r) -> assertEquals(15, l + r) }
    }
}
