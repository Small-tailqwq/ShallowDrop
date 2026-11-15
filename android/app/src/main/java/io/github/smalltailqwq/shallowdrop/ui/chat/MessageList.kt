package io.github.smalltailqwq.shallowdrop.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import io.github.smalltailqwq.shallowdrop.core.contract.ConversationState
import io.github.smalltailqwq.shallowdrop.core.model.Message
import io.github.smalltailqwq.shallowdrop.ui.chat.message.AssistantMessage
import io.github.smalltailqwq.shallowdrop.ui.chat.message.UserMessage
import io.github.smalltailqwq.shallowdrop.ui.chat.reply.Source

/** 消息列表的操作回调 */
class MessageActions(
    val onRegenerate: (String) -> Unit,
    val onContinue: (String) -> Unit,
    val onEdit: (String, String) -> Unit,
    val onSwitchBranch: (String, Int) -> Unit,
    val onOpenSources: (List<Source>) -> Unit,
    val onChangeApiKey: () -> Unit,
)

/**
 * 跟随底部状态，对照 MessageList.tsx：流式内容增长时跟随；用户向上拖动后停止跟随，
 * 回到底部后恢复。
 */
@Stable
class FollowState {
    var following by mutableStateOf(true)

    val connection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            // 手指向下拖动即内容向上滚动
            if (source == NestedScrollSource.UserInput && available.y > 0f) following = false
            return Offset.Zero
        }
    }
}

private const val BOTTOM_KEY = "bottom-anchor"

/** 滚到底部锚点；LazyColumn 会把越界的偏移夹到内容末尾。 */
suspend fun LazyListState.scrollToBottom(animated: Boolean = false) {
    val last = layoutInfo.totalItemsCount - 1
    if (last < 0) return
    if (animated) animateScrollToItem(last) else scrollToItem(last)
}

@Composable
fun MessageList(
    conversation: ConversationState,
    listState: LazyListState,
    follow: FollowState,
    actions: MessageActions,
    modifier: Modifier = Modifier,
) {
    val turns = conversation.turns
    val busy = conversation.generating

    // 内容变化（流式增量、新消息）时按跟随状态滚到底部
    LaunchedEffect(conversation, follow.following) {
        if (follow.following) listState.scrollToBottom()
    }
    // 用户自己滚回底部时恢复跟随
    LaunchedEffect(listState, follow) {
        snapshotFlow { listState.canScrollForward }.collect { canForward ->
            if (!canForward) follow.following = true
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier.nestedScroll(follow.connection),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    ) {
        itemsIndexed(turns, key = { _, turn -> turn.message.id }, contentType = { _, turn -> turn.message::class }) { index, turn ->
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                val itemModifier = Modifier.widthIn(max = 760.dp).fillMaxWidth()
                when (val message = turn.message) {
                    is Message.User -> UserMessage(
                        turn = turn,
                        busy = busy,
                        onEdit = { text ->
                            follow.following = true
                            actions.onEdit(message.id, text)
                        },
                        onSwitchBranch = { i ->
                            // 切换版本时停留在原位置，避免长短变化把操作区带出视口
                            follow.following = false
                            actions.onSwitchBranch(message.id, i)
                        },
                        modifier = itemModifier.padding(top = 16.dp, bottom = 8.dp),
                    )
                    is Message.Assistant -> AssistantMessage(
                        turn = turn,
                        isLast = index == turns.lastIndex,
                        streaming = busy && index == turns.lastIndex,
                        busy = busy,
                        onRegenerate = {
                            follow.following = true
                            actions.onRegenerate(message.id)
                        },
                        onContinue = {
                            follow.following = true
                            actions.onContinue(message.id)
                        },
                        onSwitchBranch = { i ->
                            follow.following = false
                            actions.onSwitchBranch(message.id, i)
                        },
                        onOpenSources = actions.onOpenSources,
                        onChangeApiKey = actions.onChangeApiKey,
                        modifier = itemModifier,
                    )
                }
            }
        }
        item(key = BOTTOM_KEY) { Spacer(Modifier.height(1.dp)) }
    }
}

@Composable
fun rememberFollowState(key: Any?): FollowState = remember(key) { FollowState() }
