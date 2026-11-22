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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.trollman.linernotes.ui.system.Led
import com.trollman.linernotes.ui.system.Panel
import com.trollman.linernotes.ui.system.PhysicalButton
import com.trollman.linernotes.ui.system.PhysicalTextButton
import com.trollman.linernotes.ui.system.SectionLabel
import com.trollman.linernotes.ui.system.findActivity
import com.trollman.linernotes.ui.theme.liner
import com.trollman.linernotes.ui.theme.mono

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
