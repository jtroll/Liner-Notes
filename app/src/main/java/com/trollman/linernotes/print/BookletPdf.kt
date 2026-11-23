package com.trollman.linernotes.print

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Log
import androidx.core.content.FileProvider
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.trollman.linernotes.R
import com.trollman.linernotes.data.GroupSet
import com.trollman.linernotes.data.SetEntry
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The printable booklet (SPEC.md, "Group send"), laid out like J.T.'s folded booklet: half-letter
 * pages (5.5 × 8.5 in) with the cover, intro, contents, one page per album and the closing note.
 * A dozen albums make exactly 16 pages.
 *
 * Two versions: [Format.Pages] has the pages in reading order, for reading on screen or printing
 * one page per sheet; [Format.Fold] puts two pages side by side on letter sheets in booklet order,
 * to print double-sided (flip on the short edge), fold in half and staple.
 */
object BookletPdf {

    enum class Format { Pages, Fold }

    private const val W = 396 // 5.5 in at 72 points per inch
    private const val H = 612 // 8.5 in
    private const val M = 36f // margin

    private const val INK = 0xFF1C1A17.toInt()
    private const val SOFT = 0xFF5E5850.toInt()
    private const val ACCENT = 0xFFE8501A.toInt()
    private const val TILE = 0xFFE3DED4.toInt()

    private sealed interface Page {
        data object Cover : Page
        data object Intro : Page
        data object Contents : Page
        data class Album(val index: Int, val entry: SetEntry) : Page
        data object Closing : Page
        data object Blank : Page
    }

    /** Writes the PDF into the app's cache and returns it. Loads the covers, so give it a moment. */
    suspend fun write(context: Context, set: GroupSet, fromName: String, format: Format): File = withContext(Dispatchers.IO) {
        val covers = loadCovers(context, set)
        val type = Type(context)
        val pages = buildList {
            add(Page.Cover)
            if (set.intro.isNotBlank()) add(Page.Intro)
            add(Page.Contents)
            set.entries.forEachIndexed { i, e -> add(Page.Album(i, e)) }
            if (set.closing.isNotBlank()) add(Page.Closing)
        }
        val doc = PdfDocument()
        try {
            when (format) {
                Format.Pages -> pages.forEachIndexed { i, page ->
                    val p = doc.startPage(PdfDocument.PageInfo.Builder(W, H, i + 1).create())
                    drawPage(p.canvas, page, i, set, fromName, covers, type)
                    doc.finishPage(p)
                }
                Format.Fold -> {
                    val n = Imposition.padded(pages.size)
                    // Blanks go just before the back cover, so the last page stays last.
                    val all = pages.dropLast(1) + List(n - pages.size) { Page.Blank } + pages.last()
                    Imposition.sides(n).forEachIndexed { i, (left, right) ->
                        val p = doc.startPage(PdfDocument.PageInfo.Builder(W * 2, H, i + 1).create())
                        drawPage(p.canvas, all[left], left, set, fromName, covers, type)
                        p.canvas.save()
                        p.canvas.translate(W.toFloat(), 0f)
                        drawPage(p.canvas, all[right], right, set, fromName, covers, type)
                        p.canvas.restore()
                        // A faint fold line down the middle.
                        p.canvas.drawLine(W.toFloat(), 0f, W.toFloat(), 10f, Paint().apply { color = TILE })
                        p.canvas.drawLine(W.toFloat(), H - 10f, W.toFloat(), H.toFloat(), Paint().apply { color = TILE })
                        doc.finishPage(p)
                    }
                }
            }
            val dir = File(context.cacheDir, "pdf").apply { mkdirs() }
            val safe = set.title.replace(Regex("[^A-Za-z0-9 ]"), "").trim().replace(' ', '-').ifBlank { "Booklet" }
            val file = File(dir, if (format == Format.Fold) "$safe-to-fold.pdf" else "$safe.pdf")
            FileOutputStream(file).use { doc.writeTo(it) }
            file
        } finally {
            doc.close()
        }
    }

