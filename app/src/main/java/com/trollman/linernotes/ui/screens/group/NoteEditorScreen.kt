package com.trollman.linernotes.ui.screens.group

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trollman.linernotes.GroupSendViewModel
import com.trollman.linernotes.GroupState
import com.trollman.linernotes.data.Album
import com.trollman.linernotes.data.GroupSet
import com.trollman.linernotes.data.NoteState
import com.trollman.linernotes.data.SetEntry
import androidx.core.content.ContextCompat
import com.trollman.linernotes.ui.system.CoverArt
import com.trollman.linernotes.ui.system.Dictation
import com.trollman.linernotes.ui.system.RecordKey
import com.trollman.linernotes.ui.system.Panel
import com.trollman.linernotes.ui.system.PhysicalButton
import com.trollman.linernotes.ui.system.PhysicalTextButton
import com.trollman.linernotes.ui.system.SectionLabel
import com.trollman.linernotes.ui.theme.liner
import com.trollman.linernotes.ui.theme.mono

/**
 * One album's note (SPEC.md, "Composing and editing"): edit it, re-draft it with an instruction,
 * go back to Claude's draft, dictate it, check the flags, and trim the Favorite Tracks.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NoteEditorScreen(
    set: GroupSet,
    entry: SetEntry,
    state: GroupState,
    vm: GroupSendViewModel,
    onBack: () -> Unit,
    onPlay: (Album) -> Unit,
    onOpenLink: (String) -> Unit,
    onGo: (String) -> Unit,
) {
    val c = liner
    val index = set.entries.indexOfFirst { it.key == entry.key }
    val drafting = "note:${set.id}:${entry.key}" in state.busy
    var text by remember(entry.key, entry.rev) { mutableStateOf(entry.note) }
    var instruction by rememberSaveable(entry.key) { mutableStateOf("") }
    val words = wordCount(text)
    val w = state.writing

    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 12.dp)
    ) {
        BackHeader("${(index + 1).toString().padStart(2, '0')} of ${set.entries.size}", onBack, backDescription = "Back to the notes")
        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            CoverArt(entry.album.toAlbum(), Modifier.size(84.dp).clip(RoundedCornerShape(3.dp)))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(entry.album.title, style = MaterialTheme.typography.titleLarge, color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(entry.album.artist, style = MaterialTheme.typography.bodyMedium, color = c.inkSoft)
                if (entry.album.plays > 0) {
                    Text("${entry.album.plays} PLAYS · ${entry.album.weeks} WEEKS", style = mono(10.sp), color = c.inkFaint)
                }
            }
            PhysicalButton(
                { onPlay(entry.album.toAlbum()) },
                Modifier.size(48.dp),
                accent = true,
                shape = RoundedCornerShape(14.dp),
                contentDescription = "Play ${entry.album.title}",
            ) { Icon(Icons.Rounded.PlayArrow, contentDescription = null) }
        }
        Spacer(Modifier.height(18.dp))

        OutlinedTextField(
            value = text,
            onValueChange = { text = it; vm.setNote(set.id, entry.key, it) },
            enabled = !drafting,
            placeholder = { Text(if (drafting) "Claude is reading up on it and writing…" else "Write the note, or have Claude draft it") },
            minLines = 8,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "$words WORDS · AIM ${w.minWords}–${w.maxWords}",
                style = mono(10.sp),
                color = if (words > 0 && (words < w.minWords - 15 || words > w.maxWords + 15)) c.signal else c.inkFaint,
                modifier = Modifier.weight(1f),
            )
            Text(
                when (entry.state) {
                    NoteState.Drafted -> "CLAUDE'S DRAFT"
                    NoteState.Edited -> "YOUR EDIT"
                    NoteState.Dictated -> "YOUR WORDS, WRITTEN UP"
                    NoteState.Empty -> ""
                },
                style = mono(10.sp),
                color = c.inkFaint,
            )
        }
        Spacer(Modifier.height(14.dp))

        SpeakPanel(set, entry, state, vm, drafting, hasNote = text.isNotBlank())
        Spacer(Modifier.height(14.dp))

        // Drafting and re-drafting.
        Panel {
            Spacer(Modifier.height(8.dp))
            SectionLabel(if (entry.aiDraft.isBlank()) "Draft with Claude" else "Re-draft")
            OutlinedTextField(
                value = instruction,
                onValueChange = { instruction = it },
                placeholder = { Text("Optional: \"shorter\", \"more about the lyrics\"") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (drafting) {
                    CircularProgressIndicator(Modifier.size(28.dp), color = c.signal, strokeWidth = 3.dp)
                    Text("This takes a minute or two.", style = MaterialTheme.typography.bodySmall, color = c.inkSoft)
                } else {
                    PhysicalTextButton(
                        if (entry.aiDraft.isBlank() && text.isBlank()) "Draft it" else "Re-draft",
                        { vm.draftNote(set.id, entry.key, instruction.ifBlank { if (text.isBlank()) null else "" }) },
                        Modifier.weight(1f),
                        accent = true,
                        enabled = state.online,
                    )
                    if (entry.aiDraft.isNotBlank() && entry.aiDraft != text) {
                        PhysicalTextButton("Claude's draft", { vm.restoreDraft(set.id, entry.key) }, Modifier.weight(1f))
                    }
                }
            }
            if (!state.online) {
                Spacer(Modifier.height(8.dp))
                Text("Drafting needs the backend, which this build doesn't have yet.", style = MaterialTheme.typography.bodySmall, color = c.inkFaint)
            }
            Spacer(Modifier.height(12.dp))
        }

        if (entry.flags.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            SectionLabel("Worth checking")
            entry.flags.forEach { f ->
                Row(Modifier.padding(vertical = 4.dp)) {
                    Text("!", style = mono(12.sp), color = c.signal, modifier = Modifier.width(18.dp))
                    Text(f, style = MaterialTheme.typography.bodySmall, color = c.ink)
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        SectionLabel("Favorite tracks")
        if (entry.favoriteTracks.isEmpty()) {
            Text("Filled in from your most-played tracks on it, once Last.fm has them.", style = MaterialTheme.typography.bodySmall, color = c.inkFaint)
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                entry.favoriteTracks.forEach { t ->
                    InputChip(
                        selected = false,
                        onClick = { vm.setFavoriteTracks(set.id, entry.key, entry.favoriteTracks - t) },
                        label = { Text(t) },
                        trailingIcon = { Icon(Icons.Rounded.Close, contentDescription = "Remove $t", modifier = Modifier.size(16.dp)) },
                    )
                }
            }
        }

        if (entry.sources.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            SectionLabel("Sources")
            entry.sources.forEach { s ->
                Text(
                    s.title,
                    style = MaterialTheme.typography.bodySmall,
                    color = c.inkSoft,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Button, onClickLabel = "Open") { onOpenLink(s.url) }
                        .padding(vertical = 6.dp),
                )
            }
        }

        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val prev = set.entries.getOrNull(index - 1)
            val next = set.entries.getOrNull(index + 1)
            PhysicalTextButton("Previous", { prev?.let { onGo(it.key) } }, Modifier.weight(1f), enabled = prev != null)
            PhysicalTextButton("Next", { next?.let { onGo(it.key) } }, Modifier.weight(1f), enabled = next != null)
        }
        Spacer(Modifier.height(40.dp))
    }
}

/**
 * Speaking the note (SPEC.md, "Dictating a note"): hold the record key and talk, or tap to start
 * and tap to stop. Claude writes it up in the sender's own words; what they said is kept below.
 */
