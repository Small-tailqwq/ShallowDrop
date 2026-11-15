package io.github.smalltailqwq.shallowdrop.ui.chat.reply

import io.github.smalltailqwq.shallowdrop.core.model.Message
import io.github.smalltailqwq.shallowdrop.core.model.ReplyBlock
import io.github.smalltailqwq.shallowdrop.core.model.ServerToolInput
import io.github.smalltailqwq.shallowdrop.core.model.ServerToolKind
import io.github.smalltailqwq.shallowdrop.core.model.TextCitation
import java.net.URI
import kotlin.math.max
import kotlin.math.roundToLong

/*
 * 由回复内容块推导界面结构，对照 src/services/chat/view.ts：
 * 思考与工具步骤时间线、带引用标记的正文、可引用来源与搜索结果。只读计算，不改动内容块。
 */

/** 可引用来源；index 为角标编号，从 1 开始。 */
data class Source(
    val url: String,
    val title: String,
    /** 优先使用服务端站点名，没有时用域名（去掉 www.） */
    val siteName: String,
    val publishedAt: String?,
    val snippet: String?,
    val siteIcon: String?,
    val index: Int,
)

enum class ToolStatus { Wip, Done, Failed }

data class PageItem(val id: String, val url: String, val title: String, val status: ToolStatus)

data class FindItem(val id: String, val pattern: String, val status: ToolStatus)

sealed interface Step {
    data class Search(
        val key: String,
        val kind: ServerToolKind,
        val queries: List<String>,
        val results: List<Source>,
        val status: ToolStatus,
    ) : Step

    data class Open(val pages: List<PageItem>) : Step

    data class Find(val patterns: List<FindItem>) : Step

    data class ReadLink(val links: List<PageItem>) : Step

    data class Thinking(val key: String, val blocks: List<ReplyBlock.Thinking>) : Step

    /** 工具调用前输出的说明文字 */
    data class Preamble(val key: String, val markdown: String) : Step
}

/** markdown 中在引用处插入了 `[citation:N]`；text 为原文（用于复制）。 */
data class Answer(val markdown: String, val text: String)

data class ReplyView(
    val steps: List<Step>,
    val answer: Answer?,
    val sources: List<Source>,
    val searchResults: List<Source>,
)

fun ServerToolKind.isDeepSearch() = this == ServerToolKind.DeepSearch || this == ServerToolKind.DeepImageSearch

fun ServerToolKind.isImageSearch() = this == ServerToolKind.ImageSearch || this == ServerToolKind.DeepImageSearch

fun siteNameOf(url: String): String = try {
    URI(url).host?.removePrefix("www.").orEmpty()
} catch (_: Exception) {
    ""
}

/** 来源登记：同一 URL 只编号一次，编号按首次出现顺序。 */
private class SourceRegistry {
    private class Entry(
        val url: String,
        var title: String,
        var siteName: String,
        var publishedAt: String?,
        var snippet: String?,
        var siteIcon: String?,
        val index: Int,
    ) {
        fun toSource() = Source(url, title, siteName, publishedAt, snippet, siteIcon, index)
    }

    private val entries = LinkedHashMap<String, Entry>()

    fun add(
        url: String,
        title: String?,
        publishedAt: String?,
        snippet: String? = null,
        siteIcon: String? = null,
        siteName: String? = null,
    ): Int {
        val domain = siteNameOf(url)
        val name = siteName?.trim().takeUnless { it.isNullOrEmpty() } ?: domain
        val cleanSnippet = snippet?.trim().takeUnless { it.isNullOrEmpty() }
        val cleanIcon = siteIcon?.trim().takeUnless { it.isNullOrEmpty() }
        val existing = entries[url]
        if (existing != null) {
            // 同一网页可能先被打开或先返回不完整元数据；后续结果补齐，不覆盖已有信息
            if (existing.title == url && !title.isNullOrEmpty()) existing.title = title
            if (existing.siteName == domain) existing.siteName = name
            if (existing.publishedAt.isNullOrEmpty()) existing.publishedAt = publishedAt
            if (existing.snippet == null) existing.snippet = cleanSnippet
            if (existing.siteIcon == null) existing.siteIcon = cleanIcon
            return existing.index
        }
        val entry = Entry(url, title.takeUnless { it.isNullOrEmpty() } ?: url, name, publishedAt, cleanSnippet, cleanIcon, entries.size + 1)
        entries[url] = entry
        return entry.index
    }

    fun list(): List<Source> = entries.values.map { it.toSource() }
}

private data class PendingText(val index: Int, val text: String, val citations: List<TextCitation>)

/** 文本块拼成一份 markdown：引用位置在块尾，[citation:N] 插在块尾空白之前。 */
private fun joinTextBlocks(blocks: List<PendingText>, registry: SourceRegistry): Answer {
    val markdown = StringBuilder()
    val text = StringBuilder()
    for (block in blocks) {
        text.append(block.text)
        val indexes = LinkedHashSet<Int>()
        for (citation in block.citations) {
            indexes += registry.add(citation.url, citation.title, null)
        }
        val body = if (indexes.isNotEmpty()) block.text.trimEnd() else block.text
        markdown.append(body)
        for (index in indexes) markdown.append("[citation:").append(index).append(']')
        markdown.append(block.text.substring(body.length))
    }
    return Answer(markdown.toString(), text.toString())
}

