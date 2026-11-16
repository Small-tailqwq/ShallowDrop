package io.github.smalltailqwq.shallowdrop.ui.chat.message

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import io.github.smalltailqwq.shallowdrop.ui.motion.IconButton
import androidx.compose.material3.MaterialTheme
import io.github.smalltailqwq.shallowdrop.ui.motion.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import io.github.smalltailqwq.shallowdrop.ui.motion.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.smalltailqwq.shallowdrop.R
import io.github.smalltailqwq.shallowdrop.core.contract.TurnView
import io.github.smalltailqwq.shallowdrop.core.model.Message
import io.github.smalltailqwq.shallowdrop.core.model.StopReason
import io.github.smalltailqwq.shallowdrop.core.model.ReplySwipeSettings
import io.github.smalltailqwq.shallowdrop.ui.chat.reply.NoticeAction
import io.github.smalltailqwq.shallowdrop.ui.chat.reply.Source
import io.github.smalltailqwq.shallowdrop.ui.chat.reply.buildReplyView
import io.github.smalltailqwq.shallowdrop.ui.chat.reply.describeReply
import io.github.smalltailqwq.shallowdrop.ui.common.resolve
import io.github.smalltailqwq.shallowdrop.ui.components.DsIcon

/** 助手回复，对照 AssistantMessage.tsx。 */
@Composable
fun AssistantMessage(
    turn: TurnView,
    isLast: Boolean,
    streaming: Boolean,
    busy: Boolean,
    onRegenerate: () -> Unit,
    onContinue: () -> Unit,
    onSwitchBranch: (Int) -> Unit,
    onOpenSources: (List<Source>) -> Unit,
    onChangeApiKey: () -> Unit,
    modifier: Modifier = Modifier,
    reading: Boolean = false,
    onReadAloud: (String) -> Unit = {},
    swipeSettings: ReplySwipeSettings = ReplySwipeSettings(),
    onNewChat: () -> Unit = {},
) {
    val message = turn.message as Message.Assistant
    val view = remember(message, streaming) { buildReplyView(message, streaming) }
    val notice = if (streaming) null else remember(message) { describeReply(message) }
    // 安全拒绝：已下发的思考与正文按协议丢弃，只显示模板答复
    val refused = message.stopReason == StopReason.Refusal
    val answer = if (refused) null else view.answer
    val noticeText = notice?.text?.resolve()
    val responseText = listOfNotNull(answer?.text, noticeText?.takeIf { notice.asResponse })
        .filter { it.isNotEmpty() }
        .joinToString("\n\n")
    // 只有错误提示、没有正文时不提供复制；重试放进错误卡片，操作栏不再重复
    val copyText = responseText
    val errorOnly = notice != null && !notice.asResponse && responseText.isEmpty()
    val canRegenerate = isLast && !busy && !refused && turn.canRegenerate && notice?.action != NoticeAction.NewChat
    val retryInCard = errorOnly && canRegenerate
    val waiting = streaming && view.steps.isEmpty() && view.answer == null && !message.thinking
    val copy = rememberCopyAction()

    Column(modifier.fillMaxWidth()) {
        if (!refused) {
            ReplyArea(
                message = message,
                view = view,
                streaming = streaming,
                hasResponse = responseText.isNotEmpty(),
                onOpenSources = onOpenSources,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        if (answer != null) {
            RegeneratableReply(enabled = canRegenerate && !streaming, settings = swipeSettings, onRegenerate = onRegenerate) {
                MessageMarkdown(answer.markdown, view.sources, streaming = streaming)
            }
        }
        if (waiting) WaitingDots(Modifier.padding(vertical = 8.dp))
        if (notice != null && noticeText != null) {
            if (notice.asResponse) {
                MessageMarkdown(noticeText, emptyList())
            } else {
                // 错误卡片：原因 + 就地可点的下一步（重试 / 去充值 / 换 Key），不必去找操作栏里的小图标
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f),
                    modifier = Modifier.padding(top = 4.dp).fillMaxWidth(),
                ) {
                    Column(Modifier.padding(start = 14.dp, end = 8.dp, top = 12.dp, bottom = if (retryInCard || notice.action != null) 2.dp else 12.dp)) {
                        Row(verticalAlignment = Alignment.Top) {
                            DsIcon(
                                R.drawable.ic_warning_outline_16,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(top = 3.dp).size(16.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                noticeText,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(end = 6.dp),
                            )
                        }
                        Row(Modifier.padding(start = 14.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (retryInCard) {
                                TextButton(onClick = onRegenerate, enabled = !busy) { Text(stringResource(R.string.retry)) }
                            }
                            when (notice.action) {
                                NoticeAction.ApiKey -> TextButton(onClick = onChangeApiKey) { Text(stringResource(R.string.change_api_key)) }
                                NoticeAction.TopUp -> TextButton(onClick = onChangeApiKey) { Text(stringResource(R.string.top_up)) }
                                NoticeAction.NewChat -> TextButton(onClick = onNewChat, enabled = !busy) { Text(stringResource(R.string.new_chat)) }
                                null -> Unit
                            }
                        }
                    }
                }
            }
        }
        if (!streaming && !refused && view.searchResults.isNotEmpty()) {
            Surface(
                onClick = { onOpenSources(view.searchResults) },
                shape = RoundedCornerShape(50),
                color = Color.Transparent,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.padding(top = 12.dp),
            ) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    DsIcon(R.drawable.ic_browse_outline_16, contentDescription = null, modifier = Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        pluralStringResource(R.plurals.search_summary, view.searchResults.size, view.searchResults.size),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
        // 只剩错误卡片（复制、重试、分支、续写都没有）时不留一行空白操作栏
        val hasActions = copyText.isNotEmpty() || (canRegenerate && !retryInCard) || turn.siblingCount > 1 || (isLast && turn.canContinue)
        if (!streaming && hasActions) {
            // 操作栏：复制、版本切换、继续生成靠左，重新生成在最右
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp).height(40.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (copyText.isNotEmpty()) {
                    ActionIcon(R.drawable.ic_copy_outline_16, stringResource(R.string.copy), enabled = true) { copy(copyText) }
                    ReadAloudButton(reading) { onReadAloud(copyText) }
                }
                BranchSwitcher(turn.siblingIndex, turn.siblingCount, enabled = !busy, onSelect = onSwitchBranch)
                if (isLast && turn.canContinue) {
                    Spacer(Modifier.width(4.dp))
                    OutlinedButton(onClick = onContinue, enabled = !busy, modifier = Modifier.height(32.dp)) {
                        Text(stringResource(R.string.continue_generating), style = MaterialTheme.typography.labelMedium)
                    }
                }
                if (canRegenerate && !retryInCard) {
                    Spacer(Modifier.weight(1f))
                    ActionIcon(R.drawable.ic_refresh_outline_16, stringResource(R.string.regenerate), enabled = true, onClick = onRegenerate)
                }
            }
        }
    }
}

@Composable
private fun ActionIcon(@DrawableRes icon: Int, label: String, enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(40.dp)) {
        DsIcon(icon, contentDescription = label, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.38f))
    }
}

/** 等待首个事件时的三个圆点 */
@Composable
private fun WaitingDots(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "waiting")
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(3) { index ->
            val alpha by transition.animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(600, delayMillis = index * 150), RepeatMode.Reverse),
                label = "dot$index",
            )
            Box(
                Modifier
                    .size(8.dp)
                    .alpha(alpha)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurfaceVariant),
            )
        }
    }
}
