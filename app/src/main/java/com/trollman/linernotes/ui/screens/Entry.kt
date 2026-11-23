package com.trollman.linernotes.ui.screens

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trollman.linernotes.Setup
import com.trollman.linernotes.UiState
import com.trollman.linernotes.data.DemoAlbums
import com.trollman.linernotes.ui.system.DisplayWindow
import com.trollman.linernotes.ui.system.DotMatrixText
import com.trollman.linernotes.ui.system.HardwareSwitch
import com.trollman.linernotes.ui.system.Led
import com.trollman.linernotes.ui.system.Panel
import com.trollman.linernotes.ui.system.PhysicalTextButton
import com.trollman.linernotes.ui.system.Record
import com.trollman.linernotes.ui.system.SectionLabel
import com.trollman.linernotes.ui.system.findActivity
import com.trollman.linernotes.ui.theme.liner
import com.trollman.linernotes.ui.theme.mono

/** The title block every entry screen shares: the wordmark in a display window, a record below. */
@Composable
private fun Masthead(spinning: Boolean) {
    val c = liner
    DisplayWindow {
        Text("TEST BUILD · ANDROID", style = mono(11.sp), color = c.onDisplay.copy(alpha = 0.6f))
        Spacer(Modifier.height(10.dp))
        DotMatrixText("Liner", size = 64.sp, color = c.onDisplay)
        DotMatrixText("Notes", size = 64.sp, color = c.signal)
    }
    Spacer(Modifier.height(28.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        Record(DemoAlbums.first(), spinning = spinning, modifier = Modifier.size(200.dp))
    }
    Spacer(Modifier.height(28.dp))
}

@Composable
private fun EntryColumn(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) { content() }
}

/** A build made before the Firebase project exists: say what's missing, and let them look around. */
@Composable
fun SetupNeededScreen(setup: Setup, onLookAround: () -> Unit) {
    val c = liner
    EntryColumn {
        Masthead(spinning = true)
        Text("Almost ready to play", style = MaterialTheme.typography.headlineMedium, color = c.ink)
        Spacer(Modifier.height(8.dp))
        Text(
            "This build was made before the one-time setup, so sign-in, Last.fm and Spotify are off. " +
                "The look and the shelf are here to try.",
            style = MaterialTheme.typography.bodyLarge,
            color = c.inkSoft,
        )
        Spacer(Modifier.height(16.dp))
        SectionLabel("Still to set up")
        setup.missing.forEach { line ->
            Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Led(on = false)
                Spacer(Modifier.width(10.dp))
                Text(line, style = MaterialTheme.typography.bodyMedium, color = c.ink)
            }
        }
        Spacer(Modifier.height(24.dp))
        PhysicalTextButton("Look around", onLookAround, Modifier.fillMaxWidth(), accent = true)
    }
}

@Composable
fun SignInScreen(busy: Boolean, onSignIn: (Activity) -> Unit) {
    val c = liner
    val context = LocalContext.current
    EntryColumn {
        Masthead(spinning = busy)
        Text("Albums you loved, passed hand to hand, with a note.", style = MaterialTheme.typography.headlineSmall, color = c.ink)
        Spacer(Modifier.height(24.dp))
        PhysicalTextButton(
            if (busy) "Signing in…" else "Sign in with Google",
            { onSignIn(context.findActivity()) },
            Modifier.fillMaxWidth(),
            accent = true,
            enabled = !busy,
        )
        Spacer(Modifier.height(12.dp))
        Text("Invite only. Your Google account is your Liner Notes account.", style = MaterialTheme.typography.bodySmall, color = c.inkFaint)
    }
}

/**
 * First run: the three connections in a row, as three hardware switches that flip on as each
 * completes (SPEC.md, "Making the sign-ins painless"). Each can be skipped and done in Settings.
 */
@Composable
fun ConnectionsScreen(
    ui: UiState,
    setup: Setup,
    onConnectLastFm: (android.content.Context) -> Unit,
    onConnectSpotify: () -> Unit,
    onDone: () -> Unit,
) {
    val c = liner
    EntryColumn {
        DisplayWindow {
            Text("FIRST RUN", style = mono(11.sp), color = c.onDisplay.copy(alpha = 0.6f))
            Spacer(Modifier.height(8.dp))
            DotMatrixText("Plug in", size = 56.sp, color = c.onDisplay)
        }
        Spacer(Modifier.height(20.dp))
        Text(
            "Three connections, once each. Skip any of them and finish later in Settings.",
            style = MaterialTheme.typography.bodyLarge,
            color = c.inkSoft,
        )
        Spacer(Modifier.height(20.dp))
        ConnectionSwitches(ui, setup, onConnectLastFm, onConnectSpotify)
        Spacer(Modifier.height(28.dp))
        val all = ui.profile.lastfmUsername != null && ui.profile.spotifyName != null
        PhysicalTextButton(if (all) "Done" else "Skip for now", onDone, Modifier.fillMaxWidth(), accent = all)
    }
}

/** Google, Last.fm and Spotify as switches. Shared by first run and Settings. */
@Composable
fun ConnectionSwitches(
    ui: UiState,
    setup: Setup,
    onConnectLastFm: (android.content.Context) -> Unit,
    onConnectSpotify: () -> Unit,
) {
    val context = LocalContext.current
    val lastfm = ui.profile.lastfmUsername
    val spotify = ui.profile.spotifyName
    Panel {
        HardwareSwitch(
            checked = ui.account != null,
            onToggle = {},
            enabled = false,
            label = "Google",
            description = "Your account. One tap, and it's done.",
            status = ui.account?.email?.let { "Signed in as $it" },
        )
        HorizontalDivider(color = liner.bevelDark.copy(alpha = 0.4f))
        HardwareSwitch(
            checked = lastfm != null,
            onToggle = { if (lastfm == null) onConnectLastFm(context) },
            enabled = setup.canConnectLastFm || lastfm != null,
            label = "Last.fm",
            description = "Reads what you play, for suggestions and influence.",
            status = when {
                lastfm != null -> "Connected as $lastfm"
                "lastfm" in ui.busy -> "Connecting…"
                !setup.canConnectLastFm -> "Not set up in this build"
                else -> null
            },
        )
        HorizontalDivider(color = liner.bevelDark.copy(alpha = 0.4f))
        val now = System.currentTimeMillis()
        val runOut = ui.profile.spotifyRunOut(now)
        val daysLeft = ui.profile.spotifyDaysLeft(now)
        HardwareSwitch(
            checked = spotify != null && !runOut,
            onToggle = { if (spotify == null || runOut) onConnectSpotify() },
            enabled = setup.canConnectSpotify || spotify != null,
            label = "Spotify",
            description = "Plays albums right here, and makes playlists.",
            status = when {
                "spotify" in ui.busy && spotify != null -> "Reconnecting…"
                runOut -> "Sign-in ran out · tap to reconnect"
                spotify != null && ui.profile.spotifyRunningOut(now) && daysLeft != null ->
                    "Connected as $spotify · sign-in runs out in ${daysLeft.coerceAtLeast(0)} days"
                spotify != null -> "Connected as $spotify"
                "spotify" in ui.busy -> "Connecting…"
                !setup.canConnectSpotify -> "Not set up in this build"
                else -> null
            },
        )
    }
    if ("lastfm" in ui.busy || "spotify" in ui.busy) {
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp, color = liner.signal)
        }
    }
}
