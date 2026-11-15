package io.github.smalltailqwq.shallowdrop.ui.markdown

import org.commonmark.ext.autolink.AutolinkExtension
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableBody
import org.commonmark.ext.gfm.tables.TableCell as CmTableCell
import org.commonmark.ext.gfm.tables.TableHead
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Document
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.LinkReferenceDefinition
import org.commonmark.node.ListBlock
import org.commonmark.node.ListItem as CmListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.IncludeSourceSpans
import org.commonmark.parser.Parser
import java.net.URI

/**
 * Markdown → [MdDocument]。对照 Web 版 `services/markdown/parse.ts`：
 * - GFM 表格、删除线（只认 `~~`，避免「5~10」这类范围被误判）、自动链接，LLM 风格公式（见 [MathExtension]）
 * - 关闭缩进代码块：有前导空格的列表、段落不会变成代码
 * - `[citation:N]` 拆成 [MdInline.Citation]；行内代码里的不处理
 * - 链接地址按 Web 版 sanitizeUrl 规则清洗；图片显示为指向图片地址的链接
 * - 链接引用定义显示原文；HTML 原样显示为文字，`<br>` 为换行
 *
 * 线程安全：内部 commonmark Parser 不可变，可并发调用。
 */
object MarkdownParser {
    private val parser: Parser = Parser.builder()
        .extensions(
            listOf(
                TablesExtension.create(),
                StrikethroughExtension.builder().requireTwoTildes(true).build(),
                AutolinkExtension.create(),
                MathExtension.create(),
            ),
        )
        .enabledBlockTypes(enabledBlockTypes())
        .includeSourceSpans(IncludeSourceSpans.BLOCKS)
        .build()

    /** 不含 IndentedCodeBlock */
    private fun enabledBlockTypes(): Set<Class<out org.commonmark.node.Block>> = setOf(
        Heading::class.java,
        HtmlBlock::class.java,
        ThematicBreak::class.java,
        FencedCodeBlock::class.java,
        BlockQuote::class.java,
        ListBlock::class.java,
    )

    /** 解析整篇文档 */
    fun parse(markdown: String): MdDocument = MdDocument(parseTopLevel(markdown).map { it.block })

    /** 带缓存的解析，供非流式消息使用（列表滚动回来时不必重新解析） */
    fun parseCached(markdown: String): MdDocument {
        synchronized(cache) { cache[markdown]?.let { return it } }
        val document = parse(markdown)
        synchronized(cache) { cache[markdown] = document }
        return document
    }

