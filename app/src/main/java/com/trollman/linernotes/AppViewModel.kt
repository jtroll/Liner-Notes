package com.trollman.linernotes

import android.app.Activity
import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.functions.FirebaseFunctionsException
import com.trollman.linernotes.data.Album
import com.trollman.linernotes.data.DemoAlbums
import com.trollman.linernotes.data.InviteInfo
import com.trollman.linernotes.data.InviteLinks
import com.trollman.linernotes.data.Period
import com.trollman.linernotes.data.Profile
import com.trollman.linernotes.data.SpotifyAlbum
import com.trollman.linernotes.spotify.PlayerState
import com.trollman.linernotes.spotify.SpotifySignIn
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Where the app is, as far as the first screens are concerned. */
enum class Gate { Loading, SetupNeeded, SignedOut, Connections, Shelf }

@Immutable
data class Account(val uid: String, val name: String?, val email: String?)

@Immutable
data class ShelfState(
    val period: Period = Period.Quarter,
    val albums: List<Album> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    /** Whose listening this is ("jtroll"), or null for the demo shelf. */
    val owner: String? = null,
    val demo: Boolean = false,
)

@Immutable
data class UiState(
    val gate: Gate = Gate.Loading,
    val account: Account? = null,
    val profile: Profile = Profile(),
    val shelf: ShelfState = ShelfState(),
    val busy: Set<String> = emptySet(),
    /** A one-off message for the snackbar. */
    val message: String? = null,
    /** True after "Look around" on a build without Firebase. */
    val browsingDemo: Boolean = false,
    /** An invite link waiting for the person to say Join. */
    val invite: InvitePrompt? = null,
    /** Somewhere the screens should go next: "inbox" or "send:<id>". */
    val goTo: String? = null,
)

