package com.trollman.linernotes.ui.screens.inbox

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trollman.linernotes.GroupState
import com.trollman.linernotes.data.Received
import com.trollman.linernotes.data.SetAlbum
import com.trollman.linernotes.ui.screens.group.BackHeader
import com.trollman.linernotes.ui.screens.group.Booklet
import com.trollman.linernotes.ui.screens.group.IconKey
import com.trollman.linernotes.ui.screens.group.PdfPanel
import com.trollman.linernotes.ui.system.CoverArt
import com.trollman.linernotes.ui.system.DisplayWindow
import com.trollman.linernotes.ui.system.DotMatrixText
import com.trollman.linernotes.ui.system.Panel
import com.trollman.linernotes.ui.system.PhysicalTextButton
import com.trollman.linernotes.ui.system.SectionLabel
import com.trollman.linernotes.ui.theme.liner
import com.trollman.linernotes.ui.theme.mono
import com.trollman.linernotes.whenText

/** Sets other people have sent you, newest first, and joining with an invite link. */
@Composable
fun InboxScreen(
    state: GroupState,
    joining: Boolean,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    onJoin: (String) -> Unit,
) {
    val c = liner
    val insets = WindowInsets.safeDrawing.asPaddingValues()
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 18.dp, end = 18.dp,
            top = insets.calculateTopPadding() + 12.dp,
            bottom = insets.calculateBottomPadding() + 40.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { BackHeader("Inbox", onBack, backDescription = "Back to your records") }
        item {
            DisplayWindow {
                Text("ARRIVED", style = mono(11.sp), color = c.onDisplay.copy(alpha = 0.6f))
                DotMatrixText(state.inbox.size.toString().padStart(2, '0'), size = 56.sp, color = c.onDisplay, maxLines = 1)
                Text("SETS SENT TO YOU", style = mono(11.sp), color = c.signal)
            }
        }
        if (!state.inboxReady) {
            item {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = c.signal)
                }
            }
        } else if (state.inbox.isEmpty()) {
            item {
                Text(
                    if (state.online) "Nothing here yet. When someone sends you a set, it lands here and your phone lets you know."
                    else "This build has no backend yet, so nothing can arrive (docs/SETUP.md).",
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.inkSoft,
                )
            }
        }
        items(state.inbox, key = { it.id }) { r -> InboxRow(r, onClick = { onOpen(r.id) }) }
        item { JoinPanel(joining, onJoin) }
    }
}

@Composable
private fun InboxRow(r: Received, onClick: () -> Unit) {
    val c = liner
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val first = r.set.entries.firstOrNull()
        if (first != null) {
            CoverArt(first.album.toAlbum(), Modifier.size(64.dp).clip(RoundedCornerShape(3.dp)), showTitleOnDrawn = false)
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(r.set.title, style = MaterialTheme.typography.titleSmall, color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(
                    r.fromName.ifBlank { null }?.let { "FROM ${it.uppercase()}" },
                    whenText(r.deliveredAt).uppercase(),
                    if (r.test) "TEST COPY" else null,
                ).joinToString(" · "),
                style = mono(10.sp),
                color = if (r.test) c.signal else c.inkFaint,
            )
            Text("${r.set.entries.size} albums", style = MaterialTheme.typography.bodySmall, color = c.inkSoft)
        }
    }
}

@Composable
private fun JoinPanel(joining: Boolean, onJoin: (String) -> Unit) {
    val c = liner
    var text by rememberSaveable { mutableStateOf("") }
    Panel {
        Spacer(Modifier.height(8.dp))
        SectionLabel("Join with an invite")
        Text(
            "If an invite link didn't open the app, paste it here.",
            style = MaterialTheme.typography.bodySmall,
            color = c.inkSoft,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            placeholder = { Text("The invite link") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        if (joining) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = c.signal) }
        } else {
            PhysicalTextButton("Join", { onJoin(text) }, Modifier.fillMaxWidth(), enabled = text.isNotBlank())
        }
        Spacer(Modifier.height(12.dp))
    }
}

/**
 * A set someone sent you: the booklet, each album to play or open in Spotify, the playlist, and
 * the printable PDF (SPEC.md, "Group send: a booklet view").
 */
@Composable
fun ReceivedScreen(
    received: Received,
    onBack: () -> Unit,
    onPlay: (SetAlbum) -> Unit,
    onOpenInSpotify: (SetAlbum) -> Unit,
    onOpenLink: (String) -> Unit,
) {
    val c = liner
    val set = received.set
    val insets = WindowInsets.safeDrawing.asPaddingValues()
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = insets.calculateTopPadding() + 12.dp,
            bottom = insets.calculateBottomPadding() + 120.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Box(Modifier.padding(horizontal = 18.dp)) {
                BackHeader(
                    if (received.fromName.isBlank()) "Inbox" else "From ${received.fromName}",
                    onBack,
                    backDescription = "Back to your inbox",
                )
            }
        }
        item { Booklet(set) }
        set.playlistUrl?.let { url ->
            item {
                Box(Modifier.padding(horizontal = 18.dp)) {
                    PhysicalTextButton("Open the playlist in Spotify", { onOpenLink(url) }, Modifier.fillMaxWidth(), accent = true)
                }
            }
        }
        item { Box(Modifier.padding(horizontal = 18.dp)) { SectionLabel("The albums") } }
        itemsIndexed(set.entries, key = { _, e -> e.key }) { i, e ->
            Row(Modifier.padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                Text((i + 1).toString().padStart(2, '0'), style = mono(14.sp), color = c.signal, modifier = Modifier.width(28.dp))
                CoverArt(e.album.toAlbum(), Modifier.size(48.dp).clip(RoundedCornerShape(3.dp)), showTitleOnDrawn = false)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(e.album.title, style = MaterialTheme.typography.titleSmall, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(e.album.artist, style = MaterialTheme.typography.bodySmall, color = c.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    IconKey(Icons.Rounded.PlayArrow, "Play ${e.album.title}", onClick = { onPlay(e.album) })
                    IconKey(Icons.AutoMirrored.Rounded.OpenInNew, "Open ${e.album.title} in Spotify", onClick = { onOpenInSpotify(e.album) })
                }
            }
        }
        item {
            Box(Modifier.padding(horizontal = 18.dp)) { PdfPanel(set, received.fromName) }
        }
    }
}
