package io.github.smalltailqwq.shallowdrop.data.api

import java.io.StringReader
import org.junit.Assert.assertEquals
import org.junit.Test

class ServerSentEventDecoderTest {

    private fun decodeAll(chunks: List<String>): List<ServerSentEvent> {
        val decoder = ServerSentEventDecoder()
        return chunks.flatMap { decoder.push(it) } + decoder.end()
    }

    @Test
    fun eventAndMultilineData() {
        val events = decodeAll(listOf("event: a\ndata: 1\ndata:2\n\ndata: x\n\n"))
        assertEquals(
            listOf(ServerSentEvent("a", "1\n2", ""), ServerSentEvent("message", "x", "")),
            events,
        )
    }

    @Test
    fun commentsAndUnknownFieldsIgnored() {
        val events = decodeAll(listOf(": keep-alive\n\n: ping\nretry: 10\nfoo\ndata: ok\n\n"))
        assertEquals(listOf(ServerSentEvent("message", "ok", "")), events)
    }

    @Test
    fun everySplitPointYieldsSameEvents() {
        val text = "event: e\r\ndata: 你好\r\n\r\n: c\rdata: b\r\rid: 7\ndata: c\n\n"
        val expected = decodeAll(listOf(text))
        assertEquals(
            listOf(ServerSentEvent("e", "你好", ""), ServerSentEvent("message", "b", ""), ServerSentEvent("message", "c", "7")),
            expected,
        )
        for (i in 0..text.length) {
            assertEquals("split at $i", expected, decodeAll(listOf(text.substring(0, i), text.substring(i))))
        }
        // 逐字符输入
        assertEquals(expected, decodeAll(text.map { it.toString() }))
    }

    @Test
    fun trailingCrAtEndIsLineBreak() {
        assertEquals(listOf(ServerSentEvent("message", "x", "")), decodeAll(listOf("data: x\r", "\r")))
    }

    @Test
    fun unterminatedEventDropped() {
        assertEquals(emptyList<ServerSentEvent>(), decodeAll(listOf("data: x\n")))
    }

    @Test
    fun readerStripsBom() {
        val events = mutableListOf<ServerSentEvent>()
        readServerSentEvents(StringReader("﻿data: 1\n\n")) { events += it; true }
        assertEquals(listOf(ServerSentEvent("message", "1", "")), events)
    }
}
