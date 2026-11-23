package com.trollman.linernotes.ui.system

import android.view.HapticFeedbackConstants
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trollman.linernotes.ui.theme.Motion
import com.trollman.linernotes.ui.theme.liner
import com.trollman.linernotes.ui.theme.mono

/**
 * A raised physical button (SPEC.md, "Hardware feel"). It sinks as it's pressed and gives a short
 * tick of vibration where the phone has it. [accent] makes it the one signal-orange main action.
 * [latched] keeps it pushed in, for a button that stays selected like a radio preset.
 */
@Composable
fun PhysicalButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Boolean = false,
    latched: Boolean = false,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(14.dp),
    contentDescription: String? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val c = liner
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val down = pressed || latched
    val travel by animateDpAsState(if (down) 2.5.dp else 0.dp, Motion.press(), label = "travel")
    val lift by animateDpAsState(if (pressed) 0.5.dp else if (latched) 1.5.dp else 5.dp, Motion.press(), label = "lift")
    val view = LocalView.current
    LaunchedEffect(pressed) {
        if (pressed) {
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            Clicks.press()
        }
    }
    val face = when {
        accent -> c.signal
        latched -> lerp(c.panel, c.well, 0.5f)
        else -> c.panel
    }
    val described = if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier
    Box(
        modifier = modifier
            .offset(y = travel)
            .shadow(lift, shape, clip = false, ambientColor = Color.Black, spotColor = Color.Black)
            .clip(shape)
            .background(Brush.verticalGradient(listOf(lerp(face, Color.White, if (c.dark) 0.06f else 0.18f), face)))
            .border(1.dp, Brush.verticalGradient(listOf(c.bevelLight, c.bevelDark)), shape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .then(described)
            .alpha(if (enabled) 1f else 0.45f),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides if (accent) c.onSignal else c.ink) {
            content()
        }
    }
}

/** A labelled [PhysicalButton] in body type. */
@Composable
fun PhysicalTextButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Boolean = false,
    enabled: Boolean = true,
) {
    PhysicalButton(onClick = onClick, modifier = modifier.height(52.dp), accent = accent, enabled = enabled) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
    }
}

/** A small indicator light: on in signal orange, off as a dark dot. */
@Composable
fun Led(on: Boolean, modifier: Modifier = Modifier, size: Dp = 8.dp) {
    val c = liner
    val color by animateColorAsState(if (on) c.signal else c.bevelDark, label = "led")
    Box(
        modifier
            .size(size)
            .shadow(if (on) 4.dp else 0.dp, CircleShape, ambientColor = c.signal, spotColor = c.signal)
            .clip(CircleShape)
            .background(color)
    )
}

/**
 * A hardware toggle with a label and a one-line reason (the first-run connections, Settings).
 * The knob springs across a recessed track and the LED lights when it's on.
 */
@Composable
fun HardwareSwitch(
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
    label: String,
    description: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    status: String? = null,
) {
    val c = liner
    val knob by animateDpAsState(if (checked) 30.dp else 0.dp, Motion.bouncy(), label = "knob")
    val view = LocalView.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = {
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    Clicks.release()
                    onToggle(it)
                },
            )
            .semantics { if (status != null) stateDescription = status }
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Led(on = checked)
                Spacer(Modifier.width(10.dp))
                Text(label, style = MaterialTheme.typography.titleMedium, color = c.ink)
            }
            Spacer(Modifier.height(4.dp))
            Text(description, style = MaterialTheme.typography.bodySmall, color = c.inkSoft)
            if (status != null) {
                Spacer(Modifier.height(4.dp))
                Text(status.uppercase(), style = mono(11.sp), color = c.inkFaint)
            }
        }
        Spacer(Modifier.width(16.dp))
        // The switch itself: a recessed slot and a raised knob.
        Box(
            Modifier
                .alpha(if (enabled) 1f else 0.5f)
                .width(64.dp)
                .height(34.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(c.well)
                .border(1.dp, Brush.verticalGradient(listOf(c.bevelDark, c.bevelLight)), RoundedCornerShape(8.dp))
                .padding(3.dp)
        ) {
            Box(
                Modifier
                    .offset(x = knob)
                    .size(width = 28.dp, height = 28.dp)
                    .shadow(3.dp, RoundedCornerShape(6.dp))
                    .clip(RoundedCornerShape(6.dp))
                    .background(Brush.verticalGradient(listOf(lerp(c.panel, Color.White, 0.2f), c.panel)))
                    .border(1.dp, Brush.verticalGradient(listOf(c.bevelLight, c.bevelDark)), RoundedCornerShape(6.dp)),
                contentAlignment = Alignment.Center,
            ) {
                // Grip lines
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    repeat(3) {
                        Box(Modifier.width(1.5.dp).height(12.dp).background(if (checked) c.signal else c.bevelDark))
                    }
                }
            }
        }
    }
}
