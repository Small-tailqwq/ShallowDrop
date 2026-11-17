package io.github.smalltailqwq.shallowdrop.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.smalltailqwq.shallowdrop.R
import io.github.smalltailqwq.shallowdrop.core.model.ChatFontSize
import io.github.smalltailqwq.shallowdrop.core.model.ReplySwipeDirection
import io.github.smalltailqwq.shallowdrop.core.model.ReplySwipeSettings
import io.github.smalltailqwq.shallowdrop.ui.components.BackButton
import io.github.smalltailqwq.shallowdrop.ui.theme.ChatTextTheme
import io.github.smalltailqwq.shallowdrop.ui.theme.groupedCardColor
import kotlinx.coroutines.flow.first
import kotlin.math.roundToInt

@Composable
internal fun fontSizeLabel(size: ChatFontSize): String = stringResource(when (size) {
    ChatFontSize.Small -> R.string.font_size_small
    ChatFontSize.Standard -> R.string.font_size_standard
    ChatFontSize.Large -> R.string.font_size_large
    ChatFontSize.ExtraLarge -> R.string.font_size_extra_large
})

@Composable
internal fun swipeDirectionLabel(direction: ReplySwipeDirection): String = stringResource(
    if (direction == ReplySwipeDirection.LeftToRight) R.string.reply_swipe_left_to_right else R.string.reply_swipe_right_to_left)

/**
 * 聊天字号：调节只作用于页内的预览对话，离开页面时才写入设置、应用到对话页。
 */
@Composable
fun FontSizeScreen(initial: ChatFontSize, onSave: (ChatFontSize) -> Unit, onBack: () -> Unit) {
    val slider = rememberPreviewSlider(initial.ordinal.toFloat(), 0f..ChatFontSize.entries.lastIndex.toFloat(), ChatFontSize.entries.lastIndex)
    val title = stringResource(R.string.chat_font_size)
    PreviewSettingsScaffold(
        title = title,
        onBack = {
            val selected = ChatFontSize.entries[slider.selectedValue.roundToInt()]
            if (selected != initial) onSave(selected)
            onBack()
        },
        compactWideControls = true,
        preview = { scroll ->
            ChatTextTheme(ChatFontSize.Small.sp + slider.value * 2f) { PelicanPreview(Modifier.verticalScroll(scroll)) }
        },
    ) { vertical, minHeight ->
        LabeledPreviewSlider(
            state = slider,
            title = title,
            labels = ChatFontSize.entries.map { fontSizeLabel(it) },
            vertical = vertical,
            modifier = Modifier.fillMaxWidth().heightIn(min = minHeight),
        )
    }
}

/**
 * 横滑重新生成：页内预览对话的最后一条回复可以实际滑动，死区与触发距离直接画在回复上。
 * 离开页面时写入设置。
 */
@Composable
fun ReplySwipeScreen(initial: ReplySwipeSettings, onSave: (ReplySwipeSettings) -> Unit, onBack: () -> Unit) {
    var enabled by rememberSaveable { mutableStateOf(initial.enabled) }
    var direction by rememberSaveable { mutableStateOf(initial.direction) }
    val deadZone = rememberPreviewSlider(initial.edgeDeadZoneDp.toFloat(), 0f..96f, 24)
    val distance = rememberPreviewSlider(initial.triggerDistanceDp.toFloat(), 48f..160f, 28)
    var version by rememberSaveable { mutableIntStateOf(0) }
    val draft = ReplySwipeSettings(enabled, direction, deadZone.value.roundToInt(), distance.value.roundToInt())
    PreviewSettingsScaffold(
        title = stringResource(R.string.reply_swipe_title),
        onBack = {
            val selected = ReplySwipeSettings(enabled, direction,
                deadZone.valueOnLeave(initial.edgeDeadZoneDp.toFloat()).roundToInt(),
                distance.valueOnLeave(initial.triggerDistanceDp.toFloat()).roundToInt())
            if (selected != initial) onSave(selected)
            onBack()
        },
        startAtBottom = true,
        preview = { scroll ->
            PelicanPreview(
                modifier = Modifier.verticalScroll(scroll),
                version = version,
                swipeSettings = draft,
                swipeGuide = true,
                onRegenerate = { version = (version + 1) % PELICAN_REPLY_VERSIONS.size },
                onSwitchVersion = { version = it },
            )
        },
    ) { _, _ ->
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .toggleable(enabled, role = Role.Switch, onValueChange = { enabled = it }),
            verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.reply_swipe_enable), modifier = Modifier.weight(1f))
            Switch(checked = enabled, onCheckedChange = null)
        }
        Column(Modifier.alpha(if (enabled) 1f else 0.5f)) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                ReplySwipeDirection.entries.forEachIndexed { index, option ->
                    SegmentedButton(
                        selected = direction == option,
                        onClick = { direction = option },
                        enabled = enabled,
                        shape = SegmentedButtonDefaults.itemShape(index, ReplySwipeDirection.entries.size),
                    ) { Text(swipeDirectionLabel(option)) }
                }
            }
            SwipeDistanceControl(stringResource(R.string.reply_swipe_dead_zone), deadZone, enabled)
            SwipeDistanceControl(stringResource(R.string.reply_swipe_distance), distance, enabled)
        }
    }
}

