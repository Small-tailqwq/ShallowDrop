package io.github.smalltailqwq.shallowdrop.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.smalltailqwq.shallowdrop.R
import io.github.smalltailqwq.shallowdrop.core.contract.TurnView
import io.github.smalltailqwq.shallowdrop.core.model.Message
import io.github.smalltailqwq.shallowdrop.core.model.ReplyBlock
import io.github.smalltailqwq.shallowdrop.core.model.ReplyStatus
import io.github.smalltailqwq.shallowdrop.core.model.ReplySwipeSettings
import io.github.smalltailqwq.shallowdrop.ui.chat.message.AssistantMessage
import io.github.smalltailqwq.shallowdrop.ui.chat.message.UserMessage

/** 最后一条回复可切换的版本数，滑动重新生成时依次轮换 */
internal val PELICAN_REPLY_VERSIONS = listOf(R.string.pelican_reply_2_v1, R.string.pelican_reply_2_v2, R.string.pelican_reply_2_v3)

/**
 * 「鹈鹕小剧场」：字号与滑动设置共用的静态预览对话。
 * 用真实的消息组件渲染，只在内存里构造消息，不写数据库、不请求 API。
 * [version] 为最后一条回复当前显示的版本；[onRegenerate] 为空时最后一条回复不可滑动。
 */
@Composable
internal fun PelicanPreview(
    modifier: Modifier = Modifier,
    version: Int = 0,
    swipeSettings: ReplySwipeSettings = ReplySwipeSettings(),
    swipeGuide: Boolean = false,
    onRegenerate: (() -> Unit)? = null,
    onSwitchVersion: (Int) -> Unit = {},
) {
    val user1 = stringResource(R.string.pelican_user_1)
    val thinking = stringResource(R.string.pelican_thinking_1)
    val reply1 = stringResource(R.string.pelican_reply_1)
    val user2 = stringResource(R.string.pelican_user_2)
    val reply2 = stringResource(PELICAN_REPLY_VERSIONS[version])
    val turns = remember(user1, thinking, reply1, user2) {
        listOf(
            TurnView(Message.User(id = "pelican-u1", createdAt = 0, content = user1), 0, 1),
            TurnView(
                Message.Assistant(
                    id = "pelican-a1",
                    createdAt = 0,
                    thinking = true,
                    status = ReplyStatus.Done,
                    blocks = listOf(
                        ReplyBlock.Thinking(thinking, startedAt = 0, endedAt = 12_000),
                        ReplyBlock.Text(reply1),
                    ),
                ),
                0, 1,
            ),
            TurnView(Message.User(id = "pelican-u2", createdAt = 0, content = user2), 0, 1),
        )
    }
    val last = remember(reply2, version, onRegenerate != null) {
        TurnView(
            Message.Assistant(id = "pelican-a2-$version", createdAt = 0, status = ReplyStatus.Done, blocks = listOf(ReplyBlock.Text(reply2))),
            siblingIndex = version,
            siblingCount = if (onRegenerate != null) PELICAN_REPLY_VERSIONS.size else 1,
            canRegenerate = onRegenerate != null,
        )
    }
    // 与 MessageList 相同的间距，预览和对话页看起来一致
    Column(modifier.widthIn(max = 760.dp).fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        turns.forEach { turn ->
            when (turn.message) {
                is Message.User -> UserMessage(turn, busy = false, onEdit = {}, onSwitchBranch = {},
                    modifier = Modifier.padding(top = 20.dp, bottom = 20.dp))
                is Message.Assistant -> AssistantMessage(
                    turn = turn, isLast = false, streaming = false, busy = false,
                    onRegenerate = {}, onContinue = {}, onSwitchBranch = {}, onOpenSources = {}, onChangeApiKey = {},
                )
            }
        }
        AssistantMessage(
            turn = last, isLast = true, streaming = false, busy = false,
            swipeSettings = swipeSettings,
            swipeGuide = swipeGuide,
            onRegenerate = { onRegenerate?.invoke() },
            onContinue = {}, onSwitchBranch = onSwitchVersion, onOpenSources = {}, onChangeApiKey = {},
        )
    }
}
