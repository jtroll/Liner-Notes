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
    /** When the Spotify sign-in runs out: six months after the first sign-in, whatever happens. */
    val spotifyExpiresAt: Long? = null,
    /** Spotify has already refused the sign-in. */
    val spotifyExpired: Boolean = false,
) {
    /** Whole days until the Spotify sign-in runs out; 0 or less once it has. */
    fun spotifyDaysLeft(now: Long): Int? =
        spotifyExpiresAt?.let { Math.floorDiv(it - now, 86_400_000L).toInt() }

    /** The sign-in has run out (or Spotify said so): Reconnect is the only way on. */
    fun spotifyRunOut(now: Long): Boolean =
        spotifyName != null && (spotifyExpired || (spotifyExpiresAt != null && spotifyExpiresAt <= now))

    /** Two weeks or less to go: time for the reminder (SPEC.md, "First run"). */
    fun spotifyRunningOut(now: Long): Boolean =
        spotifyName != null && !spotifyRunOut(now) && (spotifyDaysLeft(now) ?: Int.MAX_VALUE) < 14
}

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
