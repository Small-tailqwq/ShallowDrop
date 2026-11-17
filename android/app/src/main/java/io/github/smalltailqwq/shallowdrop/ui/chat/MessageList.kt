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
import androidx.compose.runtime.CompositionLocalProvider
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
import io.github.smalltailqwq.shallowdrop.core.model.ReplySwipeSettings
import io.github.smalltailqwq.shallowdrop.ui.chat.message.AssistantMessage
import io.github.smalltailqwq.shallowdrop.ui.chat.message.UserMessage
import io.github.smalltailqwq.shallowdrop.ui.chat.reply.Source
import io.github.smalltailqwq.shallowdrop.ui.components.LocalSearchHighlight
import io.github.smalltailqwq.shallowdrop.ui.components.SearchHighlightState
import io.github.smalltailqwq.shallowdrop.ui.sider.SearchTarget
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/** 消息列表的操作回调 */
class MessageActions(
    val onRegenerate: (String) -> Unit,
    val onContinue: (String) -> Unit,
    val onEdit: (String, String) -> Unit,
    val onSwitchBranch: (String, Int) -> Unit,
    val onOpenSources: (List<Source>) -> Unit,
    val onChangeApiKey: () -> Unit,
    val onFork: (String) -> Unit = {},
    val onOpenPages: (List<Source>) -> Unit = onOpenSources,
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
    readingId: String? = null,
    onReadAloud: (String, String) -> Unit = { _, _ -> },
    replySwipe: ReplySwipeSettings = ReplySwipeSettings(),
    onNewChat: () -> Unit = {},
    searchTarget: SearchTarget? = null,
    onSearchTargetHandled: (SearchTarget) -> Unit = {},
) {
    val turns = conversation.turns
    val busy = conversation.generating
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var highlight by remember(conversation.session.id) { mutableStateOf<SearchHighlightState?>(null) }
    val target = searchTarget?.takeIf { it.sessionId == conversation.session.id }
    val targetIndex = turns.indexOfFirst { it.message.id == target?.messageId }
    val locating = target != null || highlight != null

    LaunchedEffect(target, targetIndex, lifecycle) {
        if (target != null && targetIndex >= 0) {
            lifecycle.currentStateFlow.first { it.isAtLeast(Lifecycle.State.RESUMED) }
            follow.following = false
            listState.scrollToItem(targetIndex)
            highlight = SearchHighlightState(requireNotNull(target.messageId), target.query)
            onSearchTargetHandled(target)
        }
    }
    LaunchedEffect(highlight, highlight?.positioned) {
        // 关键词滚入视口后重新计时；不可见的 URL 或语法命中也会按时结束。
        if (highlight != null) {
            delay(2_000)
            highlight = null
        }
    }

    // 内容变化（流式增量、新消息）时按跟随状态滚到底部
    LaunchedEffect(conversation, follow.following, locating) {
        // 使用数据中的锚点索引，首次布局前 layoutInfo 还没有条目。
        if (!locating && follow.following) listState.scrollToItem(turns.size)
    }
    // 流式 Markdown 在后台解析后才增长高度，继续跟随实际布局。
    LaunchedEffect(listState, follow.following, turns.size, locating) {
        if (!locating && follow.following) {
            snapshotFlow { listState.canScrollForward }.collect { canForward ->
                if (canForward && follow.following) listState.requestScrollToItem(turns.size)
            }
        }
    }
    // 用户自己滚回底部时恢复跟随
    LaunchedEffect(listState, follow, locating) {
        if (locating) return@LaunchedEffect
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
            CompositionLocalProvider(LocalSearchHighlight provides highlight?.takeIf { it.messageId == turn.message.id }) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                    val itemModifier = Modifier.widthIn(max = 760.dp).fillMaxWidth()
                    when (val message = turn.message) {
                        is Message.User -> UserMessage(
                            turn = turn,
                            busy = busy,
                            reading = readingId == message.id,
                            onReadAloud = { text -> onReadAloud(message.id, text) },
                            onEdit = { text ->
                                follow.following = true
                                actions.onEdit(message.id, text)
                            },
                            onSwitchBranch = { i ->
                                // 切换版本时停留在原位置，避免长短变化把操作区带出视口
                                follow.following = false
                                actions.onSwitchBranch(message.id, i)
                            },
                            modifier = itemModifier.padding(top = 20.dp, bottom = 20.dp),
                        )
                        is Message.Assistant -> AssistantMessage(
                            turn = turn,
                            isLast = index == turns.lastIndex,
                            streaming = busy && index == turns.lastIndex,
                            busy = busy,
                            swipeSettings = replySwipe,
                            reading = readingId == message.id,
                            onReadAloud = { text -> onReadAloud(message.id, text) },
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
                            onOpenPages = actions.onOpenPages,
                            onFork = { actions.onFork(message.id) },
                            onChangeApiKey = actions.onChangeApiKey,
                            onNewChat = onNewChat,
                            modifier = itemModifier,
                        )
                    }
                }
            }
        }
        item(key = BOTTOM_KEY) { Spacer(Modifier.height(1.dp)) }
    }
}

@Composable
fun rememberFollowState(key: Any?): FollowState = remember(key) { FollowState() }
