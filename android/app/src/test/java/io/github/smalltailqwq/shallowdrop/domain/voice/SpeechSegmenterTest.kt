package io.github.smalltailqwq.shallowdrop.domain.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 流式分句：边生成边朗读的切分与清理。 */
class SpeechSegmenterTest {

    /** 按给定的增量逐步推入，返回每一步新切出的片段，最后附上 flush */
    private fun stream(vararg deltas: String): List<List<String>> {
        val segmenter = SpeechSegmenter()
        var text = ""
        val steps = deltas.map { delta ->
            text += delta
            segmenter.push(text)
        }
        return steps + listOf(segmenter.flush())
    }

    @Test
    fun firstSentenceIsCutAsSoonAsItEnds() {
        val steps = stream("今天天气", "不错。我们", "去公园吧！", "好的")
        assertEquals(listOf(emptyList(), listOf("今天天气不错。"), listOf("我们去公园吧！"), emptyList(), listOf("好的")), steps)
    }

    @Test
    fun firstSegmentMayEndAtCommaToStartSpeakingEarly() {
        val steps = stream("好的没问题，我们", "马上开始准备今天的工作内容，然后再说")
        assertEquals(listOf("好的没问题，"), steps[0])
        // 后续片段要更长才会在逗号处切
        assertEquals(listOf("我们马上开始准备今天的工作内容，"), steps[1])
        assertEquals(listOf("然后再说"), steps[2])
        // 太短的开头不在逗号处切
        assertEquals(emptyList<String>(), SpeechSegmenter().push("好的，没"))
    }

    @Test
    fun decimalsAndUrlsAreNotSplitAtDots() {
        val all = stream("圆周率约是 3.14", "，大约三点一四。").flatten()
        assertEquals(listOf("圆周率约是 3.14，", "大约三点一四。"), all)
        val english = stream("Version 2.5 is out. Try it").flatten()
        assertEquals(listOf("Version 2.5 is out.", "Try it"), english)
    }

    @Test
    fun markdownIsStrippedForSpeech() {
        assertEquals("标题", SpeechSegmenter.toSpeech("## 标题"))
        assertEquals("第一项很重要", SpeechSegmenter.toSpeech("- **第一项**很重要"))
        assertEquals("详见官网", SpeechSegmenter.toSpeech("详见[官网](https://example.com)"))
        assertEquals("访问", SpeechSegmenter.toSpeech("访问 https://example.com/a?b=1"))
        assertEquals("结论如下", SpeechSegmenter.toSpeech("结论如下[citation:2]😀"))
        assertEquals("", SpeechSegmenter.toSpeech("---"))
        assertEquals("", SpeechSegmenter.toSpeech("。"))
    }

    @Test
    fun codeBlocksAreSkippedEvenWhileStreaming() {
        val steps = stream("示例如下：\n```kot", "lin\nprintln(1)\n", "```\n就这样。")
        val all = steps.flatten()
        assertEquals(listOf("示例如下：", "就这样。"), all)
        assertTrue(all.none { it.contains("println") })
    }

    @Test
    fun longTextWithoutPunctuationIsForcedToCut() {
        val segmenter = SpeechSegmenter(maxChars = 10)
        val out = segmenter.push("一二三四五六七八九十十一十二")
        assertEquals(listOf("一二三四五六七八九十"), out)
        assertEquals(listOf("十一十二"), segmenter.flush())
    }

    @Test
    fun restartsWhenTextIsReplaced() {
        val segmenter = SpeechSegmenter()
        assertEquals(listOf("第一句。"), segmenter.push("第一句。"))
        assertEquals(listOf("另一句。"), segmenter.push("另一句。"))
    }
}
