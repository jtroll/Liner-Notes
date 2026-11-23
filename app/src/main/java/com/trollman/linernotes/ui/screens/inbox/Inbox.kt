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
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import com.trollman.linernotes.data.GroupSet
import com.trollman.linernotes.data.Reaction
import com.trollman.linernotes.data.Received
import com.trollman.linernotes.data.reactionLabel
import com.trollman.linernotes.ui.system.PhysicalButton
import com.trollman.linernotes.ui.system.VinylSleeve
import com.trollman.linernotes.data.SetAlbum
import com.trollman.linernotes.ui.screens.group.BackHeader
import com.trollman.linernotes.ui.screens.group.Booklet
import com.trollman.linernotes.ui.screens.group.IconKey
import com.trollman.linernotes.ui.screens.group.PdfPanel
import com.trollman.linernotes.ui.system.CoverArt
import com.trollman.linernotes.ui.system.Led
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
            Text(
                if (r.isMonthly && first != null) first.album.heading else r.set.title,
                style = MaterialTheme.typography.titleSmall,
                color = c.ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(
                    r.fromName.ifBlank { null }?.let { "FROM ${it.uppercase()}" },
                    whenText(r.deliveredAt).uppercase(),
                    if (r.test) "TEST COPY" else null,
                ).joinToString(" · "),
                style = mono(10.sp),
                color = if (r.test) c.signal else c.inkFaint,
            )
            Text(
                if (r.isMonthly) "${r.set.title}, with a note" else "${r.set.entries.size} albums",
                style = MaterialTheme.typography.bodySmall,
                color = c.inkSoft,
            )
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
 * the printable PDF (SPEC.md, "Group send: a booklet view"). A monthly send reads as a short
 * letter with its record. Each album takes a reaction (SPEC.md, "Reactions"). With [Received.mine]
 * it's one of your own sends as they see it, with their reactions.
 */
@Composable
fun ReceivedScreen(
    received: Received,
    onBack: () -> Unit,
    onPlay: (SetAlbum) -> Unit,
    onOpenInSpotify: (SetAlbum) -> Unit,
    onOpenLink: (String) -> Unit,
    onReact: (index: Int, reaction: String?, comment: String) -> Unit = { _, _, _ -> },
    busy: Set<String> = emptySet(),
    backLabel: String = "Back to your inbox",
    onRetryPlaylist: () -> Unit = {},
) {
    val c = liner
    val set = received.set
    val insets = WindowInsets.safeDrawing.asPaddingValues()
    val canReact = !received.mine
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
                    when {
                        received.mine -> if (set.forName.isBlank()) "Sent" else "To ${set.forName}"
                        received.fromName.isBlank() -> "Inbox"
                        else -> "From ${received.fromName}"
                    },
                    onBack,
                    backDescription = backLabel,
                )
            }
        }
        if (received.isMonthly && set.entries.isNotEmpty()) {
            val e = set.entries.first()
            item {
                Column(Modifier.padding(horizontal = 18.dp)) {
                    VinylSleeve(e.album.toAlbum(), out = 0.55f, spinning = false)
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PhysicalTextButton("Play", { onPlay(e.album) }, Modifier.weight(1f), accent = true)
                        PhysicalTextButton("Open in Spotify", { onOpenInSpotify(e.album) }, Modifier.weight(1f))
                    }
                }
            }
            item { Letter(set, received.fromName) }
            item {
                Box(Modifier.padding(horizontal = 18.dp)) {
                    PlaylistNote(received, onOpenLink, onRetryPlaylist, retrying = "playlist:${received.id}" in busy)
                }
            }
            influenceLine(received, 0)?.let { line ->
                item { Box(Modifier.padding(horizontal = 18.dp)) { InfluenceNote(line) } }
            }
            item {
                Box(Modifier.padding(horizontal = 18.dp)) {
                    ReactionPanel(e.album.title, received.reactions[0], canReact, "react:${received.id}:0" in busy, received.fromName) { r, comment ->
                        onReact(0, r, comment)
                    }
                }
            }
            return@LazyColumn
        }
        item { Booklet(set) }
        item {
            Box(Modifier.padding(horizontal = 18.dp)) {
                PlaylistNote(received, onOpenLink, onRetryPlaylist, retrying = "playlist:${received.id}" in busy)
            }
        }
        received.influence.current?.let { look ->
            item {
                Box(Modifier.padding(horizontal = 18.dp)) {
                    val best = look.best.mapNotNull { set.entries.getOrNull(it)?.album?.title }
                    InfluenceNote(
                        "${look.total} influence points after ${look.days} days" +
                            if (best.isEmpty()) "." else ". Landed best: ${best.joinToString(", ")}.",
                    )
                }
            }
        }
        item { Box(Modifier.padding(horizontal = 18.dp)) { SectionLabel("The albums") } }
        itemsIndexed(set.entries, key = { _, e -> e.key }) { i, e ->
            Column(Modifier.padding(horizontal = 18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
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
                influenceLine(received, i)?.let { line ->
                    Text(line, style = MaterialTheme.typography.bodySmall, color = c.inkSoft, modifier = Modifier.padding(start = 28.dp, top = 4.dp))
                }
                ReactionKeys(e.album.title, received.reactions[i], canReact, "react:${received.id}:$i" in busy) { r -> onReact(i, r, received.reactions[i]?.comment ?: "") }
            }
        }
        item {
            Box(Modifier.padding(horizontal = 18.dp)) { PdfPanel(set, received.fromName) }
        }
    }
}

