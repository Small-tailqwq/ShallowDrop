package io.github.smalltailqwq.shallowdrop.ui.chat.message

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.smalltailqwq.shallowdrop.R
import io.github.smalltailqwq.shallowdrop.ui.components.DsIcon
import io.github.smalltailqwq.shallowdrop.ui.motion.IconButton

@Composable
fun ReadAloudButton(active: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(40.dp)) {
        Crossfade(active, animationSpec = tween(120), label = "read-aloud") { speaking ->
            DsIcon(
                if (speaking) R.drawable.ic_stop_circle_outline_20 else R.drawable.ic_speaker_outline_20,
                contentDescription = stringResource(if (speaking) R.string.stop_reading else R.string.read_aloud),
                tint = if (speaking) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
