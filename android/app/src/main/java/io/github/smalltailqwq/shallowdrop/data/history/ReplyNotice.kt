package io.github.smalltailqwq.shallowdrop.data.history

import io.github.smalltailqwq.shallowdrop.core.model.Message
import io.github.smalltailqwq.shallowdrop.core.model.ReplyBlock
import io.github.smalltailqwq.shallowdrop.core.model.ReplyErrorKind
import io.github.smalltailqwq.shallowdrop.core.model.ReplyStatus
import io.github.smalltailqwq.shallowdrop.core.model.StopReason
import java.util.Locale

/**
 * 导出时把本地错误和拒绝提示写成 TEMPLATE_RESPONSE 的文案，对照 describe.ts 的 describeReply。
 * 集成方可替换为按 ReplyError.code 与应用语言取 strings.xml 的实现。
 */
fun interface ReplyNotice {
    /** 回复出错或未正常结束时展示的提示；没有提示返回 null */
    fun describe(reply: Message.Assistant): String?
}

/** 默认实现：中文 / 英文文案取自 src/i18n/zh.ts、en.ts，语言由 [chinese] 决定。 */
class DefaultReplyNotice(
    private val chinese: () -> Boolean = { Locale.getDefault().language == "zh" },
) : ReplyNotice {
    private fun text(zh: String, en: String) = if (chinese()) zh else en

    override fun describe(reply: Message.Assistant): String? {
        val error = reply.error
        if (reply.status == ReplyStatus.Error && error != null) {
            // 导入的历史可能来自不同版本；本层不识别 code，沿用保存时的原文。
            val message = error.message
            if (error.kind == ReplyErrorKind.Upload) {
                return message.ifEmpty { text("上传失败", "Upload failed") }
            }
            if (error.kind == ReplyErrorKind.Network) {
                return text("网络异常，请检查你的网络状况", "Network Error. Please check your network status.")
            }
            val status = error.status ?: 0
            return when {
                status == 401 -> text(
                    "API Key 无效或已被删除，请更换后重试",
                    "The API Key is invalid or has been deleted. Please replace it and try again.",
                )
                status == 402 -> text("账户余额不足，请充值后重试", "Insufficient account balance. Please top up and try again.")
                status == 429 -> text("请求过于频繁，请稍后再试", "Too many requests. Please try again later.")
                status >= 500 -> text("服务器繁忙，请稍后再试", "Server busy, please try again later.")
                else -> message.ifEmpty { null }
            }
        }
        val template = reply.blocks.joinToString("") { (it as? ReplyBlock.Text)?.takeIf { b -> b.isTemplate }?.text ?: "" }
        // 归档不带结构化错误，错误文案保存在模板中，仍按错误提示展示。
        if (reply.status == ReplyStatus.Error && template.isNotEmpty()) return template
        if (reply.stopReason == StopReason.Refusal) {
            return template.ifEmpty {
                text(
                    "你好，这个问题我暂时无法回答，让我们换个话题再聊聊吧。",
                    "Sorry, that's beyond my current scope. Let's talk about something else.",
                )
            }
        }
        return template.ifEmpty { null }
    }
}
