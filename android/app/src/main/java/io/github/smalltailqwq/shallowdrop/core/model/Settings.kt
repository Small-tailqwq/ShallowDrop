package io.github.smalltailqwq.shallowdrop.core.model

enum class AppLocale { System, ZhCN, EnUS }

enum class ThemePreference { System, Light, Dark }

enum class ChatFontSize(val sp: Int) { Small(14), Standard(16), Large(18), ExtraLarge(20) }

enum class ReplySwipeDirection { LeftToRight, RightToLeft }

data class ReplySwipeSettings(
    val enabled: Boolean = false,
    val direction: ReplySwipeDirection = ReplySwipeDirection.RightToLeft,
    val edgeDeadZoneDp: Int = 32,
    val triggerDistanceDp: Int = 96,
)

/** 朗读音色，对照 src/services/api/speech.ts 的 TtsVoice（/v1/tools/tts 的 voice_id） */
enum class TtsVoice(val id: String) { Stella("stella"), Tide("tide") }

data class CompactionThresholds(val backgroundPercent: Int = 65, val foregroundPercent: Int = 80) {
    init {
        require(backgroundPercent >= MIN_PERCENT && foregroundPercent <= MAX_WAIT_PERCENT &&
            foregroundPercent - backgroundPercent >= MIN_GAP_PERCENT)
    }

    companion object {
        const val MIN_PERCENT = 1
        const val MAX_WAIT_PERCENT = 80
        const val MIN_GAP_PERCENT = 5

        fun normalized(background: Int, foreground: Int): CompactionThresholds {
            val wait = foreground.coerceIn(MIN_PERCENT + MIN_GAP_PERCENT, MAX_WAIT_PERCENT)
            return CompactionThresholds(background.coerceIn(MIN_PERCENT, wait - MIN_GAP_PERCENT), wait)
        }
    }
}

/** 本机设置，对照 src/services/settings.ts（不含 Web 专有的侧栏收起）。 */
data class AppSettings(
    val hasApiKey: Boolean = false,
    val rechargeNoticeAccepted: Boolean = false,
    val locale: AppLocale = AppLocale.System,
    val location: LocationPreference = LocationPreference(),
    val theme: ThemePreference = ThemePreference.System,
    val chatFontSize: ChatFontSize = ChatFontSize.Standard,
    val thinking: Boolean = false,
    val search: Boolean = false,
    val model: ChatModel = ChatModel.Flash,
    val ttsVoice: TtsVoice = TtsVoice.Stella,
    val autoReadAloud: Boolean = false,
    val replySwipe: ReplySwipeSettings = ReplySwipeSettings(),
    val compactionThresholds: CompactionThresholds = CompactionThresholds(),
    val compactionEnabled: Boolean = true,
)
