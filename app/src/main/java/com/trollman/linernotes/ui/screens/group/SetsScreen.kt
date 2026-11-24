package com.trollman.linernotes.ui.screens.group

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.trollman.linernotes.data.GroupSet
import com.trollman.linernotes.ui.system.CoverArt
import com.trollman.linernotes.ui.system.DisplayWindow
import com.trollman.linernotes.ui.system.DotMatrixText
import com.trollman.linernotes.ui.system.HardwareSwitch
import com.trollman.linernotes.ui.system.Led
import com.trollman.linernotes.ui.system.Panel
import com.trollman.linernotes.ui.system.PhysicalButton
import com.trollman.linernotes.ui.system.PhysicalTextButton
import com.trollman.linernotes.ui.system.RotaryDial
import com.trollman.linernotes.ui.system.SectionLabel
import com.trollman.linernotes.ui.theme.liner
import com.trollman.linernotes.ui.theme.mono
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Group send's front page: your sets, and a new one. */
@Composable
fun SetsScreen(
    state: GroupState,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    onCreate: (forName: String, taste: String, yearList: Boolean, months: Int, count: Int) -> Unit,
    onWriting: () -> Unit,
) {
    val c = liner
    val insets = WindowInsets.safeDrawing.asPaddingValues()
    var creating by rememberSaveable { mutableStateOf(state.ready && state.groupSets.isEmpty()) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 18.dp, end = 18.dp,
            top = insets.calculateTopPadding() + 12.dp,
            bottom = insets.calculateBottomPadding() + 40.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            BackHeader("Group send", onBack, backDescription = "Back to your records") {
                PhysicalButton(onWriting, Modifier.size(44.dp), shape = RoundedCornerShape(12.dp), contentDescription = "Your writing style") {
                    Icon(Icons.Rounded.EditNote, contentDescription = null)
                }
            }
        }
        item {
            DisplayWindow {
                Text("SETS", style = mono(11.sp), color = c.onDisplay.copy(alpha = 0.6f))
                DotMatrixText(state.groupSets.size.toString().padStart(2, '0'), size = 56.sp, color = c.onDisplay, maxLines = 1)
                Text(
                    "A STACK OF RECORDS, A NOTE ON EACH",
                    style = mono(11.sp),
                    color = c.signal,
                )
            }
        }
        item {
            AnimatedVisibility(visible = !creating) {
                PhysicalTextButton("New set", { creating = true }, Modifier.fillMaxWidth(), accent = true)
            }
            AnimatedVisibility(visible = creating) {
                NewSetPanel(
                    onCancel = { creating = false },
                    onCreate = { name, taste, year, months, count ->
                        creating = false
                        onCreate(name, taste, year, months, count)
                    },
                )
            }
        }
        if (state.writing.styleExamples.isEmpty()) {
            item {
                Panel(Modifier.clickable(role = Role.Button, onClick = onWriting)) {
                    Spacer(Modifier.height(10.dp))
                    Text("Teach it your voice", style = MaterialTheme.typography.titleSmall, color = c.ink)
                    Text(
                        "Paste three to five of your past write-ups and Claude drafts each note the way you write them.",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.inkSoft,
                    )
                    Spacer(Modifier.height(10.dp))
                }
            }
        }
        if (state.groupSets.isNotEmpty()) item { SectionLabel("Your sets") }
        items(state.groupSets, key = { it.id }) { set -> SetRow(set, onClick = { onOpen(set.id) }) }
    }
}

@Composable
private fun SetRow(set: GroupSet, onClick: () -> Unit) {
    val c = liner
    val notes = set.entries.count { it.note.isNotBlank() }
    Panel(Modifier.clickable(role = Role.Button, onClickLabel = "Open", onClick = onClick)) {
        Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            // The first few covers, overlapped like records in a crate.
            Box(Modifier.width(110.dp)) {
                set.entries.take(4).forEachIndexed { i, e ->
                    CoverArt(
                        e.album.toAlbum(),
                        Modifier.padding(start = (i * 22).dp).size(44.dp).clip(RoundedCornerShape(3.dp)),
                        showTitleOnDrawn = false,
                    )
                }
                if (set.entries.isEmpty()) Led(on = false, size = 10.dp, modifier = Modifier.padding(16.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(set.title, style = MaterialTheme.typography.titleSmall, color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    "For ${set.forName.ifBlank { "someone" }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.inkSoft,
                )
                Text(
                    "${set.entries.size}/${set.count} PICKED · $notes NOTES" + (set.deliverOn?.let { " · ${shortDate(it)}" } ?: ""),
                    style = mono(10.sp),
                    color = c.inkFaint,
                )
            }
        }
    }
}

internal fun shortDate(iso: String): String = runCatching {
    LocalDate.parse(iso).format(DateTimeFormatter.ofPattern("MMM d", Locale.getDefault())).uppercase()
}.getOrDefault(iso)

/** Who it's for, which stretch of listening, how many albums. */
@Composable
private fun NewSetPanel(
    onCancel: () -> Unit,
    onCreate: (String, String, Boolean, Int, Int) -> Unit,
) {
    val c = liner
    var name by rememberSaveable { mutableStateOf("") }
    var taste by rememberSaveable { mutableStateOf("") }
    var yearList by rememberSaveable { mutableStateOf(true) }
    var months by remember { mutableIntStateOf(6) }
    var count by remember { mutableIntStateOf(12) }
    val year = LocalDate.now().year

    Panel {
        Spacer(Modifier.height(8.dp))
        SectionLabel("New set")
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Who's it for?") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        HardwareSwitch(
            checked = yearList,
            onToggle = { yearList = it },
            label = "Year-end list",
            description = "January 1 to today, 12 albums, the \"Top Album Discoveries of $year\" booklet, delivered December 25.",
        )
        AnimatedVisibility(visible = !yearList) {
            Column {
                SectionLabel("How far back")
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    RotaryDial(value = months, onValueChange = { months = it })
                }
                SectionLabel("How many albums")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconKey(Icons.Rounded.Remove, "Fewer", onClick = { count = (count - 1).coerceAtLeast(1) })
                    DotMatrixText(
                        count.toString().padStart(2, '0'),
                        size = 40.sp,
                        color = c.ink,
                        maxLines = 1,
                        modifier = Modifier.padding(horizontal = 18.dp),
                    )
                    IconKey(Icons.Rounded.Add, "More", onClick = { count = (count + 1).coerceAtMost(30) })
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = taste,
            onValueChange = { taste = it },
            label = { Text("What do they like?") },
            placeholder = { Text("A few artists or a sentence. Used for the fit scores and the notes.") },
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PhysicalTextButton("Cancel", onCancel, Modifier.weight(1f))
            PhysicalTextButton(
                "Start",
                { onCreate(name, taste, yearList, months, count) },
                Modifier.weight(1f),
                accent = true,
                enabled = name.isNotBlank(),
            )
        }
        Spacer(Modifier.height(12.dp))
    }
}
