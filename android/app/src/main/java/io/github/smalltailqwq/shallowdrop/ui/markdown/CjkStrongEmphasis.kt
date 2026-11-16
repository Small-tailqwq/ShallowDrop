package io.github.smalltailqwq.shallowdrop.ui.markdown

import org.commonmark.internal.util.Escaping
import org.commonmark.node.Node
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text
import org.commonmark.text.Characters

/** 只修正 CommonMark 留下的中文双星号，已解析的格式和代码节点保持原样。 */
internal fun repairCjkStrongEmphasis(parent: Node, source: String) {
    var child = parent.firstChild
    while (child != null) {
        repairCjkStrongEmphasis(child, source)
        child = child.next
    }
    val pairs = mutableListOf<Pair<StrongMarker, StrongMarker>>()
    var opener: StrongMarker? = null
    child = parent.firstChild
    while (child != null) {
        if (child is Text) for (marker in strongMarkers(child, source)) {
            val start = opener
            if (start != null && marker.canClose && (start.relaxed || marker.relaxed)) {
                pairs += start to marker
                opener = null
            } else if (marker.canOpen) opener = marker
        }
        child = child.next
    }
    // 同一文字节点中的多组标记从右往左处理，左侧偏移不会因拆分改变。
    for ((start, end) in pairs.asReversed()) wrapStrong(start, end)
}

private data class StrongMarker(val node: Text, val index: Int, val canOpen: Boolean, val canClose: Boolean, val relaxed: Boolean)

private fun strongMarkers(node: Text, source: String): List<StrongMarker> {
    val spans = node.sourceSpans
    if (spans.isEmpty() || "**" !in node.literal) return emptyList()
    val raw = spans.joinToString("") { source.substring(it.inputIndex, it.inputIndex + it.length) }
    var spanIndex = 0
    var spanStart = 0
    fun sourceOffset(index: Int): Int {
        while (index >= spanStart + spans[spanIndex].length) {
            spanStart += spans[spanIndex].length
            spanIndex++
        }
        return spans[spanIndex].inputIndex + index - spanStart
    }
    val markers = mutableListOf<StrongMarker>()
    var i = 0
    var consumed = 0
    var literalIndex = 0
    while (i < raw.length) {
        if (raw[i] == '\\' && i + 1 < raw.length && raw[i + 1] in Escaping.ESCAPABLE) {
            i += 2
            continue
        }
        if (raw[i] != '*') { i++; continue }
        val begin = i
        while (i < raw.length && raw[i] == '*') i++
        if (i - begin != 2) continue
        literalIndex += Escaping.unescapeString(raw.substring(consumed, begin)).length
        val offset = sourceOffset(begin)
        val before = if (offset == 0) ' '.code else source.codePointBefore(offset)
        val after = if (offset + 2 == source.length) ' '.code else source.codePointAt(offset + 2)
        val leftSpace = Characters.isWhitespaceCodePoint(before)
        val rightSpace = offset + 2 == source.length || Characters.isWhitespaceCodePoint(after)
        val leftPunctuation = Characters.isPunctuationCodePoint(before)
        val rightPunctuation = Characters.isPunctuationCodePoint(after)
        val relaxedOpen = isCjk(before) && rightPunctuation
        val relaxedClose = leftPunctuation && isCjk(after)
        if (node.literal.startsWith("**", literalIndex)) markers += StrongMarker(
            node, literalIndex,
            !rightSpace && (!rightPunctuation || leftSpace || leftPunctuation || relaxedOpen),
            !leftSpace && (!leftPunctuation || rightSpace || rightPunctuation || relaxedClose),
            relaxedOpen || relaxedClose,
        )
        literalIndex += 2
        consumed = i
    }
    return markers
}

private fun wrapStrong(start: StrongMarker, end: StrongMarker) {
    val node = StrongEmphasis("**")
    val first = start.node
    val last = end.node
    val prefix = first.literal.substring(0, start.index)
    val suffix = last.literal.substring(end.index + 2)
    if (first === last) {
        node.appendChild(Text(first.literal.substring(start.index + 2, end.index)))
        first.literal = prefix
        first.insertAfter(node)
        if (suffix.isNotEmpty()) node.insertAfter(Text(suffix))
    } else {
        val beginning = first.literal.substring(start.index + 2)
        val ending = last.literal.substring(0, end.index)
        var current = first.next
        first.literal = prefix
        first.insertAfter(node)
        if (beginning.isNotEmpty()) node.appendChild(Text(beginning))
        while (current != null && current !== last) {
            val next = current.next
            node.appendChild(current)
            current = next
        }
        if (ending.isNotEmpty()) node.appendChild(Text(ending))
        last.literal = suffix
        if (suffix.isEmpty()) last.unlink()
    }
    if (prefix.isEmpty()) first.unlink()
}

private fun isCjk(codePoint: Int): Boolean = when (Character.UnicodeScript.of(codePoint)) {
    Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA,
    Character.UnicodeScript.KATAKANA, Character.UnicodeScript.HANGUL -> true
    else -> false
}
