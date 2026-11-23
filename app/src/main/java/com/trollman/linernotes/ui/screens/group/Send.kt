package com.trollman.linernotes.ui.screens.group

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trollman.linernotes.GroupSendViewModel
import com.trollman.linernotes.GroupState
import com.trollman.linernotes.data.GroupSet
import com.trollman.linernotes.data.changedSinceScheduled
import com.trollman.linernotes.print.BookletPdf
import com.trollman.linernotes.ui.system.Led
import com.trollman.linernotes.ui.system.Panel
import com.trollman.linernotes.ui.system.PhysicalTextButton
import com.trollman.linernotes.ui.system.SectionLabel
import com.trollman.linernotes.ui.theme.liner
import com.trollman.linernotes.ui.theme.mono
import com.trollman.linernotes.whenText
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.launch

private val dayFormat = DateTimeFormatter.ofPattern("EEE MMM d", Locale.getDefault())

private fun hourText(hour: Int): String {
    val h = if (hour % 12 == 0) 12 else hour % 12
    return "$h:00 ${if (hour < 12) "AM" else "PM"}"
}

/**
 * Sending the set (SPEC.md, "Schedule and send"): the invite link it goes out on, the delivery
 * date and time, and where it stands. The set can be scheduled before the recipient has the app;
 * it reaches them when they join with the invite, or at the delivery time if they joined first.
 */
@Composable
internal fun SendPanel(set: GroupSet, state: GroupState, vm: GroupSendViewModel) {
    val c = liner
    val context = LocalContext.current
    val info = state.sends[set.id]
    val name = set.forName.ifBlank { "them" }
    val zone = ZoneId.systemDefault()

    val startDay = (info?.deliverAt ?: set.deliverAt)?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
        ?: set.deliverOn?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        ?: LocalDate.now().plusDays(1)
    val startHour = (info?.deliverAt ?: set.deliverAt)?.let { Instant.ofEpochMilli(it).atZone(zone).hour } ?: 8
    var day by rememberSaveable(set.id, info?.deliverAt) { mutableStateOf(startDay.toString()) }
    var hour by rememberSaveable(set.id, info?.deliverAt) { mutableStateOf(startHour) }
    var picking by rememberSaveable { mutableStateOf(false) }
    val picked = LocalDate.parse(day).atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()
    val now = System.currentTimeMillis()

    Panel {
        Spacer(Modifier.height(8.dp))
        SectionLabel("Send it to $name")

        // Where it stands.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Led(on = info != null)
            Spacer(Modifier.width(10.dp))
            Text(
                when {
                    info == null -> "NOT SCHEDULED"
                    info.delivered -> "DELIVERED · ${whenText(info.deliveredAt ?: info.deliverAt).uppercase()}"
                    else -> "SCHEDULED · ${whenText(info.deliverAt).uppercase()}"
                },
                style = mono(11.sp),
                color = c.ink,
            )
        }
        if (info != null && !info.delivered) {
            Spacer(Modifier.height(4.dp))
            Text(
                if (info.recipientJoined) "$name has joined, so it arrives right on time."
                else "It reaches $name when they join with your invite. If that's after the delivery time, it arrives as they join.",
                style = MaterialTheme.typography.bodySmall,
                color = c.inkSoft,
            )
            if (set.changedSinceScheduled) {
                Spacer(Modifier.height(4.dp))
                Text("You've changed the set since. Update it so $name gets this version.", style = MaterialTheme.typography.bodySmall, color = c.signal)
            }
        }
        val missingNotes = set.entries.count { it.note.isBlank() }
        if (missingNotes > 0 && info?.delivered != true) {
            Spacer(Modifier.height(4.dp))
            Text(
                "$missingNotes album${if (missingNotes == 1) " doesn't" else "s don't"} have a note yet.",
                style = MaterialTheme.typography.bodySmall,
                color = c.inkSoft,
            )
        }

        // 1. The invite.
        Spacer(Modifier.height(14.dp))
        Text("1 · INVITE", style = mono(10.sp), color = c.inkFaint)
        Spacer(Modifier.height(6.dp))
        val url = set.inviteUrl
        if (url == null) {
            Text(
                "A link for $name. Opening it in Liner Notes pairs you, and the set is waiting in their inbox. Share it whenever you like, on the day itself if it's a present.",
                style = MaterialTheme.typography.bodySmall,
                color = c.inkSoft,
            )
            Spacer(Modifier.height(10.dp))
            Busy("invite:${set.id}" in state.busy) {
                PhysicalTextButton("Make the invite link", { vm.makeInvite(set.id) }, Modifier.fillMaxWidth(), enabled = state.online)
            }
        } else {
            SelectionContainer { Text(url, style = mono(10.sp), color = c.inkSoft) }
            Spacer(Modifier.height(10.dp))
            PhysicalTextButton(
                "Share the invite",
                {
                    val text = "I made you something in Liner Notes. Install the app, then open this link: $url"
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                    context.startActivity(Intent.createChooser(send, "Share the invite"))
                },
                Modifier.fillMaxWidth(),
            )
        }

        // 2. When.
        if (info?.delivered != true) {
            Spacer(Modifier.height(16.dp))
            Text("2 · DELIVERY", style = mono(10.sp), color = c.inkFaint)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PhysicalTextButton(LocalDate.parse(day).format(dayFormat), { picking = true }, Modifier.weight(1f))
                IconKey(Icons.Rounded.Remove, "An hour earlier", onClick = { hour = (hour + 23) % 24 })
                Text(hourText(hour), style = mono(13.sp), color = c.ink, modifier = Modifier.width(72.dp))
                IconKey(Icons.Rounded.Add, "An hour later", onClick = { hour = (hour + 1) % 24 })
            }
            Spacer(Modifier.height(12.dp))
            val label = when {
                picked <= now -> if (info == null) "Send it now" else "Update and send now"
                info == null -> "Schedule for ${whenText(picked)}"
                else -> "Update and schedule for ${whenText(picked)}"
            }
            val needed = info == null || set.changedSinceScheduled || info.deliverAt != picked
            Busy("send:${set.id}" in state.busy) {
                PhysicalTextButton(
                    label,
                    { vm.schedule(set.id, picked) },
                    Modifier.fillMaxWidth(),
                    accent = needed,
                    enabled = state.online && set.inviteToken != null && set.entries.isNotEmpty(),
                )
            }
            if (info != null) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    TextButton(onClick = { vm.cancelSend(set.id) }) { Text("Cancel the send") }
                }
            }
        }

        // A copy for oneself.
        Spacer(Modifier.height(12.dp))
        Busy("test:${set.id}" in state.busy) {
            PhysicalTextButton(
                "Send a test copy to my inbox",
                { vm.sendTest(set.id) },
                Modifier.fillMaxWidth(),
                enabled = state.online && set.entries.isNotEmpty(),
            )
        }
        Spacer(Modifier.height(12.dp))
    }

    if (picking) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = LocalDate.parse(day).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { day = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString() }
                    picking = false
                }) { Text("Set") }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text("Cancel") } },
        ) {
            DatePicker(state = pickerState)
        }
    }
}

