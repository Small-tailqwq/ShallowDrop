package io.github.smalltailqwq.shallowdrop.ui.voice

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.smalltailqwq.shallowdrop.core.contract.ChatRepository
import io.github.smalltailqwq.shallowdrop.core.contract.SendOptions
import io.github.smalltailqwq.shallowdrop.core.contract.SettingsRepository
import io.github.smalltailqwq.shallowdrop.core.model.AppLocale
import io.github.smalltailqwq.shallowdrop.domain.voice.SpeechInput
import io.github.smalltailqwq.shallowdrop.domain.voice.SpeechOutput
import io.github.smalltailqwq.shallowdrop.domain.voice.VoiceConversation
import io.github.smalltailqwq.shallowdrop.domain.voice.VoiceState
import io.github.smalltailqwq.shallowdrop.ui.common.uiViewModelFactory
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class VoiceViewModel(
    chat: ChatRepository,
    private val settings: SettingsRepository,
    input: SpeechInput,
    output: SpeechOutput,
    sessionId: String?,
) : ViewModel() {
    private val conversation = VoiceConversation(
        chat = chat,
        input = input,
        output = output,
        scope = viewModelScope,
        // 语音对话追求低延迟：不开深度思考；智能搜索与模型沿用对话页设置
        options = {
            val current = settings.settings.value
            SendOptions(thinking = false, search = current.search, model = current.model)
        },
        languageTag = ::languageTag,
        sessionId = sessionId,
    )

    val state: StateFlow<VoiceState> = conversation.state

    private val mutedState = MutableStateFlow(false)
    val muted: StateFlow<Boolean> = mutedState.asStateFlow()

    private val captionsState = MutableStateFlow(true)
    val captions: StateFlow<Boolean> = captionsState.asStateFlow()

    private val permissionDeniedState = MutableStateFlow(false)
    val permissionDenied: StateFlow<Boolean> = permissionDeniedState.asStateFlow()

    fun onPermissionResult(granted: Boolean) {
        permissionDeniedState.value = !granted
        if (granted) conversation.start()
    }

    fun interrupt() = conversation.interrupt()

    fun toggleMute() {
        mutedState.value = !mutedState.value
        conversation.setMuted(mutedState.value)
    }

    fun toggleCaptions() {
        captionsState.value = !captionsState.value
    }

    /** 挂断并返回语音对话写入的会话 */
    fun hangUp(): String? {
        conversation.close()
        return state.value.sessionId
    }

    override fun onCleared() {
        conversation.release()
    }

    private fun languageTag(): String = when (settings.settings.value.locale) {
        AppLocale.ZhCN -> "zh-CN"
        AppLocale.EnUS -> "en-US"
        AppLocale.System -> Locale.getDefault().toLanguageTag()
    }

    companion object {
        const val KEY_SESSION = "sessionId"

        val Factory = uiViewModelFactory { deps, handle ->
            VoiceViewModel(
                deps.chatRepository,
                deps.settingsRepository,
                deps.speechInput,
                deps.createSpeechOutput(),
                handle.get<String>(KEY_SESSION),
            )
        }
    }
}
