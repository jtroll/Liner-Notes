package com.trollman.linernotes

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.trollman.linernotes.data.InviteLinks
import com.trollman.linernotes.push.PushService
import com.trollman.linernotes.spotify.SpotifySignIn
import com.trollman.linernotes.ui.AppRoot
import com.trollman.linernotes.ui.theme.LinerNotesTheme

class MainActivity : ComponentActivity() {

    private val vm: AppViewModel by viewModels()

    private val spotifySignIn = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        vm.finishSpotify(this, SpotifySignIn.read(result.resultCode, result.data))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleLink(intent)
        setContent {
            LinerNotesTheme {
                AppRoot(
                    vm = vm,
                    onConnectSpotify = { vm.spotifySignInIntent(this)?.let { spotifySignIn.launch(it) } },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleLink(intent)
    }

    /**
     * Links into the app: Last.fm's approval (linernotes://lastfm-callback?token=..., from
     * hosting/lastfm-callback), invites (linernotes://invite/<token>, from hosting/invite), and a
     * tapped "set has arrived" notification (the sendId extra).
     */
    private fun handleLink(intent: Intent?) {
        intent ?: return
        intent.getStringExtra(PushService.EXTRA_SEND_ID)?.let { id ->
            vm.openSend(id)
            intent.removeExtra(PushService.EXTRA_SEND_ID)
        }
        val data = intent.data ?: return
        if (data.scheme != "linernotes") return
        when (data.host) {
            "lastfm-callback" -> data.getQueryParameter("token")?.takeIf { it.isNotBlank() }?.let(vm::finishLastFm)
            "invite" -> data.lastPathSegment?.let(InviteLinks::token)?.let(vm::receiveInvite)
        }
        // Handled once: a rotation shouldn't send the same link again.
        intent.data = null
    }
}