    private val cache = object : LinkedHashMap<String, MdDocument>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MdDocument>?): Boolean = size > 64
    }

    /** 顶层块及其在输入中的起始偏移，供增量解析使用 */
    internal data class TopLevelBlock(val offset: Int, val block: MdBlock)

    internal fun parseTopLevel(markdown: String): List<TopLevelBlock> {
        val document = parser.parse(markdown) as Document
        val result = ArrayList<TopLevelBlock>()
        var child = document.firstChild
        while (child != null) {
            val offset = child.sourceSpans.firstOrNull()?.inputIndex ?: 0
            for (block in convertBlock(child, markdown)) result += TopLevelBlock(offset, block)
            child = child.next
        }
        return result
    }

    // ------------------------------------------------------------ 块

    private fun convertBlocks(parent: Node, source: String): List<MdBlock> {
        val out = ArrayList<MdBlock>()
        var child = parent.firstChild
        while (child != null) {
            out += convertBlock(child, source)
            child = child.next
        }
        return out
    }

    private fun convertBlock(node: Node, source: String): List<MdBlock> = when (node) {
        is Paragraph -> listOf(paragraph(convertInlines(node)))
        is Heading -> {
            val inlines = convertInlines(node)
            if (inlines.isEmpty()) emptyList() else listOf(MdBlock.Heading(node.level.coerceIn(1, 6), inlines))
        }
        is FencedCodeBlock -> {
            val info = node.info.orEmpty().trim()
            val language = info.split(' ', '\t').firstOrNull().orEmpty().ifEmpty { "text" }
            listOf(MdBlock.CodeBlock(language, node.literal.orEmpty().removeSuffix("\n")))
        }
        is IndentedCodeBlock -> listOf(MdBlock.CodeBlock("text", node.literal.orEmpty().removeSuffix("\n")))
        is MathBlockNode -> listOf(MdBlock.MathBlock(node.tex))
        is BlockQuote -> listOf(MdBlock.BlockQuote(convertBlocks(node, source)))
        is BulletList -> listOf(list(node, ordered = false, start = 1, source))
        is OrderedList -> listOf(list(node, ordered = true, start = node.markerStartNumber ?: 1, source))
        is ThematicBreak -> listOf(MdBlock.ThematicBreak)
        is TableBlock -> listOfNotNull(table(node))
        is HtmlBlock -> listOf(MdBlock.Paragraph(htmlText(node.literal.orEmpty().trimEnd('\n'))))
        is LinkReferenceDefinition -> listOf(MdBlock.Paragraph(listOf(MdInline.Text(raw(node, source)))))
        else -> emptyList()
    }

    /** 段落里只有一个 display 定界的公式（可带首尾空白）时提升为块级公式 */
    private fun paragraph(inlines: List<MdInline>): MdBlock {
        val meaningful = inlines.filterNot { it is MdInline.LineBreak || (it is MdInline.Text && it.text.isBlank()) }
        val only = meaningful.singleOrNull()
        if (only is MdInline.Math && only.display) return MdBlock.MathBlock(only.tex)
        return MdBlock.Paragraph(inlines)
    }

    private fun list(node: ListBlock, ordered: Boolean, start: Int, source: String): MdBlock.ListBlock {
        val items = ArrayList<ListItem>()
        var child = node.firstChild
        while (child != null) {
            if (child is CmListItem) items += listItem(child, source)
            child = child.next
        }
        return MdBlock.ListBlock(ordered, start, node.isTight, items)
    }

    private val TASK_MARKER = Regex("""^\[([ xX])](?:[ \t]+|$)""")

    /** GFM 任务项：首段以 `[ ]` / `[x]` 开头 */
    private fun listItem(node: CmListItem, source: String): ListItem {
        val blocks = convertBlocks(node, source)
        val first = blocks.firstOrNull() as? MdBlock.Paragraph
        val firstText = first?.inlines?.firstOrNull() as? MdInline.Text
        val match = firstText?.let { TASK_MARKER.find(it.text) }
        if (first == null || firstText == null || match == null) return ListItem(null, blocks)
        val checked = match.groupValues[1] != " "
        val restText = firstText.text.substring(match.range.last + 1)
        val inlines = buildList {
            if (restText.isNotEmpty()) add(MdInline.Text(restText))
            addAll(first.inlines.drop(1))
        }
        val newBlocks = if (inlines.isEmpty()) blocks.drop(1) else listOf(MdBlock.Paragraph(inlines)) + blocks.drop(1)
        return ListItem(checked, newBlocks)
    }

    private fun table(node: TableBlock): MdBlock.Table? {
        var header: List<CmTableCell> = emptyList()
        val rows = ArrayList<List<CmTableCell>>()
        var section = node.firstChild
        while (section != null) {
            when (section) {
                is TableHead -> section.firstChild?.let { header = cells(it) }
                is TableBody -> {
                    var row = section.firstChild
                    while (row != null) {
                        if (row is TableRow) rows += cells(row)
                        row = row.next
                    }
                }
            }
            section = section.next
        }
        if (header.isEmpty()) return null
        val columns = header.size
        val alignments = header.map {
            when (it.alignment) {
                CmTableCell.Alignment.LEFT -> TableAlign.Left
                CmTableCell.Alignment.CENTER -> TableAlign.Center
                CmTableCell.Alignment.RIGHT -> TableAlign.Right
                else -> TableAlign.None
            }
        }
        return MdBlock.Table(
            alignments = alignments,
            header = header.map { TableCell(convertInlines(it)) },
            rows = rows.map { row ->
                List(columns) { i -> row.getOrNull(i)?.let { TableCell(convertInlines(it)) } ?: TableCell(emptyList()) }
            },
        )
    }

    private fun cells(row: Node): List<CmTableCell> {
        val out = ArrayList<CmTableCell>()
        var cell = row.firstChild
        while (cell != null) {
            if (cell is CmTableCell) out += cell
            cell = cell.next
        }
        return out
    }

    private fun raw(node: Node, source: String): String {
        val spans = node.sourceSpans
        if (spans.isEmpty()) return ""
        val start = spans.first().inputIndex
        val end = spans.last().let { it.inputIndex + it.length }
        return source.substring(start.coerceIn(0, source.length), end.coerceIn(0, source.length))
    }

    // ------------------------------------------------------------ 行内

    private fun convertInlines(parent: Node): List<MdInline> {
        val out = ArrayList<MdInline>()
        var child = parent.firstChild
        while (child != null) {
            convertInline(child, out)
            child = child.next
        }
        // 先合并相邻文字（括号可能被拆成多个文字节点），再拆出引用角标
        return mergeText(out).flatMap { item ->
            if (item is MdInline.Text) splitCitations(item.text) else listOf(item)
        }
    }

    private fun convertInline(node: Node, out: MutableList<MdInline>) {
        when (node) {
            is Text -> out += MdInline.Text(node.literal.orEmpty())
            is Code -> out += MdInline.Code(node.literal.orEmpty())
            is Emphasis -> out += MdInline.Emphasis(convertInlines(node))
            is StrongEmphasis -> out += MdInline.Strong(convertInlines(node))
            is Strikethrough -> out += MdInline.Strikethrough(convertInlines(node))
            is Link -> out += MdInline.Link(sanitizeLinkUrl(node.destination.orEmpty()), convertInlines(node))
            is Image -> {
                val url = sanitizeUrl(node.destination.orEmpty())
                out += MdInline.Link(url, listOf(MdInline.Text(url ?: node.destination.orEmpty())))
            }
            is MathInlineNode -> out += MdInline.Math(node.tex, node.display)
            is SoftLineBreak, is HardLineBreak -> out += MdInline.LineBreak
            is HtmlInline -> out += htmlText(node.literal.orEmpty())
            else -> {
                // 未知的行内节点：展开子节点，尽量保留文字
                var child = node.firstChild
                while (child != null) {
                    convertInline(child, out)
                    child = child.next
                }
            }
        }
    }

    private val BR = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)

    private fun htmlText(raw: String): List<MdInline> {
        if (BR.matches(raw.trim())) return listOf(MdInline.LineBreak)
        val out = ArrayList<MdInline>()
        raw.split('\n').forEachIndexed { i, line ->
            if (i > 0) out += MdInline.LineBreak
            if (line.isNotEmpty()) out += MdInline.Text(line)
        }
        return out
    }

    private val CITATION = Regex("""\[citation:(\d+)]""")

    private fun splitCitations(text: String): List<MdInline> {
        if (!text.contains("[citation:")) return listOf(MdInline.Text(text))
        val out = ArrayList<MdInline>()
        var last = 0
        for (match in CITATION.findAll(text)) {
            if (match.range.first > last) out += MdInline.Text(text.substring(last, match.range.first))
            val index = match.groupValues[1].toIntOrNull()
            if (index != null) out += MdInline.Citation(index) else out += MdInline.Text(match.value)
            last = match.range.last + 1
        }
        if (last < text.length) out += MdInline.Text(text.substring(last))
        return out
    }

    /** 合并相邻文字节点，便于测试与比较 */
    private fun mergeText(list: List<MdInline>): List<MdInline> {
        if (list.size < 2) return list
        val out = ArrayList<MdInline>(list.size)
        for (item in list) {
            val prev = out.lastOrNull()
            if (item is MdInline.Text && prev is MdInline.Text) {
                out[out.size - 1] = MdInline.Text(prev.text + item.text)
            } else {
                out += item
            }
        }
        return out
    }

    /** 页内锚点与 mailto 只显示文字 */
    internal fun sanitizeLinkUrl(url: String): String? {
        if (url.startsWith("#") || url.startsWith("mailto:")) return null
        return sanitizeUrl(url)
    }

    private val SCHEME = Regex("^[a-zA-Z]+://")

    /** 对照 Web 版 Link.tsx sanitizeUrl：缺协议补 https，只允许 http / https / mailto */
    internal fun sanitizeUrl(input: String): String? {
        var url = input.trim()
        if (url.isEmpty()) return null
        if (!SCHEME.containsMatchIn(url) && !url.startsWith("mailto:")) url = "https://$url"
        return try {
            val uri = URI(url)
            val scheme = uri.scheme?.lowercase() ?: return null
            if (scheme !in setOf("http", "https", "mailto")) return null
            if (scheme != "mailto" && uri.host.isNullOrEmpty()) return null
            url
        } catch (_: Exception) {
            null
        }
    }
}
