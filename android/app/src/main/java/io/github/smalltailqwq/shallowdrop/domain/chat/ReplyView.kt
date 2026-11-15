package io.github.smalltailqwq.shallowdrop.domain.chat

import io.github.smalltailqwq.shallowdrop.core.model.Message
import io.github.smalltailqwq.shallowdrop.core.model.ReplyBlock
import io.github.smalltailqwq.shallowdrop.core.model.ServerToolInput
import io.github.smalltailqwq.shallowdrop.core.model.ServerToolKind
import io.github.smalltailqwq.shallowdrop.core.model.TextCitation
import io.github.smalltailqwq.shallowdrop.core.model.WebSearchResult
import java.net.URI

/*
 * 由回复的内容块推导界面需要的结构，对照 src/services/chat/view.ts：
 * 思考与工具步骤的时间线、带引用角标的正文、可引用的来源列表。
 * 只做纯计算，不改动内容块（内容块要原样回传）。文案由 UI 按 AreaTitle 映射。
 */

data class Source(
    val url: String,
    val title: String,
    /** 优先使用服务端站点名，没有时使用域名（去掉 www.） */
    val siteName: String,
    /** 搜索结果为 RFC 3339，阅读链接为爬虫提取的原始日期 */
    val publishedAt: String?,
    /** 明文摘要与站点图标；缺少元数据时为 null */
    val snippet: String?,
    val siteIcon: String?,
    /** 引用角标与搜索结果列表上的编号，从 1 开始 */
    val index: Int,
)

enum class ToolStatus { Wip, Done, Failed }

fun isDeepSearchKind(kind: ServerToolKind) = kind == ServerToolKind.DeepSearch || kind == ServerToolKind.DeepImageSearch

fun isImageSearchKind(kind: ServerToolKind) = kind == ServerToolKind.ImageSearch || kind == ServerToolKind.DeepImageSearch

data class PageItem(val id: String, val url: String, val title: String, val status: ToolStatus)

data class FindItem(val id: String, val pattern: String, val status: ToolStatus)

sealed interface Step {
    data class Search(
        /** 该步骤首次调用的 ID，空查询的图搜也有稳定标识 */
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

data class Answer(
    /** 引用处插入了 [citation:N] 的 markdown */
    val markdown: String,
    val text: String,
)

data class ReplyView(
    val steps: List<Step>,
    /** 最后一个思考或工具块之后的正文 */
    val answer: Answer?,
    /** 正文引用可指向的全部来源，按编号排列 */
    val sources: List<Source>,
    /** 搜索到的网页（去重），用于底部汇总与搜索结果列表 */
    val searchResults: List<Source>,
)

fun siteNameOf(url: String): String = try {
    URI(url).host?.removePrefix("www.") ?: ""
} catch (_: Exception) {
    ""
}

private fun toolStatus(result: ReplyBlock?, streaming: Boolean): ToolStatus = when (result) {
    null -> if (streaming) ToolStatus.Wip else ToolStatus.Failed
    is ReplyBlock.WebSearchToolResult -> if (result.errorCode != null) ToolStatus.Failed else ToolStatus.Done
    is ReplyBlock.WebFetchToolResult -> if (result.isSuccess()) ToolStatus.Done else ToolStatus.Failed
    else -> ToolStatus.Failed
}

private fun mergeStatus(a: ToolStatus, b: ToolStatus): ToolStatus = when {
    a == ToolStatus.Wip || b == ToolStatus.Wip -> ToolStatus.Wip
    a == ToolStatus.Failed && b == ToolStatus.Failed -> ToolStatus.Failed
    else -> ToolStatus.Done
}

/** 来源登记：同一 URL 只编号一次，编号按首次出现的顺序；后续结果补齐缺失的元数据 */
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

    private val entries = ArrayList<Entry>()
    private val byUrl = HashMap<String, Entry>()

    fun add(url: String, title: String?, publishedAt: String?, metadata: WebSearchResult? = null): Int {
        val siteName = metadata?.siteName?.trim()?.ifEmpty { null } ?: siteNameOf(url)
        val snippet = metadata?.snippet?.trim()?.ifEmpty { null }
        val siteIcon = metadata?.siteIcon?.trim()?.ifEmpty { null }
        val existing = byUrl[url]
        if (existing != null) {
            // 同一网页可能先被打开，或先返回不完整元数据；后续结果补齐，不覆盖已有正文信息。
            if (existing.title == url && !title.isNullOrEmpty()) existing.title = title
            if (existing.siteName == siteNameOf(url)) existing.siteName = siteName
            if (existing.publishedAt.isNullOrEmpty()) existing.publishedAt = publishedAt
            if (existing.snippet.isNullOrEmpty()) existing.snippet = snippet
            if (existing.siteIcon.isNullOrEmpty()) existing.siteIcon = siteIcon
            return existing.index
        }
        val entry = Entry(url, title?.ifEmpty { null } ?: url, siteName, publishedAt, snippet, siteIcon, entries.size + 1)
        entries.add(entry)
        byUrl[url] = entry
        return entry.index
    }

    fun list(): List<Source> = entries.map { it.toSource() }
}

private class PendingText(val index: Int, val text: String, val citations: List<TextCitation>)

/**
 * 文本块拼成一份 markdown：服务端在挂过引用的块之后另起新块，引用位置即块尾。
 * 引用之后的空白（含换行）仍留在被引用的块里，所以 [citation:N] 插在块尾空白之前。
 */
private fun joinTextBlocks(blocks: List<PendingText>, registry: SourceRegistry): Answer {
    val markdown = StringBuilder()
    val text = StringBuilder()
    for (block in blocks) {
        text.append(block.text)
        val indexes = LinkedHashSet<Int>()
        for (citation in block.citations) {
            indexes.add(registry.add(citation.url, citation.title, null))
        }
        val body = if (indexes.isNotEmpty()) block.text.trimEnd() else block.text
        markdown.append(body)
        for (index in indexes) markdown.append("[citation:").append(index).append(']')
        markdown.append(block.text, body.length, block.text.length)
    }
    return Answer(markdown.toString(), text.toString())
}

/** 构建中的可变步骤，结束时转成不可变的 Step */
private sealed interface Building {
    class Search(val key: String, val kind: ServerToolKind, val queries: MutableList<String>, val results: MutableList<Int>, var status: ToolStatus) : Building
    class Open(val pages: MutableList<PageItem>) : Building
    class Find(val patterns: MutableList<FindItem>) : Building
    class ReadLink(val links: MutableList<PageItem>) : Building
    class Thinking(val key: String, val blocks: MutableList<ReplyBlock.Thinking>) : Building
    class Preamble(val key: String, val markdown: String) : Building
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
    val searchResults = LinkedHashSet<Int>()
    val steps = ArrayList<Building>()
    val mergedSearch = HashMap<ServerToolKind, Building.Search>()
    var mergedThinking: Building.Thinking? = null
    var pendingText = ArrayList<PendingText>()

