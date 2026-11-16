package io.github.smalltailqwq.shallowdrop.ui.voice

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.smalltailqwq.shallowdrop.R
import io.github.smalltailqwq.shallowdrop.core.model.TtsVoice
import io.github.smalltailqwq.shallowdrop.ui.motion.IconButton
import io.github.smalltailqwq.shallowdrop.ui.motion.MotionEaseOut
import io.github.smalltailqwq.shallowdrop.ui.theme.groupedPageColor
import kotlin.math.abs
import kotlinx.coroutines.launch

@Composable
fun TtsVoice.label(): String = when (this) {
    TtsVoice.Stella -> stringResource(R.string.tts_voice_stella)
    TtsVoice.Tide -> stringResource(R.string.tts_voice_tide)
}

/** 试听时朗读的一句话 */
@Composable
private fun TtsVoice.sample(): String = when (this) {
    TtsVoice.Stella -> stringResource(R.string.tts_voice_sample_stella)
    TtsVoice.Tide -> stringResource(R.string.tts_voice_sample_tide)
}

/**
 * 音色选择：每个音色一颗光球，左右滑动切换，停在哪颗就选中哪颗；点旁边的光球滑过去。
 *
 * @param onAudition 非空时可试听：点当前光球或名字旁的喇叭，由调用方朗读一句示例
 * @param speaking 正在试听的音色，对应光球随 [level] 跳动
 */
@Composable
fun VoicePicker(
    selected: TtsVoice,
    onSelect: (TtsVoice) -> Unit,
    modifier: Modifier = Modifier,
    speaking: TtsVoice? = null,
    level: () -> Float = { 0f },
    onAudition: ((TtsVoice) -> Unit)? = null,
) {
    val voices = TtsVoice.entries
    val pager = rememberPagerState(initialPage = voices.indexOf(selected)) { voices.size }
    val scope = rememberCoroutineScope()
    val currentSelected by rememberUpdatedState(selected)
    val currentOnSelect by rememberUpdatedState(onSelect)
    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.collect { page ->
            if (voices[page] != currentSelected) currentOnSelect(voices[page])
        }
    }
    val current = voices[pager.currentPage]
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            HorizontalPager(
                state = pager,
                pageSize = PageSize.Fixed(PAGE_WIDTH),
                contentPadding = PaddingValues(horizontal = ((maxWidth - PAGE_WIDTH) / 2).coerceAtLeast(0.dp)),
                modifier = Modifier.height(ORB_SIZE * ORB_SPREAD),
            ) { page ->
                val voice = voices[page]
                val name = voice.label()
                val interaction = remember { MutableInteractionSource() }
                val pressed by interaction.collectIsPressedAsState()
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            // 离中心越远越小、越淡。逐绘制调制透明度，不走离屏缓冲，光晕才不会被页面边界裁掉
                            val distance = abs(pager.currentPage - page + pager.currentPageOffsetFraction).coerceIn(0f, 1f)
                            val scale = lerp(1f, 0.6f, distance)
                            scaleX = scale
                            scaleY = scale
                            alpha = lerp(1f, 0.5f, distance)
                            compositingStrategy = CompositingStrategy.ModulateAlpha
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    VoiceOrb(
                        look = voice.orbLook,
                        mood = if (speaking == voice) OrbMood.Speaking else OrbMood.Preview,
                        level = level,
                        pressed = pressed,
                        spread = ORB_SPREAD,
                        initialScale = 0.92f,
                        modifier = Modifier
                            .size(ORB_SIZE)
                            .semantics { contentDescription = name }
                            .clickable(interactionSource = interaction, indication = null, role = Role.Button) {
                                if (page != pager.currentPage) {
                                    scope.launch { pager.animateScrollToPage(page) }
                                } else {
                                    onAudition?.invoke(voice)
                                }
                            },
                    )
                }
            }
        }
        Row(Modifier.height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            // 喇叭按钮在右，左边留同样的宽度让名字居中
            if (onAudition != null) Spacer(Modifier.width(48.dp))
            AnimatedContent(
                targetState = current,
                transitionSpec = { fadeIn(tween(220, easing = MotionEaseOut)) togetherWith fadeOut(tween(120)) },
                label = "voiceName",
            ) { voice ->
                Text(voice.label(), style = MaterialTheme.typography.titleMedium)
            }
            if (onAudition != null) {
                IconButton(onClick = { onAudition(current) }) {
                    Icon(
                        painterResource(if (speaking == current) R.drawable.ic_stop_circle_outline_20 else R.drawable.ic_speaker_outline_20),
                        contentDescription = stringResource(if (speaking == current) R.string.tts_voice_audition_stop else R.string.tts_voice_audition),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Row(
            Modifier.padding(top = 4.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            voices.forEach { voice ->
                val width by animateDpAsState(if (voice == current) 16.dp else 6.dp, tween(220, easing = MotionEaseOut), label = "dot")
                Box(
                    Modifier
                        .size(width, 6.dp)
                        .background(
                            if (voice == current) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.outlineVariant,
                            CircleShape,
                        ),
                )
            }
        }
    }
}

/** 设置页的音色选择：底部面板，可试听 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoicePickerSheet(
    onDismiss: () -> Unit,
    viewModel: VoicePreviewViewModel = viewModel(factory = VoicePreviewViewModel.Factory),
) {
    val voice by viewModel.voice.collectAsStateWithLifecycle()
    val speaking by viewModel.speaking.collectAsStateWithLifecycle()
    val samples = TtsVoice.entries.associateWith { it.sample() }
    DisposableEffect(viewModel) {
        onDispose { viewModel.stop() }
    }
    // 内容不高，直接完全展开（横屏时半展开会把名字挡在屏幕外）
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = groupedPageColor(),
    ) {
        VoicePicker(
            selected = voice,
            onSelect = viewModel::select,
            speaking = speaking,
            level = viewModel::level,
            onAudition = { viewModel.audition(it, samples.getValue(it)) },
            modifier = Modifier
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
        )
    }
}

private val ORB_SIZE = 132.dp
private val PAGE_WIDTH = 176.dp

/** 光晕绘制范围相对光球的倍数，也决定选择器的高度 */
private const val ORB_SPREAD = 1.7f
