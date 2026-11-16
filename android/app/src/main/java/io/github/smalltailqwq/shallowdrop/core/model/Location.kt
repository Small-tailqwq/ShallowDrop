package io.github.smalltailqwq.shallowdrop.core.model

enum class LocationMode { Off, Gps, Network, Custom }

data class LocationPreference(
    val mode: LocationMode = LocationMode.Off,
    val custom: String = "",
)

/** latest_reminder 使用逗号分隔字段，位置文本不能引入额外字段或行。 */
fun locationForReminder(value: String): String = value.replace(Regex("[,\\r\\n]+"), " ").trim().take(160)
