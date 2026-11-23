package com.trollman.linernotes.ui.screens.group

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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trollman.linernotes.GroupSendViewModel
import com.trollman.linernotes.GroupState
import com.trollman.linernotes.data.GroupSet
import com.trollman.linernotes.data.History
import com.trollman.linernotes.data.NoteState
import com.trollman.linernotes.data.SetAlbum
import com.trollman.linernotes.data.SetEntry
import com.trollman.linernotes.ui.system.CoverArt
import com.trollman.linernotes.ui.system.DisplayWindow
import com.trollman.linernotes.ui.system.DotMatrixText
import com.trollman.linernotes.ui.system.Led
import com.trollman.linernotes.ui.system.Panel
import com.trollman.linernotes.ui.system.PhysicalButton
import com.trollman.linernotes.ui.system.PhysicalTextButton
import com.trollman.linernotes.ui.system.SectionLabel
import com.trollman.linernotes.ui.theme.liner
import com.trollman.linernotes.ui.theme.mono

private enum class SetTab(val label: String) { Pick("PICK"), Notes("NOTES"), Booklet("BOOKLET") }

/** One set: pick and order the albums, write the notes, preview the booklet. */
@Composable
fun SetScreen(
    set: GroupSet,
    state: GroupState,
    vm: GroupSendViewModel,
    onBack: () -> Unit,
    onOpenNote: (String) -> Unit,
    onOpenLink: (String) -> Unit,
) {
    val c = liner
    val insets = WindowInsets.safeDrawing.asPaddingValues()
    var tab by rememberSaveable(set.id) { mutableStateOf(if (set.entries.size >= set.count) SetTab.Notes else SetTab.Pick) }
    var confirmDelete by remember { mutableStateOf(false) }
    val drafted = set.entries.count { it.note.isNotBlank() }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 18.dp, end = 18.dp,
            top = insets.calculateTopPadding() + 12.dp,
            bottom = insets.calculateBottomPadding() + 40.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            BackHeader(set.title, onBack, backDescription = "Back to your sets") {
                IconKey(Icons.Rounded.DeleteOutline, "Delete this set", onClick = { confirmDelete = true })
            }
        }
        item {
            DisplayWindow {
                Text(
                    "FOR ${set.forName.uppercase()} · ${shortDate(set.fromDay)}–${shortDate(set.toDay)}",
                    style = mono(11.sp),
                    color = c.onDisplay.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                DotMatrixText("${set.entries.size}/${set.count}", size = 56.sp, color = c.onDisplay, maxLines = 1)
                Text(
                    "$drafted NOTES WRITTEN" + (set.deliverOn?.let { " · ARRIVES ${shortDate(it)}" } ?: ""),
                    style = mono(11.sp),
                    color = c.signal,
                )
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SetTab.entries.forEach { t ->
                    val on = t == tab
                    PhysicalButton(
                        onClick = { tab = t },
                        latched = on,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f).height(44.dp).semantics { selected = on },
                    ) { Text(t.label, style = mono(13.sp), color = if (on) c.signal else c.ink) }
                }
            }
        }
        when (tab) {
            SetTab.Pick -> pickTab(set, state, vm)
            SetTab.Notes -> notesTab(set, state, vm, onOpenNote)
            SetTab.Booklet -> item { BookletTab(set, state, vm, onOpenLink) }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this set?") },
            text = { Text("${set.title} and its notes go for good.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; vm.delete(set.id); onBack() }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Keep it") } },
        )
    }
}

// ---- Pick -----------------------------------------------------------------------------------

