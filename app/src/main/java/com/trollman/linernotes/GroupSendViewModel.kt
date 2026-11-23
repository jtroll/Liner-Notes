package com.trollman.linernotes

import android.app.Application
import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.functions.FirebaseFunctionsException
import com.trollman.linernotes.data.Candidate
import com.trollman.linernotes.data.CloudSetStore
import com.trollman.linernotes.data.Invite
import com.trollman.linernotes.data.Monthly
import com.trollman.linernotes.data.PairInfo
import com.trollman.linernotes.data.Pick
import java.time.YearMonth
import com.trollman.linernotes.data.DemoAlbums
import com.trollman.linernotes.data.GroupSet
import com.trollman.linernotes.data.LocalSetStore
import com.trollman.linernotes.data.NoteState
import com.trollman.linernotes.data.Received
import com.trollman.linernotes.data.SendInfo
import com.trollman.linernotes.data.forRecipient
import com.trollman.linernotes.data.storeJson
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
    /** The sender's live sends, by set id (the test sends to oneself left out). */
    val sends: Map<String, SendInfo> = emptyMap(),
    /** Sets delivered to this person, newest first. */
    val inbox: List<Received> = emptyList(),
    val inboxReady: Boolean = false,
    /** Every live send of this person's, test copies included, by send id. */
    val sendsById: Map<String, SendInfo> = emptyMap(),
    /** The pairs this person is in (SPEC.md, "Pairs and accounts"). */
    val pairs: List<PairInfo> = emptyList(),
    val pairsReady: Boolean = false,
    /** Album search results for picking any album for a monthly send. */
    val search: List<SetAlbum>? = null,
    /** The last standalone invite link made from the pairs screen. */
    val pairInvite: Invite? = null,
) {
    val current: GroupSet? get() = sets.firstOrNull { it.id == openId }
    val groupSets: List<GroupSet> get() = sets.filterNot { it.isMonthly }

    /** This month's monthly set for a pair, if one has been started. */
    fun monthlyFor(pairId: String, month: String = Monthly.monthKey()): GroupSet? =
        sets.firstOrNull { it.isMonthly && it.pairId == pairId && it.month == month }

    /** The sender's monthly send to a pair for a month, once sent. */
    fun sentMonthly(pairId: String, month: String = Monthly.monthKey()): SendInfo? =
        sendsById.values.firstOrNull { it.kind == "monthly" && it.pairId == pairId && it.month == month && !it.test }

    /** Sends both ways within a pair, newest first, as the recipient sees them. */
    fun history(pairId: String): List<Received> =
        (inbox.filter { it.pairId == pairId && !it.test } +
            sendsById.values.filter { it.pairId == pairId && it.delivered && !it.test }.mapNotNull { it.asReceived() })
            .sortedByDescending { it.deliveredAt }
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
    private var liveJobs: List<Job> = emptyList()

    /** Called whenever the account or Last.fm connection changes. Loads that person's sets. */
    fun start(uid: String?, lastfmUser: String?) {
        if (startedFor == (uid to lastfmUser)) return
        val accountChanged = startedFor?.first != uid
        startedFor = uid to lastfmUser
        _state.update { it.copy(lastfmUser = lastfmUser) }
        if (!accountChanged && _state.value.ready) return
        store = if (uid != null && graph.cloud != null) CloudSetStore(uid) else LocalSetStore(getApplication())
        watchSends(uid)
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
    /** [touch] false leaves updatedAt alone, for bookkeeping that isn't a change to the set. */
    private fun edit(id: String, touch: Boolean = true, change: (GroupSet) -> GroupSet) {
        _state.update { s ->
            s.copy(sets = s.sets.map {
                if (it.id != id) it else change(it).let { c -> if (touch) c.copy(updatedAt = System.currentTimeMillis()) else c }
            })
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
        if (s.isMonthly && s.month != null) return "in ${Monthly.monthName(Monthly.listeningMonth(s.month))}"
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
                isMonthly = s.isMonthly,
                connection = entry.connection.ifBlank { null },
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

    // ---- Sending --------------------------------------------------------------------------------

    /** Follows this person's sends and inbox in Firestore. */
    private fun watchSends(uid: String?) {
        liveJobs.forEach { it.cancel() }
        _state.update { it.copy(sends = emptyMap(), sendsById = emptyMap(), inbox = emptyList(), inboxReady = false, pairs = emptyList(), pairsReady = false) }
        val cloud = graph.cloud
        if (uid == null || cloud == null) {
            liveJobs = emptyList()
            _state.update { it.copy(inboxReady = true, pairsReady = true) }
            return
        }
        liveJobs = listOf(
            viewModelScope.launch {
                cloud.sendsFrom(uid).collect { list ->
                    _state.update { s ->
                        s.copy(sends = list.filter { !it.test }.associateBy { it.setId }, sendsById = list.associateBy { it.id })
                    }
                }
            },
            viewModelScope.launch {
                cloud.pairs(uid).collect { list -> _state.update { it.copy(pairs = list, pairsReady = true) } }
            },
            viewModelScope.launch {
                cloud.inbox(uid).collect { list -> _state.update { it.copy(inbox = list, inboxReady = true) } }
            },
        )
    }

    /** How the sender signs: their writing settings' name, else their Google first name. */
    private fun fromName(): String =
        _state.value.writing.senderName.ifBlank { graph.auth?.current?.displayName?.substringBefore(' ').orEmpty() }

    /** Makes the invite link the set goes out on. */
    fun makeInvite(id: String) {
        val cloud = graph.cloud ?: return say("Invites need the backend (docs/SETUP.md).")
        val s = set(id) ?: return
        viewModelScope.launch {
            busy("invite:$id", true)
            try {
                val invite = cloud.createInvite(s.forName, fromName())
                edit(id, touch = false) { it.copy(inviteToken = invite.token, inviteUrl = invite.url) }
            } catch (e: Exception) {
                say("Couldn't make the invite: ${describeError(e)}")
            } finally {
                busy("invite:$id", false)
            }
        }
    }

    /** Schedules the set as it is now for [deliverAt] (epoch millis), or updates the scheduled copy. */
    fun schedule(id: String, deliverAt: Long) = send(id, deliverAt, test = false)

    /** Delivers a copy to the sender's own inbox right away, to see it as the recipient will. */
    fun sendTest(id: String) = send(id, System.currentTimeMillis(), test = true)

    private fun send(id: String, deliverAt: Long, test: Boolean) {
        val cloud = graph.cloud ?: return say("Sending needs the backend (docs/SETUP.md).")
        val s = set(id) ?: return
        if (s.entries.isEmpty()) return say("Pick some albums first.")
        if (!test && s.inviteToken == null) return say("Make the invite link first.")
        val key = if (test) "test:$id" else "send:$id"
        viewModelScope.launch {
            busy(key, true)
            try {
                val json = storeJson.encodeToString(GroupSet.serializer(), s.forRecipient())
                val r = cloud.scheduleSend(id, json, deliverAt, s.inviteToken, fromName(), test)
                if (!test) edit(id, touch = false) { it.copy(deliverAt = r.deliverAt, scheduledVersion = s.updatedAt) }
                val name = s.forName.ifBlank { "them" }
                say(
                    when {
                        test -> "A copy is in your inbox, as $name will see it."
                        r.status == "delivered" -> "Delivered to $name."
                        r.waitingForJoin -> "Scheduled for ${whenText(r.deliverAt)}. It reaches $name once they join with your invite."
                        else -> "Scheduled for ${whenText(r.deliverAt)}."
                    }
                )
            } catch (e: Exception) {
                say("Couldn't send it: ${describeError(e)}")
            } finally {
                busy(key, false)
            }
        }
    }

    fun cancelSend(id: String) {
        val cloud = graph.cloud ?: return
        viewModelScope.launch {
            busy("send:$id", true)
            try {
                cloud.cancelSend(id)
                edit(id, touch = false) { it.copy(scheduledVersion = null) }
                say("Cancelled. Nothing will be delivered.")
            } catch (e: Exception) {
                say("Couldn't cancel it: ${describeError(e)}")
            } finally {
                busy("send:$id", false)
            }
        }
    }

    // ---- Monthly send (SPEC.md, "Monthly send") --------------------------------------------------

    /**
     * Opens this month's pick for [pair], starting it (and asking for a suggestion) the first
     * time. Returns the set's id.
     */
    fun openMonthly(pair: PairInfo): String {
        val month = Monthly.monthKey()
        _state.value.monthlyFor(pair.id, month)?.let { existing ->
            if (existing.picks.isEmpty() && existing.entries.isEmpty() && _state.value.sentMonthly(pair.id, month) == null) suggest(existing.id)
            return existing.id
        }
        val listening = Monthly.listeningMonth(month)
        val set = GroupSet(
            title = "${Monthly.monthName(month)} pick",
            forName = pair.partnerName,
            fromDay = listening.atDay(1).toString(),
            toDay = listening.atEndOfMonth().toString(),
            count = 1,
            kind = "monthly",
            pairId = pair.id,
            month = month,
        )
        _state.update { it.copy(sets = listOf(set) + it.sets) }
        viewModelScope.launch { runCatching { store.save(set) }.onFailure { Log.w(TAG, "Saving monthly set failed", it) } }
        if (_state.value.sentMonthly(pair.id, month) == null) suggest(set.id)
        return set.id
    }

    /**
     * Step 3 and 4: the albums the sender played heavily last month (widened to 3 months when
     * there are fewer than 3), then Claude's pick and two alternates. The pick's note is drafted
     * straight away.
     */
    fun suggest(id: String) {
        val s = set(id) ?: return
        val pairId = s.pairId ?: return
        val month = s.month ?: return
        val user = _state.value.lastfmUser
        if (user.isNullOrBlank()) return say("Connect Last.fm in Settings so the app can see what you played last month.")
        val cloud = graph.cloud ?: return say("Suggestions need the backend (docs/SETUP.md).")
        val key = "suggest:$id"
        if (key in _state.value.busy) return
        viewModelScope.launch {
            busy(key, true)
            try {
                val listening = Monthly.listeningMonth(month)
                val candidates = monthlyCandidates(user, listening)
                if (candidates.isEmpty()) {
                    say("Nothing played heavily in ${Monthly.monthName(listening)} or the months before. Search for any album instead.")
                    return@launch
                }
                val result = cloud.monthlySuggest(pairId, candidates, fromName(), Monthly.monthName(listening))
                if (result.picks.isEmpty()) {
                    say("Everything you played a lot is already known to ${s.forName.ifBlank { "them" }} or already sent. Search for any album instead.")
                    return@launch
                }
                edit(id) { it.copy(picks = result.picks, stretch = result.stretch) }
                fillPickCovers(id)
                val current = set(id)?.entries?.firstOrNull()
                if (current == null || current.note.isBlank()) choose(id, result.picks.first())
            } catch (e: Exception) {
                Log.w(TAG, "Suggestion failed", e)
                say("Couldn't suggest an album: ${describeError(e)}")
            } finally {
                _state.update { it.copy(progress = null) }
                busy(key, false)
            }
        }
    }

    private suspend fun monthlyCandidates(user: String, listening: YearMonth): List<Candidate> {
        val zone = ZoneId.systemDefault()
        val end = listening.plusMonths(1).atDay(1).atStartOfDay(zone).toEpochSecond()
        val start = listening.atDay(1).atStartOfDay(zone).toEpochSecond()
        val last = Ranking.aggregate(lastFm.rangeCharts(user, start, end, onProgress = { d, t ->
            _state.update { it.copy(progress = d to t) }
        }) { u, f, t -> weeklyAlbumChart(u, f, t) })
        val tracks = trackCounts(last.filter { it.plays < Monthly.MIN_TRACK_PLAYS }.sortedByDescending { it.plays }.take(15))
        val recent = Monthly.candidates(last, null, tracks)
        if (recent.size >= Monthly.MIN_CANDIDATES) return recent
        val quarterStart = listening.minusMonths(2).atDay(1).atStartOfDay(zone).toEpochSecond()
        val quarter = Ranking.aggregate(lastFm.rangeCharts(user, quarterStart, end) { u, f, t -> weeklyAlbumChart(u, f, t) })
        val more = trackCounts(quarter.filter { it.plays < Monthly.MIN_TRACK_PLAYS }.sortedByDescending { it.plays }.take(15))
        return Monthly.candidates(last, quarter, tracks + more)
    }

    /** Track counts from album.getInfo, four at a time, for the near-full-plays rule. */
    private suspend fun trackCounts(albums: List<SetAlbum>): Map<String, Int> = coroutineScope {
        val gate = Semaphore(4)
        albums.map { a -> async { gate.withPermit { a.key to (info(a)?.tracks?.size ?: 0) } } }
            .awaitAll().filter { it.second > 0 }.toMap()
    }

    private suspend fun fillPickCovers(id: String) = coroutineScope {
        val s = set(id) ?: return@coroutineScope
        val gate = Semaphore(4)
        val covers = s.picks.map { it.album }.filter { it.imageUrl == null }.map { a ->
            async { gate.withPermit { a.key to info(a)?.imageUrl } }
        }.awaitAll().filter { it.second != null }.toMap()
        if (covers.isEmpty()) return@coroutineScope
        edit(id, touch = false) { set ->
            set.copy(
                picks = set.picks.map { p -> covers[p.album.key]?.let { p.copy(album = p.album.copy(imageUrl = it)) } ?: p },
                entries = set.entries.map { e -> covers[e.key]?.let { e.copy(album = e.album.copy(imageUrl = it)) } ?: e },
            )
        }
    }

    /**
     * Makes [pick] the album to send. The album being replaced is kept with its note, so
     * flipping back to it loses nothing; an album without a note gets one drafted.
     */
    fun choose(id: String, pick: Pick) {
        val s = set(id) ?: return
        if (s.entries.firstOrNull()?.key == pick.album.key) return
        val parked = s.parked.firstOrNull { it.key == pick.album.key }
        val entry = parked ?: SetEntry(pick.album, connection = pick.connection)
        edit(id) { set ->
            val leaving = set.entries.filter { it.note.isNotBlank() }
            set.copy(
                entries = listOf(entry),
                parked = (set.parked.filterNot { it.key == pick.album.key || leaving.any { l -> l.key == it.key } } + leaving).takeLast(10),
            )
        }
        if (entry.favoriteTracks.isEmpty()) loadFavoriteTracks(id, pick.album)
        if (entry.note.isBlank()) draftNote(id, pick.album.key)
    }

    /** Searches Last.fm for any album to send instead of a suggestion. */
    fun search(query: String) {
        if (query.isBlank()) return _state.update { it.copy(search = null) }
        viewModelScope.launch {
            busy("search", true)
            runCatching { lastFm.albumSearch(query.trim()) }
                .onSuccess { list -> _state.update { it.copy(search = list.map { a -> SetAlbum(a.artist, a.title, imageUrl = a.imageUrl, mbid = a.mbid) }) } }
                .onFailure { e -> say("Search didn't work: ${describeError(e)}") }
            busy("search", false)
        }
    }

    fun clearSearch() = _state.update { it.copy(search = null) }

    /** Sends the monthly pick now. The recipient gets it straight away, with a notification. */
    fun sendMonthly(id: String) {
        val cloud = graph.cloud ?: return say("Sending needs the backend (docs/SETUP.md).")
        val s = set(id) ?: return
        val entry = s.entries.firstOrNull() ?: return say("Pick an album first.")
        if (entry.note.isBlank()) return say("Write a note first, or let Claude draft one.")
        val key = "send:$id"
        if (key in _state.value.busy) return
        viewModelScope.launch {
            busy(key, true)
            try {
                val json = storeJson.encodeToString(GroupSet.serializer(), s.forRecipient())
                cloud.scheduleSend(id, json, System.currentTimeMillis(), null, fromName(), false, s.pairId, "monthly", s.month)
                edit(id, touch = false) { it.copy(scheduledVersion = s.updatedAt) }
                say("Sent ${entry.album.title} to ${s.forName.ifBlank { "them" }}.")
            } catch (e: Exception) {
                say("Couldn't send it: ${describeError(e)}")
            } finally {
                busy(key, false)
            }
        }
    }

    /** The recipient's reaction to one album of a send: "loved", "liked", "notForMe" or null. */
    fun react(sendId: String, index: Int, reaction: String?, comment: String) {
        val cloud = graph.cloud ?: return
        viewModelScope.launch {
            busy("react:$sendId:$index", true)
            runCatching { cloud.react(sendId, index, reaction, comment.trim()) }
                .onSuccess { if (comment.isNotBlank()) say("Sent your comment.") }
                .onFailure { e -> say("Couldn't save that: ${describeError(e)}") }
            busy("react:$sendId:$index", false)
        }
    }

    /** Changes a pair's settings (monthlyOn, draftDay, decemberPause, nickname). */
    fun updatePair(pairId: String, changes: Map<String, Any>) {
        val cloud = graph.cloud ?: return
        viewModelScope.launch {
            runCatching { cloud.updatePair(pairId, changes) }.onFailure { e -> say("Couldn't change that: ${describeError(e)}") }
        }
    }

    /** An invite link that forms a pair without a set riding on it. */
    fun makePairInvite(forName: String) {
        val cloud = graph.cloud ?: return say("Invites need the backend (docs/SETUP.md).")
        viewModelScope.launch {
            busy("pairInvite", true)
            runCatching { cloud.createInvite(forName.trim(), fromName()) }
                .onSuccess { invite -> _state.update { it.copy(pairInvite = invite) } }
                .onFailure { e -> say("Couldn't make the invite: ${describeError(e)}") }
            busy("pairInvite", false)
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

private val whenFormat = DateTimeFormatter.ofPattern("EEE MMM d, h:mm a", Locale.getDefault())

/** "Fri Dec 25, 8:00 AM", in the phone's time zone. */
fun whenText(millis: Long): String =
    java.time.Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(whenFormat)

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
