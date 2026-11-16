package io.github.smalltailqwq.shallowdrop.data.settings

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.smalltailqwq.shallowdrop.core.contract.SettingsRepository
import io.github.smalltailqwq.shallowdrop.core.model.LocationPreference
import io.github.smalltailqwq.shallowdrop.core.model.LocationMode
import io.github.smalltailqwq.shallowdrop.core.model.AppLocale
import io.github.smalltailqwq.shallowdrop.core.model.AppSettings
import io.github.smalltailqwq.shallowdrop.core.model.ChatModel
import io.github.smalltailqwq.shallowdrop.core.model.ChatFontSize
import io.github.smalltailqwq.shallowdrop.core.model.ReplySwipeSettings
import io.github.smalltailqwq.shallowdrop.core.model.CompactionThresholds
import io.github.smalltailqwq.shallowdrop.core.model.ThemePreference
import io.github.smalltailqwq.shallowdrop.core.model.TtsVoice
import java.io.IOException
import java.security.GeneralSecurityException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * 设置持久化：DataStore Preferences；API Key 经 [KeyCipher]（Keystore AES-GCM）加密后存储，
 * 明文只在 [apiKey] 调用时解密返回，不进入 [settings]。
 */
class DataStoreSettingsRepository(
    context: Context,
    private val cipher: KeyCipher = KeyCipher.androidKeystore(),
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : SettingsRepository {
    private val appContext = context.applicationContext
    private val store = appContext.settingsStore

    private val state = MutableStateFlow(AppSettings())
    override val settings: StateFlow<AppSettings> = state.asStateFlow()

    private val loadedState = MutableStateFlow(false)

    /** 首次从磁盘读取完成后为 true；之前 [settings] 只是默认值，UI 据此决定起始页面。 */
    val loaded: StateFlow<Boolean> = loadedState.asStateFlow()

    init {
        scope.launch {
            store.data
                .catch { e ->
                    if (e is IOException) {
                        Log.w(TAG, "读取设置失败，使用默认值", e)
                        emit(emptyPreferences())
                    } else {
                        throw e
                    }
                }
                .collect { prefs ->
                    state.value = prefs.toSettings()
                    loadedState.value = true
                }
        }
    }

    override suspend fun apiKey(): String? {
        val encrypted = store.data.first()[Keys.API_KEY] ?: return null
        return withContext(Dispatchers.Default) {
            try {
                cipher.decrypt(encrypted)
            } catch (e: GeneralSecurityException) {
                // 密钥被系统清除（如重置锁屏）后旧密文无法解密，视为未设置，等待用户重新输入
                Log.w(TAG, "API Key 解密失败", e)
                null
            }
        }
    }

    override suspend fun setApiKey(apiKey: String?) {
        val value = apiKey?.trim()?.takeIf { it.isNotEmpty() }
        val encrypted = value?.let { withContext(Dispatchers.Default) { cipher.encrypt(it) } }
        store.edit { prefs ->
            if (encrypted == null) prefs.remove(Keys.API_KEY) else prefs[Keys.API_KEY] = encrypted
        }
    }

    override suspend fun acceptRechargeNotice() {
        store.edit { it[Keys.RECHARGE_NOTICE] = true }
    }

    override suspend fun setLocation(location: LocationPreference) {
        store.edit {
            it[Keys.LOCATION_MODE] = location.mode.name
            it[Keys.CUSTOM_LOCATION] = location.custom.trim().take(160)
        }
    }

    override suspend fun setLocale(locale: AppLocale) {
        // 先写镜像再写 DataStore：低版本收到变化后立即 recreate，attachBaseContext 需读到新值
        withContext(Dispatchers.Main) { AppLocales.apply(appContext, locale) }
        store.edit { it[Keys.LOCALE] = locale.name }
    }

    override suspend fun setTheme(theme: ThemePreference) {
        store.edit { it[Keys.THEME] = theme.name }
    }

    override suspend fun setChatFontSize(size: ChatFontSize) {
        store.edit { it[Keys.CHAT_FONT_SIZE] = size.name }
    }

    override suspend fun setAutoReadAloud(enabled: Boolean) {
        store.edit { it[Keys.AUTO_READ_ALOUD] = enabled }
    }

    override suspend fun setReplySwipe(settings: ReplySwipeSettings) {
        store.edit {
            it[Keys.REPLY_SWIPE_ENABLED] = settings.enabled
            it[Keys.REPLY_SWIPE_DIRECTION] = settings.direction.name
            it[Keys.REPLY_SWIPE_DEAD_ZONE] = settings.edgeDeadZoneDp
            it[Keys.REPLY_SWIPE_DISTANCE] = settings.triggerDistanceDp
        }
    }

    override suspend fun setThinking(enabled: Boolean) {
        store.edit { it[Keys.THINKING] = enabled }
    }

    override suspend fun setSearch(enabled: Boolean) {
        store.edit { it[Keys.SEARCH] = enabled }
    }

    override suspend fun setModel(model: ChatModel) {
        store.edit { it[Keys.MODEL] = model.id }
    }

    override suspend fun setTtsVoice(voice: TtsVoice) {
        store.edit { it[Keys.TTS_VOICE] = voice.id }
    }

    override suspend fun setCompactionSettings(thresholds: CompactionThresholds, enabled: Boolean) {
        store.edit {
            it[Keys.COMPACTION_ENABLED] = enabled
            it[Keys.COMPACTION_BACKGROUND] = thresholds.backgroundPercent
            it[Keys.COMPACTION_FOREGROUND] = thresholds.foregroundPercent
        }
    }

    private fun Preferences.toSettings(): AppSettings {
        val defaults = AppSettings()
        return AppSettings(
            hasApiKey = this[Keys.API_KEY] != null,
            rechargeNoticeAccepted = this[Keys.RECHARGE_NOTICE] ?: false,
            location = LocationPreference(enumOrDefault(this[Keys.LOCATION_MODE], LocationMode.Off), this[Keys.CUSTOM_LOCATION].orEmpty()),
            locale = enumOrDefault(this[Keys.LOCALE], defaults.locale),
            theme = enumOrDefault(this[Keys.THEME], defaults.theme),
            chatFontSize = enumOrDefault(this[Keys.CHAT_FONT_SIZE], defaults.chatFontSize),
            autoReadAloud = this[Keys.AUTO_READ_ALOUD] ?: defaults.autoReadAloud,
            replySwipe = ReplySwipeSettings(
                enabled = this[Keys.REPLY_SWIPE_ENABLED] ?: defaults.replySwipe.enabled,
                direction = enumOrDefault(this[Keys.REPLY_SWIPE_DIRECTION], defaults.replySwipe.direction),
                edgeDeadZoneDp = this[Keys.REPLY_SWIPE_DEAD_ZONE] ?: defaults.replySwipe.edgeDeadZoneDp,
                triggerDistanceDp = this[Keys.REPLY_SWIPE_DISTANCE] ?: defaults.replySwipe.triggerDistanceDp,
            ),
            thinking = this[Keys.THINKING] ?: defaults.thinking,
            search = this[Keys.SEARCH] ?: defaults.search,
            model = ChatModel.entries.firstOrNull { it.id == this[Keys.MODEL] } ?: defaults.model,
            // 与 Web 一致：只有 tide 是男声，其余（含缺省）为 stella
            ttsVoice = if (this[Keys.TTS_VOICE] == TtsVoice.Tide.id) TtsVoice.Tide else TtsVoice.Stella,
            compactionThresholds = CompactionThresholds.normalized(
                this[Keys.COMPACTION_BACKGROUND] ?: defaults.compactionThresholds.backgroundPercent,
                this[Keys.COMPACTION_FOREGROUND] ?: defaults.compactionThresholds.foregroundPercent,
            ),
            compactionEnabled = this[Keys.COMPACTION_ENABLED] ?: defaults.compactionEnabled,
        )
    }

    private inline fun <reified E : Enum<E>> enumOrDefault(name: String?, default: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: default

    private object Keys {
        val RECHARGE_NOTICE = booleanPreferencesKey("recharge_notice_accepted")
        val API_KEY = stringPreferencesKey("api_key_encrypted")
        val LOCATION_MODE = stringPreferencesKey("location_mode")
        val CUSTOM_LOCATION = stringPreferencesKey("custom_location")
        val LOCALE = stringPreferencesKey("locale")
        val THEME = stringPreferencesKey("theme")
        val CHAT_FONT_SIZE = stringPreferencesKey("chat_font_size")
        val AUTO_READ_ALOUD = booleanPreferencesKey("auto_read_aloud")
        val REPLY_SWIPE_ENABLED = booleanPreferencesKey("reply_swipe_enabled")
        val REPLY_SWIPE_DIRECTION = stringPreferencesKey("reply_swipe_direction")
        val REPLY_SWIPE_DEAD_ZONE = intPreferencesKey("reply_swipe_dead_zone_dp")
        val REPLY_SWIPE_DISTANCE = intPreferencesKey("reply_swipe_distance_dp")
        val THINKING = booleanPreferencesKey("thinking")
        val SEARCH = booleanPreferencesKey("search")
        val MODEL = stringPreferencesKey("model")
        val TTS_VOICE = stringPreferencesKey("tts_voice")
        val COMPACTION_BACKGROUND = intPreferencesKey("compaction_background_percent")
        val COMPACTION_ENABLED = booleanPreferencesKey("compaction_enabled")
        val COMPACTION_FOREGROUND = intPreferencesKey("compaction_foreground_percent")
    }

    private companion object {
        const val TAG = "SettingsRepository"
    }
}
