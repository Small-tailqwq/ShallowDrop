package io.github.smalltailqwq.shallowdrop.data.api

import java.io.Reader

/*
 * SSE（text/event-stream）解析，移植自 src/services/api/sse.ts，按 WHATWG 规范实现：
 * https://html.spec.whatwg.org/multipage/server-sent-events.html#event-stream-interpretation
 *
 * 字节流 → UTF-8 解码 → 按 CRLF / LF / CR 切行 → 字段累积 → 空行派发事件。
 * 注释行（如服务端保活的 `: keep-alive`）直接忽略；`retry` 只对 EventSource 重连有意义，这里不处理。
 */

data class ServerSentEvent(
    /** `event` 字段，缺省为 `message` */
    val event: String,
    /** 多行 `data` 以 `\n` 连接 */
    val data: String,
    /** 最近一次 `id` 字段 */
    val lastEventId: String,
)

class ServerSentEventDecoder {
    private val buffer = StringBuilder()
    private val data = StringBuilder()
    private var hasData = false
    private var eventType = ""
    private var lastEventId = ""

    /** 输入一段已解码的文本，返回其中完整的事件 */
    fun push(text: CharSequence): List<ServerSentEvent> {
        buffer.append(text)
        val events = mutableListOf<ServerSentEvent>()
        var lineStart = 0
        var i = 0
        while (i < buffer.length) {
            val c = buffer[i]
            if (c != '\r' && c != '\n') {
                i++
                continue
            }
            // 结尾的 CR 可能是 CRLF 的前半，等下一段再判断
            if (c == '\r' && i == buffer.length - 1) break
            processLine(buffer.substring(lineStart, i))?.let(events::add)
            i += if (c == '\r' && buffer[i + 1] == '\n') 2 else 1
            lineStart = i
        }
        buffer.delete(0, lineStart)
        return events
    }

    /** 流结束：结尾的 CR 是完整的换行，其余未以空行结尾的事件按规范丢弃 */
    fun end(): List<ServerSentEvent> {
        val event = if (buffer.endsWith("\r")) processLine(buffer.substring(0, buffer.length - 1)) else null
        buffer.setLength(0)
        data.setLength(0)
        hasData = false
        eventType = ""
        return listOfNotNull(event)
    }

    private fun processLine(line: String): ServerSentEvent? {
        if (line.isEmpty()) return dispatch()
        if (line.startsWith(":")) return null
        val colon = line.indexOf(':')
        val field = if (colon == -1) line else line.substring(0, colon)
        var value = if (colon == -1) "" else line.substring(colon + 1)
        if (value.startsWith(" ")) value = value.substring(1)
        when (field) {
            "data" -> {
                if (hasData) data.append('\n')
                data.append(value)
                hasData = true
            }
            "event" -> eventType = value
            "id" -> if (!value.contains('\u0000')) lastEventId = value
        }
        return null
    }

    private fun dispatch(): ServerSentEvent? {
        val event = if (hasData) ServerSentEvent(eventType.ifEmpty { "message" }, data.toString(), lastEventId) else null
        data.setLength(0)
        hasData = false
        eventType = ""
        return event
    }
}

/**
 * 阻塞读取字符流中的 SSE 事件，逐个交给 onEvent；流读完即返回。
 * onEvent 返回 false 时提前结束读取。reader 需按 UTF-8 解码（InputStreamReader 会处理跨分片的多字节字符）。
 */
internal inline fun readServerSentEvents(reader: Reader, onEvent: (ServerSentEvent) -> Boolean) {
    val decoder = ServerSentEventDecoder()
    val chars = CharArray(8192)
    var first = true
    while (true) {
        val n = reader.read(chars)
        if (n == -1) break
        if (n == 0) continue
        var start = 0
        // 与 TextDecoder 一致，去掉开头的 BOM
        if (first) {
            first = false
            if (chars[0] == '﻿') start = 1
        }
        for (event in decoder.push(String(chars, start, n - start))) {
            if (!onEvent(event)) return
        }
    }
    for (event in decoder.end()) {
        if (!onEvent(event)) return
    }
}
