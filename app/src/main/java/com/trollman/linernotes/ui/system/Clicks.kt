package com.trollman.linernotes.ui.system

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * The click a hardware control makes when it's pressed (SPEC.md, "Hardware feel": off by default,
 * switched on in Setup). Synthesised rather than shipped as a file: a few milliseconds of filtered
 * noise for the contact, over a short, fast-decaying knock for the body, so it sounds like a key
 * bottoming out rather than a phone beep. Played through the sonification stream, so the phone's
 * own volume and silent mode apply.
 */
object Clicks {
    /** Set from the saved preference (AppViewModel). */
    @Volatile
    var enabled: Boolean = false

    private const val RATE = 44_100
    private var press: AudioTrack? = null
    private var release: AudioTrack? = null
    private var failed = false

    /** The key going down: the louder of the two. */
    fun press() = play(down = true)

    /** A switch landing, or a key coming back up: softer and higher. */
    fun release() = play(down = false)

    private fun play(down: Boolean) {
        if (!enabled || failed) return
        try {
            val track = synchronized(this) {
                if (down) press ?: build(down = true).also { press = it }
                else release ?: build(down = false).also { release = it }
            }
            track.stop()
            track.reloadStaticData()
            track.play()
        } catch (e: Exception) {
            // A phone that won't make the sound just stays quiet.
            Log.w("Clicks", "Couldn't play the click", e)
            failed = true
        }
    }

    private fun build(down: Boolean): AudioTrack {
        val samples = clickSamples(down)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(samples.size * 2)
            .build()
        track.write(samples, 0, samples.size)
        return track
    }

    /** The click itself, as 16-bit samples. Public for the unit test. */
    fun clickSamples(down: Boolean): ShortArray {
        val ms = if (down) 28 else 18
        val n = RATE * ms / 1000
        val knock = if (down) 1_900.0 else 3_100.0
        val gain = if (down) 0.55 else 0.32
        val random = Random(if (down) 7 else 11)
        var last = 0.0
        return ShortArray(n) { i ->
            val t = i.toDouble() / RATE
            // Contact: noise, lightly low-passed, gone in about 3 ms.
            val noise = random.nextDouble(-1.0, 1.0)
            last += 0.45 * (noise - last)
            val contact = last * exp(-t * 1_400.0)
            // Body: a damped knock.
            val body = sin(2 * PI * knock * t) * exp(-t * (if (down) 260.0 else 380.0))
            val v = (contact * 0.8 + body * 0.6) * gain
            (v.coerceIn(-1.0, 1.0) * Short.MAX_VALUE).toInt().toShort()
        }
    }
}