/**
 * The influence line for album [index], in the second person for the recipient ("You've played
 * it 14 times") and as written for the sender ("Sam has played it 14 times").
 */
private fun influenceLine(received: Received, index: Int): String? {
    val line = received.influence.current?.items?.get(index)?.line?.takeIf { it.isNotBlank() } ?: return null
    if (received.mine) return line
    return line
        .replaceFirst(Regex("""^.+? hasn't played"""), "You haven't played")
        .replaceFirst(Regex("""^.+? has played"""), "You've played")
        .replace("made their top 20", "made your top 20")
}

@Composable
private fun InfluenceNote(text: String) {
    val c = liner
    Row(verticalAlignment = Alignment.CenterVertically) {
        Led(on = true)
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = c.ink)
    }
}

/** A monthly send as the recipient reads it: a short letter on paper. */
@Composable
fun Letter(set: GroupSet, fromName: String) {
    val e = set.entries.firstOrNull() ?: return
    Box(Modifier.padding(horizontal = 18.dp)) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(4.dp))
                .background(Color(0xFFF7F3EA))
                .padding(22.dp)
        ) {
            Text(set.title.uppercase(), style = mono(10.sp), color = Color(0xFF6B655A))
            Spacer(Modifier.height(10.dp))
            Text(e.album.heading, style = MaterialTheme.typography.titleMedium, color = Color(0xFF1E1B16))
            Spacer(Modifier.height(10.dp))
            Text(e.note, style = MaterialTheme.typography.bodyMedium, color = Color(0xFF1E1B16))
            if (e.favoriteTracks.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text("Favorite Tracks:", style = MaterialTheme.typography.labelMedium, color = Color(0xFF6B655A))
                Text(e.favoriteTracks.joinToString(", ") { "\u201c$it\u201d" }, style = MaterialTheme.typography.bodySmall, color = Color(0xFF3A362F))
            }
            if (fromName.isNotBlank()) {
                Spacer(Modifier.height(14.dp))
                Text("\u2014 $fromName", style = MaterialTheme.typography.bodyMedium, color = Color(0xFF1E1B16))
            }
        }
    }
}

private val reactionChoices = listOf("loved", "liked", "notForMe")

