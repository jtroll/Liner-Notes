package com.trollman.linernotes.ui.system

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.trollman.linernotes.data.Album
import com.trollman.linernotes.ui.theme.AlloFamily
import com.trollman.linernotes.ui.theme.Motion
import com.trollman.linernotes.ui.theme.liner
import com.trollman.linernotes.ui.theme.mono
import com.trollman.linernotes.ui.theme.reduceMotion
import kotlin.math.abs

/**
 * An album's cover. Last.fm's art where it has some; otherwise a sleeve drawn from the title, in
 * a colour picked from the album's name so the same album always looks the same.
 */
@Composable
fun CoverArt(album: Album, modifier: Modifier = Modifier, showTitleOnDrawn: Boolean = true) {
    val url = album.imageUrl
    if (url != null) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current).data(url).crossfade(true).build(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier,
        )
    } else {
        DrawnSleeve(album, modifier, showTitleOnDrawn)
    }
}

private val SleeveTones = listOf(
    Color(0xFFE8501A), Color(0xFF2F4B3A), Color(0xFFD9C9A3), Color(0xFF1F3A5F),
    Color(0xFF8C2F39), Color(0xFFC9A227), Color(0xFF5B4B8A), Color(0xFF3E7C7B),
)

/** The tone a drawn sleeve uses for [album]: stable for the same album. */
fun drawnTone(album: Album): Color = SleeveTones[abs(album.key.hashCode()) % SleeveTones.size]

@Composable
private fun DrawnSleeve(album: Album, modifier: Modifier, showTitle: Boolean) {
    val tone = drawnTone(album)
    val light = (tone.red * 0.299f + tone.green * 0.587f + tone.blue * 0.114f) > 0.6f
    val ink = if (light) Color(0xFF141414) else Color(0xFFF2F1EE)
    Box(modifier.background(tone)) {
        // A record's worth of rings, off-centre, as a print on the sleeve.
        Canvas(Modifier.fillMaxSize()) {
            val center = androidx.compose.ui.geometry.Offset(size.width * 0.78f, size.height * 0.22f)
            var r = size.minDimension * 0.9f
            while (r > 8f) {
                drawCircle(ink.copy(alpha = 0.10f), radius = r, center = center, style = Stroke(width = 1.2f))
                r -= size.minDimension * 0.06f
            }
        }
        if (showTitle) {
            Column(
                Modifier.align(Alignment.BottomStart).padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    album.title,
                    color = ink,
                    fontFamily = AlloFamily,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 15.sp,
                    lineHeight = 17.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(album.artist.uppercase(), color = ink.copy(alpha = 0.8f), style = mono(9.sp), maxLines = 1)
            }
        }
    }
}

/**
 * A vinyl record with [album]'s cover on its centre label. Spins at 33⅓ rpm while [spinning],
 * unless the phone asks for reduced motion.
 */
@Composable
fun Record(album: Album, spinning: Boolean, modifier: Modifier = Modifier) {
    val c = liner
    val reduce = reduceMotion()
    val rotation = remember { Animatable(0f) }
    LaunchedEffect(spinning, reduce) {
        if (spinning && !reduce) {
            while (true) {
                rotation.snapTo(rotation.value % 360f)
                rotation.animateTo(rotation.value + 360f, tween(Motion.RECORD_TURN_MS, easing = LinearEasing))
            }
        }
    }
    Box(modifier.aspectRatio(1f)) {
        Box(Modifier.fillMaxSize().graphicsLayer { rotationZ = rotation.value }) {
            Canvas(Modifier.fillMaxSize()) {
                val r = size.minDimension / 2f
                drawCircle(c.record, radius = r)
                var g = r * 0.95f
                val step = r * 0.032f
                while (g > r * 0.42f) {
                    drawCircle(c.groove, radius = g, style = Stroke(width = 1.1f))
                    g -= step
                }
                drawCircle(Color.Black, radius = r * 0.37f)
            }
            CoverArt(
                album,
                Modifier.fillMaxSize(0.34f).align(Alignment.Center).clip(CircleShape),
                showTitleOnDrawn = false,
            )
        }
        // Light catching the grooves: it stays put while the record turns.
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(
                brush = Brush.sweepGradient(
                    listOf(
                        Color.White.copy(alpha = 0f), Color.White.copy(alpha = 0.10f), Color.White.copy(alpha = 0f),
                        Color.White.copy(alpha = 0f), Color.White.copy(alpha = 0.07f), Color.White.copy(alpha = 0f),
                    )
                ),
                radius = size.minDimension / 2f,
            )
            // The spindle hole.
            drawCircle(c.casing, radius = size.minDimension * 0.018f)
        }
    }
}

/**
 * An album as a record in its sleeve (SPEC.md, "Albums are vinyl"). [out] is how far the record
 * has slid out, from 0 (inside) to 1 (all the way to the right edge). The sleeve takes 72% of the
 * width; the rest is room for the record.
 */
@Composable
fun VinylSleeve(
    album: Album,
    out: Float,
    spinning: Boolean,
    modifier: Modifier = Modifier,
) {
    val reduce = reduceMotion()
    val shown by animateFloatAsState(
        targetValue = out.coerceIn(0f, 1f),
        animationSpec = if (reduce) snap() else Motion.slide(),
        label = "record out",
    )
    BoxWithConstraints(modifier.fillMaxWidth().aspectRatio(1f / 0.72f)) {
        val sleeve = maxWidth * 0.72f
        val record = sleeve * 0.94f
        val travel = maxWidth - record
        Record(
            album,
            spinning = spinning,
            modifier = Modifier
                .size(record)
                .align(Alignment.CenterStart)
                .offset(x = travel * shown),
        )
        CoverArt(
            album,
            Modifier
                .size(sleeve)
                .shadow(10.dp, RoundedCornerShape(3.dp))
                .clip(RoundedCornerShape(3.dp))
                .border(0.5.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(3.dp))
                .semantics { contentDescription = "${album.title} by ${album.artist}" },
        )
    }
}
