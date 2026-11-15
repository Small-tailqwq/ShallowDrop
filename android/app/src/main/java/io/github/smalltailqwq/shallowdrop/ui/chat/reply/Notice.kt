package io.github.smalltailqwq.shallowdrop.ui.chat.reply

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import io.github.smalltailqwq.shallowdrop.R
import io.github.smalltailqwq.shallowdrop.core.model.Message
import io.github.smalltailqwq.shallowdrop.core.model.ReplyBlock
import io.github.smalltailqwq.shallowdrop.core.model.ReplyErrorKind
import io.github.smalltailqwq.shallowdrop.core.model.ReplyStatus
import io.github.smalltailqwq.shallowdrop.core.model.StopReason

/*
 * 回复出错或未正常结束时的提示，对照 src/services/chat/describe.ts。
 */

/** 文案：资源或原文二选一 */
sealed interface UiText {
    data class Res(@param:StringRes val id: Int) : UiText

    data class Raw(val text: String) : UiText

    data class Plural(@param:PluralsRes val id: Int, val count: Int) : UiText

    /** 单个字符串参数的格式化文案 */
    data class Format(@param:StringRes val id: Int, val arg: String) : UiText
}

enum class NoticeAction { ApiKey, TopUp }

data class Notice(
    val text: UiText,
    /** 模板答复使用正文样式；否则为红色错误提示 */
    val asResponse: Boolean = false,
    val action: NoticeAction? = null,
)

/** HTTP 状态分类，对照 src/services/api/errors.ts classifyStatus */
@StringRes
private fun statusMessage(status: Int?): Int? = when {
    status == 401 -> R.string.reply_invalid_api_key
    status == 402 -> R.string.reply_insufficient_balance
    status == 429 -> R.string.reply_rate_limited
    (status ?: 0) >= 500 -> R.string.server_busy
    else -> null
}

/**
 * 错误码到文案的映射；未知码回退到原文。
 * 来源：data/api 的 ApiErrorCodes（errors.ts 分类名）、domain/chat 的 UploadErrorCodes（i18n 键名）。
 */
@StringRes
fun errorCodeMessage(code: String?): Int? = when (code) {
    "unauthorized", "replyInvalidApiKey", "apiKeyInvalidRetry" -> R.string.reply_invalid_api_key
    "insufficientBalance", "replyInsufficientBalance" -> R.string.reply_insufficient_balance
    "rateLimited", "replyRateLimited" -> R.string.reply_rate_limited
    "serverBusy", "completionServerBusyToast", "uploadServerBusy" -> R.string.server_busy
    "network", "commonNetworkErrorToast" -> R.string.error_network
    "uploadFileStatusFailed", "uploadParseFailed", "parseFailed" -> R.string.upload_failed
    "replyRefused" -> R.string.reply_refused
    else -> null
}

private fun templateText(reply: Message.Assistant) =
    reply.blocks.joinToString("") { if (it is ReplyBlock.Text && it.isTemplate) it.text else "" }

fun describeReply(reply: Message.Assistant): Notice? {
    val error = reply.error
    if (reply.status == ReplyStatus.Error && error != null) {
        val message: UiText? = errorCodeMessage(error.code)?.let { UiText.Res(it) }
            ?: error.message.takeIf { it.isNotEmpty() }?.let { UiText.Raw(it) }
        if (error.kind == ReplyErrorKind.Upload) {
            return Notice(message ?: UiText.Res(R.string.upload_failed))
        }
        if (error.kind == ReplyErrorKind.Network) {
            return Notice(UiText.Res(R.string.error_network))
        }
        return when (error.status) {
            401 -> Notice(UiText.Res(R.string.reply_invalid_api_key), action = NoticeAction.ApiKey)
            402 -> Notice(UiText.Res(R.string.reply_insufficient_balance), action = NoticeAction.TopUp)
            else -> statusMessage(error.status)?.let { Notice(UiText.Res(it)) } ?: message?.let { Notice(it) }
        }
    }
    val template = templateText(reply)
    // 归档不带结构化错误，错误文案保存在模板中，仍按错误提示展示
    if (reply.status == ReplyStatus.Error && template.isNotEmpty()) {
        return Notice(UiText.Raw(template))
    }
    if (reply.stopReason == StopReason.Refusal) {
        return Notice(
            if (template.isNotEmpty()) UiText.Raw(template) else UiText.Res(R.string.reply_refused),
            asResponse = true,
        )
    }
    if (template.isNotEmpty()) return Notice(UiText.Raw(template), asResponse = true)
    return null
}
