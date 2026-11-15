package io.github.smalltailqwq.shallowdrop.ui.markdown

/**
 * 流式增量解析：新文本以上次文本为前缀（只追加）时，只从上次倒数第二个顶层块的行首起重新解析，
 * 更早的顶层块原样复用（同一实例），渲染层据此跳过它们的重组。
 *
 * 成立的前提：更早的顶层块都已被后续块关闭，追加内容只可能改变末尾两块
 * （段落变 setext 标题、段落变表格、表格吸收新行、列表/代码块/公式继续增长等）。
 * 唯一例外是后出现的链接引用定义会影响前文，流式结束后由完整解析修正。
 *
 * 非追加（如重新生成）时整篇解析，并按结构相等复用旧块实例。
 *
 * 方法加锁，流式协程（后台线程）与结束时的完整解析（主线程）可能交错调用。
 */
class IncrementalMarkdownParser {
    private var lastText: String = ""
    private var lastBlocks: List<MarkdownParser.TopLevelBlock> = emptyList()
    private var lastDocument: MdDocument = MdDocument.Empty

    /** 是否解析过内容 */
    val hasState: Boolean
        @Synchronized get() = lastBlocks.isNotEmpty()

    @Synchronized
    fun parse(text: String): MdDocument = parse(text, incremental = true)

    /** 整篇解析（修正增量解析可能遗漏的跨块影响），仍按结构相等复用旧块实例 */
    @Synchronized
    fun finish(text: String): MdDocument = parse(text, incremental = false)

    private fun parse(text: String, incremental: Boolean): MdDocument {
        if (incremental && text == lastText && lastBlocks.isNotEmpty()) return lastDocument
        val blocks = if (incremental && lastBlocks.isNotEmpty() && text.startsWith(lastText)) {
            // 从倒数第二块起重解析：最后一块可能被前一块吸收（如表格后单独的 `|` 先成段落，补全后变成表格行）
            val tail = lastBlocks[maxOf(0, lastBlocks.size - 2)]
            // 回退到该块所在行的行首，保证重新解析的片段从行首开始
            val restart = if (tail.offset <= 0) 0 else text.lastIndexOf('\n', tail.offset - 1) + 1
            val kept = lastBlocks.filter { it.offset < restart }
            val reparsed = MarkdownParser.parseTopLevel(text.substring(restart))
                .map { MarkdownParser.TopLevelBlock(it.offset + restart, it.block) }
            kept + reparsed
        } else {
            val previous = HashMap<MdBlock, MdBlock>()
            for (block in lastBlocks) previous[block.block] = block.block
            MarkdownParser.parseTopLevel(text).map { top ->
                previous[top.block]?.let { top.copy(block = it) } ?: top
            }
        }
        lastText = text
        lastBlocks = blocks
        lastDocument = MdDocument(blocks.map { it.block })
        return lastDocument
    }
}
