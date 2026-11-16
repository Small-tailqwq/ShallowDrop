package io.github.smalltailqwq.shallowdrop.ui.easteregg

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.smalltailqwq.shallowdrop.R
import io.github.smalltailqwq.shallowdrop.ui.motion.IconButton
import kotlinx.coroutines.isActive

internal enum class RideStyle { Ink, Coast, Night, Reef, Orbit, Cloud }

internal class VersionTapTrigger {
    private var count = 0
    private var lastTap: Long? = null

    fun tap(now: Long): Boolean {
        count = if (lastTap?.let { now - it in 0..900 } == true) count + 1 else 1
        lastTap = now
        return (count == 7).also { if (it) reset() }
    }

    fun reset() {
        count = 0
        lastTap = null
    }
}

@Composable
internal fun PelicanEasterEgg(style: RideStyle, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val view = LocalView.current
        DisposableEffect(view) {
            val window = (view.parent as DialogWindowProvider).window
            val controller = WindowCompat.getInsetsController(window, view)
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
            onDispose { controller.show(WindowInsetsCompat.Type.systemBars()) }
        }
        val elapsed = rememberSaveable(style) { mutableFloatStateOf(0f) }
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        LaunchedEffect(style, lifecycle) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                var previous: Long? = null
                while (isActive) {
                    val durationScale = coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f
                    if (durationScale == 0f) {
                        elapsed.floatValue = 8f
                        return@repeatOnLifecycle
                    }
                    withFrameNanos { now ->
                        previous?.let { elapsed.floatValue += (now - it) / 1_000_000_000f / durationScale }
                        previous = now
                    }
                }
            }
        }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            PelicanScene(style, elapsed = { elapsed.floatValue }, modifier = Modifier.fillMaxSize())
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.TopEnd).safeDrawingPadding().padding(12.dp)
                    .background(Color.Black.copy(alpha = 0.16f), CircleShape),
            ) {
                Icon(Icons.Outlined.Close, stringResource(R.string.easter_egg_close), tint = Color.White)
            }
        }
    }
}