    // 来源编号按搜索结果、打开的网页在回复中出现的顺序，与正文引用无关
    for (block in reply.blocks) {
        if (block !is ReplyBlock.ServerToolUse) continue
        when (val result = results[block.id]) {
            is ReplyBlock.WebSearchToolResult -> for (item in result.results) {
                searchResults.add(registry.add(item.url, item.title, item.pageAge, item))
            }
            is ReplyBlock.WebFetchToolResult ->
                if (result.isSuccess() && block.kind == ServerToolKind.DeepSearch && result.url != null) {
                    registry.add(result.url, result.title, result.publishedAt)
                }
            else -> Unit
        }
    }

    fun flushPreamble() {
        if (pendingText.isEmpty()) return
        val markdown = joinTextBlocks(pendingText, registry).markdown
        if (markdown.isNotBlank()) steps.add(Building.Preamble("text-${pendingText[0].index}", markdown))
        pendingText = ArrayList()
    }

    reply.blocks.forEachIndexed { index, block ->
        // 模板统一由界面单独展示，不能混入模型正文或工具步骤前的说明文字。
        if (block is ReplyBlock.Text && block.isTemplate) return@forEachIndexed
        if (block !is ReplyBlock.Thinking) mergedThinking = null
        when (block) {
            is ReplyBlock.Text -> pendingText.add(PendingText(index, block.text, block.citations))
            is ReplyBlock.Thinking -> {
                // 相邻思考块沿用同一个圆点和 Markdown；原始块与签名不变。
                val merged = mergedThinking
                if (merged != null) {
                    merged.blocks.add(block)
                } else if (block.thinking.isNotEmpty()) {
                    flushPreamble()
                    mergedThinking = Building.Thinking("thinking-$index", mutableListOf(block)).also { steps.add(it) }
                }
            }
            is ReplyBlock.ServerToolUse -> handleToolUse(block, results[block.id], streaming, registry, steps, mergedSearch, ::flushPreamble)
            else -> Unit
        }
    }

