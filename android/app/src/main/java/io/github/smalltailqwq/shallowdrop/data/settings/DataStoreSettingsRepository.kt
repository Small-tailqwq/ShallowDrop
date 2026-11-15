package io.github.smalltailqwq.shallowdrop.data.settings

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.smalltailqwq.shallowdrop.core.contract.SettingsRepository
import io.github.smalltailqwq.shallowdrop.core.model.AppLocale
import io.github.smalltailqwq.shallowdrop.core.model.AppSettings
import io.github.smalltailqwq.shallowdrop.core.model.ChatModel
import io.github.smalltailqwq.shallowdrop.core.model.ThemePreference
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

    override suspend fun setLocale(locale: AppLocale) {
        // 先写镜像再写 DataStore：低版本收到变化后立即 recreate，attachBaseContext 需读到新值
        withContext(Dispatchers.Main) { AppLocales.apply(appContext, locale) }
        store.edit { it[Keys.LOCALE] = locale.name }
    }

    override suspend fun setTheme(theme: ThemePreference) {
        store.edit { it[Keys.THEME] = theme.name }
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

    private fun Preferences.toSettings(): AppSettings {
        val defaults = AppSettings()
        return AppSettings(
            hasApiKey = this[Keys.API_KEY] != null,
            locale = enumOrDefault(this[Keys.LOCALE], defaults.locale),
            theme = enumOrDefault(this[Keys.THEME], defaults.theme),
            thinking = this[Keys.THINKING] ?: defaults.thinking,
            search = this[Keys.SEARCH] ?: defaults.search,
            model = ChatModel.entries.firstOrNull { it.id == this[Keys.MODEL] } ?: defaults.model,
        )
    }

    private inline fun <reified E : Enum<E>> enumOrDefault(name: String?, default: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: default

    private object Keys {
        val API_KEY = stringPreferencesKey("api_key_encrypted")
        val LOCALE = stringPreferencesKey("locale")
        val THEME = stringPreferencesKey("theme")
        val THINKING = booleanPreferencesKey("thinking")
        val SEARCH = booleanPreferencesKey("search")
        val MODEL = stringPreferencesKey("model")
    }

    private companion object {
        const val TAG = "SettingsRepository"
    }
}