@Composable
private fun SwipeDistanceControl(title: String, slider: PreviewSliderState, enabled: Boolean) {
    Column(Modifier.padding(top = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, modifier = Modifier.weight(1f))
            Text(stringResource(R.string.reply_swipe_dp, slider.value.roundToInt()), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        PreviewSlider(slider, title, enabled = enabled, modifier = Modifier.fillMaxWidth())
    }
}

/**
 * 预览类设置页的骨架：上方是对话预览（与对话页同底色），下方或右侧是控件卡片。
 * 横屏较宽时左右排布，避免预览被控件挤没。系统返回与左上角返回都走 [onBack]。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PreviewSettingsScaffold(
    title: String,
    onBack: () -> Unit,
    preview: @Composable (ScrollState) -> Unit,
    startAtBottom: Boolean = false,
    compactWideControls: Boolean = false,
    controls: @Composable ColumnScope.(vertical: Boolean, minHeight: Dp) -> Unit,
) {
    val back by rememberUpdatedState(onBack)
    BackHandler { back() }
    val scroll = rememberScrollState()
    if (startAtBottom) {
        LaunchedEffect(scroll) {
            val max = snapshotFlow { scroll.maxValue }.first { it in 1 until Int.MAX_VALUE }
            scroll.scrollTo(max)
        }
    }
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(title, style = MaterialTheme.typography.titleMedium) },
                navigationIcon = { BackButton({ back() }, Modifier.padding(start = 12.dp)) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            val wide = maxWidth >= 600.dp && maxWidth > maxHeight
            val vertical = wide && compactWideControls
            val panelMaxHeight = maxHeight * 0.5f
            val panel: @Composable (Modifier) -> Unit = { modifier ->
                Surface(modifier.testTag("preview-controls"), shape = RoundedCornerShape(20.dp), color = groupedCardColor(), tonalElevation = 1.dp) {
                    BoxWithConstraints {
                        val minHeight = if (vertical) (maxHeight - 24.dp).coerceAtLeast(0.dp) else 0.dp
                        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp)) {
                            controls(vertical, minHeight)
                        }
                    }
                }
            }
            if (wide) {
                Row(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f).fillMaxHeight().testTag("preview-content"), contentAlignment = Alignment.TopCenter) { preview(scroll) }
                    panel(Modifier.width(if (vertical) 176.dp else 360.dp)
                        .then(if (vertical) Modifier.fillMaxHeight() else Modifier)
                        .padding(end = 16.dp, bottom = 16.dp).navigationBarsPadding())
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f).fillMaxWidth().testTag("preview-content"), contentAlignment = Alignment.TopCenter) { preview(scroll) }
                    panel(Modifier.fillMaxWidth().heightIn(max = panelMaxHeight).padding(horizontal = 12.dp).padding(bottom = 12.dp).navigationBarsPadding())
                }
            }
        }
    }
}
