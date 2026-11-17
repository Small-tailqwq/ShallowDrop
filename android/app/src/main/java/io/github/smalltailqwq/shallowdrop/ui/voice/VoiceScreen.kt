package io.github.smalltailqwq.shallowdrop.ui.voice

import android.Manifest
import android.content.pm.PackageManager
import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import io.github.smalltailqwq.shallowdrop.ui.components.AppBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.util.lerp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.smalltailqwq.shallowdrop.R
import io.github.smalltailqwq.shallowdrop.core.model.TtsVoice
import io.github.smalltailqwq.shallowdrop.domain.voice.VoicePhase
import io.github.smalltailqwq.shallowdrop.domain.voice.VoiceProblem
import io.github.smalltailqwq.shallowdrop.domain.voice.VoiceState
import io.github.smalltailqwq.shallowdrop.ui.motion.IconButton
import io.github.smalltailqwq.shallowdrop.ui.motion.MotionEaseOut
import io.github.smalltailqwq.shallowdrop.ui.motion.pressFeedback
import io.github.smalltailqwq.shallowdrop.ui.theme.groupedCardColor
import io.github.smalltailqwq.shallowdrop.ui.theme.groupedPageColor
import kotlin.math.PI
import kotlin.math.sin

/**
 * 语音对话页。居中的光球表现当前阶段（外观随音色而定，见 [OrbLook]），其下一行状态；
 * 底部三个圆钮：麦克风、挂断、字幕；右上角调整音色与智能搜索。
 *
 * - 点按光球：思考或说话中为打断，出错停下时为重试，缺权限时重新申请。
 * - 打开字幕：光球缩小上移，腾出空间显示听到的话与回答。
 * - 出错停下时麦克风钮变为重试。
 *
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
    val settings by viewModel.settingsState.collectAsStateWithLifecycle()
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

    // 只关闭一次：退出过渡期间页面还在，再点挂断或再按返回会重复出栈，把对话页也弹掉
    var closing by remember { mutableStateOf(false) }
    val hangUp = {
        if (!closing) {
            closing = true
            onClose(viewModel.hangUp())
        }
    }
    BackHandler(onBack = hangUp)

    var options by rememberSaveable { mutableStateOf(false) }
    val problem = if (permissionDenied) VoiceProblem.Permission else state.problem
    // 挂断后阶段也是 Stopped，但那是正常结束，退出过渡中不显示重试
    val stopped = !closing && (permissionDenied || state.phase == VoicePhase.Stopped)
    val mood = if (permissionDenied) OrbMood.Stopped else state.phase.mood()
    // 打断与重试给一下触感；聆听中点按没有作用，不振动
    val onOrb = {
        if (stopped || state.phase == VoicePhase.Thinking || state.phase == VoicePhase.Speaking) {
            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        }
        if (permissionDenied) requestOrStart() else viewModel.interrupt()
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(groupedPageColor())
            .safeDrawingPadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            IconButton(onClick = { options = true }) {
                Icon(Icons.Outlined.Tune, contentDescription = stringResource(R.string.voice_options))
            }
        }
        Stage(
            state = state,
            mood = mood,
            look = animateOrbLook(settings.ttsVoice.orbLook),
            level = remember(viewModel) { viewModel::orbLevel },
            status = when {
                problem != null -> Status(problemText(problem), error = true, busy = false)
                else -> Status(phaseText(state.phase, muted), error = false, busy = state.phase.isBusy())
            },
            captions = captions,
            onOrb = onOrb,
        )
        Row(
            Modifier
                .widthIn(max = 480.dp)
                .fillMaxWidth()
                .padding(top = 12.dp, bottom = 28.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (stopped) {
                RoundControl(Icons.Outlined.Refresh, stringResource(R.string.retry), onClick = onOrb)
            } else {
                RoundControl(
                    icon = if (muted) Icons.Filled.MicOff else Icons.Filled.Mic,
                    label = stringResource(if (muted) R.string.voice_unmute else R.string.voice_mute),
                    onClick = viewModel::toggleMute,
                    container = if (muted) MaterialTheme.colorScheme.onSurface else groupedCardColor(),
                    content = if (muted) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurface,
                )
            }
            RoundControl(
                icon = Icons.Filled.CallEnd,
                label = stringResource(R.string.voice_hang_up),
                onClick = hangUp,
                content = MaterialTheme.colorScheme.error,
            )
            RoundControl(
                icon = Icons.Outlined.Subtitles,
                label = stringResource(R.string.voice_captions),
                onClick = viewModel::toggleCaptions,
                container = if (captions) MaterialTheme.colorScheme.primaryContainer else groupedCardColor(),
                content = if (captions) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
            )
        }
    }

    if (options) {
        VoiceOptionsSheet(
            voice = settings.ttsVoice,
            onVoice = viewModel::setVoice,
            search = settings.search,
            onSearch = viewModel::setSearch,
            onDismiss = { options = false },
        )
    }
}

private data class Status(val text: String, val error: Boolean, val busy: Boolean)

/** 光球、状态行与字幕。字幕展开时光球缩小上移，三者之间的留白连续过渡 */
@Composable
private fun ColumnScope.Stage(
    state: VoiceState,
    mood: OrbMood,
    look: OrbLook,
    level: () -> Float,
    status: Status,
    captions: Boolean,
    onOrb: () -> Unit,
) {
    val shift by animateFloatAsState(
        if (captions) 1f else 0f,
        spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMediumLow),
        label = "captions",
    )
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    BoxWithConstraints(
        Modifier
            .weight(1f)
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
    ) {
        // 横屏、分屏等高度有限时按可用空间缩小光球与留白
        val large = minOf(ORB_LARGE, maxHeight * 0.5f, maxWidth * 0.7f)
        val small = minOf(ORB_SMALL, maxHeight * 0.3f)
        val gap = minOf(56.dp, maxHeight * 0.14f)
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.weight(lerp(1f, 0.2f, shift)))
            VoiceOrb(
                look = look,
                mood = mood,
                level = level,
                pressed = pressed,
                modifier = Modifier
                    .size(lerp(large, small, shift))
                    .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onOrb),
            )
            Spacer(Modifier.height(lerp(gap, gap * 0.6f, shift)))
            StatusLine(status)
            Box(
                Modifier
                    .weight(lerp(0.8f, 2.6f, shift))
                    .widthIn(max = 560.dp)
                    .fillMaxWidth(),
            ) {
                if (shift > 0.01f) {
                    Captions(
                        heard = state.heard,
                        reply = if (state.phase != VoicePhase.Listening) state.reply else "",
                        modifier = Modifier.graphicsLayer { alpha = shift },
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusLine(status: Status) {
    AnimatedContent(
        targetState = status,
        transitionSpec = {
            (fadeIn(tween(220, easing = MotionEaseOut)) + slideInVertically(tween(220, easing = MotionEaseOut)) { it / 3 }) togetherWith
                fadeOut(tween(120))
        },
        contentAlignment = Alignment.Center,
        modifier = Modifier.heightIn(min = 28.dp),
        label = "status",
    ) { current ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (current.busy) {
                Dots(MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(10.dp))
            }
            Text(
                current.text,
                style = MaterialTheme.typography.bodyLarge,
                color = if (current.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** 三个依次亮起的小点，表示正在进行 */
@Composable
private fun Dots(color: Color) {
    val phase by rememberInfiniteTransition(label = "dots").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing)),
        label = "phase",
    )
    Canvas(Modifier.size(width = 26.dp, height = 6.dp)) {
        val radius = size.height / 2f
        repeat(3) { i ->
            val wave = 0.5f + 0.5f * sin((phase - i * 0.18f) * 2f * PI.toFloat())
            drawCircle(color.copy(alpha = 0.25f + 0.6f * wave), radius, Offset(radius + i * (size.width - size.height) / 2f, radius))
        }
    }
}

/** 字幕：听到的话与回答，内容增长时跟到底部，上下边缘渐隐 */
@Composable
private fun Captions(heard: String, reply: String, modifier: Modifier = Modifier) {
    val scroll = rememberScrollState()
    LaunchedEffect(scroll.maxValue) { scroll.animateScrollTo(scroll.maxValue) }
    Column(
        modifier
            .fillMaxSize()
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                drawRect(
                    Brush.verticalGradient(0f to Color.Transparent, 0.1f to Color.Black, 0.9f to Color.Black, 1f to Color.Transparent),
                    blendMode = BlendMode.DstIn,
                )
            }
            .verticalScroll(scroll)
            .padding(vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (heard.isNotEmpty()) {
            Text(
                heard,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        if (reply.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(
                reply,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun RoundControl(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    container: Color = groupedCardColor(),
    content: Color = MaterialTheme.colorScheme.onSurface,
) {
    val source = remember { MutableInteractionSource() }
    val containerColor by animateColorAsState(container, tween(180), label = "container")
    val contentColor by animateColorAsState(content, tween(180), label = "content")
    Surface(
        onClick = onClick,
        modifier = Modifier
            .size(64.dp)
            .pressFeedback(source),
        shape = CircleShape,
        color = containerColor,
        contentColor = contentColor,
        // 深色主题下阴影不可见，靠底色与页面拉开层次
        shadowElevation = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) 0.dp else 3.dp,
        interactionSource = source,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(28.dp))
        }
    }
}

/** 通话中调整：音色（下一句朗读起生效，光球随之换装）与智能搜索 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VoiceOptionsSheet(
    voice: TtsVoice,
    onVoice: (TtsVoice) -> Unit,
    search: Boolean,
    onSearch: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    AppBottomSheet(
        onDismissRequest = onDismiss,
        skipPartiallyExpanded = true,
        containerColor = groupedPageColor(),
    ) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
        ) {
            VoicePicker(selected = voice, onSelect = onVoice)
            Spacer(Modifier.height(12.dp))
            Surface(shape = RoundedCornerShape(16.dp), color = groupedCardColor()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .toggleable(value = search, role = Role.Switch, onValueChange = onSearch)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(painterResource(R.drawable.ic_globe_language_outline_14), contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.search_switch), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Switch(checked = search, onCheckedChange = null)
                }
            }
        }
    }
}

private fun VoicePhase.mood(): OrbMood = when (this) {
    VoicePhase.Preparing -> OrbMood.Connecting
    VoicePhase.Listening -> OrbMood.Listening
    VoicePhase.Thinking -> OrbMood.Thinking
    VoicePhase.Speaking -> OrbMood.Speaking
    VoicePhase.Muted -> OrbMood.Muted
    VoicePhase.Stopped -> OrbMood.Stopped
}

private fun VoicePhase.isBusy() = this == VoicePhase.Preparing || this == VoicePhase.Listening || this == VoicePhase.Thinking

@Composable
private fun phaseText(phase: VoicePhase, muted: Boolean): String = when (phase) {
    VoicePhase.Preparing -> stringResource(R.string.voice_connecting)
    VoicePhase.Listening -> stringResource(R.string.voice_listening)
    VoicePhase.Thinking -> stringResource(R.string.voice_thinking)
    // 静音时不监听插话，只能点按打断
    VoicePhase.Speaking -> stringResource(if (muted) R.string.voice_speaking_muted else R.string.voice_speaking)
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
    VoiceProblem.ServiceRejected -> stringResource(R.string.voice_service_rejected)
}

private val ORB_LARGE = 220.dp
private val ORB_SMALL = 124.dp
