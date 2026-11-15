package io.github.smalltailqwq.shallowdrop.ui.markdown

/**
 * Markdown 中间模型：解析层（纯 Kotlin）产出，渲染层只读这里的结构，不再接触 commonmark 节点。
 *
 * 全部为不可变 data class：结构相等即内容相等。流式解析时未变化的顶层块会复用同一实例，
 * Compose（强跳过模式下按实例比较）据此跳过对应块的重组。
 */
data class MdDocument(val blocks: List<MdBlock>) {
    companion object {
        val Empty = MdDocument(emptyList())
    }
}

sealed interface MdBlock {
    data class Paragraph(val inlines: List<MdInline>) : MdBlock

    /** level 1..6；没有内容的标题在解析时已丢弃 */
    data class Heading(val level: Int, val inlines: List<MdInline>) : MdBlock

    /** language 为信息串第一个词，缺省为 "text"（与 Web 版一致） */
    data class CodeBlock(val language: String, val code: String) : MdBlock

    /** 块级公式：`$$ … $$`、`\[ … \]`，以及整段只有一个 display 定界符公式的段落 */
    data class MathBlock(val tex: String) : MdBlock

    data class BlockQuote(val blocks: List<MdBlock>) : MdBlock

    data class ListBlock(
        val ordered: Boolean,
        /** 有序列表起始编号 */
        val start: Int,
        /** 紧凑列表：项之间没有空行 */
        val tight: Boolean,
        val items: List<ListItem>,
    ) : MdBlock

    data object ThematicBreak : MdBlock

    data class Table(
        val alignments: List<TableAlign>,
        val header: List<TableCell>,
        /** 每行已按表头列数补齐或截断 */
        val rows: List<List<TableCell>>,
    ) : MdBlock
}

/** checked 为 null 表示普通列表项，true / false 为 GFM 任务项 */
data class ListItem(val checked: Boolean?, val blocks: List<MdBlock>)

data class TableCell(val inlines: List<MdInline>)

enum class TableAlign { None, Left, Center, Right }

sealed interface MdInline {
    data class Text(val text: String) : MdInline

    data class Code(val code: String) : MdInline

    data class Emphasis(val children: List<MdInline>) : MdInline

    data class Strong(val children: List<MdInline>) : MdInline

    data class Strikethrough(val children: List<MdInline>) : MdInline

    /** url 为 null 时只显示文字（页内锚点、mailto、无法识别的地址），与 Web 版 Link 组件一致 */
    data class Link(val url: String?, val children: List<MdInline>) : MdInline

    /** display 为 true 表示源文使用 `$$` 或 `\[` 定界 */
    data class Math(val tex: String, val display: Boolean) : MdInline

    /** 正文里的 `[citation:N]`，N 从 1 开始 */
    data class Citation(val index: Int) : MdInline

    /** 硬换行、软换行与 `<br>`：源文每个换行都渲染为视觉换行（与 Web 版 TextContent 一致） */
    data object LineBreak : MdInline
}
