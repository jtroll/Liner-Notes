package com.trollman.linernotes.data

import androidx.compose.runtime.Immutable

/** One album from someone's listening, as Last.fm reports it. */
@Immutable
data class Album(
    val artist: String,
    val title: String,
    val playCount: Int,
    val imageUrl: String?,
    val mbid: String? = null,
    val lastfmUrl: String? = null,
) {
    /** "Artist — Album", keeping the artist's own styling, as the booklet headings do. */
    val heading: String get() = "$artist — $title"
    val key: String get() = "${artist.lowercase()}\u0000${title.lowercase()}"
}

/** The Spotify release an [Album] was matched to. */
@Immutable
data class SpotifyAlbum(
    val uri: String,
    val name: String,
    val artist: String,
    val imageUrl: String?,
    val webUrl: String?,
)

/** What the backend knows about the signed-in person's connections. */
@Immutable
data class Profile(
    val lastfmUsername: String? = null,
    val spotifyName: String? = null,
    val spotifyConnectedAt: Long? = null,
)

/** Last.fm's listening windows. */
enum class Period(val apiValue: String, val label: String, val longLabel: String) {
    Month("1month", "1M", "the last month"),
    Quarter("3month", "3M", "the last 3 months"),
    Half("6month", "6M", "the last 6 months"),
    Year("12month", "12M", "the last 12 months"),
}

/**
 * The shelf shown before Last.fm is connected: a few 2024 albums (some are the spec's examples), drawn as
 * sleeves without cover art (the art comes from Last.fm once it's connected).
 */
val DemoAlbums: List<Album> = listOf(
    Album("The Smile", "Cutouts", 0, null),
    Album("WILLOW", "empathogen", 0, null),
    Album("Tyler, The Creator", "CHROMAKOPIA", 0, null),
    Album("Remi Wolf", "Big Ideas", 0, null),
    Album("Brittany Howard", "What Now", 0, null),
    Album("Beyoncé", "COWBOY CARTER", 0, null),
)