    /** Opens the share sheet with the PDF (print, save to Drive, send). */
    fun share(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/pdf")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(file.name, uri)
        context.startActivity(Intent.createChooser(send, "Booklet PDF").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    // ---- Drawing --------------------------------------------------------------------------------

    private class Type(context: Context) {
        val body: Typeface = ResourcesCompat.getFont(context, R.font.allo_crisp_variable) ?: Typeface.SANS_SERIF
        val dots: Typeface = ResourcesCompat.getFont(context, R.font.doto_variable) ?: Typeface.MONOSPACE
        val mono: Typeface = ResourcesCompat.getFont(context, R.font.plex_mono_regular) ?: Typeface.MONOSPACE
    }

    private fun paint(face: Typeface, size: Float, color: Int, variation: String? = null) =
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = face
            textSize = size
            this.color = color
            if (variation != null) setFontVariationSettings(variation)
        }

    private fun layoutOf(text: CharSequence, paint: TextPaint, width: Int, align: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL) =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(align)
            .setLineSpacing(0f, 1.28f)
            .setIncludePad(false)
            .build()

    /** Lays [text] out to fit [maxHeight], shrinking the type a half point at a time if it must. */
    private fun fitted(text: CharSequence, base: TextPaint, width: Int, maxHeight: Float, minSize: Float = 7f): StaticLayout {
        var size = base.textSize
        while (true) {
            val l = layoutOf(text, TextPaint(base).apply { textSize = size }, width)
            if (l.height <= maxHeight || size <= minSize) return l
            size -= 0.5f
        }
    }

    private fun Canvas.draw(l: StaticLayout, x: Float, y: Float) {
        save()
        translate(x, y)
        l.draw(this)
        restore()
    }

    private fun drawPage(
        c: Canvas,
        page: Page,
        index: Int,
        set: GroupSet,
        fromName: String,
        covers: Map<String, Bitmap>,
        t: Type,
    ) {
        val inner = (W - 2 * M).toInt()
        val body = paint(t.body, 10.5f, INK, "'wght' 400")
        when (page) {
            Page.Blank -> return
            Page.Cover -> {
                val title = layoutOf(set.title, paint(t.dots, 30f, INK, "'wght' 800, 'ROND' 100"), inner)
                c.draw(title, M, 96f)
                val forLine = if (set.yearList) "For ${set.forName}, each year" else "For ${set.forName}"
                val sub = layoutOf(forLine, paint(t.body, 14f, SOFT, "'wght' 400"), inner)
                c.draw(sub, M, 96f + title.height + 14f)
                // Up to twelve covers in a grid.
                val cols = 4
                val gap = 8f
                val size = (inner - gap * (cols - 1)) / cols
                val top = H - M - 30f - 3 * size - 2 * gap
                set.entries.take(12).forEachIndexed { i, e ->
                    val x = M + (i % cols) * (size + gap)
                    val y = top + (i / cols) * (size + gap)
                    drawCover(c, e, covers, RectF(x, y, x + size, y + size), t)
                }
                val foot = listOfNotNull(fromName.ifBlank { null }, set.year.toString()).joinToString(" · ").uppercase()
                c.drawText(foot, M, H - M, paint(t.mono, 8f, SOFT))
            }
            Page.Intro -> c.draw(fitted(set.intro, body, inner, H - 2 * M - 30f), M, M + 24f)
            Page.Closing -> c.draw(fitted(set.closing, body, inner, H - 2 * M - 30f), M, M + 24f)
            Page.Contents -> {
                c.drawText("CONTENTS", M, M + 10f, paint(t.mono, 9f, ACCENT))
                val lines = set.entries.mapIndexed { i, e -> "${i + 1}.  ${e.album.heading}" }.joinToString("\n")
                c.draw(fitted(lines, paint(t.body, 11f, INK, "'wght' 400"), inner, H - 2 * M - 50f), M, M + 30f)
            }
            is Page.Album -> {
                val e = page.entry
                val art = 96f
                val textWidth = (inner - art - 14f).toInt()
                c.drawText("${page.index + 1}", M, M + 10f, paint(t.mono, 10f, ACCENT))
                val heading = fitted(e.album.heading, paint(t.body, 15f, INK, "'wght' 650"), textWidth, art - 18f, 10f)
                c.draw(heading, M, M + 20f)
                drawCover(c, e, covers, RectF(W - M - art, M, W - M, M + art), t)
                val favorites = if (e.favoriteTracks.isEmpty()) null else
                    layoutOf(e.favoriteTracks.joinToString(", ") { "“$it”" }, paint(t.body, 9f, SOFT, "'wght' 400"), inner)
                val favHeight = favorites?.let { it.height + 18f } ?: 0f
                val noteTop = M + art + 18f
                val note = fitted(e.note.ifBlank { " " }, body, inner, H - M - 24f - noteTop - favHeight)
                c.draw(note, M, noteTop)
                if (favorites != null) {
                    val y = H - M - 20f - favorites.height
                    c.drawText("${set.year} · FAVORITE TRACKS", M, y - 6f, paint(t.mono, 7f, ACCENT))
                    c.draw(favorites, M, y)
                }
            }
        }
        if (page != Page.Cover) {
            val number = paint(t.mono, 7.5f, SOFT).apply { textAlign = Paint.Align.CENTER }
            c.drawText("${index + 1}", W / 2f, H - M / 2f, number)
        }
    }

    private fun drawCover(c: Canvas, e: SetEntry, covers: Map<String, Bitmap>, r: RectF, t: Type) {
        val bmp = covers[e.key]
        if (bmp != null) {
            c.drawBitmap(bmp, null, r, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        } else {
            c.drawRect(r, Paint().apply { color = TILE })
            val label = layoutOf(e.album.title, paint(t.body, (r.width() / 9f).coerceIn(5f, 10f), SOFT, "'wght' 500"), (r.width() - 8).toInt().coerceAtLeast(10))
            c.save()
            c.clipRect(r)
            c.draw(label, r.left + 4f, r.top + 4f)
            c.restore()
        }
        c.drawRect(r, Paint().apply { style = Paint.Style.STROKE; strokeWidth = 0.4f; color = Color.argb(60, 0, 0, 0) })
    }

    private suspend fun loadCovers(context: Context, set: GroupSet): Map<String, Bitmap> {
        val loader = context.imageLoader
        val out = mutableMapOf<String, Bitmap>()
        for (e in set.entries) {
            val url = e.album.imageUrl ?: continue
            val request = ImageRequest.Builder(context).data(url).allowHardware(false).size(512).build()
            val result = runCatching { loader.execute(request) }.getOrNull()
            val drawable = (result as? SuccessResult)?.drawable ?: continue
            val bmp = runCatching { (drawable as? BitmapDrawable)?.bitmap ?: drawable.toBitmap(512, 512) }
                .onFailure { Log.w("BookletPdf", "Cover failed for ${e.album.title}", it) }
                .getOrNull() ?: continue
            out[e.key] = bmp
        }
        return out
    }
}
