package com.trollman.linernotes.ui.screens.group

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import com.trollman.linernotes.data.History
import com.trollman.linernotes.data.PastList
import com.trollman.linernotes.ui.theme.mono
import java.time.LocalDate
import androidx.compose.ui.unit.dp
import com.trollman.linernotes.data.Writing
import com.trollman.linernotes.ui.system.Panel
import com.trollman.linernotes.ui.system.PhysicalTextButton
import com.trollman.linernotes.ui.system.SectionLabel
import com.trollman.linernotes.ui.theme.liner

/**
 * The sender's writing settings (SPEC.md, "Template settings"): past write-ups as style examples,
 * tone notes, target length, last year's intro and closing as starting points, earlier years'
 * lists (so albums already given are marked), and albums saved for later.
 */
@Composable
fun WritingScreen(writing: Writing, onChange: (Writing) -> Unit, onBack: () -> Unit) {
    val c = liner
    // Edited locally and saved on every change, so typing never waits on storage.
    var w by remember { mutableStateOf(writing) }
    fun update(next: Writing) { w = next; onChange(next) }

    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 12.dp)
    ) {
        BackHeader("Your writing", onBack, backDescription = "Back to group send")
        Spacer(Modifier.height(18.dp))
        Text(
            "Claude drafts each note in your voice from what you put here. Change it any time and re-draft.",
            style = MaterialTheme.typography.bodyMedium,
            color = c.inkSoft,
        )
        Spacer(Modifier.height(16.dp))
        Panel {
            Spacer(Modifier.height(8.dp))
            SectionLabel("You")
            OutlinedTextField(
                value = w.senderName,
                onValueChange = { update(w.copy(senderName = it)) },
                label = { Text("Your name, as you sign notes") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = w.toneNotes,
                onValueChange = { update(w.copy(toneNotes = it)) },
                label = { Text("Tone notes") },
                placeholder = { Text("Warm, candid about my biases, light humour, no superlative pile-ups") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                WordsField("Shortest", w.minWords, Modifier.weight(1f)) { update(w.copy(minWords = it)) }
                Spacer(Modifier.width(10.dp))
                WordsField("Longest", w.maxWords, Modifier.weight(1f)) { update(w.copy(maxWords = it)) }
            }
            Spacer(Modifier.height(12.dp))
        }

        Spacer(Modifier.height(16.dp))
        SectionLabel("Style examples · ${w.styleExamples.size} of 3 to 5")
        Text(
            "Paste past write-ups, one per box. Claude copies the voice, not the facts.",
            style = MaterialTheme.typography.bodySmall,
            color = c.inkSoft,
        )
        Spacer(Modifier.height(8.dp))
        w.styleExamples.forEachIndexed { i, example ->
            OutlinedTextField(
                value = example,
                onValueChange = { v -> update(w.copy(styleExamples = w.styleExamples.toMutableList().also { it[i] = v })) },
                label = { Text("Example ${i + 1}") },
                minLines = 4,
                trailingIcon = {
                    IconKey(Icons.Rounded.Close, "Remove example ${i + 1}", onClick = {
                        update(w.copy(styleExamples = w.styleExamples.toMutableList().also { it.removeAt(i) }))
                    })
                },
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            )
        }
        if (w.styleExamples.size < 6) {
            PhysicalTextButton("Add an example", { update(w.copy(styleExamples = w.styleExamples + "")) }, Modifier.fillMaxWidth())
        }

        Spacer(Modifier.height(16.dp))
        SectionLabel("Last time's intro and closing")
        Text(
            "Claude starts this year's from these.",
            style = MaterialTheme.typography.bodySmall,
            color = c.inkSoft,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = w.lastIntro,
            onValueChange = { update(w.copy(lastIntro = it)) },
            label = { Text("Intro") },
            minLines = 3,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = w.lastClosing,
            onValueChange = { update(w.copy(lastClosing = it)) },
            label = { Text("Closing note") },
            minLines = 3,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(16.dp))
        SectionLabel("Earlier lists")
        Text(
            "Paste an earlier year's list, one album to a line, like \"Artist — Album\". Albums on it are marked as already given, and notes can mention when an artist was on an earlier list.",
            style = MaterialTheme.typography.bodySmall,
            color = c.inkSoft,
        )
        Spacer(Modifier.height(8.dp))
        w.pastLists.forEach { list ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "${list.year}${if (list.forName.isNotBlank()) " · for ${list.forName}" else ""}",
                        style = MaterialTheme.typography.titleSmall,
                        color = c.ink,
                    )
                    Text(
                        list.albums.joinToString(", ") { it.title },
                        style = MaterialTheme.typography.bodySmall,
                        color = c.inkSoft,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(10.dp))
                IconKey(Icons.Rounded.Close, "Remove the ${list.year} list", onClick = { update(w.copy(pastLists = w.pastLists - list)) })
            }
        }
        var year by remember { mutableStateOf((LocalDate.now().year - 1).toString()) }
        var forName by remember { mutableStateOf("") }
        var pasted by remember { mutableStateOf("") }
        val parsed = remember(pasted) { History.parseList(pasted) }
        Panel {
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = year,
                    onValueChange = { year = it.filter(Char::isDigit).take(4) },
                    label = { Text("Year") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(0.4f),
                )
                OutlinedTextField(
                    value = forName,
                    onValueChange = { forName = it },
                    label = { Text("For (optional)") },
                    singleLine = true,
                    modifier = Modifier.weight(0.6f),
                )
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = pasted,
                onValueChange = { pasted = it },
                label = { Text("The list") },
                minLines = 4,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                if (pasted.isBlank()) "" else "READ ${parsed.size} ALBUMS",
                style = mono(10.sp),
                color = c.inkFaint,
            )
            Spacer(Modifier.height(6.dp))
            val y = year.toIntOrNull()
            PhysicalTextButton(
                "Add this list",
                {
                    if (y != null) {
                        val others = w.pastLists.filterNot {
                            it.year == y && (History.sameName(it.forName, forName) || (it.forName.isBlank() && forName.isBlank()))
                        }
                        update(w.copy(pastLists = (others + PastList(y, forName.trim(), parsed)).sortedByDescending { it.year }))
                        pasted = ""
                    }
                },
                Modifier.fillMaxWidth(),
                enabled = y != null && y in 1990..2100 && parsed.isNotEmpty(),
            )
            Spacer(Modifier.height(12.dp))
        }

        if (w.saved.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            SectionLabel("Saved for later · ${w.saved.size}")
            Text(
                "Held back from monthly suggestions and put at the top of group-send shortlists.",
                style = MaterialTheme.typography.bodySmall,
                color = c.inkSoft,
            )
            w.saved.forEach { saved ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(saved.album.title, style = MaterialTheme.typography.titleSmall, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            saved.album.artist + if (saved.forName.isNotBlank()) " · for ${saved.forName}" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = c.inkSoft,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconKey(Icons.Rounded.Close, "Stop saving ${saved.album.title}", onClick = { update(w.copy(saved = w.saved - saved)) })
                }
            }
        }
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun WordsField(label: String, value: Int, modifier: Modifier, onChange: (Int) -> Unit) {
    var text by remember { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { v ->
            text = v.filter(Char::isDigit).take(3)
            text.toIntOrNull()?.takeIf { it in 20..400 }?.let(onChange)
        },
        label = { Text("$label (words)") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}
