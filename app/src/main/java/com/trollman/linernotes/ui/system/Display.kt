package com.trollman.linernotes.ui.system

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trollman.linernotes.ui.theme.dotMatrix
import com.trollman.linernotes.ui.theme.liner

/**
 * Dot-matrix display type for big titles, months, counts and scores. Only at 32sp and up, and
 * read aloud as the plain words (the capitals are a look, not the content).
 */
@Composable
fun DotMatrixText(
    text: String,
    modifier: Modifier = Modifier,
    size: TextUnit = 48.sp,
    color: Color = liner.ink,
    maxLines: Int = Int.MAX_VALUE,
) {
    require(size.value >= 32f) { "Dot-matrix type is for 32sp and up" }
    Text(
        text = text.uppercase(),
        style = dotMatrix(size),
        color = color,
        maxLines = maxLines,
        overflow = TextOverflow.Clip,
        modifier = modifier.semantics { contentDescription = text },
    )
}

/** A recessed dark window, like the display on a piece of hi-fi, for dot-matrix readouts. */
@Composable
fun DisplayWindow(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val c = liner
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Brush.verticalGradient(listOf(c.display, Color(0xFF1E1E1E).copy(alpha = if (c.dark) 0.6f else 1f))))
            .border(1.5.dp, Brush.verticalGradient(listOf(c.bevelDark, c.bevelLight)), shape)
            .padding(horizontal = 20.dp, vertical = 18.dp),
        content = content,
    )
}
