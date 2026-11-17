package io.github.smalltailqwq.shallowdrop.domain.chat

import io.github.smalltailqwq.shallowdrop.core.model.locationForReminder
import java.time.Instant
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/*
 * 「当前情况」：提问那一刻的时间、时区和位置，作为本轮提问开头的一个文本块发送。
 *
 * latest_reminder 的五项格式由服务端校验（日期只能是 YYYY-MM-DD，不能多项），带不了时刻和时区，
 * 所以 Android 另加这一段；latest_reminder 照常发送。每轮的内容随回复保存为快照，
 * 之后的请求原样回放，既不破坏前缀缓存，也让模型知道每条消息是什么时候、在哪说的。
 * 跨对话记忆的提示词用同一个名称引用这段内容，见 domain/memory/MemoryPrompts.kt。
 */

const val CURRENT_STATE_LABEL_ZH = "当前情况"
const val CURRENT_STATE_LABEL_EN = "Current situation"

/**
 * 形如「（当前情况：2026-11-17 星期二 14:30，UTC+09:00 Asia/Tokyo；位置：日本 东京）」的一行。
 * 写成一行括号而不是带标题的多行块：实测多行块偶尔被模型当成表单，在回答开头照着填一遍。
 * [includeTime] 为 false 时只有日期；没有位置时省略位置。
 * 既不发送时刻、也没有位置时返回 null：日期已由 latest_reminder 提供。
 */
fun createCurrentState(
    locale: ReminderLocale,
    now: Long,
    zone: ZoneId = ZoneId.systemDefault(),
    location: String = "",
    includeTime: Boolean = true,
): String? {
    val place = locationForReminder(location)
    if (!includeTime && place.isEmpty()) return null
    val time = Instant.ofEpochMilli(now).atZone(zone)
    val zh = locale == ReminderLocale.ZhCN
    return buildString {
        append(if (zh) "（$CURRENT_STATE_LABEL_ZH：" else "($CURRENT_STATE_LABEL_EN: ")
        append(String.format(Locale.ROOT, "%04d-%02d-%02d", time.year, time.monthValue, time.dayOfMonth))
        append(' ').append(time.dayOfWeek.getDisplayName(TextStyle.FULL, locale.javaLocale))
        if (includeTime) {
            append(String.format(Locale.ROOT, " %02d:%02d", time.hour, time.minute))
            append(if (zh) "，" else ", ").append(if (time.offset.totalSeconds == 0) "UTC" else "UTC${time.offset.id}")
            // 固定偏移的时区没有地区名，只写偏移
            if ('/' in zone.id) append(' ').append(zone.id)
        }
        if (place.isNotEmpty()) append(if (zh) "；位置：" else "; location: ").append(place)
        append(if (zh) "）" else ")")
    }
}