    val answer = if (pendingText.isNotEmpty()) joinTextBlocks(pendingText, registry) else null
    val sources = registry.list()
    return ReplyView(
        steps = steps.map { it.freeze(sources) },
        answer = answer?.takeIf { it.text.isNotEmpty() },
        sources = sources,
        searchResults = searchResults.map { sources[it - 1] },
    )
}

private fun handleToolUse(
    block: ReplyBlock.ServerToolUse,
    result: ReplyBlock?,
    streaming: Boolean,
    registry: SourceRegistry,
    steps: MutableList<Building>,
    mergedSearch: MutableMap<ServerToolKind, Building.Search>,
    flushPreamble: () -> Unit,
) {
    val input = block.input
    val fetched = (result as? ReplyBlock.WebFetchToolResult)?.takeIf { it.isSuccess() }
    // 只有签名、没有明文 URL 的网页仍用于请求，但没有可展示的链接。
    if (input is ServerToolInput.ReadLink && input.url.isEmpty() && fetched?.url.isNullOrEmpty()) return
    flushPreamble()
    val kind = block.kind
    val status = toolStatus(result, streaming)
    val last = steps.lastOrNull()

    when (input) {
        is ServerToolInput.Search -> {
            val found = (result as? ReplyBlock.WebSearchToolResult)?.results?.map {
                registry.add(it.url, it.title, it.pageAge, it)
            } ?: emptyList()
            // 普通搜索按种类聚合到首次出现的位置；边想边搜只合并相邻、同种类的调用。
            val target = if (isDeepSearchKind(kind)) {
                (last as? Building.Search)?.takeIf { it.kind == kind }
            } else {
                mergedSearch[kind]
            }
            if (target != null) {
                target.queries.addAll(input.queries)
                found.filterNot { it in target.results }.forEach { target.results.add(it) }
                target.status = mergeStatus(target.status, status)
            } else {
                val step = Building.Search(block.id, kind, input.queries.toMutableList(), found.toMutableList(), status)
                steps.add(step)
                if (!isDeepSearchKind(kind)) mergedSearch[kind] = step
            }
        }
        is ServerToolInput.ReadLink, is ServerToolInput.OpenPage -> {
            val url = (input as? ServerToolInput.ReadLink)?.url ?: (input as ServerToolInput.OpenPage).url
            val page = PageItem(
                id = block.id,
                url = url.ifEmpty { fetched?.url ?: "" },
                title = fetched?.title?.ifEmpty { null } ?: url,
                status = status,
            )
            if (input is ServerToolInput.ReadLink) {
                // 阅读链接仅展示进行态；成功、失败或中止后隐藏，原始结果仍保留用于后续请求。
                if (status != ToolStatus.Wip) return
                if (last is Building.ReadLink) last.links.add(page) else steps.add(Building.ReadLink(mutableListOf(page)))
            } else {
                if (last is Building.Open) last.pages.add(page) else steps.add(Building.Open(mutableListOf(page)))
            }
        }
        is ServerToolInput.FindInPage -> {
            val item = FindItem(block.id, input.pattern, status)
            if (last is Building.Find) last.patterns.add(item) else steps.add(Building.Find(mutableListOf(item)))
        }
    }
}

private fun Building.freeze(sources: List<Source>): Step = when (this) {
    is Building.Search -> Step.Search(key, kind, queries.toList(), results.map { sources[it - 1] }, status)
    is Building.Open -> Step.Open(pages.toList())
    is Building.Find -> Step.Find(patterns.toList())
    is Building.ReadLink -> Step.ReadLink(links.toList())
    is Building.Thinking -> Step.Thinking(key, blocks.toList())
    is Building.Preamble -> Step.Preamble(key, markdown)
}

/** 思考总用时（秒）：各思考块用时之和，至少 1 秒；有块缺少用时时返回 null。 */
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
            val startedAt = block.startedAt ?: return null
            total += ((block.endedAt ?: now) - startedAt).toDouble()
        }
    }
    return maxOf(1L, Math.round(total / 1000))
}

/** 折叠区标题，UI 映射为文案（对照 view.ts getAreaTitle 用到的 i18n key） */
sealed interface AreaTitle {
    /** 有回答但缺少用时：thinkingSwitch */
    data object Thought : AreaTitle

    /** messageThinkDuration(Plural) */
    data class ThoughtFor(val seconds: Long) : AreaTitle

    /** messageThinkStopped */
    data object Stopped : AreaTitle

    /** imageSearching */
    data object ImageSearching : AreaTitle

    /** deepSearchSearching */
    data object Searching : AreaTitle

    /** groupTitleToolOpen */
    data object OpeningPage : AreaTitle

    /** readingLink */
    data object ReadingLink : AreaTitle

    /** messageThinking */
    data object Thinking : AreaTitle
}

fun areaTitle(view: ReplyView, streaming: Boolean, now: Long, hasResponse: Boolean = view.answer != null): AreaTitle {
    if (hasResponse) {
        val count = totalThinkingSeconds(view.steps, now) ?: return AreaTitle.Thought
        return AreaTitle.ThoughtFor(count)
    }
    if (!streaming) return AreaTitle.Stopped
    return when (val last = view.steps.lastOrNull()) {
        is Step.Search if last.status == ToolStatus.Wip ->
            if (isImageSearchKind(last.kind)) AreaTitle.ImageSearching else AreaTitle.Searching
        is Step.Open if last.pages.any { it.status == ToolStatus.Wip } -> AreaTitle.OpeningPage
        is Step.Find if last.patterns.any { it.status == ToolStatus.Wip } -> AreaTitle.OpeningPage
        is Step.ReadLink if last.links.any { it.status == ToolStatus.Wip } -> AreaTitle.ReadingLink
        else -> AreaTitle.Thinking
    }
}
