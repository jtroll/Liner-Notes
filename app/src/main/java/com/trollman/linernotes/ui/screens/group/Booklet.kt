package com.trollman.linernotes.ui.screens.group

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trollman.linernotes.data.GroupSet
import com.trollman.linernotes.data.SetEntry
import com.trollman.linernotes.ui.system.CoverArt
import com.trollman.linernotes.ui.system.DotMatrixText
import com.trollman.linernotes.ui.theme.mono

private val Paper = Color(0xFFF6F1E7)
private val PaperInk = Color(0xFF1C1A17)
private val PaperSoft = Color(0xFF5E5850)
private val PaperAccent = Color(0xFFE8501A)

private sealed interface Page {
    data object Cover : Page
    data object Contents : Page
    data object Intro : Page
    data class Album(val index: Int, val entry: SetEntry) : Page
    data object Closing : Page
}

/**
 * The booklet as the recipient will see it, a page at a time: cover, contents, intro, one page
 * per album (heading, note, Favorite Tracks, cover) and the closing note.
 */
@Composable
fun Booklet(set: GroupSet) {
    val pages = buildList {
        add(Page.Cover)
        if (set.intro.isNotBlank()) add(Page.Intro)
        if (set.entries.isNotEmpty()) add(Page.Contents)
        set.entries.forEachIndexed { i, e -> add(Page.Album(i, e)) }
        if (set.closing.isNotBlank()) add(Page.Closing)
    }
    val pager = rememberPagerState { pages.size }
    Column {
        HorizontalPager(
            state = pager,
            contentPadding = PaddingValues(horizontal = 20.dp),
            pageSpacing = 12.dp,
            modifier = Modifier.fillMaxWidth(),
        ) { i ->
            PaperPage { PageContent(set, pages[i]) }
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            repeat(pages.size) { i ->
                Box(
                    Modifier
                        .padding(horizontal = 3.dp)
                        .size(if (i == pager.currentPage) 8.dp else 6.dp)
                        .clip(CircleShape)
                        .background(if (i == pager.currentPage) PaperAccent else PaperSoft.copy(alpha = 0.4f))
                )
            }
        }
    }
}

@Composable
private fun PaperPage(content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(4.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(0.72f)
            .shadow(8.dp, shape)
            .clip(shape)
            .background(Paper)
            .border(0.5.dp, PaperSoft.copy(alpha = 0.3f), shape)
            .padding(22.dp)
    ) { content() }
}

@Composable
private fun PageContent(set: GroupSet, page: Page) {
    val body = MaterialTheme.typography.bodyMedium.copy(color = PaperInk, lineHeight = 21.sp)
    when (page) {
        Page.Cover -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
            DotMatrixText(set.title, size = 34.sp, color = PaperInk)
            Spacer(Modifier.height(16.dp))
            Text(
                if (set.yearList) "For ${set.forName}, each year" else "For ${set.forName}",
                style = MaterialTheme.typography.titleMedium.copy(fontStyle = FontStyle.Italic),
                color = PaperSoft,
            )
            Spacer(Modifier.height(28.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                set.entries.take(6).forEach { e ->
                    CoverArt(e.album.toAlbum(), Modifier.size(36.dp).clip(RoundedCornerShape(2.dp)), showTitleOnDrawn = false)
                }
            }
        }
        Page.Intro -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.Center) {
            Text(set.intro, style = body)
        }
        Page.Contents -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Text("CONTENTS", style = mono(12.sp), color = PaperAccent)
            Spacer(Modifier.height(12.dp))
            set.entries.forEachIndexed { i, e ->
                Row(Modifier.padding(vertical = 3.dp)) {
                    Text("${i + 1}.", style = mono(12.sp), color = PaperSoft, modifier = Modifier.width(30.dp))
                    Text(e.album.heading, style = MaterialTheme.typography.bodySmall, color = PaperInk)
                }
            }
        }
        is Page.Album -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text("${page.index + 1}", style = mono(12.sp), color = PaperAccent)
                    Text(page.entry.album.heading, style = MaterialTheme.typography.titleMedium, color = PaperInk)
                }
                Spacer(Modifier.width(12.dp))
                CoverArt(page.entry.album.toAlbum(), Modifier.size(84.dp).shadow(4.dp).clip(RoundedCornerShape(2.dp)))
            }
            Spacer(Modifier.height(14.dp))
            Text(
                page.entry.note.ifBlank { "No note yet." },
                style = if (page.entry.note.isBlank()) body.copy(color = PaperSoft, fontStyle = FontStyle.Italic) else body,
            )
            if (page.entry.favoriteTracks.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                Text("${set.year} · FAVORITE TRACKS", style = mono(10.sp), color = PaperAccent)
                Text(
                    page.entry.favoriteTracks.joinToString(", ") { "“$it”" },
                    style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
                    color = PaperSoft,
                )
            }
        }
        Page.Closing -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.Center) {
            Text(set.closing, style = body, textAlign = TextAlign.Start)
        }
    }
}
