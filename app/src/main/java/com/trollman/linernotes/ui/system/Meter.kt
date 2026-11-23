package com.trollman.linernotes.ui.system

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trollman.linernotes.ui.theme.liner
import com.trollman.linernotes.ui.theme.mono
import kotlin.math.cos
import kotlin.math.sin

/**
 * An analog VU meter (SPEC.md, "Influence": "an analog-style meter for each direction"): a lit
 * face with a scale, a needle that swings to [level] (0 to 1) and settles with a little overshoot,
 * the [score] in dot-matrix type, and a caption. [description] is what a screen reader says.
 */
@Composable
fun VuMeter(
    level: Float,
    score: String,
    caption: String,
    description: String,
    modifier: Modifier = Modifier,
) {
    val c = liner
    val needle by animateFloatAsState(
        level.coerceIn(0f, 1f),
        spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessVeryLow),
        label = "needle",
    )
    val face = Color(0xFFF3E7C9) // warm backlit paper, like an old meter
    val faceInk = Color(0xFF2A2420)
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier
            .clip(shape)
            .background(Brush.verticalGradient(listOf(face, Color(0xFFE6D5AE))))
            .border(1.5.dp, Brush.verticalGradient(listOf(c.bevelDark, c.bevelLight)), shape)
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .semantics { contentDescription = description },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Canvas(Modifier.fillMaxWidth().aspectRatio(1.9f)) {
            val cx = size.width / 2
            val cy = size.height * 0.98f
            val r = size.width * 0.46f
            val sweep = 100.0
            fun point(t: Double, radius: Float): Offset {
                // t = 0 at the left end of the scale, 1 at the right.
                val a = Math.toRadians(180 - (180 - sweep) / 2 - t * sweep)
                return Offset(cx + radius * cos(a).toFloat(), cy - radius * sin(a).toFloat())
            }
            // The scale arc, with the last fifth in signal orange (the "landed" zone).
            val arcTop = Offset(cx - r, cy - r)
            val arcSize = Size(r * 2, r * 2)
            val leftDeg = (180 + (180 - sweep) / 2).toFloat()
            drawArc(faceInk, leftDeg, (sweep * 0.8).toFloat(), false, arcTop, arcSize, style = Stroke(2.dp.toPx()))
            drawArc(c.signal, leftDeg + (sweep * 0.8).toFloat(), (sweep * 0.2).toFloat(), false, arcTop, arcSize, style = Stroke(5.dp.toPx()))
            for (i in 0..10) {
                val t = i / 10.0
                val long = i % 5 == 0
                drawLine(
                    if (t > 0.8) c.signal else faceInk,
                    point(t, r),
                    point(t, r - (if (long) 12.dp else 6.dp).toPx()),
                    strokeWidth = (if (long) 2.dp else 1.dp).toPx(),
                )
            }
            // The needle and its pivot.
            drawLine(
                Color(0xFF1A1512),
                Offset(cx, cy),
                point(needle.toDouble(), r * 1.04f),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round,
            )
            drawCircle(Color(0xFF1A1512), radius = 5.dp.toPx(), center = Offset(cx, cy))
        }
        DotMatrixText(score, size = 36.sp, color = faceInk, maxLines = 1)
        Text(caption, style = mono(10.sp), color = faceInk.copy(alpha = 0.7f), maxLines = 1)
    }
}
