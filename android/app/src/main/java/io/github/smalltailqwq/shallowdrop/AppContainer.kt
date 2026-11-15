package io.github.smalltailqwq.shallowdrop

import android.content.Context
import io.github.smalltailqwq.shallowdrop.core.contract.AttachmentStore
import io.github.smalltailqwq.shallowdrop.core.contract.ChatApi
import io.github.smalltailqwq.shallowdrop.core.contract.ChatRepository
import io.github.smalltailqwq.shallowdrop.core.contract.HistoryBackup
import io.github.smalltailqwq.shallowdrop.core.contract.SessionStore
import io.github.smalltailqwq.shallowdrop.core.contract.SettingsRepository

/**
 * 手动依赖注入。每个组件一行，由对应工作流替换为真实实现；集成阶段统一接线。
 * W0 阶段全部为 TODO，UI 开发期使用 ui 内的 Fake 实现。
 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val chatApi: ChatApi by lazy { TODO("W1: data.api.DeepSeekApi") }

    val sessionStore: SessionStore by lazy { TODO("W2: data.db") }

    val attachmentStore: AttachmentStore by lazy { TODO("W2: data.db") }

    val historyBackup: HistoryBackup by lazy { TODO("W2: data.history") }

    val settingsRepository: SettingsRepository by lazy { TODO("W4: data.settings") }

    val chatRepository: ChatRepository by lazy { TODO("W5: domain.chat") }
}
