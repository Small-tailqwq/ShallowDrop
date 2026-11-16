package io.github.smalltailqwq.shallowdrop.core.model

enum class AppLocale { System, ZhCN, EnUS }

enum class ThemePreference { System, Light, Dark }

/** 朗读音色，对照 src/services/api/speech.ts 的 TtsVoice（/v1/tools/tts 的 voice_id） */
enum class TtsVoice(val id: String) { Stella("stella"), Tide("tide") }

/** 本机设置，对照 src/services/settings.ts（不含 Web 专有的侧栏收起与位置）。 */
data class AppSettings(
    val hasApiKey: Boolean = false,
    val locale: AppLocale = AppLocale.System,
    val theme: ThemePreference = ThemePreference.System,
    val thinking: Boolean = false,
    val search: Boolean = false,
    val model: ChatModel = ChatModel.Flash,
    val ttsVoice: TtsVoice = TtsVoice.Stella,
)
