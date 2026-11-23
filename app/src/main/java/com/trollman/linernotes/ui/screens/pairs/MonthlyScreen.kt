package com.trollman.linernotes.ui.screens.pairs

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.trollman.linernotes.GroupSendViewModel
import com.trollman.linernotes.GroupState
import com.trollman.linernotes.data.Album
import com.trollman.linernotes.data.GroupSet
import com.trollman.linernotes.data.Monthly
import com.trollman.linernotes.data.PairInfo
import com.trollman.linernotes.data.Pick
import com.trollman.linernotes.data.SetAlbum
import com.trollman.linernotes.ui.screens.group.BackHeader
import com.trollman.linernotes.ui.screens.group.Busy
import com.trollman.linernotes.ui.screens.group.IconKey
import com.trollman.linernotes.ui.screens.group.wordCount
import com.trollman.linernotes.ui.screens.inbox.Letter
import com.trollman.linernotes.ui.system.CoverArt
import com.trollman.linernotes.ui.system.Led
import com.trollman.linernotes.ui.system.Panel
import com.trollman.linernotes.ui.system.PhysicalButton
import com.trollman.linernotes.ui.system.PhysicalTextButton
import com.trollman.linernotes.ui.system.SectionLabel
import com.trollman.linernotes.ui.system.VinylSleeve
import com.trollman.linernotes.ui.theme.liner
import com.trollman.linernotes.ui.theme.mono
import com.trollman.linernotes.whenText

/**
 * Composing a monthly send (SPEC.md, "Key screens: Compose a monthly send"): the suggested album
 * on the platter, the alternates to flip through like a crate, any album by search, the note, a
 * preview of what they'll see, and Send.
 */
