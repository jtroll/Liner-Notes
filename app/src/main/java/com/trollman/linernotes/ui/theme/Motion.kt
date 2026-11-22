package com.trollman.linernotes.ui.theme

import android.provider.Settings
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Motion tokens. Interface pieces move on springs with a little bounce (Material 3 Expressive's
 * character); the analog pieces move like objects: the record slides, the button sinks.
 */
object Motion {
    /** Bouncy, for chips, switches, sheets. */
    fun <T> bouncy(): SpringSpec<T> = spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow)

    /** A physical button travelling down and back: quick and firm. */
    fun <T> press(): SpringSpec<T> = spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessHigh)

    /** A record sliding out of its sleeve. */
    fun <T> slide(): SpringSpec<T> = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessLow)

    /** One turn at 33⅓ rpm. */
    const val RECORD_TURN_MS = 1800
}

/** True when the phone asks for reduced motion (animations off in Developer or Accessibility settings). */
@Composable
fun reduceMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}
