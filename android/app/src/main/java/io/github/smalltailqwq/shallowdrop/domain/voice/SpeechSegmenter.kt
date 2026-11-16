package io.github.smalltailqwq.shallowdrop.domain.voice

/**
 * 把流式回复切成可朗读的片段，实现「边生成边播报」：不等整段回答结束，攒够一句就交给 TTS。
 *
 * - [push] 传入到目前为止的完整正文（流式文本是累加的），返回新凑齐的片段；回复结束时调用 [flush] 取出余下内容。
 * - 句末标点（。！？；换行等）处切分；首段在逗号处也可提前切出，缩短开口前的等待。超长无标点时在 [maxChars] 处强制切分。
 * - 代码块整体跳过：围栏未闭合时先停在围栏前，等闭合后跳过整块。
 * - 每段经 [toSpeech] 去掉 Markdown、链接、引用角标与表情，清理后为空的片段不输出。
 */
class SpeechSegmenter(
    private val firstMinChars: Int = 6,
    private val minChars: Int = 16,
    private val maxChars: Int = 120,
) {
    private var source = ""
    private var consumed = 0

    fun push(fullText: String): List<String> {
        if (!fullText.startsWith(source)) reset()
        source = fullText
        val text = speakable(fullText, final = false)
        return cut(text, final = false)
    }

    fun flush(): List<String> = cut(speakable(source, final = true), final = true)

    fun reset() {
        source = ""
        consumed = 0
    }

    private fun cut(text: String, final: Boolean): List<String> {
        val out = mutableListOf<String>()
        while (consumed < text.length) {
            val end = nextBoundary(text, consumed, first = out.isEmpty() && consumed == 0) ?: if (final) text.length else break
            val segment = toSpeech(text.substring(consumed, end))
            consumed = end
            if (segment.isNotEmpty()) out += segment
        }
        return out
    }

    /** 返回下一个切分点（不含），还没凑够一句时返回 null */
    private fun nextBoundary(text: String, start: Int, first: Boolean): Int? {
        val softMin = if (first) firstMinChars else minChars
        var lastSoft = -1
        var i = start
        while (i < text.length) {
            val c = text[i]
            val length = i - start + 1
            when {
                c in HARD_STOPS -> return i + 1
                // 英文句点：后面是空白才算句末，避免切开小数、缩写和网址
                c == '.' || c == '!' || c == '?' -> {
                    val next = text.getOrNull(i + 1) ?: return null
                    if (next.isWhitespace()) return i + 1
                }
                c in SOFT_STOPS -> {
                    if (length >= softMin) return i + 1
                    lastSoft = i + 1
                }
            }
            if (length >= maxChars) return if (lastSoft > start) lastSoft else i + 1
            i++
        }
        return null
    }

    companion object {
        private const val HARD_STOPS = "。！？；\n"
        private const val SOFT_STOPS = "，、：,;:"

        private val FENCE = Regex("```[\\s\\S]*?```")
        private val IMAGE = Regex("!\\[[^\\]]*]\\([^)]*\\)")
        private val LINK = Regex("\\[([^\\]]+)]\\([^)]*\\)")
        private val URL = Regex("https?://\\S+")
        private val CITATION = Regex("\\[(?:citation:)?\\d+]")
        private val HEADING = Regex("(?m)^\\s{0,3}#{1,6}\\s*")
        private val BULLET = Regex("(?m)^\\s*(?:[-*+]|\\d+[.)])\\s+")
        private val QUOTE = Regex("(?m)^\\s*>\\s?")
        private val TABLE_RULE = Regex("(?m)^\\s*\\|?\\s*:?-{3,}.*$")
        private val EMPHASIS = Regex("[*_~`]+")
        private val EMOJI = Regex("[\\x{1F000}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{FE0F}\\x{200D}]")
        private val SPACES = Regex("[ \\t\\u3000]+")
        private val ONLY_PUNCTUATION = Regex("^[\\p{P}\\p{S}\\s]*$")

        /**
         * 可朗读的正文：去掉已闭合的代码块；未闭合的围栏及其后内容先不读，结尾可能组成围栏的反引号也先保留。
         * 正文增长时结果只会在末尾追加，已切出的前缀保持不变。
         */
        internal fun speakable(text: String, final: Boolean): String {
            val stripped = FENCE.replace(text, "\n")
            val open = stripped.indexOf("```")
            if (open >= 0) return stripped.substring(0, open)
            if (final) return stripped
            return stripped.trimEnd('`')
        }

        /** 单个片段转成朗读文本 */
        fun toSpeech(segment: String): String {
            var s = segment
            s = IMAGE.replace(s, "")
            s = LINK.replace(s, "$1")
            s = URL.replace(s, "")
            s = CITATION.replace(s, "")
            s = TABLE_RULE.replace(s, "")
            s = HEADING.replace(s, "")
            s = BULLET.replace(s, "")
            s = QUOTE.replace(s, "")
            s = s.replace('|', ' ')
            s = EMPHASIS.replace(s, "")
            s = EMOJI.replace(s, "")
            s = SPACES.replace(s.replace('\n', ' '), " ").trim()
            return if (ONLY_PUNCTUATION.matches(s)) "" else s
        }
    }
}
