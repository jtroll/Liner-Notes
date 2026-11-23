package com.trollman.linernotes.ui.screens.pairs

import android.content.Intent
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trollman.linernotes.GroupSendViewModel
import com.trollman.linernotes.GroupState
import com.trollman.linernotes.data.Monthly
import com.trollman.linernotes.data.PairInfo
import com.trollman.linernotes.data.Received
import com.trollman.linernotes.ui.screens.group.BackHeader
import com.trollman.linernotes.ui.screens.group.Busy
import com.trollman.linernotes.ui.screens.group.IconKey
import com.trollman.linernotes.ui.system.CoverArt
import com.trollman.linernotes.ui.system.DisplayWindow
import com.trollman.linernotes.ui.system.DotMatrixText
import com.trollman.linernotes.ui.system.HardwareSwitch
import com.trollman.linernotes.ui.system.Led
import com.trollman.linernotes.ui.system.Panel
import com.trollman.linernotes.ui.system.PhysicalTextButton
import com.trollman.linernotes.ui.system.SectionLabel
import com.trollman.linernotes.ui.theme.liner
import com.trollman.linernotes.ui.theme.mono
import com.trollman.linernotes.whenText

/** Where this month's sends stand in a pair, both ways. */
private data class MonthStatus(val mine: String, val mineOn: Boolean, val theirs: String, val theirsOn: Boolean)

private fun status(state: GroupState, pair: PairInfo): MonthStatus {
    val month = Monthly.monthKey()
    val sent = state.sentMonthly(pair.id, month)
    val draft = state.monthlyFor(pair.id, month)
    val arrived = state.inbox.any { it.pairId == pair.id && it.isMonthly && !it.test && it.set.month == month }
    return MonthStatus(
        mine = when {
            sent != null -> "YOUR PICK · SENT"
            !pair.monthlyOn -> "MONTHLY · OFF"
            draft?.entries?.isNotEmpty() == true -> "YOUR PICK · DRAFT"
            else -> "YOUR PICK · NOT YET"
        },
        mineOn = sent != null,
        theirs = if (arrived) "THEIR PICK · ARRIVED" else "THEIR PICK · NOT YET",
        theirsOn = arrived,
    )
}

/**
 * Home for pairs (SPEC.md, "Key screens: Home"): each pair with this month's send status for both
 * people, and an invite link to start a new pair.
 */
@Composable
fun PairsScreen(
    state: GroupState,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    vm: GroupSendViewModel,
) {
    val c = liner
    val insets = WindowInsets.safeDrawing.asPaddingValues()
    val month = Monthly.monthKey()
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 18.dp, end = 18.dp,
            top = insets.calculateTopPadding() + 12.dp,
            bottom = insets.calculateBottomPadding() + 120.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { BackHeader("Monthly send", onBack, backDescription = "Back to your records") }
        item {
            DisplayWindow {
                Text("ONE ALBUM EACH WAY", style = mono(11.sp), color = c.onDisplay.copy(alpha = 0.6f))
                DotMatrixText(Monthly.monthName(month).take(3).uppercase(), size = 56.sp, color = c.onDisplay, maxLines = 1)
                Text("FROM WHAT YOU LOVED IN ${Monthly.monthName(Monthly.listeningMonth(month)).uppercase()}", style = mono(11.sp), color = c.signal)
            }
        }
        if (!state.pairsReady) {
            item {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = c.signal) }
            }
        } else if (state.pairs.isEmpty()) {
            item {
                Text(
                    if (state.online) "No pairs yet. A pair forms when someone joins with your invite link, or you join with theirs."
                    else "This build has no backend yet, so there are no pairs (docs/SETUP.md).",
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.inkSoft,
                )
            }
        }
        items(state.pairs, key = { it.id }) { pair -> PairRow(pair, status(state, pair), onClick = { onOpen(pair.id) }) }
        item { InvitePanel(state, vm) }
    }
}

