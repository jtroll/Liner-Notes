package com.trollman.linernotes.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trollman.linernotes.AppViewModel
import com.trollman.linernotes.BuildConfig
import com.trollman.linernotes.Setup
import com.trollman.linernotes.UiState
import com.trollman.linernotes.spotify.Connection
import com.trollman.linernotes.spotify.PlayerState
import com.trollman.linernotes.ui.system.DotMatrixText
import com.trollman.linernotes.ui.system.HardwareSwitch
import com.trollman.linernotes.ui.system.Led
import com.trollman.linernotes.ui.system.Panel
import com.trollman.linernotes.ui.system.PhysicalButton
import com.trollman.linernotes.ui.system.PhysicalTextButton
import com.trollman.linernotes.ui.system.SectionLabel
import com.trollman.linernotes.ui.system.findActivity
import com.trollman.linernotes.ui.theme.liner
import com.trollman.linernotes.ui.theme.mono
import com.trollman.linernotes.update.UpdateState

@Composable
fun SettingsScreen(
    ui: UiState,
    player: PlayerState,
    setup: Setup,
    vm: AppViewModel,
    onConnectSpotify: () -> Unit,
    onBack: () -> Unit,
) {
    val c = liner
    val context = LocalContext.current
    val updates by vm.updates.collectAsState()
    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PhysicalButton(onBack, Modifier.size(44.dp), shape = RoundedCornerShape(12.dp), contentDescription = "Back to your records") {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null)
            }
            Spacer(Modifier.width(16.dp))
            DotMatrixText("Setup", size = 40.sp, color = c.ink)
        }
        Spacer(Modifier.height(20.dp))

        if (ui.account != null) {
            SpotifyExpiryNotice(ui.profile, busy = "spotify" in ui.busy, onReconnect = onConnectSpotify)
            if (ui.profile.spotifyRunOut(System.currentTimeMillis()) || ui.profile.spotifyRunningOut(System.currentTimeMillis())) {
                Spacer(Modifier.height(16.dp))
            }
            SectionLabel("Connections")
            ConnectionSwitches(ui, setup, vm::connectLastFm, onConnectSpotify)
            Spacer(Modifier.height(16.dp))

            SectionLabel("Playback")
            Panel {
                Row(Modifier.padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Led(on = player.connection == Connection.Connected)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        when (player.connection) {
                            Connection.Connected -> "Connected to the Spotify app"
                            Connection.Connecting -> "Connecting to the Spotify app…"
                            Connection.NotSetUp -> "Not set up in this build"
                            Connection.NotInstalled -> "The Spotify app isn't installed"
                            Connection.Failed -> player.problem ?: "Couldn't connect"
                            Connection.Disconnected -> "Not connected yet"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = c.ink,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (player.connection != Connection.Connected && setup.canPlay) {
                    PhysicalTextButton("Connect playback", { vm.connectPlayer(context.findActivity()) }, Modifier.fillMaxWidth())
                    Spacer(Modifier.height(12.dp))
                }
            }
            Spacer(Modifier.height(16.dp))

            // Phase 0 proof: Spotify playlist creation works in Development Mode.
            SectionLabel("Checks")
            Panel {
                val first = ui.shelf.albums.firstOrNull()
                Text(
                    if (first != null) "Make a private playlist in your Spotify from ${first.heading}, to prove playlists work."
                    else "Connect Last.fm to try making a playlist.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.inkSoft,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
                PhysicalTextButton(
                    if ("playlist" in ui.busy) "Making it…" else "Make a test playlist",
                    { first?.let(vm::makeTestPlaylist) },
                    Modifier.fillMaxWidth(),
                    enabled = first != null && ui.profile.spotifyName != null && "playlist" !in ui.busy,
                )
                Spacer(Modifier.height(12.dp))
            }
            Spacer(Modifier.height(16.dp))

            SectionLabel("Account")
            Panel {
                Text(
                    listOfNotNull(ui.account.name, ui.account.email).joinToString("\n"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.ink,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
                PhysicalTextButton("Sign out", vm::signOut, Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
            }
            Spacer(Modifier.height(16.dp))
        }

        SectionLabel("Sound")
        Panel {
            HardwareSwitch(
                checked = ui.clicks,
                onToggle = vm::setClicks,
                label = "Click sound",
                description = "A soft click when you press a button, as well as the buzz. Follows the phone's volume.",
            )
        }
        Spacer(Modifier.height(16.dp))

        UpdatesPanel(updates, vm::checkForUpdate, vm::installUpdate, enabled = BuildConfig.HAS_FIREBASE_CONFIG)
        Spacer(Modifier.height(16.dp))

        if (ui.account != null) {
            DeleteMyData(busy = "delete" in ui.busy, onDelete = vm::deleteMyData)
            Spacer(Modifier.height(16.dp))
        }

        SectionLabel("This build")
        Panel {
            Text(
                "Version ${BuildConfig.VERSION_NAME}" +
                    if (BuildConfig.FIREBASE_PROJECT_ID.isNotBlank()) " · ${BuildConfig.FIREBASE_PROJECT_ID}" else "",
                style = MaterialTheme.typography.bodyMedium,
                color = c.ink,
                modifier = Modifier.padding(top = 12.dp),
            )
            if (setup.complete) {
                Text("Everything is set up.", style = MaterialTheme.typography.bodySmall, color = c.inkSoft, modifier = Modifier.padding(bottom = 12.dp))
            } else {
                Text("Still to set up:", style = MaterialTheme.typography.bodySmall, color = c.inkSoft)
                setup.missing.forEach {
                    Text("· $it", style = MaterialTheme.typography.bodySmall, color = c.inkSoft)
                }
                Spacer(Modifier.height(12.dp))
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "TYPE: ALLO CRISP, DOTO, IBM PLEX MONO (OFL). PLAYBACK: SPOTIFY APP REMOTE SDK (APACHE 2.0).",
            style = mono(10.sp),
            color = c.inkFaint,
        )
        Spacer(Modifier.height(24.dp))
    }
}

/** Setup > Updates: the newest build from App Distribution, installed from here. */
@Composable
private fun UpdatesPanel(state: UpdateState, onCheck: () -> Unit, onInstall: () -> Unit, enabled: Boolean) {
    val c = liner
    SectionLabel("Updates")
    Panel {
        val available = state.available
        Text(
            when {
                available != null -> "Version ${available.version} is ready. You have ${BuildConfig.VERSION_NAME}."
                state.upToDate -> "You have the newest build, ${BuildConfig.VERSION_NAME}."
                else -> "New builds arrive in App Tester. You can also check and install them here."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = c.ink,
            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
        )
        available?.notes?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = c.inkSoft, maxLines = 6)
        }
        state.problem?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = c.inkSoft)
        }
        Spacer(Modifier.height(10.dp))
        if (state.downloading) {
            val p = state.progress
            if (p != null) LinearProgressIndicator(progress = { p }, Modifier.fillMaxWidth(), color = c.signal, trackColor = c.well)
            else LinearProgressIndicator(Modifier.fillMaxWidth(), color = c.signal, trackColor = c.well)
            Spacer(Modifier.height(6.dp))
            Text("DOWNLOADING", style = mono(11.sp), color = c.inkFaint)
        } else if (available != null) {
            PhysicalTextButton("Update to ${available.version}", onInstall, Modifier.fillMaxWidth(), accent = true)
        } else {
            PhysicalTextButton(
                if (state.checking) "Checking…" else "Check for an update",
                onCheck,
                Modifier.fillMaxWidth(),
                enabled = enabled && !state.checking,
            )
        }
        Spacer(Modifier.height(12.dp))
    }
}

/**
 * Setup > Delete my data (SPEC.md, "Data model"). Asks once more, in plain words, before anything
 * goes: what's deleted, what the other person keeps, and that it can't be undone.
 */
@Composable
private fun DeleteMyData(busy: Boolean, onDelete: () -> Unit) {
    val c = liner
    var asking by remember { mutableStateOf(false) }
    SectionLabel("Your data")
    Panel {
        Text(
            "Delete everything Liner Notes keeps about you: your sets, notes and writing, your sends, " +
                "your pairs, your Last.fm and Spotify connections, and your sign-in.",
            style = MaterialTheme.typography.bodySmall,
            color = c.inkSoft,
            modifier = Modifier.padding(top = 12.dp, bottom = 10.dp),
        )
        PhysicalTextButton(if (busy) "Deleting…" else "Delete my data", { asking = true }, Modifier.fillMaxWidth(), enabled = !busy)
        Spacer(Modifier.height(12.dp))
    }
    if (asking) {
        AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text("Delete your data?") },
            text = {
                Text(
                    "Your sets, notes, sends, pairs and connections are deleted, and you're signed out. " +
                        "Sets you were sent stay in the sender's archive, without your reactions. " +
                        "Playlists already in your Spotify stay there. This can't be undone.",
                )
            },
            confirmButton = {
                TextButton(onClick = { asking = false; onDelete() }) { Text("Delete everything") }
            },
            dismissButton = {
                TextButton(onClick = { asking = false }) { Text("Keep it") }
            },
        )
    }
}