private fun statusOf(result: ReplyBlock?, streaming: Boolean): ToolStatus = when (result) {
    null -> if (streaming) ToolStatus.Wip else ToolStatus.Failed
    is ReplyBlock.WebSearchToolResult -> if (result.errorCode != null) ToolStatus.Failed else ToolStatus.Done
    is ReplyBlock.WebFetchToolResult -> if (result.errorCode != null) ToolStatus.Failed else ToolStatus.Done
    else -> ToolStatus.Failed
}

private fun mergeStatus(a: ToolStatus, b: ToolStatus): ToolStatus = when {
    a == ToolStatus.Wip || b == ToolStatus.Wip -> ToolStatus.Wip
    a == ToolStatus.Failed && b == ToolStatus.Failed -> ToolStatus.Failed
    else -> ToolStatus.Done
}

fun buildReplyView(reply: Message.Assistant, streaming: Boolean): ReplyView {
    val results = HashMap<String, ReplyBlock>()
    for (block in reply.blocks) {
        when (block) {
            is ReplyBlock.WebSearchToolResult -> results[block.toolUseId] = block
            is ReplyBlock.WebFetchToolResult -> results[block.toolUseId] = block
            else -> Unit
        }
    }

    val registry = SourceRegistry()
    val searchResultIndexes = LinkedHashSet<Int>()

    // 来源编号按搜索结果、打开的网页在回复中出现的顺序，与正文引用无关
    for (block in reply.blocks) {
        if (block !is ReplyBlock.ServerToolUse) continue
        when (val result = results[block.id]) {
            is ReplyBlock.WebSearchToolResult -> for (item in result.results) {
                searchResultIndexes += registry.add(item.url, item.title, item.pageAge, item.snippet, item.siteIcon, item.siteName)
            }
            is ReplyBlock.WebFetchToolResult -> {
                val url = result.url
                if (result.errorCode == null && url != null && block.kind == ServerToolKind.DeepSearch) {
                    registry.add(url, result.title, result.publishedAt)
                }
            }
            else -> Unit
        }
    }

    // 步骤用可变草稿构建，最后转成不可变视图
    val steps = mutableListOf<Any>()
    val mergedSearch = HashMap<ServerToolKind, SearchDraft>()
    var mergedThinking: ThinkingDraft? = null
    var pendingText = mutableListOf<PendingText>()

    fun flushPreamble() {
        if (pendingText.isEmpty()) return
        val markdown = joinTextBlocks(pendingText, registry).markdown
        if (markdown.isNotBlank()) steps += Step.Preamble("text-${pendingText[0].index}", markdown)
        pendingText = mutableListOf()
    }

    reply.blocks.forEachIndexed { index, block ->
        // 模板统一由 describeReply 展示，不混入正文或说明文字
        if (block is ReplyBlock.Text && block.isTemplate) return@forEachIndexed
        if (block !is ReplyBlock.Thinking) mergedThinking = null
        when (block) {
            is ReplyBlock.Text -> pendingText += PendingText(index, block.text, block.citations)
            is ReplyBlock.Thinking -> {
                val current = mergedThinking
                if (current != null) {
                    current.blocks += block
                } else if (block.thinking.isNotEmpty()) {
                    flushPreamble()
                    val draft = ThinkingDraft("thinking-$index", mutableListOf(block))
                    mergedThinking = draft
                    steps += draft
                }
            }
            is ReplyBlock.ServerToolUse -> handleToolUse(block, results[block.id], streaming, registry, steps, mergedSearch, ::flushPreamble)
            else -> Unit
        }
    }

    val answer = if (pendingText.isNotEmpty()) joinTextBlocks(pendingText, registry) else null
    val sources = registry.list()
    val byIndex = sources.associateBy { it.index }
    return ReplyView(
        steps = steps.map { step ->
            when (step) {
                is SearchDraft -> Step.Search(step.key, step.kind, step.queries.toList(), step.results.mapNotNull { byIndex[it] }, step.status)
                is ThinkingDraft -> Step.Thinking(step.key, step.blocks.toList())
                is OpenDraft -> Step.Open(step.pages.toList())
                is FindDraft -> Step.Find(step.patterns.toList())
                is ReadLinkDraft -> Step.ReadLink(step.links.toList())
                else -> step as Step
            }
        },
        answer = answer?.takeIf { it.text.isNotEmpty() },
        sources = sources,
        searchResults = searchResultIndexes.mapNotNull { byIndex[it] },
    )
}

private class SearchDraft(
    val key: String,
    val kind: ServerToolKind,
    val queries: MutableList<String>,
    val results: LinkedHashSet<Int>,
    var status: ToolStatus,
)

private class ThinkingDraft(val key: String, val blocks: MutableList<ReplyBlock.Thinking>)

private class OpenDraft(val pages: MutableList<PageItem>)

private class FindDraft(val patterns: MutableList<FindItem>)

