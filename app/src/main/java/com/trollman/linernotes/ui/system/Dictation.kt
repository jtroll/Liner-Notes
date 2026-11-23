package com.trollman.linernotes.ui.system

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.trollman.linernotes.ui.theme.Motion
import com.trollman.linernotes.ui.theme.liner

/**
 * Speaking a note (SPEC.md, "Dictating a note"), with Android's own speech recognition, which is
 * free and runs on the phone where the phone supports it.
 *
 * The recognizer stops on its own after a pause, so while the sender still wants to talk it's
 * started again and the pieces are joined. [onDone] gets the whole transcript once they stop.
 * Use from the main thread only.
 */
class Dictation(
    private val context: Context,
    private val onDone: (String) -> Unit,
    private val onProblem: (String) -> Unit,
) {
    var listening by mutableStateOf(false)
        private set
    /** What's been heard so far, for showing as they talk. */
    var heard by mutableStateOf("")
        private set
    /** Loudness, 0 to 1, for the meter. */
    var level by mutableFloatStateOf(0f)
        private set

    /** Names to listen out for: the artist, album and tracks. Used on Android 13 and up. */
    var hints: List<String> = emptyList()

    private val committed = StringBuilder()
    private var partial = ""
    private var wanted = false
    private var retries = 0
    private var recognizer: SpeechRecognizer? = null
    private val handler = Handler(Looper.getMainLooper())
    private val giveUp = Runnable { finish() }

    val available: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    fun start() {
        if (listening) return
        if (!available) return onProblem("This phone has no speech recognition. Type the note instead.")
        committed.clear()
        partial = ""
        heard = ""
        retries = 0
        wanted = true
        listening = true
        listen()
    }

    /** Stops listening; the transcript arrives through onDone once the last words are in. */
    fun stop() {
        if (!listening || !wanted) return
        wanted = false
        recognizer?.stopListening()
        handler.postDelayed(giveUp, 4000)
    }

    fun cancel() {
        wanted = false
        handler.removeCallbacksAndMessages(null)
        recognizer?.cancel()
        listening = false
        level = 0f
    }

    fun release() {
        cancel()
        recognizer?.destroy()
        recognizer = null
    }

    private fun listen() {
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also {
            it.setRecognitionListener(listener)
            recognizer = it
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 4000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
            if (hints.isNotEmpty()) putStringArrayListExtra(BIASING_STRINGS, ArrayList(hints.take(40)))
        }
        r.startListening(intent)
    }

    private fun finish() {
        handler.removeCallbacksAndMessages(null)
        if (!listening) return
        listening = false
        level = 0f
        if (partial.isNotBlank()) commit(partial)
        val text = committed.toString().trim()
        if (text.isNotBlank()) onDone(text) else onProblem("I didn't catch anything. Hold the button and speak.")
    }

    private fun commit(text: String) {
        if (text.isNotBlank()) {
            if (committed.isNotEmpty()) committed.append(' ')
            committed.append(text.trim())
        }
        partial = ""
        heard = committed.toString()
    }

    private fun best(results: Bundle?): String =
        results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()

    private val listener = object : RecognitionListener {
        override fun onResults(results: Bundle?) {
            commit(best(results))
            retries = 0
            if (wanted) listen() else finish()
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val p = best(partialResults)
            if (p.isBlank()) return
            partial = p
            heard = if (committed.isEmpty()) p else "$committed $p"
        }

        override fun onError(error: Int) {
            when {
                !wanted -> finish()
                error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    cancel()
                    onProblem("Liner Notes needs the microphone to take a spoken note.")
                }
                error in RESTARTABLE && retries < 8 -> {
                    retries++
                    if (partial.isNotBlank()) commit(partial)
                    handler.postDelayed({ if (wanted) listen() }, 150)
                }
                else -> {
                    wanted = false
                    if (committed.isNotEmpty() || partial.isNotBlank()) finish()
                    else {
                        cancel()
                        onProblem(describe(error))
                    }
                }
            }
        }

        override fun onRmsChanged(rmsdB: Float) {
            level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
        }

        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun describe(error: Int) = when (error) {
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER ->
            "Speech recognition needs a connection on this phone just now. Try again, or type it."
        SpeechRecognizer.ERROR_AUDIO -> "The microphone didn't record. Try again."
        else -> "Speech recognition stopped (error $error). Try again, or type it."
    }

    private companion object {
        /** RecognizerIntent.EXTRA_BIASING_STRINGS, Android 13 and up; ignored before. */
        const val BIASING_STRINGS = "android.speech.extra.BIASING_STRINGS"
        val RESTARTABLE = setOf(
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
            SpeechRecognizer.ERROR_CLIENT,
        )
    }
}

/**
 * The record key: hold to talk and let go to stop, or tap once to start and again to stop.
 * [onStart] returns false when it couldn't start (no microphone permission yet, say).
 */
@Composable
fun RecordKey(
    listening: Boolean,
    level: Float,
    onStart: () -> Boolean,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: Dp = 76.dp,
) {
    val c = liner
    val view = LocalView.current
    val isListening by rememberUpdatedState(listening)
    val start by rememberUpdatedState(onStart)
    val stop by rememberUpdatedState(onStop)
    val travel by animateDpAsState(if (listening) 2.5.dp else 0.dp, Motion.press(), label = "travel")
    val lift by animateDpAsState(if (listening) 1.dp else 5.dp, Motion.press(), label = "lift")
    val pulse by animateFloatAsState(if (listening) 1f + level * 0.35f else 1f, label = "pulse")
    val face = if (listening) lerp(c.panel, c.well, 0.5f) else c.panel
    Box(
        modifier
            .size(size)
            .offset(y = travel)
            .shadow(lift, CircleShape, clip = false, ambientColor = Color.Black, spotColor = Color.Black)
            .clip(CircleShape)
            .background(Brush.verticalGradient(listOf(lerp(face, Color.White, if (c.dark) 0.06f else 0.18f), face)))
            .border(1.dp, Brush.verticalGradient(listOf(c.bevelLight, c.bevelDark)), CircleShape)
            .semantics {
                role = Role.Button
                contentDescription = "Record a spoken note"
                stateDescription = if (listening) "Recording" else "Not recording"
                onClick(label = if (listening) "Stop" else "Start") {
                    if (isListening) stop() else start()
                    true
                }
            }
            .alpha(if (enabled) 1f else 0.45f)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    awaitFirstDown().consume()
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    Clicks.press()
                    if (isListening) {
                        // Tapped while recording in tap mode: stop.
                        stop()
                        waitForUpOrCancellation()
                        return@awaitEachGesture
                    }
                    val downAt = System.currentTimeMillis()
                    if (!start()) {
                        waitForUpOrCancellation()
                        return@awaitEachGesture
                    }
                    waitForUpOrCancellation()
                    // A long press is hold-to-talk; a quick tap leaves it recording until the next tap.
                    if (System.currentTimeMillis() - downAt >= HOLD_MILLIS) {
                        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        Clicks.release()
                        stop()
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(size * 0.32f)
                .scale(pulse)
                .clip(CircleShape)
                .background(if (listening) c.signal else lerp(c.signal, c.well, 0.35f))
        )
    }
}

private const val HOLD_MILLIS = 450L
