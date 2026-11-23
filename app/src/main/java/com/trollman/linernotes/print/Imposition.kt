package com.trollman.linernotes.print

/**
 * Page order for a folded booklet (saddle stitch): pages printed two to a side on sheets, printed
 * double-sided (flip on the short edge), stacked, folded in half and stapled in the middle.
 */
object Imposition {
    /** [pages] rounded up to a multiple of 4, since each sheet carries four pages. */
    fun padded(pages: Int): Int = ((pages + 3) / 4) * 4

    /**
     * For an [n]-page booklet (a multiple of 4), the (left, right) page index on each printed side,
     * in printing order: sheet 1 front, sheet 1 back, sheet 2 front, and so on. Indices start at 0.
     */
    fun sides(n: Int): List<Pair<Int, Int>> {
        require(n > 0 && n % 4 == 0) { "A booklet needs a multiple of 4 pages, not $n" }
        return buildList {
            for (sheet in 0 until n / 4) {
                add((n - 1 - 2 * sheet) to (2 * sheet))
                add((2 * sheet + 1) to (n - 2 - 2 * sheet))
            }
        }
    }
}