private fun androidx.compose.foundation.lazy.LazyListScope.pickTab(set: GroupSet, state: GroupState, vm: GroupSendViewModel) {
    item(key = "taste") { TastePanel(set, state, vm) }
    item(key = "list-label") {
        SectionLabel(if (set.entries.isEmpty()) "Your list · tap albums below to add them" else "Your list")
    }
    val givenBefore = state.given(set)
    itemsIndexed(set.entries, key = { _, e -> "picked:${e.key}" }) { i, e ->
        PickedRow(
            index = i,
            entry = e,
            mark = givenBefore[History.matchKey(e.album)],
            last = i == set.entries.lastIndex,
            onUp = { vm.move(set.id, i, -1) },
            onDown = { vm.move(set.id, i, 1) },
            onRemove = { vm.togglePick(set.id, e.album) },
        )
    }
    if (set.entries.size > set.count) {
        item(key = "over") {
            Text(
                "That's ${set.entries.size - set.count} more than the ${set.count} you planned. Fine if you want them all.",
                style = MaterialTheme.typography.bodySmall,
                color = liner.inkSoft,
            )
        }
    }
    item(key = "short-label") {
        val loading = "shortlist:${set.id}" in state.busy
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("Shortlist · from your Last.fm", Modifier.weight(1f))
            IconKey(Icons.Rounded.Refresh, "Rebuild the shortlist", onClick = { vm.loadShortlist(set.id) }, enabled = !loading)
        }
        if (loading) {
            val p = state.progress
            Column(Modifier.padding(vertical = 8.dp)) {
                if (p != null && p.second > 0) {
                    LinearProgressIndicator(progress = { p.first / p.second.toFloat() }, modifier = Modifier.fillMaxWidth(), color = liner.signal)
                    Spacer(Modifier.height(6.dp))
                    Text("READING WEEK ${p.first} OF ${p.second}", style = mono(10.sp), color = liner.inkFaint)
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = liner.signal)
                }
            }
        } else if (set.shortlist.isEmpty()) {
            PhysicalTextButton("Build the shortlist", { vm.loadShortlist(set.id) }, Modifier.fillMaxWidth())
        }
    }
    val pickedKeys = set.entries.map { it.key }.toSet()
    val given = state.given(set)
    itemsIndexed(set.shortlist, key = { _, a -> "short:${a.key}" }) { _, a ->
        ShortlistRow(
            a,
            picked = a.key in pickedKeys,
            fit = set.fit[a.key],
            mark = given[History.matchKey(a)] ?: if (state.isSaved(a)) "SAVED FOR LATER" else null,
            onToggle = { vm.togglePick(set.id, a) },
        )
    }
}

