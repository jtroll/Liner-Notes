package com.trollman.linernotes.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.trollman.linernotes.data.Profile
import com.trollman.linernotes.ui.system.Led
import com.trollman.linernotes.ui.system.Panel
import com.trollman.linernotes.ui.system.PhysicalTextButton
import com.trollman.linernotes.ui.theme.liner

/**
 * Spotify asks everyone to sign in again six months after they first did (SPEC.md, "First run"),
 * so two weeks before, and once it has run out, this sits on the shelf and in Setup with a
 * one-tap Reconnect. Shows nothing the rest of the time.
 */
@Composable
fun SpotifyExpiryNotice(profile: Profile, busy: Boolean, onReconnect: () -> Unit, modifier: Modifier = Modifier) {
    val now = System.currentTimeMillis()
    val runOut = profile.spotifyRunOut(now)
    if (!runOut && !profile.spotifyRunningOut(now)) return
    val days = profile.spotifyDaysLeft(now) ?: 0
    val c = liner
    Panel(modifier) {
        Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Led(on = true)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    when {
                        runOut -> "Your Spotify sign-in has run out"
                        days <= 0 -> "Your Spotify sign-in runs out today"
                        days == 1 -> "Your Spotify sign-in runs out tomorrow"
                        else -> "Your Spotify sign-in runs out in $days days"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = c.ink,
                )
                Text(
                    if (runOut) "Albums and playlists can't reach your Spotify until you reconnect."
                    else "Spotify asks for a fresh sign-in every six months. One tap does it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.inkSoft,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        PhysicalTextButton(
            if (busy) "Reconnecting…" else "Reconnect Spotify",
            onReconnect,
            Modifier.fillMaxWidth(),
            accent = true,
            enabled = !busy,
        )
        Spacer(Modifier.height(12.dp))
    }
}
