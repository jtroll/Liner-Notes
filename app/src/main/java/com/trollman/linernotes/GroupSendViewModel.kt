package com.trollman.linernotes

import android.app.Application
import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.functions.FirebaseFunctionsException
import com.trollman.linernotes.data.CloudSetStore
import com.trollman.linernotes.data.DemoAlbums
import com.trollman.linernotes.data.GroupSet
import com.trollman.linernotes.data.LocalSetStore
import com.trollman.linernotes.data.NoteState
import com.trollman.linernotes.data.Ranking
import com.trollman.linernotes.data.SetAlbum
import com.trollman.linernotes.data.SetEntry
import com.trollman.linernotes.data.SetStore
import com.trollman.linernotes.data.Writing
import com.trollman.linernotes.lastfm.LastFm
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Immutable
data class GroupState(
    val ready: Boolean = false,
    val sets: List<GroupSet> = emptyList(),
    val openId: String? = null,
    val writing: Writing = Writing(),
    /** Weekly charts fetched so far while building a shortlist: (done, total). */
    val progress: Pair<Int, Int>? = null,
    val busy: Set<String> = emptySet(),
    val message: String? = null,
    val lastfmUser: String? = null,
    /** False on a build without Firebase: drafting, fit scores and playlists need the backend. */
    val online: Boolean = false,
) {
    val current: GroupSet? get() = sets.firstOrNull { it.id == openId }
}

/**
 * Group send (SPEC.md, "Group send"): the sets, the shortlist from Last.fm, picking and ordering,
 * Claude's drafts and the playlist. Sets save themselves a moment after each change.
 */
class GroupSendViewModel(app: Application) : AndroidViewModel(app) {

    private val graph = (app as LinerNotesApp).graph
    private val lastFm: LastFm = graph.lastFm

    private val _state = MutableStateFlow(GroupState(online = graph.cloud != null))
    val state: StateFlow<GroupState> = _state.asStateFlow()

    private var store: SetStore = LocalSetStore(app)
    private var startedFor: Pair<String?, String?>? = null
    private val saveJobs = mutableMapOf<String, Job>()
    private var writingJob: Job? = null
    private val albumInfo = mutableMapOf<String, LastFm.AlbumInfo>()
    private val trackPlays = mutableMapOf<String, Deferred<Map<String, Map<String, Int>>>>()

    /** Called whenever the account or Last.fm connection changes. Loads that person's sets. */
    fun start(uid: String?, lastfmUser: String?) {
        if (startedFor == (uid to lastfmUser)) return
        val accountChanged = startedFor?.first != uid
        startedFor = uid to lastfmUser
        _state.update { it.copy(lastfmUser = lastfmUser) }
        if (!accountChanged && _state.value.ready) return
        store = if (uid != null && graph.cloud != null) CloudSetStore(uid) else LocalSetStore(getApplication())
        viewModelScope.launch {
            val sets = runCatching { store.sets() }.onFailure { Log.w(TAG, "Loading sets failed", it) }.getOrDefault(emptyList())
            val writing = runCatching { store.writing() }.getOrDefault(Writing())
            _state.update { it.copy(ready = true, sets = sets, writing = writing) }
        }
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }
    private fun say(text: String) = _state.update { it.copy(message = text) }
    private fun busy(key: String, on: Boolean) = _state.update { it.copy(busy = if (on) it.busy + key else it.busy - key) }
    private fun set(id: String): GroupSet? = _state.value.sets.firstOrNull { it.id == id }

    /** Changes one set and saves it shortly after. */
    private fun edit(id: String, change: (GroupSet) -> GroupSet) {
        _state.update { s ->
            s.copy(sets = s.sets.map { if (it.id == id) change(it).copy(updatedAt = System.currentTimeMillis()) else it })
        }
        saveJobs[id]?.cancel()
        saveJobs[id] = viewModelScope.launch {
            delay(700)
            val latest = set(id) ?: return@launch
            runCatching { store.save(latest) }.onFailure {
                Log.w(TAG, "Saving set failed", it)
                say("Couldn't save just now. Your changes are kept here and will save on the next edit.")
            }
        }
    }

