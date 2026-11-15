package io.github.smalltailqwq.shallowdrop.data.api

import io.github.smalltailqwq.shallowdrop.core.model.CreateMessageRequest
import io.github.smalltailqwq.shallowdrop.core.model.MessageParam
import io.github.smalltailqwq.shallowdrop.core.model.UserContentParam
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/*
 * CreateMessageRequest → 请求体 JSON，与 src/services/api/types.ts 的 CreateMessageRequest
 * 及 client.ts streamMessage 中的 `{ ...body, stream: true }` 逐字段对应。
 * 字段顺序与 store.ts 构造请求时一致：model、system、messages、thinking、safety_check、tools、title、stream。
 * Web 版不传 max_tokens 等其他参数，这里同样不传。
 */

/** 扩展模式的安全检查等级，Web 版固定为 high */
private const val SAFETY_CHECK_LEVEL = "high"

internal fun CreateMessageRequest.toJson(json: Json): JsonObject = buildJsonObject {
    put("model", model.id)
    put("system", system)
    put("messages", JsonArray(messages.map { it.toJson(json) }))
    putJsonObject("thinking") { put("type", if (thinking) "enabled" else "disabled") }
    putJsonObject("safety_check") { put("level", SAFETY_CHECK_LEVEL) }
    put(
        "tools",
        buildJsonArray {
            for (tool in tools) {
                add(buildJsonObject { put("type", tool.type); put("name", tool.type) })
            }
        },
    )
    // 只有请求标题时才带 title，与 Web 版 `...(titleRequests.has(controller) ? { title: { enabled: true } } : {})` 一致
    if (title) putJsonObject("title") { put("enabled", true) }
    put("stream", true)
}

private fun MessageParam.toJson(json: Json): JsonObject = when (this) {
    is MessageParam.LatestReminder -> buildJsonObject {
        put("role", "latest_reminder")
        put("content", content)
    }
    is MessageParam.User -> buildJsonObject {
        put("role", "user")
        put("content", userContent(content, json))
    }
    is MessageParam.Assistant -> buildJsonObject {
        put("role", "assistant")
        put("content", JsonArray(content))
    }
}

/**
 * 对照 reply.ts toUserParam：没有文件时 content 为字符串；有文件时为内容块数组（先文件后文本）。
 * 这里不重排，块顺序由 domain 决定；纯文本时多个文本片段直接拼接。
 */
private fun userContent(content: List<UserContentParam>, json: Json): JsonElement {
    if (content.all { it is UserContentParam.Text }) {
        return JsonPrimitive(content.joinToString("") { (it as UserContentParam.Text).text })
    }
    return JsonArray(content.map { json.encodeToJsonElement(UserContentParam.serializer(), it) })
}

/** 消息中是否引用了 Files API 文件（图片或文档），需带 anthropic-beta 头 */
internal fun CreateMessageRequest.referencesFiles(): Boolean =
    hasFiles || messages.any { message ->
        message is MessageParam.User && message.content.any { it !is UserContentParam.Text }
    }