@Composable
private fun SpeakPanel(set: GroupSet, entry: SetEntry, state: GroupState, vm: GroupSendViewModel, drafting: Boolean, hasNote: Boolean) {
    val c = liner
    val context = LocalContext.current
    var add by remember(entry.key) { mutableStateOf(hasNote) }
    var problem by remember(entry.key) { mutableStateOf<String?>(null) }
    var showSaid by remember(entry.key) { mutableStateOf(false) }
    val addNow by androidx.compose.runtime.rememberUpdatedState(add && hasNote)
    val dictation = remember(entry.key) {
        Dictation(
            context.applicationContext,
            onDone = { said -> problem = null; vm.dictate(set.id, entry.key, said, addNow) },
            onProblem = { problem = it },
        )
    }
    dictation.hints = listOf(entry.album.artist, entry.album.title) + entry.favoriteTracks
    DisposableEffect(dictation) { onDispose { dictation.release() } }
    val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        problem = if (granted) "Ready. Hold the key and talk." else "Liner Notes needs the microphone to take a spoken note."
    }
    fun begin(): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            mic.launch(Manifest.permission.RECORD_AUDIO)
            return false
        }
        problem = null
        dictation.start()
        return dictation.listening
    }

    Panel {
        Spacer(Modifier.height(8.dp))
        SectionLabel("Say it")
        Row(verticalAlignment = Alignment.CenterVertically) {
            RecordKey(
                listening = dictation.listening,
                level = dictation.level,
                onStart = ::begin,
                onStop = { dictation.stop() },
                enabled = !drafting && state.online,
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                when {
                    dictation.listening -> {
                        Text("LISTENING", style = mono(10.sp), color = c.signal)
                        Text(
                            dictation.heard.ifBlank { "Go ahead." },
                            style = MaterialTheme.typography.bodyMedium,
                            color = c.ink,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    drafting && entry.transcripts.isNotEmpty() ->
                        Text("Writing up what you said…", style = MaterialTheme.typography.bodyMedium, color = c.inkSoft)
                    else -> Text(
                        problem ?: "Hold to talk, or tap to start and tap again to stop. Claude tidies it into the note, in your words.",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (problem != null) c.ink else c.inkSoft,
                    )
                }
            }
        }
        if (hasNote) {
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = add, onClick = { add = true }, label = { Text("Add to the note") }, enabled = !dictation.listening)
                FilterChip(selected = !add, onClick = { add = false }, label = { Text("Replace it") }, enabled = !dictation.listening)
            }
        }
        if (!state.online) {
            Spacer(Modifier.height(8.dp))
            Text("Writing it up needs the backend, which this build doesn't have yet.", style = MaterialTheme.typography.bodySmall, color = c.inkFaint)
        }
        if (entry.state == NoteState.Dictated && entry.fixes.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text("CORRECTED", style = mono(10.sp), color = c.inkFaint)
            entry.fixes.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = c.inkSoft) }
        }
        val said = entry.transcripts.lastOrNull()
        if (said != null) {
            Spacer(Modifier.height(6.dp))
            TextButton(onClick = { showSaid = !showSaid }) {
                Text(if (showSaid) "Hide what you said" else "What you said, word for word")
            }
            if (showSaid) {
                Text(said, style = MaterialTheme.typography.bodySmall, color = c.inkSoft)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PhysicalTextButton("Use as said", { vm.useTranscript(set.id, entry.key, said) }, Modifier.weight(1f), enabled = !drafting)
                    PhysicalTextButton("Write it up again", { vm.dictate(set.id, entry.key, said, add = false) }, Modifier.weight(1f), enabled = !drafting && state.online)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}