    private fun editEntry(id: String, key: String, change: (SetEntry) -> SetEntry) =
        edit(id) { s -> s.copy(entries = s.entries.map { if (it.key == key) change(it) else it }) }

    // ---- Sets -----------------------------------------------------------------------------------

    fun open(id: String?) = _state.update { it.copy(openId = id) }

    /** Makes a set and opens it. A year-end list runs from January 1 to today, 12 albums. */
    fun create(forName: String, taste: String, yearList: Boolean, months: Int, count: Int): String {
        val today = LocalDate.now()
        val from = if (yearList) today.withDayOfYear(1) else today.minusMonths(months.toLong())
        val set = GroupSet(
            title = if (yearList) GroupSet.yearListTitle(today.year) else "Albums for ${forName.ifBlank { "you" }}",
            forName = forName.trim(),
            taste = taste.trim(),
            fromDay = from.toString(),
            toDay = today.toString(),
            count = if (yearList) 12 else count,
            yearList = yearList,
            deliverOn = if (yearList) LocalDate.of(today.year, 12, 25).toString() else null,
        )
        _state.update { it.copy(sets = listOf(set) + it.sets, openId = set.id) }
        viewModelScope.launch { runCatching { store.save(set) }.onFailure { Log.w(TAG, "Saving new set failed", it) } }
        loadShortlist(set.id)
        return set.id
    }

    fun delete(id: String) {
        saveJobs.remove(id)?.cancel()
        _state.update { s -> s.copy(sets = s.sets.filterNot { it.id == id }, openId = if (s.openId == id) null else s.openId) }
        viewModelScope.launch { runCatching { store.delete(id) }.onFailure { Log.w(TAG, "Deleting set failed", it) } }
    }

    fun setTitle(id: String, title: String) = edit(id) { it.copy(title = title) }
    fun setForName(id: String, name: String) = edit(id) { it.copy(forName = name) }
    fun setTaste(id: String, taste: String) = edit(id) { it.copy(taste = taste) }
    fun setIntro(id: String, text: String) = edit(id) { it.copy(intro = text) }
    fun setClosing(id: String, text: String) = edit(id) { it.copy(closing = text) }
    fun setCount(id: String, count: Int) = edit(id) { it.copy(count = count.coerceIn(1, 30)) }

    // ---- Shortlist ------------------------------------------------------------------------------

    /** Ranks the sender's albums from the set's range on Last.fm, about 40 of them. */
    fun loadShortlist(id: String) {
        val s = set(id) ?: return
        val user = _state.value.lastfmUser
        if (user.isNullOrBlank()) {
            edit(id) { it.copy(shortlist = DemoAlbums.map { a -> SetAlbum(a.artist, a.title) }) }
            say("Connect Last.fm in Settings to rank your own listening. Showing sample albums for now.")
            return
        }
        val key = "shortlist:$id"
        if (key in _state.value.busy) return
        viewModelScope.launch {
            busy(key, true)
            try {
                val (from, to) = rangeSeconds(s)
                val weeks = lastFm.rangeCharts(user, from, to, onProgress = { done, total ->
                    _state.update { it.copy(progress = done to total) }
                }) { u, f, t -> weeklyAlbumChart(u, f, t) }
                val ranked = Ranking.rank(Ranking.aggregate(weeks))
                edit(id) { it.copy(shortlist = ranked) }
                if (ranked.isEmpty()) say("Nothing in that stretch on Last.fm. Is Spotify scrobbling to it?")
                fillCovers(id, ranked)
            } catch (e: Exception) {
                Log.w(TAG, "Shortlist failed", e)
                say("Couldn't read your listening from Last.fm: ${describeError(e)}")
            } finally {
                _state.update { it.copy(progress = null) }
                busy(key, false)
            }
        }
    }

