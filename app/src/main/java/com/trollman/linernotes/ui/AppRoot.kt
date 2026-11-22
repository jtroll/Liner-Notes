package com.trollman.linernotes.ui

import androidx.activity.compose.BackHandler
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
    data class Note(val setId: String, val key: String) : Place
    data object Writing : Place
}

/** Where Back goes from each place. */
private fun Place.up(): Place = when (this) {
    is Place.Note -> Place.SetOpen(setId)
    is Place.SetOpen, Place.Writing -> Place.Sets
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
                                        onBack = { place = Place.SetOpen(set.id) },
                                        onPlay = { album -> vm.play(context.findActivity(), album) },
                                        onOpenLink = openLink,
                                        onGo = { key -> place = Place.Note(set.id, key) },
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
}