/** The printable booklet: reading order, or ready to print double-sided and fold. */
@Composable
fun PdfPanel(set: GroupSet, fromName: String) {
    val c = liner
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var making by rememberSaveable { mutableStateOf<String?>(null) }
    var failed by rememberSaveable { mutableStateOf(false) }

    fun make(format: BookletPdf.Format) {
        making = format.name
        failed = false
        scope.launch {
            try {
                val file = BookletPdf.write(context, set, fromName, format)
                BookletPdf.share(context, file)
            } catch (e: Exception) {
                failed = true
            } finally {
                making = null
            }
        }
    }

    Panel {
        Spacer(Modifier.height(8.dp))
        SectionLabel("Printable booklet")
        Text(
            "To fold: print double-sided, flipping on the short edge, then fold the stack in half. Page by page: read it on screen or print one page a sheet.",
            style = MaterialTheme.typography.bodySmall,
            color = c.inkSoft,
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Busy(making == BookletPdf.Format.Fold.name, Modifier.weight(1f)) {
                PhysicalTextButton("PDF to fold", { make(BookletPdf.Format.Fold) }, Modifier.fillMaxWidth(), enabled = making == null && set.entries.isNotEmpty())
            }
            Busy(making == BookletPdf.Format.Pages.name, Modifier.weight(1f)) {
                PhysicalTextButton("Page by page", { make(BookletPdf.Format.Pages) }, Modifier.fillMaxWidth(), enabled = making == null && set.entries.isNotEmpty())
            }
        }
        if (failed) {
            Spacer(Modifier.height(6.dp))
            Text("The PDF didn't work. Try again.", style = MaterialTheme.typography.bodySmall, color = c.signal)
        }
        Spacer(Modifier.height(12.dp))
    }
}

/** Shows a spinner in place of [content] while [busy]. */
@Composable
internal fun Busy(busy: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        if (busy) {
            Column(Modifier.height(52.dp), verticalArrangement = Arrangement.Center) {
                CircularProgressIndicator(Modifier.size(28.dp), color = liner.signal, strokeWidth = 3.dp)
            }
        } else {
            content()
        }
    }
}
