package com.trollman.linernotes.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trollman.linernotes.AppViewModel
import com.trollman.linernotes.UiState
import com.trollman.linernotes.data.Album
import com.trollman.linernotes.data.Period
import com.trollman.linernotes.spotify.PlayerState
import com.trollman.linernotes.ui.system.DisplayWindow
import com.trollman.linernotes.ui.system.DotMatrixText
import com.trollman.linernotes.ui.system.Led
import com.trollman.linernotes.ui.system.PhysicalButton
import com.trollman.linernotes.ui.system.PhysicalTextButton
import com.trollman.linernotes.ui.system.PlayerDeck
import com.trollman.linernotes.ui.system.VinylSleeve
import com.trollman.linernotes.ui.theme.liner
import com.trollman.linernotes.ui.theme.mono

/** True when [player] is playing something from [album]. */
fun PlayerState.isFrom(album: Album): Boolean =
    isPlaying && this.album != null && this.album.equals(album.title, ignoreCase = true)

/**
 * Your records: the albums you've played most over a window, as sleeves with the record peeking
 * out (Phase 0's "browse your albums"; Phase 1 turns this into months and crates).
 */
@Composable
fun ShelfScreen(
    ui: UiState,
    player: PlayerState,
    vm: AppViewModel,
    onOpen: (Album) -> Unit,
    onSettings: () -> Unit,
    onGroupSend: () -> Unit,
    onInbox: () -> Unit,
    inboxCount: Int,
    onPairs: () -> Unit,
) {
    val c = liner
    val shelf = ui.shelf
    LaunchedEffect(Unit) { if (shelf.albums.isEmpty() && !shelf.loading) vm.loadShelf() }
    val insets = WindowInsets.safeDrawing.asPaddingValues()

    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = insets.calculateTopPadding() + 12.dp,
                bottom = insets.calculateBottomPadding() + 120.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("LINER NOTES", style = mono(13.sp), color = c.ink, modifier = Modifier.weight(1f))
                        PhysicalButton(
                            onPairs,
                            Modifier.height(44.dp),
                            shape = RoundedCornerShape(12.dp),
                            contentDescription = "Monthly send: an album each way with your pairs",
                        ) { Text("MONTHLY", style = mono(12.sp), modifier = Modifier.padding(horizontal = 12.dp)) }
                        Spacer(Modifier.width(8.dp))
                        PhysicalButton(
                            onGroupSend,
                            Modifier.height(44.dp),
                            shape = RoundedCornerShape(12.dp),
                            contentDescription = "Group send: a set of albums with a note on each",
                        ) { Text("GROUP", style = mono(12.sp), modifier = Modifier.padding(horizontal = 12.dp)) }
                        Spacer(Modifier.width(8.dp))
                        PhysicalButton(
                            onInbox,
                            Modifier.size(44.dp),
                            shape = RoundedCornerShape(12.dp),
                            contentDescription = if (inboxCount > 0) "Inbox, $inboxCount sets" else "Inbox",
                        ) {
                            Icon(Icons.Rounded.Inbox, contentDescription = null)
                            if (inboxCount > 0) Led(on = true, Modifier.align(Alignment.TopEnd).padding(6.dp), size = 7.dp)
                        }
                        Spacer(Modifier.width(10.dp))
                        PhysicalButton(
                            onSettings,
                            Modifier.size(44.dp),
                            shape = RoundedCornerShape(12.dp),
                            contentDescription = "Settings and connections",
                        ) { Icon(Icons.Rounded.Tune, contentDescription = null) }
                    }
                    Spacer(Modifier.height(14.dp))
                    DisplayWindow {
                        Text(
                            if (shelf.demo) "SAMPLE SHELF" else "TOP ALBUMS · ${shelf.owner.orEmpty().uppercase()}",
                            style = mono(11.sp),
                            color = c.onDisplay.copy(alpha = 0.6f),
                        )
                        Spacer(Modifier.height(6.dp))
                        DotMatrixText(
                            if (shelf.demo) "2024" else shelf.period.label.replace("M", " mo"),
                            size = 56.sp,
                            color = c.onDisplay,
                            maxLines = 1,
                        )
                        Text(
                            if (shelf.demo) "CONNECT LAST.FM FOR YOURS" else "${shelf.albums.size} RECORDS · ${shelf.period.longLabel.uppercase()}",
                            style = mono(11.sp),
                            color = c.signal,
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    if (!shelf.demo) {
                        PeriodKeys(current = shelf.period, onSelect = vm::setPeriod)
                        Spacer(Modifier.height(8.dp))
                    } else {
                        Text(
                            "Connect Last.fm in Settings to fill this shelf with what you've been playing.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = c.inkSoft,
                        )
                    }
                    when {
                        shelf.loading -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = c.signal)
                        }
                        shelf.error != null -> Column(Modifier.padding(vertical = 12.dp)) {
                            Text(shelf.error, style = MaterialTheme.typography.bodyMedium, color = c.ink)
                            Spacer(Modifier.height(10.dp))
                            PhysicalTextButton("Try again", vm::loadShelf)
                        }
                        !shelf.loading && shelf.albums.isEmpty() && !shelf.demo -> Text(
                            "Nothing scrobbled in ${shelf.period.longLabel}. Is Spotify connected to Last.fm?",
                            style = MaterialTheme.typography.bodyMedium,
                            color = c.inkSoft,
                            modifier = Modifier.padding(vertical = 12.dp),
                        )
                    }
                }
            }
            items(shelf.albums, key = { it.key }) { album ->
                AlbumCard(album, spinning = player.isFrom(album), onClick = { onOpen(album) })
            }
        }
        PlayerDeck(
            player = player,
            onPrevious = vm::previous,
            onPlayPause = { if (player.isPaused) vm.resume() else vm.pause() },
            onNext = vm::next,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

/** Four latching keys for the listening window, like presets on a radio. */
@Composable
private fun PeriodKeys(current: Period, onSelect: (Period) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Period.entries.forEach { p ->
            val on = p == current
            PhysicalButton(
                onClick = { onSelect(p) },
                latched = on,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
                    .semantics { selected = on },
                contentDescription = p.longLabel,
            ) {
                Text(p.label, style = mono(13.sp), color = if (on) liner.signal else liner.ink)
            }
        }
    }
}

@Composable
private fun AlbumCard(album: Album, spinning: Boolean, onClick: () -> Unit) {
    val c = liner
    Column(
        Modifier.clickable(role = Role.Button, onClickLabel = "Open", onClick = onClick)
    ) {
        VinylSleeve(album, out = if (spinning) 0.55f else 0.18f, spinning = spinning)
        Spacer(Modifier.height(10.dp))
        Text(
            album.title,
            style = MaterialTheme.typography.titleSmall,
            color = c.ink,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(album.artist, style = MaterialTheme.typography.bodySmall, color = c.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (album.playCount > 0) {
            Text("${album.playCount} PLAYS", style = mono(10.sp), color = c.inkFaint)
        }
    }
}