private class ReadLinkDraft(val links: MutableList<PageItem>)

private fun handleToolUse(
    block: ReplyBlock.ServerToolUse,
    result: ReplyBlock?,
    streaming: Boolean,
    registry: SourceRegistry,
    steps: MutableList<Any>,
    mergedSearch: HashMap<ServerToolKind, SearchDraft>,
    flushPreamble: () -> Unit,
) {
    val fetched = result as? ReplyBlock.WebFetchToolResult
    val fetchedOk = fetched?.takeIf { it.errorCode == null }
    val input = block.input
    // 只有签名、没有明文 URL 的网页仍用于请求，但没有可展示的链接
    if (input is ServerToolInput.ReadLink && input.url.isEmpty() && fetchedOk?.url.isNullOrEmpty()) return
    flushPreamble()
    val status = statusOf(result, streaming)
    val last = steps.lastOrNull()

    if (input is ServerToolInput.Search) {
        val queries = input.queries.ifEmpty { listOfNotNull(input.query) }
        val found = (result as? ReplyBlock.WebSearchToolResult)?.results?.map {
            registry.add(it.url, it.title, it.pageAge, it.snippet, it.siteIcon, it.siteName)
        }.orEmpty()
        // 普通搜索按种类聚合到首次出现的位置；边想边搜只合并相邻、同种类的调用
        val target = if (block.kind.isDeepSearch()) {
            (last as? SearchDraft)?.takeIf { it.kind == block.kind }
        } else {
            mergedSearch[block.kind]
        }
        if (target != null) {
            target.queries += queries
            target.results += found
            target.status = mergeStatus(target.status, status)
        } else {
            val draft = SearchDraft(block.id, block.kind, queries.toMutableList(), LinkedHashSet(found), status)
            steps += draft
            if (!block.kind.isDeepSearch()) mergedSearch[block.kind] = draft
        }
        return
    }

    val inputUrl = when (input) {
        is ServerToolInput.OpenPage -> input.url
        is ServerToolInput.FindInPage -> input.url
        is ServerToolInput.ReadLink -> input.url
    }
    val page = PageItem(
        id = block.id,
        url = inputUrl.ifEmpty { fetchedOk?.url.orEmpty() },
        title = fetchedOk?.title?.takeIf { it.isNotEmpty() } ?: inputUrl,
        status = status,
    )
    when (input) {
        is ServerToolInput.ReadLink -> {
            // 阅读链接仅展示进行态；结束后隐藏，原始结果仍保留用于后续请求
            if (status != ToolStatus.Wip) return
            if (last is ReadLinkDraft) last.links += page else steps += ReadLinkDraft(mutableListOf(page))
        }
        is ServerToolInput.OpenPage ->
            if (last is OpenDraft) last.pages += page else steps += OpenDraft(mutableListOf(page))
        is ServerToolInput.FindInPage -> {
            val item = FindItem(block.id, input.pattern, status)
            if (last is FindDraft) last.patterns += item else steps += FindDraft(mutableListOf(item))
        }
    }
}

/** 思考总用时（秒）：各思考块用时之和，至少 1 秒；有块缺少用时返回 null。 */
fun totalThinkingSeconds(steps: List<Step>, now: Long): Long? {
    var total = 0.0
    for (step in steps) {
        if (step !is Step.Thinking) continue
        for (block in step.blocks) {
            val elapsed = block.elapsedSeconds
            if (elapsed != null) {
                total += elapsed * 1000
                continue
            }
            val started = block.startedAt ?: return null
            total += ((block.endedAt ?: now) - started).toDouble()
        }
    }
    return max(1L, (total / 1000).roundToLong())
}

/** 折叠区标题 */
sealed interface AreaTitle {
    /** 已思考（用时 N 秒） */
    data class Duration(val seconds: Long) : AreaTitle

    /** 缺少用时信息，显示“深度思考” */
    data object ThinkingLabel : AreaTitle

    data object Stopped : AreaTitle

    data object ImageSearching : AreaTitle

    data object Searching : AreaTitle

    data object Browsing : AreaTitle

    data object Reading : AreaTitle

    data object Thinking : AreaTitle
}

fun areaTitle(view: ReplyView, streaming: Boolean, now: Long, hasResponse: Boolean): AreaTitle {
    if (hasResponse) {
        val seconds = totalThinkingSeconds(view.steps, now) ?: return AreaTitle.ThinkingLabel
        return AreaTitle.Duration(seconds)
    }
    if (!streaming) return AreaTitle.Stopped
    val last = view.steps.lastOrNull()
    return when {
        last is Step.Search && last.status == ToolStatus.Wip ->
            if (last.kind.isImageSearch()) AreaTitle.ImageSearching else AreaTitle.Searching
        last is Step.Open && last.pages.any { it.status == ToolStatus.Wip } -> AreaTitle.Browsing
        last is Step.Find && last.patterns.any { it.status == ToolStatus.Wip } -> AreaTitle.Browsing
        last is Step.ReadLink && last.links.any { it.status == ToolStatus.Wip } -> AreaTitle.Reading
        else -> AreaTitle.Thinking
    }
}
