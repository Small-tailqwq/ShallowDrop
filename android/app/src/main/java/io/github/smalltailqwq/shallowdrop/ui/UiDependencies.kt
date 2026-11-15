package io.github.smalltailqwq.shallowdrop.ui

import android.app.Application
import io.github.smalltailqwq.shallowdrop.App
import io.github.smalltailqwq.shallowdrop.core.contract.ChatRepository
import io.github.smalltailqwq.shallowdrop.core.contract.HistoryBackup
import io.github.smalltailqwq.shallowdrop.core.contract.SettingsRepository
import io.github.smalltailqwq.shallowdrop.core.model.ApiException
import io.github.smalltailqwq.shallowdrop.core.model.ReplyErrorKind
import io.github.smalltailqwq.shallowdrop.data.settings.DataStoreSettingsRepository
import io.github.smalltailqwq.shallowdrop.ui.fake.FakeChatRepository
import io.github.smalltailqwq.shallowdrop.ui.fake.FakeHistoryBackup
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * UI 所需依赖。开发期 [USE_FAKE] 为 true：设置用真实的 [DataStoreSettingsRepository]，
 * 对话与导入导出用 ui/fake 的内存实现，Key 校验只做格式模拟。
 * 集成阶段把 [USE_FAKE] 改为 false，并在 AppContainer 中接好各实现（settingsRepository 需为
 * DataStoreSettingsRepository，以便取得 [settingsLoaded]）。
 */
class UiDependencies(
    val settingsRepository: SettingsRepository,
    val chatRepository: ChatRepository,
    val historyBackup: HistoryBackup,
    /** 校验 API Key（GET /models）；失败抛 ApiException */
    val verifyApiKey: suspend (String) -> Unit,
    /** 设置首次读取完成；之前不决定起始页面 */
    val settingsLoaded: StateFlow<Boolean>,
) {
    companion object {
        /** 集成切换点：true 时对话与历史使用 Fake */
        const val USE_FAKE = true

        @Volatile
        private var instance: UiDependencies? = null

        fun from(application: Application): UiDependencies =
            instance ?: synchronized(this) { instance ?: create(application).also { instance = it } }

        private fun create(application: Application): UiDependencies {
            if (USE_FAKE) {
                val settings = DataStoreSettingsRepository(application)
                return UiDependencies(
                    settingsRepository = settings,
                    chatRepository = FakeChatRepository(application),
                    historyBackup = FakeHistoryBackup(application),
                    verifyApiKey = ::fakeVerify,
                    settingsLoaded = settings.loaded,
                )
            }
            val container = (application as App).container
            val settings = container.settingsRepository
            return UiDependencies(
                settingsRepository = settings,
                chatRepository = container.chatRepository,
                historyBackup = container.historyBackup,
                verifyApiKey = { container.chatApi.verifyApiKey(it) },
                settingsLoaded = (settings as? DataStoreSettingsRepository)?.loaded ?: MutableStateFlow(true),
            )
        }

        /** 以 sk- 开头视为有效，其余模拟 401 */
        private suspend fun fakeVerify(apiKey: String) {
            delay(800)
            if (!apiKey.startsWith("sk-")) {
                throw ApiException(ReplyErrorKind.Http, "Authentication Fails", status = 401)
            }
        }
    }
}
