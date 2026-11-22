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
import com.trollman.linernotes.AppViewModel
import com.trollman.linernotes.Gate
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
}

@Composable
fun AppRoot(vm: AppViewModel, onConnectSpotify: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val player by vm.player.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var place by remember { mutableStateOf<Place>(Place.Shelf) }

    LaunchedEffect(ui.message) {
        ui.message?.let {
            snackbar.showSnackbar(it)
            vm.consumeMessage()
        }
    }

    val gate = if (ui.gate == Gate.SetupNeeded && ui.browsingDemo) Gate.Shelf else ui.gate
    BackHandler(enabled = gate == Gate.Shelf && place != Place.Shelf) { place = Place.Shelf }

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
                        }
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
    }
}