/** Loved, liked, not for me: three small latching keys under an album. */
@Composable
private fun ReactionKeys(title: String, current: Reaction?, canReact: Boolean, busy: Boolean, onPick: (String?) -> Unit) {
    val c = liner
    if (!canReact) {
        if (current != null && current.label.isNotBlank()) {
            Text(
                current.label.uppercase() + if (current.comment.isNotBlank()) " · \u201c${current.comment}\u201d" else "",
                style = mono(10.sp),
                color = c.signal,
                modifier = Modifier.padding(start = 28.dp, top = 6.dp),
            )
        }
        return
    }
    Row(Modifier.padding(start = 28.dp, top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        reactionChoices.forEach { r ->
            val on = current?.reaction == r
            PhysicalButton(
                { onPick(if (on) null else r) },
                Modifier.height(34.dp),
                latched = on,
                enabled = !busy,
                shape = RoundedCornerShape(10.dp),
                contentDescription = "${reactionLabel(r)}: $title",
            ) { Text(reactionLabel(r).uppercase(), style = mono(10.sp), modifier = Modifier.padding(horizontal = 10.dp)) }
        }
    }
}

/** The reaction and an optional comment back to the sender, for a monthly send. */
@Composable
private fun ReactionPanel(
    title: String,
    current: Reaction?,
    canReact: Boolean,
    busy: Boolean,
    fromName: String,
    onSave: (String?, String) -> Unit,
) {
    val c = liner
    var comment by rememberSaveable(current?.comment) { mutableStateOf(current?.comment ?: "") }
    Panel {
        Spacer(Modifier.height(8.dp))
        SectionLabel(if (canReact) "How did it land?" else "How it landed")
        if (!canReact) {
            Text(
                current?.label?.ifBlank { null } ?: "No reaction yet.",
                style = MaterialTheme.typography.titleSmall,
                color = if (current?.label.isNullOrBlank()) c.inkSoft else c.signal,
            )
            if (!current?.comment.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text("\u201c${current?.comment}\u201d", style = MaterialTheme.typography.bodyMedium, color = c.ink)
            }
            Spacer(Modifier.height(12.dp))
            return@Panel
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            reactionChoices.forEach { r ->
                val on = current?.reaction == r
                PhysicalButton(
                    { onSave(if (on) null else r, comment) },
                    Modifier.weight(1f).height(44.dp),
                    latched = on,
                    enabled = !busy,
                    shape = RoundedCornerShape(12.dp),
                    contentDescription = "${reactionLabel(r)}: $title",
                ) { Text(reactionLabel(r).uppercase(), style = mono(11.sp)) }
            }
        }
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = comment,
            onValueChange = { comment = it.take(1000) },
            placeholder = { Text(if (fromName.isBlank()) "A note back" else "A note back to $fromName") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
        )
        Spacer(Modifier.height(8.dp))
        PhysicalTextButton(
            "Send the note back",
            { onSave(current?.reaction, comment) },
            Modifier.fillMaxWidth(),
            enabled = !busy && comment.isNotBlank() && comment != current?.comment,
        )
        Spacer(Modifier.height(12.dp))
    }
}

/**
 * Where a send's albums are in Spotify (functions/index.js, "Playlists in the recipient's
 * Spotify"): for the recipient, their own playlist, or how to get one; for the sender, whether it
 * reached the other person's Spotify, with Retry when it didn't. A group send made before any of
 * this falls back to the sender's own playlist link.
 */
@Composable
private fun PlaylistNote(received: Received, onOpenLink: (String) -> Unit, onRetry: () -> Unit, retrying: Boolean) {
    val c = liner
    val p = received.playlist
    val senderUrl = received.set.playlistUrl
    val them = received.set.forName.ifBlank { "them" }
    val from = received.fromName.ifBlank { "them" }
    if (received.test) {
        senderUrl?.let { PhysicalTextButton("Open the playlist in Spotify", { onOpenLink(it) }, Modifier.fillMaxWidth()) }
        return
    }
    Column {
        val line: String? = when {
            p == null -> null
            received.mine && p.done -> if (received.isMonthly) "Added to the \u201cFrom\u201d playlist in $them\u2019s Spotify." else "It\u2019s a playlist in $them\u2019s Spotify too."
            received.mine && p.notConnected -> "$them hasn\u2019t connected Spotify, so it\u2019s only in Liner Notes for now. It goes in their Spotify when they do."
            received.mine && p.failed -> "Couldn\u2019t add it to $them\u2019s Spotify. ${p.reason.orEmpty()}"
            p.done -> if (received.isMonthly) "Added to your \u201cFrom $from\u201d playlist in Spotify." else "It\u2019s in your Spotify as a playlist, in the booklet\u2019s order."
            p.notConnected -> "Connect Spotify in Setup and sends from $from land in a playlist in your account too."
            p.failed -> "Couldn\u2019t add it to your Spotify. ${p.reason.orEmpty()}"
            else -> null
        }
        line?.let {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Led(on = p?.done == true)
                Spacer(Modifier.width(10.dp))
                Text(it.trim(), style = MaterialTheme.typography.bodySmall, color = c.inkSoft)
            }
            Spacer(Modifier.height(10.dp))
        }
        if (p != null && p.missing.isNotEmpty()) {
            Text(
                "Not on Spotify: ${p.missing.joinToString(", ")}.",
                style = MaterialTheme.typography.bodySmall,
                color = c.inkFaint,
            )
            Spacer(Modifier.height(10.dp))
        }
        when {
            p?.failed == true -> PhysicalTextButton(if (retrying) "Trying again…" else "Retry", onRetry, Modifier.fillMaxWidth(), enabled = !retrying)
            p?.done == true && p.url != null && !received.mine ->
                PhysicalTextButton("Open the playlist in Spotify", { onOpenLink(p.url) }, Modifier.fillMaxWidth(), accent = !received.isMonthly)
            !received.isMonthly && senderUrl != null ->
                PhysicalTextButton(if (received.mine) "Open your playlist" else "Open the playlist in Spotify", { onOpenLink(senderUrl) }, Modifier.fillMaxWidth())
        }
    }
}