    private fun rangeSeconds(s: GroupSet): Pair<Long, Long> {
        val zone = ZoneId.systemDefault()
        val from = s.from.atStartOfDay(zone).toEpochSecond()
        val to = s.to.plusDays(1).atStartOfDay(zone).toEpochSecond().coerceAtMost(System.currentTimeMillis() / 1000)
        return from to to
    }

    private suspend fun info(album: SetAlbum): LastFm.AlbumInfo? {
        albumInfo[album.key]?.let { return it }
        return runCatching { lastFm.albumInfo(album.artist, album.title) }.getOrNull()?.also { albumInfo[album.key] = it }
    }

    /** Weekly charts carry no covers, so they come from album.getInfo, four at a time. */
    private suspend fun fillCovers(id: String, albums: List<SetAlbum>) = coroutineScope {
        val gate = Semaphore(4)
        val covers = albums.filter { it.imageUrl == null }.map { a ->
            async { gate.withPermit { a.key to info(a)?.imageUrl } }
        }.awaitAll().filter { it.second != null }.toMap()
        if (covers.isEmpty()) return@coroutineScope
        edit(id) { s ->
            s.copy(
                shortlist = s.shortlist.map { a -> covers[a.key]?.let { a.copy(imageUrl = it) } ?: a },
                entries = s.entries.map { e -> covers[e.key]?.let { e.copy(album = e.album.copy(imageUrl = it)) } ?: e },
            )
        }
    }

    /** Claude's "fit for them" score for each shortlisted album, against the typed taste. */
    fun scoreFit(id: String) {
        val s = set(id) ?: return
        val cloud = graph.cloud ?: return say("Fit scores need the backend (docs/SETUP.md).")
        if (s.taste.isBlank()) return say("Describe what ${s.forName.ifBlank { "they" }} like first, then score the fit.")
        if (s.shortlist.isEmpty()) return
        viewModelScope.launch {
            busy("fit:$id", true)
            runCatching { cloud.scoreFit(s.taste, s.shortlist.map { it.artist to it.title }) }
                .onSuccess { scores ->
                    val fits = s.shortlist.zip(scores).mapNotNull { (a, f) -> f?.let { a.key to it } }.toMap()
                    edit(id) { it.copy(fit = it.fit + fits) }
                }
                .onFailure { e -> say("Fit scores didn't work: ${describeError(e)}") }
            busy("fit:$id", false)
        }
    }

    // ---- Picking and ordering -------------------------------------------------------------------

    fun togglePick(id: String, album: SetAlbum) {
        val s = set(id) ?: return
        if (s.picked(album.key)) {
            edit(id) { it.copy(entries = it.entries.filterNot { e -> e.key == album.key }) }
        } else {
            edit(id) { it.copy(entries = it.entries + SetEntry(album)) }
            loadFavoriteTracks(id, album)
        }
    }

    fun move(id: String, index: Int, delta: Int) = edit(id) { s ->
        val to = index + delta
        if (index !in s.entries.indices || to !in s.entries.indices) s
        else s.copy(entries = s.entries.toMutableList().apply { add(to, removeAt(index)) })
    }

    private fun trackPlaysFor(s: GroupSet, user: String): Deferred<Map<String, Map<String, Int>>> =
        trackPlays.getOrPut("$user:${s.fromDay}:${s.toDay}") {
            viewModelScope.async(start = CoroutineStart.LAZY) {
                val (from, to) = rangeSeconds(s)
                Ranking.trackPlays(lastFm.rangeCharts(user, from, to) { u, f, t -> weeklyTrackChart(u, f, t) })
            }
        }

    /** Fills an entry's Favorite Tracks from the sender's most-played tracks on it. */
    private fun loadFavoriteTracks(id: String, album: SetAlbum) {
        val user = _state.value.lastfmUser ?: return
        val s = set(id) ?: return
        viewModelScope.launch {
            val tracks = info(album)?.tracks.orEmpty()
            if (tracks.isEmpty()) return@launch
            val plays = runCatching { trackPlaysFor(s, user).await() }.getOrElse {
                trackPlays.remove("$user:${s.fromDay}:${s.toDay}")
                return@launch
            }
            val favorites = Ranking.favoriteTracks(album.artist, tracks, plays)
            editEntry(id, album.key) { e -> if (e.favoriteTracks.isEmpty()) e.copy(favoriteTracks = favorites) else e }
        }
    }

