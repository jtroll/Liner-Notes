package com.trollman.linernotes.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.core.content.ContextCompat
import com.trollman.linernotes.InvitePrompt
import com.trollman.linernotes.ui.screens.inbox.InboxScreen
import com.trollman.linernotes.ui.screens.inbox.ReceivedScreen
import com.trollman.linernotes.ui.screens.pairs.MonthlyScreen
import com.trollman.linernotes.ui.screens.pairs.PairScreen
import com.trollman.linernotes.ui.screens.pairs.PairsScreen
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.lifecycle.viewmodel.compose.viewModel
import com.trollman.linernotes.AppViewModel
import com.trollman.linernotes.Gate
import com.trollman.linernotes.GroupSendViewModel
import com.trollman.linernotes.ui.screens.group.NoteEditorScreen
import com.trollman.linernotes.ui.screens.group.SetScreen
import com.trollman.linernotes.ui.screens.group.SetsScreen
import com.trollman.linernotes.ui.screens.group.WritingScreen
import com.trollman.linernotes.ui.system.findActivity
import com.trollman.linernotes.data.Album
import com.trollman.linernotes.ui.screens.AlbumScreen
import com.trollman.linernotes.ui.screens.ConnectionsScreen
import com.trollman.linernotes.ui.screens.SettingsScreen
import com.trollman.linernotes.ui.screens.SetupNeededScreen
import com.trollman.linernotes.ui.screens.ShelfScreen
import com.trollman.linernotes.ui.screens.SignInScreen
import com.trollman.linernotes.ui.theme.liner

/** The screens a person moves between once they're in. */
private sealed interface Place {
    data object Shelf : Place
    data class AlbumOpen(val album: Album) : Place
    data object Settings : Place
    data object Sets : Place
    data class SetOpen(val id: String) : Place
    data class Note(val setId: String, val key: String, val monthlyPair: String? = null) : Place
    data object Writing : Place
    data object Inbox : Place
    /** A send in your inbox, or one of your own; [pairId] when opened from a pair's page. */
    data class Received(val id: String, val pairId: String? = null) : Place
    data object Pairs : Place
    data class Pair(val id: String) : Place
    data class Monthly(val setId: String, val pairId: String) : Place
}

/** Where Back goes from each place. */
private fun Place.up(): Place = when (this) {
    is Place.Note -> if (monthlyPair != null) Place.Monthly(setId, monthlyPair) else Place.SetOpen(setId)
    is Place.SetOpen, Place.Writing -> Place.Sets
    is Place.Received -> if (pairId != null) Place.Pair(pairId) else Place.Inbox
    is Place.Monthly -> Place.Pair(pairId)
    is Place.Pair -> Place.Pairs
    else -> Place.Shelf
}

