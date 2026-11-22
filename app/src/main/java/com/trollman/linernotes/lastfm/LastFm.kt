package com.trollman.linernotes.lastfm

import android.net.Uri
import com.trollman.linernotes.data.Album
import com.trollman.linernotes.data.Period
import kotlinx.coroutines.Dispatchers
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

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

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
