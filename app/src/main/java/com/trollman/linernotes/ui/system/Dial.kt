package com.trollman.linernotes.ui.system

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trollman.linernotes.ui.theme.Motion
import com.trollman.linernotes.ui.theme.liner
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** The dial's sweep: from the bottom left, clockwise over the top, to the bottom right. */
private const val START = 135f
private const val SWEEP = 270f

/**
 * Which whole value a touch at [degrees] (0 is to the right, clockwise) points at, on a dial from
 * [min] to [max]. A touch in the dead zone at the bottom snaps to the nearer end. Pure, for tests.
 */
fun dialValueAt(degrees: Float, min: Int, max: Int): Int {
    val rel = ((degrees - START) % 360f + 360f) % 360f
    val clamped = if (rel > SWEEP) (if (rel > SWEEP + (360f - SWEEP) / 2f) 0f else SWEEP) else rel
    val steps = (max - min).coerceAtLeast(1)
    return (min + (clamped / SWEEP * steps).roundToInt()).coerceIn(min, max)
}

/**
 * A rotary dial with a detent for each whole value (SPEC.md, "Hardware feel": the months dial in
 * a group send). Drag round it, or tap a point on it; each detent buzzes (and clicks, if the
 * click sound is on). The value sits in the middle in dot-matrix type. Screen readers get it as an
 * adjustable value.
 */
@Composable
fun RotaryDial(
    value: Int,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    min: Int = 1,
    max: Int = 12,
    unit: String = "MO",
    description: String = "How many months back",
    size: Dp = 168.dp,
) {
    val c = liner
    val view = LocalView.current
    val current by rememberUpdatedState(value)
    val change by rememberUpdatedState(onValueChange)
    val steps = (max - min).coerceAtLeast(1)
    val target = START + (value - min).toFloat() / steps * SWEEP
    val angle by animateFloatAsState(target, Motion.press(), label = "dial")

    fun pick(v: Int) {
        if (v != current) {
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            Clicks.release()
            change(v)
        }
    }

    Box(
        modifier
            .size(size)
            .semantics {
                contentDescription = description
                stateDescription = "$value"
                progressBarRangeInfo = ProgressBarRangeInfo(value.toFloat(), min.toFloat()..max.toFloat(), steps - 1)
                setProgress { v ->
                    pick(v.roundToInt().coerceIn(min, max))
                    true
                }
            }
            .pointerInput(min, max) {
                fun at(p: Offset): Int {
                    val dx = p.x - this.size.width / 2f
                    val dy = p.y - this.size.height / 2f
                    val deg = Math.toDegrees(atan2(dy, dx).toDouble()).toFloat()
                    return dialValueAt(deg, min, max)
                }
                detectTapGestures { pick(at(it)) }
            }
            .pointerInput(min, max) {
                fun at(p: Offset): Int {
                    val dx = p.x - this.size.width / 2f
                    val dy = p.y - this.size.height / 2f
                    val deg = Math.toDegrees(atan2(dy, dx).toDouble()).toFloat()
                    return dialValueAt(deg, min, max)
                }
                detectDragGestures(onDragStart = { pick(at(it)) }) { change, _ ->
                    change.consume()
                    pick(at(change.position))
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(size)) {
            val r = this.size.minDimension / 2f
            val centre = Offset(this.size.width / 2f, this.size.height / 2f)
            // Ticks round the outside, lit up to the current value.
            for (i in 0..steps) {
                val a = Math.toRadians((START + i.toFloat() / steps * SWEEP).toDouble())
                val outer = r * 0.98f
                val inner = r * (if (i % 3 == 0 || i == steps) 0.84f else 0.88f)
                val lit = min + i <= value
                drawLine(
                    color = if (lit) c.signal else c.bevelDark,
                    start = centre + Offset((cos(a) * inner).toFloat(), (sin(a) * inner).toFloat()),
                    end = centre + Offset((cos(a) * outer).toFloat(), (sin(a) * outer).toFloat()),
                    strokeWidth = 3.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }
            // The knob: a raised disc with a bevel and a pointer groove.
            val knob = r * 0.74f
            drawCircle(
                brush = Brush.verticalGradient(
                    listOf(lerp(c.panel, Color.White, if (c.dark) 0.08f else 0.25f), c.panel),
                    startY = centre.y - knob,
                    endY = centre.y + knob,
                ),
                radius = knob,
                center = centre,
            )
            drawCircle(
                brush = Brush.verticalGradient(listOf(c.bevelLight, c.bevelDark), startY = centre.y - knob, endY = centre.y + knob),
                radius = knob,
                center = centre,
                style = Stroke(1.5.dp.toPx()),
            )
            rotate(angle, centre) {
                drawLine(
                    color = c.signal,
                    start = centre + Offset(knob * 0.62f, 0f),
                    end = centre + Offset(knob * 0.92f, 0f),
                    strokeWidth = 4.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }
        }
        DotMatrixText("$value $unit", size = 26.sp, color = c.ink, maxLines = 1)
    }
}
