package com.trollman.linernotes.ui.system

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trollman.linernotes.data.Album
import com.trollman.linernotes.spotify.Connection
import com.trollman.linernotes.spotify.PlayerState
import com.trollman.linernotes.ui.theme.liner
import com.trollman.linernotes.ui.theme.mono

/**
 * The compact turntable deck along the bottom while something plays: the spinning record and
 * square transport keys, like the portable player in design/references.
 */
@Composable
fun PlayerDeck(
    player: PlayerState,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = liner
    val visible = player.connection == Connection.Connected && player.track != null
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically { it },
        exit = slideOutVertically { it },
        modifier = modifier,
    ) {
        val shape = RoundedCornerShape(26.dp)
        Row(
            Modifier
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .fillMaxWidth()
                .shadow(12.dp, shape)
                .clip(shape)
                .background(c.panel)
                .border(1.dp, Brush.verticalGradient(listOf(c.bevelLight, c.bevelDark)), shape)
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Record(
                Album(player.artist.orEmpty(), player.album.orEmpty(), 0, null),
                spinning = player.isPlaying,
                modifier = Modifier.size(52.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    player.track.orEmpty(),
                    style = MaterialTheme.typography.titleSmall,
                    color = c.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOfNotNull(player.artist, player.album).joinToString(" · ").uppercase(),
                    style = mono(10.sp),
                    color = c.inkFaint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val key = RoundedCornerShape(10.dp)
                PhysicalButton(onPrevious, Modifier.size(44.dp), shape = key, contentDescription = "Previous track") {
                    Icon(Icons.Rounded.SkipPrevious, contentDescription = null)
                }
                PhysicalButton(
                    onPlayPause,
                    Modifier.size(44.dp),
                    accent = true,
                    shape = key,
                    contentDescription = if (player.isPaused) "Play" else "Pause",
                ) {
                    Icon(if (player.isPaused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, contentDescription = null)
                }
                PhysicalButton(onNext, Modifier.size(44.dp), shape = key, contentDescription = "Next track") {
                    Icon(Icons.Rounded.SkipNext, contentDescription = null)
                }
            }
        }
    }
}
