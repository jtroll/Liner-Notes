package com.trollman.linernotes.ui.screens

import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.palette.graphics.Palette
import coil.imageLoader
import coil.request.ImageRequest
import com.trollman.linernotes.AppViewModel
import com.trollman.linernotes.UiState
import com.trollman.linernotes.data.Album
import com.trollman.linernotes.spotify.Connection
import com.trollman.linernotes.spotify.PlayerState
import com.trollman.linernotes.ui.system.DotMatrixText
import com.trollman.linernotes.ui.system.PhysicalButton
import com.trollman.linernotes.ui.system.PhysicalTextButton
import com.trollman.linernotes.ui.system.PlayerDeck
import com.trollman.linernotes.ui.system.SectionLabel
import com.trollman.linernotes.ui.system.VinylSleeve
import com.trollman.linernotes.ui.system.drawnTone
import com.trollman.linernotes.ui.system.findActivity
import com.trollman.linernotes.ui.theme.liner
import com.trollman.linernotes.ui.theme.mono

/** The album's accent, pulled from its cover (SPEC.md, "Album art leads"). */
@Composable
private fun rememberCoverAccent(album: Album): Color {
    val context = LocalContext.current
    var accent by remember(album.key) { mutableStateOf(drawnTone(album)) }
    LaunchedEffect(album.imageUrl) {
        val url = album.imageUrl ?: return@LaunchedEffect
        val request = ImageRequest.Builder(context).data(url).allowHardware(false).build()
        val bitmap = (context.imageLoader.execute(request).drawable as? BitmapDrawable)?.bitmap ?: return@LaunchedEffect
        val palette = Palette.from(bitmap).generate()
        val swatch = palette.vibrantSwatch ?: palette.dominantSwatch ?: return@LaunchedEffect
        accent = Color(swatch.rgb)
    }
    return accent
}

/**
 * One album opened: the record slides out of its sleeve and spins while it plays. Play starts it
 * in the app through Spotify; the note arrives with Phase 2.
 */
@Composable
fun AlbumScreen(
    album: Album,
    ui: UiState,
    player: PlayerState,
    vm: AppViewModel,
    onBack: () -> Unit,
) {
    val c = liner
    val context = LocalContext.current
    val accent = rememberCoverAccent(album)
    var opened by remember(album.key) { mutableStateOf(false) }
    LaunchedEffect(album.key) { opened = true }
    val playingThis = player.isFrom(album)
    val finding = "play:${album.key}" in ui.busy || player.connection == Connection.Connecting

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(accent.copy(alpha = 0.22f), c.casing, c.casing)))
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .padding(bottom = 100.dp)
        ) {
            PhysicalButton(onBack, Modifier.size(44.dp), shape = RoundedCornerShape(12.dp), contentDescription = "Back to your records") {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null)
            }
            Spacer(Modifier.height(20.dp))
            VinylSleeve(album, out = if (opened) 0.92f else 0.08f, spinning = playingThis)
            Spacer(Modifier.height(24.dp))
            if (album.title.length <= 22) {
                DotMatrixText(album.title, size = 40.sp, color = c.ink)
            } else {
                Text(album.title, style = MaterialTheme.typography.displaySmall, color = c.ink)
            }
            Spacer(Modifier.height(6.dp))
            Text(album.artist, style = MaterialTheme.typography.titleLarge, color = c.inkSoft)
            if (album.playCount > 0) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(accent))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "${album.playCount} PLAYS · ${ui.shelf.period.longLabel.uppercase()}",
                        style = mono(12.sp),
                        color = c.inkFaint,
                    )
                }
            }
            Spacer(Modifier.height(28.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                PhysicalButton(
                    onClick = {
                        when {
                            playingThis -> vm.pause()
                            player.isPaused && player.album.equals(album.title, ignoreCase = true) -> vm.resume()
                            else -> vm.play(context.findActivity(), album)
                        }
                    },
                    accent = true,
                    shape = CircleShape,
                    modifier = Modifier.size(88.dp),
                    contentDescription = if (playingThis) "Pause ${album.title}" else "Play ${album.title}",
                ) {
                    if (finding) {
                        CircularProgressIndicator(Modifier.size(28.dp), color = c.onSignal, strokeWidth = 3.dp)
                    } else {
                        Icon(
                            if (playingThis) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(40.dp),
                        )
                    }
                }
                Column(Modifier.weight(1f)) {
                    PhysicalTextButton("Open in Spotify", { vm.openInSpotify(context, album) }, Modifier.fillMaxWidth())
                    player.problem?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall, color = c.inkSoft)
                    }
                }
            }
            Spacer(Modifier.height(32.dp))
            SectionLabel("Liner note")
            NotePlaceholder(album)
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

/** Where the note will sit, laid out like a page of the booklet. Phase 2 fills it. */
@Composable
private fun NotePlaceholder(album: Album) {
    val c = liner
    val shape = RoundedCornerShape(6.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(c.panel)
            .border(1.dp, c.bevelDark.copy(alpha = 0.5f), shape)
            .padding(20.dp)
    ) {
        Text(album.heading, style = MaterialTheme.typography.titleMedium, color = c.ink)
        Spacer(Modifier.height(10.dp))
        Text(
            "Notes come next: a draft in your own voice, from Wikipedia and reviews, ready to edit " +
                "or dictate over. For now this is where it will sit.",
            style = MaterialTheme.typography.bodyLarge,
            color = c.inkSoft,
        )
        Spacer(Modifier.height(14.dp))
        Text("FAVORITE TRACKS: SOON", style = mono(11.sp), color = c.inkFaint)
    }
}