/** The "Join?" question for an opened invite link. [info] is null while it loads. */
@Immutable
data class InvitePrompt(val token: String, val info: InviteInfo? = null, val error: String? = null)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val graph = (app as LinerNotesApp).graph
    val setup: Setup = graph.setup
    val player: StateFlow<PlayerState> = graph.player.state

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    /** Albums matched on Spotify this session, so a second Play doesn't search again. */
    private val matches = mutableMapOf<String, SpotifyAlbum?>()
    private var shelfJob: Job? = null

    init {
        viewModelScope.launch {
            graph.prefs.shelfPeriod.collect { p -> _ui.update { it.copy(shelf = it.shelf.copy(period = p)) } }
        }
        val auth = graph.auth
        if (auth == null) {
            _ui.update { it.copy(gate = Gate.SetupNeeded) }
        } else {
            viewModelScope.launch {
                auth.currentUser.collect { user ->
                    _ui.update {
                        it.copy(account = user?.let { u -> Account(u.uid, u.displayName, u.email) })
                    }
                    if (user != null) graph.cloud?.touchUser(user.displayName)
                    recomputeGate()
                }
            }
            viewModelScope.launch {
                ui.map { it.account?.uid }.distinctUntilChanged().flatMapLatest { uid ->
                    if (uid == null) flowOf(Profile()) else graph.cloud?.profile(uid) ?: flowOf(Profile())
                }.collect { profile ->
                    val before = _ui.value.profile.lastfmUsername
                    _ui.update { it.copy(profile = profile) }
                    if (profile.lastfmUsername != before) loadShelf()
                }
            }
            viewModelScope.launch {
                graph.prefs.connectionsSeen.collect { recomputeGate() }
            }
            // Once signed in: register this phone for "a set has arrived", and offer any invite
            // link that was opened before signing in.
            viewModelScope.launch {
                ui.map { it.account?.uid }.distinctUntilChanged().collect { uid ->
                    if (uid != null) {
                        runCatching { graph.cloud?.registerPush(uid) }
                            .onFailure { Log.w("AppViewModel", "Push registration failed", it) }
                    }
                }
            }
            viewModelScope.launch {
                combine(graph.prefs.pendingInvite, ui.map { it.account?.uid }.distinctUntilChanged()) { token, uid ->
                    if (uid == null) null else token
                }.distinctUntilChanged().collect { token -> if (token != null) loadInvite(token) }
            }
        }
    }

    private suspend fun recomputeGate() {
        if (graph.auth == null) return
        val seen = graph.prefs.connectionsSeen.first()
        _ui.update {
            it.copy(
                gate = when {
                    it.account == null -> Gate.SignedOut
                    !seen -> Gate.Connections
                    else -> Gate.Shelf
                }
            )
        }
    }

    fun consumeMessage() = _ui.update { it.copy(message = null) }
    private fun say(text: String) = _ui.update { it.copy(message = text) }
    private fun busy(key: String, on: Boolean) =
        _ui.update { it.copy(busy = if (on) it.busy + key else it.busy - key) }

    // ---- Setup-needed build -------------------------------------------------------------------

    fun lookAround() {
        _ui.update { it.copy(browsingDemo = true) }
        loadShelf()
    }

    // ---- Google ---------------------------------------------------------------------------------

    fun signIn(activity: Activity) {
        val auth = graph.auth ?: return
        viewModelScope.launch {
            busy("google", true)
            val outcome = auth.signInWithGoogle(activity)
            busy("google", false)
            outcome.message()?.let(::say)
        }
    }

    fun signOut() {
        graph.player.disconnect()
        graph.auth?.signOut()
        matches.clear()
        viewModelScope.launch { graph.prefs.setConnectionsSeen(false) }
    }

    fun finishConnections() {
        viewModelScope.launch { graph.prefs.setConnectionsSeen(true) }
    }

    // ---- Invites and the inbox -----------------------------------------------------------------

    /** An invite link was opened (MainActivity) or pasted. Offered once the person is signed in. */
    fun receiveInvite(token: String) {
        viewModelScope.launch { graph.prefs.setPendingInvite(token) }
    }

    /** "Join with an invite": a pasted link or code. */
    fun joinWithLink(text: String) {
        val token = InviteLinks.token(text)
        if (token == null) {
            say("That doesn't look like an invite link. Copy the whole link and try again.")
            return
        }
        if (graph.cloud == null) {
            say("Joining needs the backend (docs/SETUP.md).")
            return
        }
        receiveInvite(token)
        // The same link pasted twice: the collector won't fire again, so load it here.
        if (_ui.value.invite?.token != token && _ui.value.account != null) loadInvite(token)
    }

    private fun loadInvite(token: String) {
        val cloud = graph.cloud ?: return
        _ui.update { it.copy(invite = InvitePrompt(token)) }
        viewModelScope.launch {
            runCatching { cloud.inviteInfo(token) }
                .onSuccess { info ->
                    if (info.alreadyJoined) {
                        graph.prefs.setPendingInvite(null)
                        _ui.update { it.copy(invite = null, goTo = "inbox") }
                    } else {
                        _ui.update { it.copy(invite = InvitePrompt(token, info)) }
                    }
                }
                .onFailure { e -> _ui.update { it.copy(invite = InvitePrompt(token, error = describe(e))) } }
        }
    }

    fun acceptInvite() {
        val prompt = _ui.value.invite ?: return
        val cloud = graph.cloud ?: return
        viewModelScope.launch {
            busy("invite", true)
            try {
                val (inviter, delivered) = cloud.acceptInvite(prompt.token, _ui.value.account?.name.orEmpty())
                graph.prefs.setPendingInvite(null)
                val who = inviter.ifBlank { "them" }
                _ui.update {
                    it.copy(
                        invite = null,
                        goTo = "inbox",
                        message = if (delivered > 0) "You're in. $who's set is waiting for you." else "You're in. Sets from $who will arrive in your inbox.",
                    )
                }
            } catch (e: Exception) {
                _ui.update { it.copy(invite = prompt.copy(error = describe(e))) }
            } finally {
                busy("invite", false)
            }
        }
    }

    fun dismissInvite() {
        _ui.update { it.copy(invite = null) }
        viewModelScope.launch { graph.prefs.setPendingInvite(null) }
    }

    /** A "set has arrived" notification was tapped. */
    fun openSend(sendId: String) = _ui.update { it.copy(goTo = "send:$sendId") }

    /** A tapped monthly reminder: straight to that pair. */
    fun openPair(pairId: String) = _ui.update { it.copy(goTo = "pair:$pairId") }

    fun consumeGoTo() = _ui.update { it.copy(goTo = null) }

    // ---- Last.fm --------------------------------------------------------------------------------

    fun connectLastFm(context: Context) {
        if (!setup.canConnectLastFm) {
            say("This build can't connect Last.fm yet: it needs the Last.fm API key and Firebase (docs/SETUP.md).")
            return
        }
        try {
            CustomTabsIntent.Builder().setShowTitle(true).build()
                .launchUrl(context, Uri.parse(graph.lastFm.approvalUrl))
        } catch (e: ActivityNotFoundException) {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(graph.lastFm.approvalUrl)))
        }
    }

    /** Last.fm's approval came back (MainActivity, linernotes://lastfm-callback?token=...). */
    fun finishLastFm(token: String) {
        val cloud = graph.cloud ?: return
        viewModelScope.launch {
            busy("lastfm", true)
            runCatching { cloud.connectLastFm(token) }
                .onSuccess { name -> say("Last.fm connected as $name.") }
                .onFailure { e -> say("Last.fm didn't connect: ${describe(e)}") }
            busy("lastfm", false)
        }
    }

    // ---- Spotify --------------------------------------------------------------------------------

    /** The intent that opens Spotify's sign-in, or null (with a message) if this build can't. */
    fun spotifySignInIntent(activity: Activity): Intent? {
        if (!setup.canConnectSpotify) {
            say("This build can't connect Spotify yet: it needs the Spotify client ID and Firebase (docs/SETUP.md).")
            return null
        }
        busy("spotify", true)
        return SpotifySignIn.intent(activity, BuildConfig.SPOTIFY_CLIENT_ID, BuildConfig.SPOTIFY_REDIRECT_URI)
    }

    fun finishSpotify(activity: Activity, result: SpotifySignIn.Result) {
        when (result) {
            is SpotifySignIn.Result.Cancelled -> busy("spotify", false)
            is SpotifySignIn.Result.Failed -> {
                busy("spotify", false)
                say("Spotify didn't connect: ${result.message}")
            }
            is SpotifySignIn.Result.Code -> {
                val cloud = graph.cloud ?: return
                viewModelScope.launch {
                    runCatching { cloud.connectSpotify(result.code, BuildConfig.SPOTIFY_REDIRECT_URI) }
                        .onSuccess { name ->
                            say("Spotify connected${if (name.isNotBlank()) " as $name" else ""}.")
                            // Connect playback straight away, so Play works first time.
                            graph.player.connect(activity)
                        }
                        .onFailure { e -> say("Spotify didn't connect: ${describe(e)}") }
                    busy("spotify", false)
                }
            }
        }
    }

    fun connectPlayer(activity: Activity) = graph.player.connect(activity)

    // ---- Shelf ----------------------------------------------------------------------------------

    fun setPeriod(period: Period) {
        if (period == _ui.value.shelf.period) return
        _ui.update { it.copy(shelf = it.shelf.copy(period = period)) }
        viewModelScope.launch { graph.prefs.setShelfPeriod(period) }
        loadShelf()
    }

    fun loadShelf() {
        val state = _ui.value
        val user = state.profile.lastfmUsername
        val period = state.shelf.period
        shelfJob?.cancel()
        if (user.isNullOrBlank()) {
            _ui.update { it.copy(shelf = it.shelf.copy(albums = DemoAlbums, loading = false, error = null, owner = null, demo = true)) }
            return
        }
        shelfJob = viewModelScope.launch {
            _ui.update { it.copy(shelf = it.shelf.copy(loading = true, error = null, owner = user, demo = false)) }
            runCatching { graph.lastFm.topAlbums(user, period) }
                .onSuccess { albums -> _ui.update { it.copy(shelf = it.shelf.copy(albums = albums, loading = false)) } }
                .onFailure { e ->
                    Log.w("AppViewModel", "Top albums failed", e)
                    _ui.update { it.copy(shelf = it.shelf.copy(loading = false, error = e.message ?: "Couldn't reach Last.fm")) }
                }
        }
    }

    // ---- Playing --------------------------------------------------------------------------------

    private suspend fun match(album: Album): SpotifyAlbum? {
        if (matches.containsKey(album.key)) return matches[album.key]
        val cloud = graph.cloud ?: return null
        val found = runCatching { cloud.findSpotifyAlbum(album.artist, album.title) }
            .onFailure { Log.w("AppViewModel", "Spotify search failed", it) }
            .getOrNull()
        matches[album.key] = found
        return found
    }

    /** Plays a received album whose Spotify release the sender already matched. */
    fun play(activity: Activity, album: Album, spotifyUri: String?) {
        if (spotifyUri == null || !setup.canPlay) return play(activity, album)
        graph.player.play(activity, spotifyUri)
    }

    /** Opens a received album in the Spotify app, using the sender's match when there is one. */
    fun openInSpotify(context: Context, album: Album, spotifyUri: String?) {
        if (spotifyUri == null) return openInSpotify(context, album)
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(spotifyUri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            val web = "https://open.spotify.com/album/" + spotifyUri.substringAfterLast(':')
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(web)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /** Plays [album] in the app through the Spotify app, or opens Spotify's search as a fallback. */
    fun play(activity: Activity, album: Album) {
        if (!setup.canPlay) {
            say("This build can't play yet: it needs the Spotify client ID (docs/SETUP.md).")
            return
        }
        viewModelScope.launch {
            busy("play:${album.key}", true)
            val found = match(album)
            busy("play:${album.key}", false)
            if (found != null) {
                graph.player.play(activity, found.uri)
            } else {
                say("Couldn't find it on Spotify from here, so opening a search instead.")
                openInSpotify(activity, album)
            }
        }
    }

    fun openInSpotify(context: Context, album: Album) {
        viewModelScope.launch {
            val found = matches[album.key] ?: match(album)
            val uri = found?.uri ?: "spotify:search:${Uri.encode("${album.artist} ${album.title}")}"
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                val web = found?.webUrl ?: "https://open.spotify.com/search/${Uri.encode("${album.artist} ${album.title}")}"
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(web)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }

    fun pause() = graph.player.pause()
    fun resume() = graph.player.resume()
    fun next() = graph.player.next()
    fun previous() = graph.player.previous()

    /** Phase 0 proof that playlist creation works in Development Mode (SPEC.md, "Build order"). */
    fun makeTestPlaylist(album: Album) {
        val cloud = graph.cloud ?: return
        viewModelScope.launch {
            busy("playlist", true)
            val found = match(album)
            if (found == null) {
                say("Couldn't find ${album.title} on Spotify to put in a playlist.")
            } else {
                runCatching { cloud.makeTestPlaylist(found.uri) }
                    .onSuccess { say("Made a private playlist in your Spotify: Liner Notes test.") }
                    .onFailure { e -> say("Playlist didn't work: ${describe(e)}") }
            }
            busy("playlist", false)
        }
    }

    private fun describe(e: Throwable): String = when (e) {
        is FirebaseFunctionsException -> when (e.code) {
            FirebaseFunctionsException.Code.NOT_FOUND, FirebaseFunctionsException.Code.UNIMPLEMENTED ->
                "the backend isn't deployed yet (docs/SETUP.md)."
            FirebaseFunctionsException.Code.UNAUTHENTICATED -> "you're signed out. Sign in and try again."
            FirebaseFunctionsException.Code.UNAVAILABLE -> "couldn't reach the backend. Check the connection."
            else -> e.message ?: e.code.name
        }
        else -> e.message ?: (e::class.simpleName ?: "unknown error")
    }
}
