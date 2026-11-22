package com.trollman.linernotes.lastfm

import android.net.Uri
import com.trollman.linernotes.data.Album
import com.trollman.linernotes.data.ChartAlbum
import com.trollman.linernotes.data.ChartTrack
import com.trollman.linernotes.data.Period
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Last.fm, the app's listening source (SPEC.md, "Spotify integration and its limits").
 *
 * Connecting is Last.fm's own web approval: the app opens [approvalUrl] in a Custom Tab, Last.fm
 * sends the person back to the Hosting callback with a token, and the lastfmConnect function
 * signs it with the shared secret for a session key. No password is typed into the app.
 *
 * Reading someone's top albums needs only the public API key, so the shelf reads them straight
 * from Last.fm. The monthly snapshots (Phase 1) run in Functions instead.
 */
class LastFm(private val apiKey: String, private val callbackUrl: String) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    val approvalUrl: String
        get() = Uri.parse("https://www.last.fm/api/auth/").buildUpon()
            .appendQueryParameter("api_key", apiKey)
            .appendQueryParameter("cb", callbackUrl)
            .build().toString()

    /** The person's most-played albums over [period], most played first. */
    suspend fun topAlbums(user: String, period: Period, limit: Int = 50): List<Album> =
        withContext(Dispatchers.IO) {
            val url = "https://ws.audioscrobbler.com/2.0/".toHttpUrl().newBuilder()
                .addQueryParameter("method", "user.gettopalbums")
                .addQueryParameter("user", user)
                .addQueryParameter("period", period.apiValue)
                .addQueryParameter("limit", limit.toString())
                .addQueryParameter("api_key", apiKey)
                .addQueryParameter("format", "json")
                .build()
            val request = Request.Builder().url(url)
                .header("User-Agent", "LinerNotes/0.1 (Android)")
                .build()
            http.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                parseTopAlbums(body)
            }
        }

    /** One Last.fm API call, parsed. Throws [IOException] with Last.fm's own message on an error. */
    private suspend fun get(method: String, params: Map<String, String>): JsonObject = withContext(Dispatchers.IO) {
        val url = "https://ws.audioscrobbler.com/2.0/".toHttpUrl().newBuilder()
            .addQueryParameter("method", method)
            .apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }
            .addQueryParameter("api_key", apiKey)
            .addQueryParameter("format", "json")
            .build()
        val request = Request.Builder().url(url).header("User-Agent", "LinerNotes/0.1 (Android)").build()
        http.newCall(request).execute().use { response -> parseRoot(response.body?.string().orEmpty()) }
    }

    /** The weeks Last.fm keeps charts for, as (from, to) in Unix seconds. */
    suspend fun weeklyChartList(user: String): List<Pair<Long, Long>> =
        parseChartList(get("user.getweeklychartlist", mapOf("user" to user)))

    suspend fun weeklyAlbumChart(user: String, from: Long, to: Long): List<ChartAlbum> =
        parseWeeklyAlbums(get("user.getweeklyalbumchart", mapOf("user" to user, "from" to "$from", "to" to "$to")))

    suspend fun weeklyTrackChart(user: String, from: Long, to: Long): List<ChartTrack> =
        parseWeeklyTracks(get("user.getweeklytrackchart", mapOf("user" to user, "from" to "$from", "to" to "$to")))

    /** An album's cover and tracklist. */
    suspend fun albumInfo(artist: String, title: String): AlbumInfo =
        parseAlbumInfo(get("album.getinfo", mapOf("artist" to artist, "album" to title, "autocorrect" to "1")))

    /**
     * Every weekly chart overlapping [fromSec]..[toSec], fetched four at a time, plus the
     * unfinished current week where Last.fm will give it. [onProgress] gets (done, total).
     */
    suspend fun <T> rangeCharts(
        user: String,
        fromSec: Long,
        toSec: Long,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
        fetch: suspend LastFm.(String, Long, Long) -> List<T>,
    ): List<List<T>> = coroutineScope {
        val weeks = weeklyChartList(user).filter { (f, t) -> t > fromSec && f < toSec }.toMutableList()
        val lastEnd = weeks.maxOfOrNull { it.second } ?: fromSec
        if (lastEnd < toSec) weeks += lastEnd to toSec
        val gate = Semaphore(4)
        var done = 0
        onProgress(0, weeks.size)
        weeks.map { (f, t) ->
            async {
                gate.withPermit {
                    val chart = runCatching { this@LastFm.fetch(user, f, t) }.getOrElse { e ->
                        // The open-ended last week is a bonus; a failed full week is a real error.
                        if (t == toSec && f == lastEnd) emptyList() else throw e
                    }
                    synchronized(weeks) { done++; onProgress(done, weeks.size) }
                    chart
                }
            }
        }.awaitAll()
    }

    data class AlbumInfo(val imageUrl: String?, val tracks: List<String>, val url: String?)

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        fun parseRoot(body: String): JsonObject {
            val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
                ?: throw IOException("Last.fm sent something unreadable")
            root["error"]?.let {
                val message = root.str("message") ?: "error ${it.jsonPrimitive.contentOrNull}"
                throw IOException("Last.fm: $message")
            }
            return root
        }

        /** A Last.fm list that may come back as an array, a single object, or not at all. */
        private fun items(el: JsonElement?): List<JsonObject> = when (el) {
            is JsonArray -> el.mapNotNull { it as? JsonObject }
            is JsonObject -> listOf(el)
            else -> emptyList()
        }

        private fun JsonObject.int(key: String): Int =
            (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() } ?: 0

        /** Artist name from either {"#text": ...} (charts) or {"name": ...} (top lists). */
        private fun JsonObject.artistName(): String? = when (val a = this["artist"]) {
            is JsonObject -> a.str("#text") ?: a.str("name")
            is kotlinx.serialization.json.JsonPrimitive -> a.contentOrNull
            else -> null
        }

        fun parseChartList(root: JsonObject): List<Pair<Long, Long>> =
            items((root["weeklychartlist"] as? JsonObject)?.get("chart")).mapNotNull { c ->
                val f = c.str("from")?.toLongOrNull() ?: return@mapNotNull null
                val t = c.str("to")?.toLongOrNull() ?: return@mapNotNull null
                f to t
            }

        fun parseWeeklyAlbums(root: JsonObject): List<ChartAlbum> =
            items((root["weeklyalbumchart"] as? JsonObject)?.get("album")).mapNotNull { a ->
                val title = a.str("name")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val artist = a.artistName()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                ChartAlbum(artist, title, a.str("mbid")?.ifBlank { null }, a.int("playcount"))
            }

        fun parseWeeklyTracks(root: JsonObject): List<ChartTrack> =
            items((root["weeklytrackchart"] as? JsonObject)?.get("track")).mapNotNull { t ->
                val title = t.str("name")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val artist = t.artistName()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                ChartTrack(artist, title, t.int("playcount"))
            }

        fun parseAlbumInfo(root: JsonObject): AlbumInfo {
            val album = root["album"] as? JsonObject ?: return AlbumInfo(null, emptyList(), null)
            val image = (album["image"] as? JsonArray)
                ?.mapNotNull { (it as? JsonObject)?.str("#text") }
                ?.lastOrNull { it.isNotBlank() }
                ?.takeUnless { it.contains(PLACEHOLDER_IMAGE) }
            val tracks = items((album["tracks"] as? JsonObject)?.get("track")).mapNotNull { it.str("name") }
            return AlbumInfo(image, tracks, album.str("url"))
        }

        // Last.fm's stand-in when it has no cover: a grey star. Better to draw our own sleeve.
        private const val PLACEHOLDER_IMAGE = "2a96cbd8b46e442fc41c2b86b821562f"

        /** Parses user.getTopAlbums. Throws [IOException] with Last.fm's own message on an error. */
        fun parseTopAlbums(body: String): List<Album> {
            val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
                ?: throw IOException("Last.fm sent something unreadable")
            (root["error"])?.let {
                val message = root.str("message") ?: "error ${it.jsonPrimitive.contentOrNull}"
                throw IOException("Last.fm: $message")
            }
            val albums = (root["topalbums"] as? JsonObject)?.get("album")
            val list: List<JsonElement> = when (albums) {
                is JsonArray -> albums
                is JsonObject -> listOf(albums) // a single album comes back as an object
                else -> emptyList()
            }
            return list.mapNotNull { el ->
                val a = el as? JsonObject ?: return@mapNotNull null
                val title = a.str("name") ?: return@mapNotNull null
                val artist = (a["artist"] as? JsonObject)?.str("name") ?: return@mapNotNull null
                val image = (a["image"] as? JsonArray)
                    ?.mapNotNull { (it as? JsonObject)?.str("#text") }
                    ?.lastOrNull { it.isNotBlank() }
                    ?.takeUnless { it.contains(PLACEHOLDER_IMAGE) }
                Album(
                    artist = artist,
                    title = title,
                    playCount = a["playcount"]?.jsonPrimitive?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() } ?: 0,
                    imageUrl = image,
                    mbid = a.str("mbid")?.ifBlank { null },
                    lastfmUrl = a.str("url"),
                )
            }
        }

        private fun JsonObject.str(key: String): String? =
            (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
    }
}