@Composable
private fun PairRow(pair: PairInfo, s: MonthStatus, onClick: () -> Unit) {
    val c = liner
    Panel(Modifier.clickable(role = Role.Button, onClick = onClick)) {
        Spacer(Modifier.height(12.dp))
        Text(pair.label, style = MaterialTheme.typography.titleMedium, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(8.dp))
        StatusLine(s.mine, s.mineOn)
        Spacer(Modifier.height(4.dp))
        StatusLine(s.theirs, s.theirsOn)
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun StatusLine(text: String, on: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Led(on = on)
        Spacer(Modifier.width(10.dp))
        Text(text, style = mono(11.sp), color = liner.ink)
    }
}

@Composable
private fun InvitePanel(state: GroupState, vm: GroupSendViewModel) {
    val c = liner
    val context = LocalContext.current
    var name by rememberSaveable { mutableStateOf("") }
    Panel {
        Spacer(Modifier.height(8.dp))
        SectionLabel("Start a new pair")
        Text(
            "Make an invite link for someone. When they open it in Liner Notes, you're paired and can send each other an album every month.",
            style = MaterialTheme.typography.bodySmall,
            color = c.inkSoft,
        )
        Spacer(Modifier.height(8.dp))
        val invite = state.pairInvite
        if (invite == null) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                placeholder = { Text("Their name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            Busy("pairInvite" in state.busy) {
                PhysicalTextButton("Make the invite link", { vm.makePairInvite(name) }, Modifier.fillMaxWidth(), enabled = state.online)
            }
        } else {
            SelectionContainer { Text(invite.url, style = mono(10.sp), color = c.inkSoft) }
            Spacer(Modifier.height(10.dp))
            PhysicalTextButton(
                "Share the invite",
                {
                    val text = "Let's swap an album every month in Liner Notes. Install the app, then open this link: ${invite.url}"
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                    context.startActivity(Intent.createChooser(send, "Share the invite"))
                },
                Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(12.dp))
    }
}

/**
 * One pair (SPEC.md, "Pair view" and "Pair settings"): this month's picks both ways, the way into
 * your pick, every send both ways newest first, and the pair's settings.
 */
@Composable
fun PairScreen(
    pair: PairInfo,
    state: GroupState,
    vm: GroupSendViewModel,
    onBack: () -> Unit,
    onCompose: () -> Unit,
    onOpenSend: (Received) -> Unit,
) {
    val c = liner
    val insets = WindowInsets.safeDrawing.asPaddingValues()
    val month = Monthly.monthKey()
    val s = status(state, pair)
    val sent = state.sentMonthly(pair.id, month)
    val draft = state.monthlyFor(pair.id, month)
    val paused = Monthly.pausedForDecember(month, pair, state.sendsById.values)
    val history = state.history(pair.id)
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 18.dp, end = 18.dp,
            top = insets.calculateTopPadding() + 12.dp,
            bottom = insets.calculateBottomPadding() + 120.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { BackHeader(pair.label, onBack, backDescription = "Back to your pairs") }
        item {
            DisplayWindow {
                Text(Monthly.monthName(month).uppercase(), style = mono(11.sp), color = c.onDisplay.copy(alpha = 0.6f))
                DotMatrixText(history.size.toString().padStart(2, '0'), size = 56.sp, color = c.onDisplay, maxLines = 1)
                Text("ALBUMS SWAPPED SO FAR", style = mono(11.sp), color = c.signal)
            }
        }
        item {
            Panel {
                Spacer(Modifier.height(12.dp))
                StatusLine(s.mine, s.mineOn)
                Spacer(Modifier.height(4.dp))
                StatusLine(s.theirs, s.theirsOn)
                Spacer(Modifier.height(12.dp))
                when {
                    sent != null -> Text(
                        "You sent ${sent.set?.entries?.firstOrNull()?.album?.title ?: "your pick"} on ${whenText(sent.deliveredAt ?: sent.deliverAt)}.",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.inkSoft,
                    )
                    paused -> Text(
                        "Paused for December, because your year-end set goes to ${pair.who} this month. You can still make a pick.",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.inkSoft,
                    )
                }
                if (sent == null) {
                    Spacer(Modifier.height(10.dp))
                    PhysicalTextButton(
                        if (draft?.entries?.isNotEmpty() == true) "Open your ${Monthly.monthName(month)} pick" else "Make your ${Monthly.monthName(month)} pick",
                        onCompose,
                        Modifier.fillMaxWidth(),
                        accent = !paused,
                        enabled = state.online,
                    )
                }
                Spacer(Modifier.height(12.dp))
            }
        }
        if (history.isNotEmpty()) item { SectionLabel("Sends both ways") }
        items(history, key = { it.id }) { r -> HistoryRow(r, pair, onClick = { onOpenSend(r) }) }
        item { PairSettings(pair, vm) }
    }
}

@Composable
private fun HistoryRow(r: Received, pair: PairInfo, onClick: () -> Unit) {
    val c = liner
    val first = r.set.entries.firstOrNull()
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (first != null) {
            CoverArt(first.album.toAlbum(), Modifier.size(56.dp).clip(RoundedCornerShape(3.dp)), showTitleOnDrawn = false)
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                if (r.isMonthly && first != null) first.album.title else r.set.title,
                style = MaterialTheme.typography.titleSmall,
                color = c.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOf(
                    if (r.mine) "TO ${pair.who.uppercase()}" else "FROM ${pair.who.uppercase()}",
                    whenText(r.deliveredAt).uppercase(),
                ).joinToString(" · "),
                style = mono(10.sp),
                color = c.inkFaint,
            )
            val reacted = r.reactions.values.mapNotNull { it.label.ifBlank { null } }
            if (reacted.isNotEmpty()) {
                Text(
                    if (r.isMonthly) reacted.first() else "${reacted.size} reaction${if (reacted.size == 1) "" else "s"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.signal,
                )
            } else if (r.isMonthly && first != null) {
                Text(first.album.artist, style = MaterialTheme.typography.bodySmall, color = c.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun PairSettings(pair: PairInfo, vm: GroupSendViewModel) {
    val c = liner
    Panel {
        Spacer(Modifier.height(8.dp))
        SectionLabel("Pair settings")
        HardwareSwitch(
            checked = pair.monthlyOn,
            onToggle = { vm.updatePair(pair.id, mapOf("monthlyOn" to it)) },
            label = "Monthly send",
            description = "An album each way, every month, with a reminder on the draft day.",
        )
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Draft day", style = MaterialTheme.typography.titleSmall, color = c.ink)
                Text("The day each month your pick is ready.", style = MaterialTheme.typography.bodySmall, color = c.inkSoft)
            }
            IconKey(Icons.Rounded.Remove, "Earlier in the month", onClick = {
                vm.updatePair(pair.id, mapOf("draftDay" to (pair.draftDay - 1).coerceIn(1, 28)))
            }, enabled = pair.draftDay > 1)
            Text(ordinal(pair.draftDay), style = mono(13.sp), color = c.ink, modifier = Modifier.width(52.dp).padding(start = 12.dp))
            IconKey(Icons.Rounded.Add, "Later in the month", onClick = {
                vm.updatePair(pair.id, mapOf("draftDay" to (pair.draftDay + 1).coerceIn(1, 28)))
            }, enabled = pair.draftDay < 28)
        }
        Spacer(Modifier.height(10.dp))
        HardwareSwitch(
            checked = pair.decemberPause,
            onToggle = { vm.updatePair(pair.id, mapOf("decemberPause" to it)) },
            label = "December pause",
            description = "No monthly reminders in a December when a year-end set is going to the same person.",
        )
        Spacer(Modifier.height(12.dp))
    }
}

private fun ordinal(n: Int): String = n.toString() + when {
    n % 100 in 11..13 -> "th"
    n % 10 == 1 -> "st"
    n % 10 == 2 -> "nd"
    n % 10 == 3 -> "rd"
    else -> "th"
}
