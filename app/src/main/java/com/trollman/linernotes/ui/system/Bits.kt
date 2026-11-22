package com.trollman.linernotes.ui.system

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trollman.linernotes.ui.theme.liner
import com.trollman.linernotes.ui.theme.mono

/** The Activity behind a Compose context: sign-in sheets and App Remote need one. */
fun Context.findActivity(): Activity {
    var c: Context = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    error("No Activity in this context")
}

/** A small monospace capitals label above a group, read as a heading. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = mono(12.sp),
        color = liner.inkFaint,
        modifier = modifier.semantics { heading() }.padding(top = 8.dp, bottom = 6.dp),
    )
}

/** A raised panel on the casing, for a group of controls. */
@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val c = liner
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier
            .fillMaxWidth()
            .shadow(3.dp, shape)
            .clip(shape)
            .background(c.panel)
            .border(1.dp, Brush.verticalGradient(listOf(c.bevelLight, c.bevelDark)), shape)
            .padding(horizontal = 18.dp, vertical = 6.dp),
        content = content,
    )
}