@Composable
private fun TastePanel(set: GroupSet, state: GroupState, vm: GroupSendViewModel) {
    val c = liner
    var taste by remember(set.id) { mutableStateOf(set.taste) }
    val scoring = "fit:${set.id}" in state.busy
    Panel {
        Spacer(Modifier.height(8.dp))
        SectionLabel("What ${set.forName.ifBlank { "they" }} like")
        OutlinedTextField(
            value = taste,
            onValueChange = { taste = it; vm.setTaste(set.id, it) },
            placeholder = { Text("A few artists or a sentence about their taste") },
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (set.fit.isEmpty()) "Claude can score each shortlisted album for fit." else "Fit scored for ${set.fit.size} albums.",
                style = MaterialTheme.typography.bodySmall,
                color = c.inkSoft,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(10.dp))
            if (scoring) {
                CircularProgressIndicator(Modifier.size(28.dp), color = c.signal, strokeWidth = 3.dp)
            } else {
                PhysicalTextButton(
                    "Score fit",
                    { vm.scoreFit(set.id) },
                    enabled = state.online && taste.isNotBlank() && set.shortlist.isNotEmpty(),
                )
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun PickedRow(index: Int, entry: SetEntry, mark: String?, last: Boolean, onUp: () -> Unit, onDown: () -> Unit, onRemove: () -> Unit) {
    val c = liner
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text((index + 1).toString().padStart(2, '0'), style = mono(14.sp), color = c.signal, modifier = Modifier.width(28.dp))
        CoverArt(entry.album.toAlbum(), Modifier.size(48.dp).clip(RoundedCornerShape(3.dp)), showTitleOnDrawn = false)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(entry.album.title, style = MaterialTheme.typography.titleSmall, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(entry.album.artist, style = MaterialTheme.typography.bodySmall, color = c.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (mark != null) Text("ALREADY GIVEN · $mark", style = mono(10.sp), color = c.signal, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            IconKey(Icons.Rounded.KeyboardArrowUp, "Move ${entry.album.title} up", onClick = onUp, enabled = index > 0)
            IconKey(Icons.Rounded.KeyboardArrowDown, "Move ${entry.album.title} down", onClick = onDown, enabled = !last)
            IconKey(Icons.Rounded.Close, "Take ${entry.album.title} off the list", onClick = onRemove)
        }
    }
}

@Composable
private fun ShortlistRow(album: SetAlbum, picked: Boolean, fit: com.trollman.linernotes.data.Fit?, mark: String?, onToggle: () -> Unit) {
    val c = liner
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Checkbox, onClickLabel = if (picked) "Take off the list" else "Add to the list", onClick = onToggle)
            .semantics { selected = picked }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverArt(album.toAlbum(), Modifier.size(56.dp).clip(RoundedCornerShape(3.dp)), showTitleOnDrawn = false)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(album.title, style = MaterialTheme.typography.titleSmall, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(album.artist, style = MaterialTheme.typography.bodySmall, color = c.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val stats = buildList {
                if (album.plays > 0) add("${album.plays} PLAYS")
                if (album.weeks > 0) add("${album.weeks} WKS")
                if (fit != null) add("FIT ${fit.score}")
            }
            if (stats.isNotEmpty()) Text(stats.joinToString(" · "), style = mono(10.sp), color = if (fit != null && fit.score >= 70) c.signal else c.inkFaint)
            if (mark != null) Text(mark, style = mono(10.sp), color = c.signal, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (fit != null && fit.reason.isNotBlank()) {
                Text(fit.reason, style = MaterialTheme.typography.bodySmall, color = c.inkSoft, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(10.dp))
        PhysicalButton(
            onToggle,
            Modifier.size(42.dp),
            latched = picked,
            shape = RoundedCornerShape(10.dp),
            contentDescription = if (picked) "On the list" else "Add",
        ) {
            if (picked) Icon(Icons.Rounded.Check, contentDescription = null, tint = c.signal) else Led(on = false)
        }
    }
}

// ---- Notes ----------------------------------------------------------------------------------

private fun androidx.compose.foundation.lazy.LazyListScope.notesTab(
    set: GroupSet,
    state: GroupState,
    vm: GroupSendViewModel,
    onOpenNote: (String) -> Unit,
) {
    item(key = "intro") {
        FrameCard(
            label = "Intro",
            text = set.intro,
            rev = set.frameRev,
            setId = set.id,
            busy = "frame:${set.id}:intro" in state.busy,
            online = state.online,
            onChange = { vm.setIntro(set.id, it) },
            onDraft = { vm.draftFrame(set.id, "intro") },
        )
    }
    if (set.entries.isEmpty()) {
        item(key = "none") {
            Text("Pick some albums first, then each one gets a note here.", style = MaterialTheme.typography.bodyMedium, color = liner.inkSoft)
        }
    } else {
        item(key = "all") {
            val empty = set.entries.count { it.state == NoteState.Empty }
            val running = "all:${set.id}" in state.busy
            PhysicalTextButton(
                when {
                    running -> "Drafting… you can keep editing"
                    empty == 0 -> "Every album has a note"
                    else -> "Draft $empty ${if (empty == 1) "note" else "notes"} with Claude"
                },
                { vm.draftAll(set.id) },
                Modifier.fillMaxWidth(),
                accent = empty > 0 && !running,
                enabled = state.online && empty > 0 && !running,
            )
        }
    }
    itemsIndexed(set.entries, key = { _, e -> "note:${e.key}" }) { i, e ->
        NoteRow(i, e, drafting = "note:${set.id}:${e.key}" in state.busy, onOpen = { onOpenNote(e.key) })
    }
    item(key = "closing") {
        FrameCard(
            label = "Closing note",
            text = set.closing,
            rev = set.frameRev,
            setId = set.id,
            busy = "frame:${set.id}:closing" in state.busy,
            online = state.online,
            onChange = { vm.setClosing(set.id, it) },
            onDraft = { vm.draftFrame(set.id, "closing") },
        )
    }
}

@Composable
private fun NoteRow(index: Int, entry: SetEntry, drafting: Boolean, onOpen: () -> Unit) {
    val c = liner
    Panel(Modifier.clickable(role = Role.Button, onClickLabel = "Edit the note", onClick = onOpen)) {
        Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text((index + 1).toString().padStart(2, '0'), style = mono(14.sp), color = c.signal, modifier = Modifier.width(28.dp))
            Text(entry.album.heading, style = MaterialTheme.typography.titleSmall, color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            if (drafting) CircularProgressIndicator(Modifier.size(18.dp), color = c.signal, strokeWidth = 2.dp)
            else Led(on = entry.state != NoteState.Empty)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            when {
                drafting -> "Claude is reading up on it and writing…"
                entry.note.isBlank() -> "No note yet."
                else -> entry.note
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (entry.note.isBlank()) c.inkFaint else c.inkSoft,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        val meta = buildList {
            if (entry.note.isNotBlank()) add("${wordCount(entry.note)} WORDS")
            when (entry.state) {
                NoteState.Drafted -> add("CLAUDE'S DRAFT")
                NoteState.Edited -> add("YOUR EDIT")
                NoteState.Dictated -> add("YOUR WORDS")
                NoteState.Empty -> {}
            }
            if (entry.flags.isNotEmpty()) add("${entry.flags.size} TO CHECK")
        }
        if (meta.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(meta.joinToString(" · "), style = mono(10.sp), color = if (entry.flags.isNotEmpty()) c.signal else c.inkFaint)
        }
        Spacer(Modifier.height(12.dp))
    }
}

/** The intro or the closing note: edit in place, or have Claude draft it from last year's. */
@Composable
private fun FrameCard(
    label: String,
    text: String,
    rev: Int,
    setId: String,
    busy: Boolean,
    online: Boolean,
    onChange: (String) -> Unit,
    onDraft: () -> Unit,
) {
    val c = liner
    var value by remember(setId, rev) { mutableStateOf(text) }
    Panel {
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel(label, Modifier.weight(1f))
            if (busy) CircularProgressIndicator(Modifier.size(20.dp), color = c.signal, strokeWidth = 2.dp)
            else PhysicalButton(onDraft, Modifier.height(36.dp), enabled = online, shape = RoundedCornerShape(10.dp)) {
                Text(if (value.isBlank()) "DRAFT" else "RE-DRAFT", style = mono(11.sp), modifier = Modifier.padding(horizontal = 12.dp))
            }
        }
        OutlinedTextField(
            value = value,
            onValueChange = { value = it; onChange(it) },
            placeholder = { Text("Write it yourself, or let Claude start from last year's") },
            minLines = 3,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
    }
}

// ---- Booklet --------------------------------------------------------------------------------

@Composable
private fun BookletTab(set: GroupSet, state: GroupState, vm: GroupSendViewModel, onOpenLink: (String) -> Unit) {
    val c = liner
    val making = "playlist:${set.id}" in state.busy
    Column {
        Booklet(set)
        Spacer(Modifier.height(18.dp))
        Panel {
            Spacer(Modifier.height(8.dp))
            SectionLabel("Playlist")
            Text(
                "A private playlist in your Spotify with every track of each album, in this order.",
                style = MaterialTheme.typography.bodySmall,
                color = c.inkSoft,
            )
            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth()) {
                if (making) {
                    CircularProgressIndicator(Modifier.align(Alignment.Center), color = c.signal)
                } else {
                    PhysicalTextButton(
                        if (set.playlistUrl == null) "Make the Spotify playlist" else "Make it again",
                        { vm.makePlaylist(set.id) },
                        Modifier.fillMaxWidth(),
                        accent = set.playlistUrl == null,
                        enabled = state.online && set.entries.isNotEmpty(),
                    )
                }
            }
            set.playlistUrl?.let { url ->
                Spacer(Modifier.height(10.dp))
                PhysicalTextButton("Open the playlist", { onOpenLink(url) }, Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(12.dp))
        }
        Spacer(Modifier.height(18.dp))
        SendPanel(set, state, vm)
        Spacer(Modifier.height(18.dp))
        PdfPanel(set, state.writing.senderName)
    }
}