@Composable
fun AppRoot(vm: AppViewModel, onConnectSpotify: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val player by vm.player.collectAsStateWithLifecycle()
    val group: GroupSendViewModel = viewModel()
    val groupState by group.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val openLink: (String) -> Unit = { url -> runCatching { uriHandler.openUri(url) } }
    val snackbar = remember { SnackbarHostState() }
    var place by remember { mutableStateOf<Place>(Place.Shelf) }

    LaunchedEffect(ui.message) {
        ui.message?.let {
            snackbar.showSnackbar(it)
            vm.consumeMessage()
        }
    }
    LaunchedEffect(groupState.message) {
        groupState.message?.let {
            snackbar.showSnackbar(it)
            group.consumeMessage()
        }
    }
    LaunchedEffect(ui.account?.uid, ui.profile.lastfmUsername, ui.gate) {
        if (ui.gate == Gate.Shelf || ui.browsingDemo) group.start(ui.account?.uid, ui.profile.lastfmUsername)
    }

    val gate = if (ui.gate == Gate.SetupNeeded && ui.browsingDemo) Gate.Shelf else ui.gate

    // "A set has arrived" needs permission on Android 13+. Asked when it starts to matter: on
    // joining with an invite, or on first opening the inbox.
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    var askedNotifications by remember { mutableStateOf(false) }
    val askNotifications: () -> Unit = {
        if (!askedNotifications && Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            askedNotifications = true
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // A tapped notification or a just-accepted invite.
    LaunchedEffect(ui.goTo, gate) {
        val target = ui.goTo ?: return@LaunchedEffect
        if (gate != Gate.Shelf) return@LaunchedEffect
        place = when {
            target.startsWith("send:") -> Place.Received(target.removePrefix("send:"))
            target.startsWith("pair:") -> Place.Pair(target.removePrefix("pair:"))
            target == "settings" -> Place.Settings
            else -> Place.Inbox
        }
        vm.consumeGoTo()
    }
    BackHandler(enabled = gate == Gate.Shelf && place != Place.Shelf) { place = place.up() }

    Box(Modifier.fillMaxSize().background(liner.casing)) {
        AnimatedContent(targetState = gate, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "gate") { g ->
            when (g) {
                Gate.Loading -> Box(Modifier.fillMaxSize())
                Gate.SetupNeeded -> SetupNeededScreen(setup = vm.setup, onLookAround = vm::lookAround)
                Gate.SignedOut -> SignInScreen(busy = "google" in ui.busy, onSignIn = vm::signIn)
                Gate.Connections -> ConnectionsScreen(
                    ui = ui,
                    setup = vm.setup,
                    onConnectLastFm = vm::connectLastFm,
                    onConnectSpotify = onConnectSpotify,
                    onDone = vm::finishConnections,
                )
                Gate.Shelf -> {
                    AnimatedContent(targetState = place, label = "place") { p ->
                        when (p) {
                            Place.Shelf -> ShelfScreen(
                                ui = ui,
                                player = player,
                                vm = vm,
                                onOpen = { place = Place.AlbumOpen(it) },
                                onSettings = { place = Place.Settings },
                                onGroupSend = { place = Place.Sets },
                                onInbox = { place = Place.Inbox },
                                inboxCount = groupState.inbox.size,
                                onPairs = { place = Place.Pairs },
                                onReconnectSpotify = onConnectSpotify,
                            )
                            is Place.AlbumOpen -> AlbumScreen(
                                album = p.album,
                                ui = ui,
                                player = player,
                                vm = vm,
                                onBack = { place = Place.Shelf },
                            )
                            Place.Settings -> SettingsScreen(
                                ui = ui,
                                player = player,
                                setup = vm.setup,
                                vm = vm,
                                onConnectSpotify = onConnectSpotify,
                                onBack = { place = Place.Shelf },
                            )
                            Place.Sets -> SetsScreen(
                                state = groupState,
                                onBack = { place = Place.Shelf },
                                onOpen = { id -> group.open(id); place = Place.SetOpen(id) },
                                onCreate = { name, taste, yearList, months, count ->
                                    val id = group.create(name, taste, yearList, months, count)
                                    place = Place.SetOpen(id)
                                },
                                onWriting = { place = Place.Writing },
                            )
                            is Place.SetOpen -> {
                                val set = groupState.sets.firstOrNull { it.id == p.id }
                                if (set == null) {
                                    LaunchedEffect(Unit) { place = Place.Sets }
                                } else {
                                    SetScreen(
                                        set = set,
                                        state = groupState,
                                        vm = group,
                                        onBack = { place = Place.Sets },
                                        onOpenNote = { key -> place = Place.Note(set.id, key) },
                                        onOpenLink = openLink,
                                    )
                                }
                            }
                            is Place.Note -> {
                                val set = groupState.sets.firstOrNull { it.id == p.setId }
                                val entry = set?.entries?.firstOrNull { it.key == p.key }
                                if (set == null || entry == null) {
                                    LaunchedEffect(Unit) { place = p.up() }
                                } else {
                                    NoteEditorScreen(
                                        set = set,
                                        entry = entry,
                                        state = groupState,
                                        vm = group,
                                        onBack = { place = p.up() },
                                        onPlay = { album -> vm.play(context.findActivity(), album) },
                                        onOpenLink = openLink,
                                        onGo = { key -> place = Place.Note(set.id, key, p.monthlyPair) },
                                    )
                                }
                            }
                            Place.Inbox -> {
                                LaunchedEffect(Unit) { askNotifications() }
                                InboxScreen(
                                    state = groupState,
                                    joining = "invite" in ui.busy,
                                    onBack = { place = Place.Shelf },
                                    onOpen = { id -> place = Place.Received(id) },
                                    onJoin = vm::joinWithLink,
                                )
                            }
                            is Place.Received -> {
                                val received = groupState.inbox.firstOrNull { it.id == p.id }
                                    ?: groupState.sendsById[p.id]?.asReceived()
                                if (received == null) {
                                    // Not arrived on this phone yet (or gone): the inbox shows what's here.
                                    InboxScreen(
                                        state = groupState,
                                        joining = "invite" in ui.busy,
                                        onBack = { place = Place.Shelf },
                                        onOpen = { id -> place = Place.Received(id) },
                                        onJoin = vm::joinWithLink,
                                    )
                                } else {
                                    ReceivedScreen(
                                        received = received,
                                        onBack = { place = p.up() },
                                        onPlay = { a -> vm.play(context.findActivity(), a.toAlbum(), a.spotifyUri) },
                                        onOpenInSpotify = { a -> vm.openInSpotify(context, a.toAlbum(), a.spotifyUri) },
                                        onOpenLink = openLink,
                                        onReact = { i, r, comment -> group.react(received.id, i, r, comment) },
                                        busy = groupState.busy,
                                        backLabel = if (p.pairId != null) "Back to the pair" else "Back to your inbox",
                                        onRetryPlaylist = { group.retryPlaylist(received.id) },
                                    )
                                }
                            }
                            Place.Pairs -> {
                                LaunchedEffect(Unit) { askNotifications() }
                                PairsScreen(
                                    state = groupState,
                                    onBack = { place = Place.Shelf },
                                    onOpen = { id -> place = Place.Pair(id) },
                                    vm = group,
                                )
                            }
                            is Place.Pair -> {
                                val pair = groupState.pairs.firstOrNull { it.id == p.id }
                                if (pair == null) {
                                    if (groupState.pairsReady) LaunchedEffect(Unit) { place = Place.Pairs }
                                } else {
                                    PairScreen(
                                        pair = pair,
                                        state = groupState,
                                        vm = group,
                                        onBack = { place = Place.Pairs },
                                        onCompose = { place = Place.Monthly(group.openMonthly(pair), pair.id) },
                                        onOpenSend = { r -> place = Place.Received(r.id, pair.id) },
                                    )
                                }
                            }
                            is Place.Monthly -> {
                                val set = groupState.sets.firstOrNull { it.id == p.setId }
                                if (set == null) {
                                    LaunchedEffect(Unit) { place = Place.Pair(p.pairId) }
                                } else {
                                    MonthlyScreen(
                                        set = set,
                                        pair = groupState.pairs.firstOrNull { it.id == p.pairId },
                                        state = groupState,
                                        vm = group,
                                        onBack = { place = Place.Pair(p.pairId) },
                                        onOpenNote = { key -> place = Place.Note(set.id, key, p.pairId) },
                                        onPlay = { album -> vm.play(context.findActivity(), album) },
                                    )
                                }
                            }
                            Place.Writing -> if (groupState.ready) {
                                WritingScreen(
                                    writing = groupState.writing,
                                    onChange = group::saveWriting,
                                    onBack = { place = Place.Sets },
                                )
                            }
                        }
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
    }

    val invite = ui.invite
    if (invite != null && ui.account != null) {
        InviteDialog(
            prompt = invite,
            joining = "invite" in ui.busy,
            onJoin = { askNotifications(); vm.acceptInvite() },
            onDismiss = vm::dismissInvite,
        )
    }
}

/** "Join J.T.'s Liner Notes?" for an opened invite link. */
@Composable
private fun InviteDialog(prompt: InvitePrompt, joining: Boolean, onJoin: () -> Unit, onDismiss: () -> Unit) {
    val info = prompt.info
    val problem = prompt.error ?: info?.problem
    val inviter = info?.inviterName?.ifBlank { null } ?: "Someone"
    AlertDialog(
        onDismissRequest = { if (!joining) onDismiss() },
        title = {
            Text(
                when {
                    problem != null -> "This invite can't be used"
                    info == null -> "Opening the invite"
                    else -> "Join $inviter on Liner Notes?"
                }
            )
        },
        text = {
            when {
                problem != null -> Text(problem)
                info == null || joining -> Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                }
                else -> Text("You'll be paired with $inviter, so the albums they send you arrive in your inbox, each with a note.")
            }
        },
        confirmButton = {
            if (problem == null && info != null) {
                TextButton(onClick = onJoin, enabled = !joining) { Text("Join") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !joining) { Text(if (problem != null) "Close" else "Not now") }
        },
    )
}