    fun setFavoriteTracks(id: String, key: String, tracks: List<String>) =
        editEntry(id, key) { it.copy(favoriteTracks = tracks) }

    // ---- Notes ----------------------------------------------------------------------------------

    fun setNote(id: String, key: String, text: String) = editEntry(id, key) {
        it.copy(note = text, state = if (text.isBlank()) NoteState.Empty else if (text == it.aiDraft) NoteState.Drafted else NoteState.Edited)
    }

    fun restoreDraft(id: String, key: String) = editEntry(id, key) {
        if (it.aiDraft.isBlank()) it else it.copy(note = it.aiDraft, state = NoteState.Drafted, rev = it.rev + 1)
    }

    private fun rangeLabel(s: GroupSet): String {
        val f = DateTimeFormatter.ofPattern("MMMM d", Locale.getDefault())
        return if (s.yearList) "this year, since January 1" else "between ${s.from.format(f)} and ${s.to.format(f)}"
    }

    /** Drafts (or re-drafts, with an optional instruction) one album's note. */
    fun draftNote(id: String, key: String, instruction: String? = null) {
        viewModelScope.launch { draftOne(id, key, instruction) }
    }

    private suspend fun draftOne(id: String, key: String, instruction: String?) {
        val cloud = graph.cloud ?: return say("Drafting needs the backend (docs/SETUP.md).")
        val s = set(id) ?: return
        val index = s.entries.indexOfFirst { it.key == key }
        val entry = s.entries.getOrNull(index) ?: return
        val busyKey = "note:$id:$key"
        if (busyKey in _state.value.busy) return
        busy(busyKey, true)
        try {
            val w = _state.value.writing
            val draft = cloud.draftNote(
                artist = entry.album.artist,
                album = entry.album.title,
                writing = w,
                recipientName = s.forName,
                taste = s.taste,
                plays = entry.album.plays,
                weeks = entry.album.weeks,
                rangeLabel = rangeLabel(s),
                favoriteTracks = entry.favoriteTracks,
                isCloser = s.yearList && s.entries.size > 1 && index == s.entries.lastIndex,
                instruction = instruction?.takeIf { it.isNotBlank() },
                previous = entry.note.takeIf { instruction != null && it.isNotBlank() },
            )
            if (draft.note.isBlank()) {
                say("The draft for ${entry.album.title} came back empty. Try again.")
            } else {
                editEntry(id, key) {
                    it.copy(note = draft.note, aiDraft = draft.note, flags = draft.flags, sources = draft.sources, state = NoteState.Drafted, rev = it.rev + 1)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Draft failed", e)
            say("Couldn't draft ${entry.album.title}: ${describeError(e)}")
        } finally {
            busy(busyKey, false)
        }
    }

    /** Drafts every note that's still empty, three at a time. */
    fun draftAll(id: String) {
        val s = set(id) ?: return
        if (graph.cloud == null) return say("Drafting needs the backend (docs/SETUP.md).")
        val empty = s.entries.filter { it.state == NoteState.Empty }
        if (empty.isEmpty()) return say("Every album has a note already.")
        viewModelScope.launch {
            busy("all:$id", true)
            val gate = Semaphore(3)
            empty.map { e -> launch { gate.withPermit { draftOne(id, e.key, null) } } }.forEach { it.join() }
            busy("all:$id", false)
        }
    }

    /** Drafts the intro or closing note from last year's. */
    fun draftFrame(id: String, kind: String, instruction: String? = null) {
        val cloud = graph.cloud ?: return say("Drafting needs the backend (docs/SETUP.md).")
        val s = set(id) ?: return
        val w = _state.value.writing
        viewModelScope.launch {
            busy("frame:$id:$kind", true)
            runCatching {
                cloud.draftFrame(
                    kind = kind,
                    writing = w,
                    recipientName = s.forName,
                    year = s.year,
                    title = s.title,
                    albums = s.entries.map { it.album.heading },
                    lastText = if (kind == "closing") w.lastClosing else w.lastIntro,
                    instruction = instruction?.takeIf { it.isNotBlank() },
                    previous = (if (kind == "closing") s.closing else s.intro).takeIf { instruction != null && it.isNotBlank() },
                )
            }.onSuccess { text ->
                if (text.isNotBlank()) edit(id) {
                    if (kind == "closing") it.copy(closing = text, frameRev = it.frameRev + 1) else it.copy(intro = text, frameRev = it.frameRev + 1)
                }
            }.onFailure { e -> say("Couldn't draft the $kind: ${describeError(e)}") }
            busy("frame:$id:$kind", false)
        }
    }

    // ---- Playlist -------------------------------------------------------------------------------

    /** Finds each album on Spotify and makes a private playlist of the whole set, in order. */
    fun makePlaylist(id: String) {
        val cloud = graph.cloud ?: return say("Playlists need the backend (docs/SETUP.md).")
        val s = set(id) ?: return
        if (s.entries.isEmpty()) return say("Pick some albums first.")
        viewModelScope.launch {
            busy("playlist:$id", true)
            try {
                val missing = mutableListOf<String>()
                val uris = s.entries.map { e ->
                    e.album.spotifyUri ?: runCatching { cloud.findSpotifyAlbum(e.album.artist, e.album.title) }.getOrNull()?.uri.also { uri ->
                        if (uri != null) {
                            editEntry(id, e.key) { it.copy(album = it.album.copy(spotifyUri = uri)) }
                        } else {
                            missing.add(e.album.title)
                        }
                    }
                }.filterNotNull()
                if (uris.isEmpty()) {
                    say("Couldn't find any of these on Spotify.")
                    return@launch
                }
                val description = "${s.title}, for ${s.forName.ifBlank { "you" }}. Made with Liner Notes."
                val url = cloud.makeSetPlaylist(s.title, description, uris)
                edit(id) { it.copy(playlistUrl = url.ifBlank { null }) }
                say(
                    if (missing.isEmpty()) "Made a private playlist in your Spotify: ${s.title}."
                    else "Made the playlist, without ${missing.joinToString()} (not found on Spotify)."
                )
            } catch (e: Exception) {
                say("The playlist didn't work: ${describeError(e)}")
            } finally {
                busy("playlist:$id", false)
            }
        }
    }

    // ---- Writing settings -----------------------------------------------------------------------

    fun saveWriting(writing: Writing) {
        _state.update { it.copy(writing = writing) }
        writingJob?.cancel()
        writingJob = viewModelScope.launch {
            delay(700)
            runCatching { store.saveWriting(writing) }.onFailure { Log.w(TAG, "Saving writing failed", it) }
        }
    }

    companion object {
        private const val TAG = "GroupSend"
    }
}

/** A short reason for a failed call, in words for the person. */
fun describeError(e: Throwable): String = when (e) {
    is FirebaseFunctionsException -> when (e.code) {
        FirebaseFunctionsException.Code.NOT_FOUND, FirebaseFunctionsException.Code.UNIMPLEMENTED ->
            "the backend isn't deployed yet (docs/SETUP.md)."
        FirebaseFunctionsException.Code.UNAUTHENTICATED -> "you're signed out. Sign in and try again."
        FirebaseFunctionsException.Code.UNAVAILABLE -> "couldn't reach the backend. Check the connection."
        FirebaseFunctionsException.Code.DEADLINE_EXCEEDED -> "it took too long. Try again."
        else -> e.message ?: e.code.name
    }
    else -> e.message ?: (e::class.simpleName ?: "unknown error")
}
