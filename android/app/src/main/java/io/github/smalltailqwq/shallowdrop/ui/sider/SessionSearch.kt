package io.github.smalltailqwq.shallowdrop.ui.sider

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.smalltailqwq.shallowdrop.core.contract.ChatRepository
import io.github.smalltailqwq.shallowdrop.core.contract.MessageMatch
import io.github.smalltailqwq.shallowdrop.core.model.SessionSummary
import io.github.smalltailqwq.shallowdrop.ui.common.uiViewModelFactory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn

/*
 * 会话搜索：会话标题、消息正文（提问与回复）、提问附带的文件名。
 * 结果按会话归并，会话顺序沿用最近更新在前；每个会话最多列出 MAX_SNIPPETS 条命中片段。
 */

/** 命中片段：已截取到命中词附近的单行文本；高亮由界面按查询词标出。 */
@Immutable
data class SearchSnippet(val text: String, val fromUser: Boolean, val isFileName: Boolean)

@Immutable
data class SearchResult(
    val session: SessionSummary,
    val snippets: List<SearchSnippet>,
)

private const val MAX_SNIPPETS = 3
private const val CONTEXT_BEFORE = 16
private const val CONTEXT_AFTER = 64

/** 以命中词为中心截取单行片段，两端被截断时加省略号。needle 必须在 text 中出现（忽略大小写）。 */
fun snippetAround(text: String, needle: String): String {
    val flat = text.map { if (it.isWhitespace()) ' ' else it }.joinToString("")
    val at = flat.indexOf(needle, ignoreCase = true).coerceAtLeast(0)
    val from = (at - CONTEXT_BEFORE).coerceAtLeast(0)
    val to = (at + needle.length + CONTEXT_AFTER).coerceAtMost(flat.length)
    val body = flat.substring(from, to).trim()
    return (if (from > 0) "…" else "") + body + (if (to < flat.length) "…" else "")
}

/**
 * 合并标题命中与消息命中。sessions 的顺序决定结果顺序；标题命中但消息无命中的会话也保留（snippets 为空）。
 * 同一条消息只取一个片段（正文优先，其次文件名）。
 */
fun buildSearchResults(
    sessions: List<SessionSummary>,
    matches: List<MessageMatch>,
    query: String,
    untitled: String,
): List<SearchResult> {
    val needle = query.trim()
    if (needle.isEmpty()) return emptyList()
    val bySession = matches.groupBy { it.sessionId }
    return sessions.mapNotNull { session ->
        val titleHit = session.title.ifEmpty { untitled }.contains(needle, ignoreCase = true)
        val snippets = bySession[session.id].orEmpty()
            .sortedWith(compareBy({ it.createdAt }, { it.isFileName }))
            .distinctBy { it.messageId }
            .take(MAX_SNIPPETS)
            .map { SearchSnippet(snippetAround(it.text, needle), it.fromUser, it.isFileName) }
        if (!titleHit && snippets.isEmpty()) null else SearchResult(session, snippets)
    }
}

/** 某次查询读到的消息命中；query 为去掉首尾空白后的查询词。 */
data class SearchMatches(val query: String, val matches: List<MessageMatch>)

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class SessionSearchViewModel(private val repository: ChatRepository) : ViewModel() {
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query

    fun setQuery(value: String) {
        _query.value = value
    }

    /** 会话列表，最近更新在前；标题命中和重命名、删除后的结果都由界面据此重新合并 */
    val sessions: StateFlow<List<SessionSummary>> = repository.sessions
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 查询为空时为 null；输入停顿 200ms 后才读取消息，新的输入会取消上一次读取 */
    val matches: StateFlow<SearchMatches?> = _query
        .debounce { if (it.isBlank()) 0L else 200L }
        .mapLatest { raw ->
            val needle = raw.trim()
            if (needle.isEmpty()) null else SearchMatches(needle, repository.searchMessages(needle))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    companion object {
        val Factory = uiViewModelFactory { deps, _ -> SessionSearchViewModel(deps.chatRepository) }
    }
}
