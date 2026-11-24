package com.trollman.linernotes.data

import androidx.compose.runtime.Immutable

/**
 * Influence (SPEC.md, "Influence"): what a send did to the recipient's listening, measured on the
 * server from their Last.fm (functions/lib/influence.js). Only for pairs where both people turned
 * sharing on; the app only ever sees these summaries.
 */
@Immutable
data class InfluenceItem(
    val plays: Int,
    val fullPlays: Int,
    val listened: Boolean,
    val cameBack: Boolean,
    /** Other albums by the artist they tried. */
    val deeper: List<String>,
    val top20: Boolean,
    /** How many similar artists they found. */
    val spilled: Int,
    val points: Int,
    /** "Sam has played Cutouts 14 times and tried two more Smile albums." */
    val line: String,
)

/** One look at a send: the running one, or the 30 or 90 day mark. */
@Immutable
data class InfluenceLook(
    val at: Long,
    val days: Int,
    val items: Map<Int, InfluenceItem>,
    val total: Int,
    val max: Int,
    /** Album positions that landed best, best first. */
    val best: List<Int>,
)

/** Every look at a send, by "latest", "d30" and "d90". */
@Immutable
data class Influence(val looks: Map<String, InfluenceLook> = emptyMap()) {
    /** The look to show: the 90 day mark once it's in, else the running look, else the 30 day mark. */
    val current: InfluenceLook? get() = looks["d90"] ?: looks["latest"] ?: looks["d30"]
    val isEmpty: Boolean get() = looks.isEmpty()

    companion object {
        /** Points per album that count as a send that clearly landed, for the meter's full scale. */
        const val LANDED = 5

        @Suppress("UNCHECKED_CAST")
        fun parse(raw: Any?): Influence {
            val m = raw as? Map<String, Any?> ?: return Influence()
            return Influence(m.mapNotNull { (k, v) -> look(v)?.let { k to it } }.toMap())
        }

        private fun look(raw: Any?): InfluenceLook? {
            val m = raw as? Map<*, *> ?: return null
            val at = (m["at"] as? com.google.firebase.Timestamp)?.toDate()?.time ?: return null
            val items = (m["items"] as? Map<*, *>).orEmpty().mapNotNull { (k, v) ->
                val i = (k as? String)?.toIntOrNull() ?: return@mapNotNull null
                val it = v as? Map<*, *> ?: return@mapNotNull null
                i to InfluenceItem(
                    plays = (it["plays"] as? Number)?.toInt() ?: 0,
                    fullPlays = (it["fullPlays"] as? Number)?.toInt() ?: 0,
                    listened = it["listened"] as? Boolean ?: false,
                    cameBack = it["cameBack"] as? Boolean ?: false,
                    deeper = (it["deeper"] as? List<*>).orEmpty().filterIsInstance<String>(),
                    top20 = it["top20"] as? Boolean ?: false,
                    spilled = (it["spilled"] as? Number)?.toInt() ?: 0,
                    points = (it["points"] as? Number)?.toInt() ?: 0,
                    line = it["line"] as? String ?: "",
                )
            }.toMap()
            return InfluenceLook(
                at = at,
                days = (m["days"] as? Number)?.toInt() ?: 0,
                items = items,
                total = (m["total"] as? Number)?.toInt() ?: items.values.sumOf { it.points },
                max = (m["max"] as? Number)?.toInt() ?: 0,
                best = (m["best"] as? List<*>).orEmpty().mapNotNull { (it as? Number)?.toInt() },
            )
        }
    }
}

/** One direction of a pair, for its meter: the latest measured send and all time. */
@Immutable
data class Meter(val latest: Int, val latestAlbums: Int, val allTime: Int, val allTimeAlbums: Int, val measured: Int) {
    /** 0 to 1 for the needle: points against [Influence.LANDED] per album. */
    fun needle(points: Int, albums: Int): Float =
        if (albums <= 0) 0f else (points.toFloat() / (albums * Influence.LANDED)).coerceIn(0f, 1f)

    companion object {
        /** From sends one way, each as (when delivered, its influence, how many albums). */
        fun of(sends: List<Triple<Long, Influence, Int>>): Meter {
            val measured = sends.mapNotNull { (at, inf, n) -> inf.current?.let { Triple(at, it, n) } }
            val latest = measured.maxByOrNull { it.first }
            return Meter(
                latest = latest?.second?.total ?: 0,
                latestAlbums = latest?.third ?: 0,
                allTime = measured.sumOf { it.second.total },
                allTimeAlbums = measured.sumOf { it.third },
                measured = measured.size,
            )
        }
    }
}
