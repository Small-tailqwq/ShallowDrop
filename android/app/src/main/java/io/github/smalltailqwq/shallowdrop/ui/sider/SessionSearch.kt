package io.github.smalltailqwq.shallowdrop.ui.sider

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.smalltailqwq.shallowdrop.core.contract.ChatRepository
import io.github.smalltailqwq.shallowdrop.core.contract.MessageMatch
import io.github.smalltailqwq.shallowdrop.core.model.SessionSummary
import io.github.smalltailqwq.shallowdrop.ui.common.uiViewModelFactory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import org.commonmark.node.AbstractVisitor
import org.commonmark.node.Code
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.Text
import org.commonmark.parser.Parser

/*
 * 会话搜索：会话标题、消息正文（提问与回复）、提问附带的文件名。
 * 结果按会话归并，最近更新在前；每个会话展示最近一条命中摘要。
 */

/** 命中片段：已截取到命中词附近的单行文本；高亮由界面按查询词标出。 */
@Immutable
data class SearchSnippet(val text: String, val fromUser: Boolean, val isFileName: Boolean)

@Immutable
data class SearchResult(
    val session: SessionSummary,
    val snippets: List<SearchSnippet>,
    val messageId: String? = null,
)

data class SearchTarget(val sessionId: String, val messageId: String?, val query: String)

private const val CONTEXT_BEFORE = 16
private const val CONTEXT_AFTER = 64
private val SEARCH_MARKDOWN = Parser.builder().build()
private val WHITESPACE = Regex("\\s+")

private fun previewText(text: String): String = buildString {
    SEARCH_MARKDOWN.parse(text).accept(object : AbstractVisitor() {
        override fun visit(text: Text) { append(text.literal) }
        override fun visit(code: Code) { append(code.literal) }
        override fun visit(lineBreak: SoftLineBreak) { append(' ') }
        override fun visit(lineBreak: HardLineBreak) { append(' ') }
        override fun visit(paragraph: Paragraph) { visitChildren(paragraph); append(' ') }
        override fun visit(heading: Heading) { visitChildren(heading); append(' ') }
        override fun visit(block: FencedCodeBlock) { append(block.literal); append(' ') }
        override fun visit(block: IndentedCodeBlock) { append(block.literal); append(' ') }
    })
}.replace(WHITESPACE, " ").trim()

/** 以命中词为中心截取单行片段，两端被截断时加省略号。needle 必须在 text 中出现（忽略大小写）。 */
fun snippetAround(text: String, needle: String, markdown: Boolean = true): String {
    val plain = if (markdown) previewText(text) else text.replace(WHITESPACE, " ").trim()
    // URL、Markdown 标记本身也能被搜索，保留这类命中的字面内容。
    val flat = if (plain.contains(needle, ignoreCase = true)) plain else text.replace(WHITESPACE, " ").trim()
    val at = flat.indexOf(needle, ignoreCase = true).coerceAtLeast(0)
    val from = (at - CONTEXT_BEFORE).coerceAtLeast(0)
    val to = (at + needle.length + CONTEXT_AFTER).coerceAtMost(flat.length)
    val body = flat.substring(from, to).trim()
    return (if (from > 0) "…" else "") + body + (if (to < flat.length) "…" else "")
}

/**
 * 合并标题命中与消息命中。sessions 的顺序决定结果顺序；标题命中但消息无命中的会话也保留（snippets 为空）。
 * 取最近的命中；时间相同时优先正文，再优先回复，避免同一轮问答重复占位。
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
        val match = bySession[session.id].orEmpty().maxWithOrNull(
            compareBy<MessageMatch> { it.createdAt }.thenBy { !it.isFileName }.thenBy { !it.fromUser },
        )
        val snippets = match?.let { listOf(SearchSnippet(snippetAround(it.text, needle, markdown = !it.isFileName), it.fromUser, it.isFileName)) }.orEmpty()
        if (!titleHit && snippets.isEmpty()) null else SearchResult(session, snippets, match?.messageId)
    }
}

/** 某次查询读到的消息命中；query 为去掉首尾空白后的查询词。 */
data class SearchMatches(val query: String, val matches: List<MessageMatch>)

@OptIn(ExperimentalCoroutinesApi::class)
class SessionSearchViewModel(private val repository: ChatRepository) : ViewModel() {
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query

    fun setQuery(value: String) {
        _query.value = value
    }

    /** 会话列表，最近更新在前；标题命中和重命名、删除后的结果都由界面据此重新合并 */
    val sessions: StateFlow<List<SessionSummary>> = repository.sessions
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 改词即取消旧查询并清空结果，停顿 200ms 后读取消息；首尾空白不触发重复读取。 */
    val matches: StateFlow<SearchMatches?> = _query
        .map { it.trim() }
        .distinctUntilChanged()
        .transformLatest { needle ->
            emit(null)
            if (needle.isNotEmpty()) {
                delay(200)
                emit(SearchMatches(needle, repository.searchMessages(needle)))
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    companion object {
        val Factory = uiViewModelFactory { deps, _ -> SessionSearchViewModel(deps.chatRepository) }
    }
}