@Composable
fun MonthlyScreen(
    set: GroupSet,
    pair: PairInfo?,
    state: GroupState,
    vm: GroupSendViewModel,
    onBack: () -> Unit,
    onOpenNote: (String) -> Unit,
    onPlay: (Album) -> Unit,
) {
    val c = liner
    val insets = WindowInsets.safeDrawing.asPaddingValues()
    val month = set.month ?: Monthly.monthKey()
    val name = set.forName.ifBlank { pair?.who ?: "them" }
    val entry = set.entries.firstOrNull()
    val sent = set.pairId?.let { state.sentMonthly(it, month) }
    val suggesting = "suggest:${set.id}" in state.busy
    val drafting = entry != null && "note:${set.id}:${entry.key}" in state.busy
    val sending = "send:${set.id}" in state.busy
    val currentPick = set.picks.firstOrNull { it.album.key == entry?.key }
    var previewing by rememberSaveable { mutableStateOf(false) }
    var confirming by rememberSaveable { mutableStateOf(false) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 18.dp, end = 18.dp,
            top = insets.calculateTopPadding() + 12.dp,
            bottom = insets.calculateBottomPadding() + 120.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { BackHeader("${Monthly.monthName(month)} pick for $name", onBack, backDescription = "Back to the pair") }

        if (sent != null) {
            item {
                Panel {
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Led(on = true)
                        Spacer(Modifier.width(10.dp))
                        Text("SENT · ${whenText(sent.deliveredAt ?: sent.deliverAt).uppercase()}", style = mono(11.sp), color = c.ink)
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }
        }

        // The platter: the album going out, its record sliding out of the sleeve.
        item {
            if (entry == null) {
                Panel {
                    Spacer(Modifier.height(12.dp))
                    if (suggesting) {
                        Text(
                            "Looking at what you played in ${Monthly.monthName(Monthly.listeningMonth(month))} and choosing an album $name should hear.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = c.inkSoft,
                        )
                        Spacer(Modifier.height(10.dp))
                        val p = state.progress
                        if (p != null && p.second > 0) {
                            LinearProgressIndicator(progress = { p.first.toFloat() / p.second }, modifier = Modifier.fillMaxWidth(), color = c.signal)
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = c.signal)
                        }
                    } else {
                        Text(
                            "No album yet. Ask for a suggestion, or search for any album below.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = c.inkSoft,
                        )
                        Spacer(Modifier.height(10.dp))
                        PhysicalTextButton("Suggest an album", { vm.suggest(set.id) }, Modifier.fillMaxWidth(), accent = true, enabled = state.online)
                    }
                    Spacer(Modifier.height(12.dp))
                }
            } else {
                Column {
                    VinylSleeve(entry.album.toAlbum(), out = if (drafting || suggesting) 0.2f else 0.55f, spinning = drafting)
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(entry.album.title, style = MaterialTheme.typography.titleLarge, color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(entry.album.artist, style = MaterialTheme.typography.bodyMedium, color = c.inkSoft)
                        }
                        IconKey(Icons.Rounded.PlayArrow, "Play ${entry.album.title}", onClick = { onPlay(entry.album.toAlbum()) })
                    }
                    val tags = listOfNotNull(
                        currentPick?.let { "CONFIDENCE ${"●".repeat(it.confidence.coerceIn(1, 5))}${"○".repeat(5 - it.confidence.coerceIn(1, 5))}" },
                        if (set.stretch && currentPick == set.picks.firstOrNull()) "A STRETCH PICK" else null,
                        if (currentPick?.fromEarlier == true) "FROM EARLIER" else null,
                        if (entry.album.plays > 0) "${entry.album.plays} PLAYS" else null,
                    )
                    if (tags.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text(tags.joinToString(" · "), style = mono(10.sp), color = c.signal)
                    }
                    if (entry.connection.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(entry.connection, style = MaterialTheme.typography.bodyMedium, color = c.ink)
                    }
                }
            }
        }

        // The crate: the suggestion and its alternates.
        if (set.picks.size > 1 && sent == null) {
            item { SectionLabel("Flip through the crate") }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(set.picks, key = { it.album.key }) { pick ->
                        CrateSleeve(pick, chosen = pick.album.key == entry?.key, onClick = { vm.choose(set.id, pick) })
                    }
                }
            }
        }

        // Any album, by search.
        if (sent == null) item { SearchPanel(set, state, vm) }

        // The note.
        if (entry != null) {
            item {
                Panel {
                    Spacer(Modifier.height(8.dp))
                    SectionLabel("The note")
                    when {
                        drafting -> Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(20.dp), color = c.signal, strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                            Text("Claude is reading up on it and drafting in your voice.", style = MaterialTheme.typography.bodySmall, color = c.inkSoft)
                        }
                        entry.note.isBlank() -> Text("No note yet.", style = MaterialTheme.typography.bodySmall, color = c.inkSoft)
                        else -> {
                            Text(entry.note, style = MaterialTheme.typography.bodyMedium, color = c.ink)
                            Spacer(Modifier.height(6.dp))
                            Text(
                                listOfNotNull(
                                    "${wordCount(entry.note)} WORDS",
                                    if (entry.flags.isNotEmpty()) "${entry.flags.size} TO CHECK" else null,
                                ).joinToString(" · "),
                                style = mono(10.sp),
                                color = if (entry.flags.isNotEmpty()) c.signal else c.inkFaint,
                            )
                        }
                    }
                    if (entry.favoriteTracks.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Favorite Tracks: " + entry.favoriteTracks.joinToString(", ") { "“$it”" },
                            style = MaterialTheme.typography.bodySmall,
                            color = c.inkSoft,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PhysicalTextButton(if (sent == null) "Edit" else "Read", { onOpenNote(entry.key) }, Modifier.weight(1f))
                        if (entry.note.isBlank() && !drafting && sent == null) {
                            PhysicalTextButton("Draft it", { vm.draftNote(set.id, entry.key) }, Modifier.weight(1f), enabled = state.online)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }
        }

        // Preview and send.
        if (entry != null && sent == null) {
            item {
                Panel {
                    Spacer(Modifier.height(8.dp))
                    SectionLabel("Send it to $name")
                    Text(
                        "It arrives in their inbox straight away, with a notification. One pick each month.",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.inkSoft,
                    )
                    Spacer(Modifier.height(10.dp))
                    PhysicalTextButton(
                        if (previewing) "Hide the preview" else "Preview what $name sees",
                        { previewing = !previewing },
                        Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    Busy(sending) {
                        PhysicalTextButton(
                            "Send to $name",
                            { confirming = true },
                            Modifier.fillMaxWidth(),
                            accent = true,
                            enabled = state.online && entry.note.isNotBlank() && !drafting,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }
            if (previewing) item { Letter(set, fromName = state.writing.senderName) }
        }
    }

    if (confirming && entry != null) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Send ${entry.album.title} to $name?") },
            text = { Text("It goes to their inbox now with your note, and can't be called back.") },
            confirmButton = {
                TextButton(onClick = { confirming = false; vm.sendMonthly(set.id) }) { Text("Send") }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text("Not yet") } },
        )
    }
}

@Composable
private fun CrateSleeve(pick: Pick, chosen: Boolean, onClick: () -> Unit) {
    val c = liner
    Column(
        Modifier.width(112.dp).clickable(role = Role.Button, onClick = onClick),
        horizontalAlignment = Alignment.Start,
    ) {
        Box {
            CoverArt(pick.album.toAlbum(), Modifier.size(112.dp).clip(RoundedCornerShape(3.dp)), showTitleOnDrawn = false)
            if (chosen) Led(on = true, Modifier.align(Alignment.TopEnd).padding(6.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(pick.album.title, style = MaterialTheme.typography.labelMedium, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(pick.album.artist, style = MaterialTheme.typography.bodySmall, color = c.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SearchPanel(set: GroupSet, state: GroupState, vm: GroupSendViewModel) {
    val c = liner
    var query by rememberSaveable(set.id) { mutableStateOf("") }
    Panel {
        Spacer(Modifier.height(8.dp))
        SectionLabel("Or send any album")
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Album or artist") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            if ("search" in state.busy) {
                Box(Modifier.size(38.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(20.dp), color = c.signal, strokeWidth = 2.dp)
                }
            } else {
                IconKey(Icons.Rounded.Search, "Search", onClick = { vm.search(query) }, enabled = query.isNotBlank())
            }
        }
        val results = state.search
        if (results != null) {
            Spacer(Modifier.height(8.dp))
            if (results.isEmpty()) Text("Nothing found.", style = MaterialTheme.typography.bodySmall, color = c.inkSoft)
            results.forEach { a -> SearchRow(a, onClick = { vm.choose(set.id, Pick(a)); vm.clearSearch(); query = "" }) }
            Spacer(Modifier.height(4.dp))
            PhysicalButton({ vm.clearSearch() }, Modifier.size(38.dp), shape = RoundedCornerShape(10.dp), contentDescription = "Clear the search") {
                androidx.compose.material3.Icon(Icons.Rounded.Close, contentDescription = null, modifier = Modifier.size(20.dp))
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun SearchRow(a: SetAlbum, onClick: () -> Unit) {
    val c = liner
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverArt(a.toAlbum(), Modifier.size(44.dp).clip(RoundedCornerShape(3.dp)), showTitleOnDrawn = false)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(a.title, style = MaterialTheme.typography.titleSmall, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(a.artist, style = MaterialTheme.typography.bodySmall, color = c.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
