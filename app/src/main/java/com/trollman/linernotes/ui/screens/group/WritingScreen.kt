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
import androidx.compose.ui.unit.dp
import com.trollman.linernotes.data.Writing
import com.trollman.linernotes.ui.system.Panel
import com.trollman.linernotes.ui.system.PhysicalTextButton
import com.trollman.linernotes.ui.system.SectionLabel
import com.trollman.linernotes.ui.theme.liner

/**
 * The sender's writing settings (SPEC.md, "Template settings"): past write-ups as style examples,
 * tone notes, target length, and last year's intro and closing as starting points.
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
