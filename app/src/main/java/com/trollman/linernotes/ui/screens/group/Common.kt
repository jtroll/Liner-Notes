package com.trollman.linernotes.ui.screens.group

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trollman.linernotes.ui.system.PhysicalButton
import com.trollman.linernotes.ui.theme.liner
import com.trollman.linernotes.ui.theme.mono

/** The row at the top of each group-send screen: a back key, a mono label, and room for keys. */
@Composable
internal fun BackHeader(
    label: String,
    onBack: () -> Unit,
    backDescription: String = "Back",
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        PhysicalButton(onBack, Modifier.size(44.dp), shape = RoundedCornerShape(12.dp), contentDescription = backDescription) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null)
        }
        Spacer(Modifier.width(14.dp))
        Text(
            label.uppercase(),
            style = mono(13.sp),
            color = liner.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, content = trailing)
    }
}

/** A small square key with an icon, for list rows (move up, move down, remove). */
@Composable
internal fun IconKey(icon: ImageVector, description: String, onClick: () -> Unit, enabled: Boolean = true, latched: Boolean = false) {
    PhysicalButton(
        onClick,
        Modifier.size(38.dp),
        latched = latched,
        enabled = enabled,
        shape = RoundedCornerShape(10.dp),
        contentDescription = description,
    ) { Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp)) }
}

/** Words in a note, as the editor and the booklet count them. */
internal fun wordCount(text: String): Int = text.trim().split(Regex("\\s+")).count { it.isNotBlank() }
