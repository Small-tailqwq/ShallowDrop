package io.github.smalltailqwq.shallowdrop.domain.chat

import io.github.smalltailqwq.shallowdrop.core.model.locationForReminder
import io.github.smalltailqwq.shallowdrop.core.model.AppLocale
import java.time.Instant
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/*
 * latest_reminder 快照，对照 src/services/chat/latestReminder.ts。
 * 模型约定五项信息，逗号连接：日期,星期,位置,平台,语言。
 * - 日期与星期使用设备本地时区，避免 UTC 日期跨日；
 * - 位置来自用户选择的定位方式或自定义文本；关闭时留空；
 * - 平台 Web 版为 "Web"，原生客户端为 "App"（服务端只接受这两个值，填 "Android" 会被 400 拒绝）。
 */

/** 快照中的平台名 */
const val REMINDER_PLATFORM = "App"

/** 与 Web 的 Locale 一致：只有简体中文与美式英文；跟随系统时按系统语言是否为中文判断。 */
enum class ReminderLocale(val tag: String, val language: String) {
    ZhCN("zh-CN", "中文"),
    EnUS("en-US", "English"),
    ;

    val javaLocale: Locale get() = Locale.forLanguageTag(tag)

    companion object {
        fun resolve(locale: AppLocale, system: Locale = Locale.getDefault()): ReminderLocale = when (locale) {
            AppLocale.ZhCN -> ZhCN
            AppLocale.EnUS -> EnUS
            AppLocale.System -> if (system.language.equals("zh", ignoreCase = true)) ZhCN else EnUS
        }
    }
}

fun createLatestReminder(locale: ReminderLocale, now: Long, zone: ZoneId = ZoneId.systemDefault(), location: String = ""): String {
    val date = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val formatted = String.format(Locale.ROOT, "%04d-%02d-%02d", date.year, date.monthValue, date.dayOfMonth)
    val weekday = date.dayOfWeek.getDisplayName(TextStyle.FULL, locale.javaLocale)
    val locationName = locationForReminder(location)
    return listOf(formatted, weekday, locationName, REMINDER_PLATFORM, locale.language).joinToString(",")
}
