package com.trollman.linernotes

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
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

    /** Last.fm's approval returns as linernotes://lastfm-callback?token=... (hosting/lastfm-callback). */
    private fun handleLink(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme == "linernotes" && data.host == "lastfm-callback") {
            data.getQueryParameter("token")?.takeIf { it.isNotBlank() }?.let(vm::finishLastFm)
            // Handled once: a rotation shouldn't send the same token again.
            intent.data = null
        }
    }
}
