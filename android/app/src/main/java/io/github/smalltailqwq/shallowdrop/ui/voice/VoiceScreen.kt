package io.github.smalltailqwq.shallowdrop.ui.voice

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CallEnd
import androidx.compose.material.icons.outlined.ClosedCaption
import androidx.compose.material.icons.outlined.ClosedCaptionDisabled
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MicOff
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.smalltailqwq.shallowdrop.R
import io.github.smalltailqwq.shallowdrop.domain.voice.VoicePhase
import io.github.smalltailqwq.shallowdrop.domain.voice.VoiceProblem
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 语音对话页：中间光球随状态与音量变化，点按可打断回答；底部为静音、挂断、字幕。
 * onClose 返回本次语音对话写入的会话，便于回到对话页继续查看。
 */
@Composable
fun VoiceScreen(
    onClose: (String?) -> Unit,
    viewModel: VoiceViewModel = viewModel(factory = VoiceViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val muted by viewModel.muted.collectAsStateWithLifecycle()
    val captions by viewModel.captions.collectAsStateWithLifecycle()
    val permissionDenied by viewModel.permissionDenied.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission(), viewModel::onPermissionResult)
    val requestOrStart = {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            viewModel.onPermissionResult(true)
        } else {
            permission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    LaunchedEffect(Unit) { requestOrStart() }

    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    val hangUp = { onClose(viewModel.hangUp()) }
    BackHandler(onBack = hangUp)

    val problem = if (permissionDenied) VoiceProblem.Permission else state.problem
    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))
        Box(
            Modifier
                .size(240.dp)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                    if (permissionDenied) requestOrStart() else viewModel.interrupt()
                },
            contentAlignment = Alignment.Center,
        ) {
            VoiceOrb(phase = state.phase, level = state.level, modifier = Modifier.fillMaxSize())
        }
        Spacer(Modifier.height(24.dp))
        Text(
            text = problem?.let { problemText(it) } ?: phaseText(state.phase),
            style = MaterialTheme.typography.titleMedium,
            color = if (problem != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Column(
            Modifier
                .weight(1f)
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .heightIn(min = 0.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (captions) {
                if (state.heard.isNotEmpty()) {
                    Text(
                        state.heard,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
                if (state.reply.isNotEmpty() && state.phase != VoicePhase.Listening) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        state.reply,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 24.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ControlButton(
                icon = if (muted) Icons.Outlined.MicOff else Icons.Outlined.Mic,
                label = stringResource(if (muted) R.string.voice_unmute else R.string.voice_mute),
                onClick = viewModel::toggleMute,
                active = muted,
            )
            FilledIconButton(
                onClick = hangUp,
                modifier = Modifier.size(72.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) {
                Icon(Icons.Outlined.CallEnd, contentDescription = stringResource(R.string.voice_hang_up), modifier = Modifier.size(32.dp))
            }
            ControlButton(
                icon = if (captions) Icons.Outlined.ClosedCaption else Icons.Outlined.ClosedCaptionDisabled,
                label = stringResource(R.string.voice_captions),
                onClick = viewModel::toggleCaptions,
                active = !captions,
            )
        }
    }
}

@Composable
private fun ControlButton(icon: ImageVector, label: String, onClick: () -> Unit, active: Boolean) {
    FilledIconButton(
        onClick = onClick,
        modifier = Modifier.size(56.dp),
        shape = CircleShape,
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = if (active) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Icon(icon, contentDescription = label)
    }
}

/** 自绘光球：聆听时随音量起伏，思考时缓慢呼吸，说话时轻快跳动 */
@Composable
private fun VoiceOrb(phase: VoicePhase, level: Float, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "orb")
    val spin by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(if (phase == VoicePhase.Speaking) 2400 else 6000, easing = LinearEasing)),
        label = "spin",
    )
    val breath by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(if (phase == VoicePhase.Speaking) 420 else 1600),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "breath",
    )
    val target = when (phase) {
        VoicePhase.Listening -> 0.78f + level * 0.22f
        VoicePhase.Speaking -> 0.84f + breath * 0.12f
        VoicePhase.Thinking, VoicePhase.Preparing -> 0.74f + breath * 0.06f
        VoicePhase.Muted, VoicePhase.Stopped -> 0.7f
    }
    val scale by animateFloatAsState(target, tween(120), label = "scale")
    val dim = phase == VoicePhase.Muted || phase == VoicePhase.Stopped
    val primary = MaterialTheme.colorScheme.primary.copy(alpha = if (dim) 0.35f else 1f)
    val secondary = MaterialTheme.colorScheme.tertiary.copy(alpha = if (dim) 0.25f else 0.9f)
    val glow = MaterialTheme.colorScheme.primary.copy(alpha = if (dim) 0.08f else 0.22f)

    Canvas(modifier) {
        val radius = size.minDimension / 2f * scale
        // 外圈光晕
        drawCircle(Brush.radialGradient(listOf(glow, Color.Transparent), center, radius * 1.25f), radius * 1.25f)
        // 主体：两色径向渐变，高光点随相位绕圈
        val highlight = Offset(center.x + cos(spin) * radius * 0.35f, center.y + sin(spin) * radius * 0.35f)
        drawCircle(Brush.radialGradient(listOf(secondary, primary), highlight, radius * 1.4f), radius)
        drawCircle(
            Brush.radialGradient(listOf(Color.White.copy(alpha = if (dim) 0.1f else 0.35f), Color.Transparent), highlight, radius * 0.6f),
            radius,
        )
    }
}

@Composable
private fun phaseText(phase: VoicePhase): String = when (phase) {
    VoicePhase.Preparing -> stringResource(R.string.voice_connecting)
    VoicePhase.Listening -> stringResource(R.string.voice_listening)
    VoicePhase.Thinking -> stringResource(R.string.voice_thinking)
    VoicePhase.Speaking -> stringResource(R.string.voice_speaking)
    VoicePhase.Muted -> stringResource(R.string.voice_muted)
    VoicePhase.Stopped -> ""
}

@Composable
private fun problemText(problem: VoiceProblem): String = when (problem) {
    VoiceProblem.NoRecognizer -> stringResource(R.string.voice_no_recognizer)
    VoiceProblem.NoSynthesizer -> stringResource(R.string.voice_no_synthesizer)
    VoiceProblem.Permission -> stringResource(R.string.voice_permission)
    VoiceProblem.Network -> stringResource(R.string.voice_network)
    VoiceProblem.ReplyFailed -> stringResource(R.string.voice_reply_failed)
}
