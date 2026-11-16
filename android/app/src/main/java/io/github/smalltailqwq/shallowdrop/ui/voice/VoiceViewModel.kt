package io.github.smalltailqwq.shallowdrop.ui.voice

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.smalltailqwq.shallowdrop.core.contract.ChatRepository
import io.github.smalltailqwq.shallowdrop.core.contract.SendOptions
import io.github.smalltailqwq.shallowdrop.core.contract.SettingsRepository
import io.github.smalltailqwq.shallowdrop.core.model.AppLocale
import io.github.smalltailqwq.shallowdrop.core.model.AppSettings
import io.github.smalltailqwq.shallowdrop.core.model.TtsVoice
import io.github.smalltailqwq.shallowdrop.domain.voice.SpeechInput
import io.github.smalltailqwq.shallowdrop.domain.voice.SpeechOutput
import io.github.smalltailqwq.shallowdrop.domain.voice.VoiceConversation
import io.github.smalltailqwq.shallowdrop.domain.voice.VoicePhase
import io.github.smalltailqwq.shallowdrop.domain.voice.VoiceState
import io.github.smalltailqwq.shallowdrop.ui.common.uiViewModelFactory
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class VoiceViewModel(
    chat: ChatRepository,
    private val settings: SettingsRepository,
    input: SpeechInput,
    private val output: SpeechOutput,
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

    /**
     * 界面状态。输入音量约每 60ms 变一次，不放进来（恒为 0）：光球逐帧读 [orbLevel]，
     * 否则聆听期间整页都会跟着音量不停重组。
     */
    val state: StateFlow<VoiceState> = conversation.state
        .map { it.copy(level = 0f) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, conversation.state.value.copy(level = 0f))

    /** 已挂断：页面退出过渡期间还能点到光球与重试，不能再把对话重新拉起来 */
    private var closed = false

    private val mutedState = MutableStateFlow(false)
    val muted: StateFlow<Boolean> = mutedState.asStateFlow()

    /** 音色（决定光球外观）与智能搜索开关，可在语音页内调整 */
    val settingsState: StateFlow<AppSettings> = settings.settings

    /** 字幕默认收起，让光球居中 */
    private val captionsState = MutableStateFlow(false)
    val captions: StateFlow<Boolean> = captionsState.asStateFlow()

    private val permissionDeniedState = MutableStateFlow(false)
    val permissionDenied: StateFlow<Boolean> = permissionDeniedState.asStateFlow()

    fun onPermissionResult(granted: Boolean) {
        permissionDeniedState.value = !granted
        if (granted && !closed) conversation.start()
    }

    fun interrupt() {
        if (!closed) conversation.interrupt()
    }

    fun toggleMute() {
        mutedState.value = !mutedState.value
        conversation.setMuted(mutedState.value)
    }

    fun toggleCaptions() {
        captionsState.value = !captionsState.value
    }

    /** 光球的音量：说话时为朗读的响度，其余为麦克风输入 */
    fun orbLevel(): Float = conversation.state.value.let { if (it.phase == VoicePhase.Speaking) output.level else it.level }

    /** 换音色：下一句朗读起生效 */
    fun setVoice(voice: TtsVoice) {
        viewModelScope.launch { settings.setTtsVoice(voice) }
    }

    /** 智能搜索：下一轮提问起生效 */
    fun setSearch(enabled: Boolean) {
        viewModelScope.launch { settings.setSearch(enabled) }
    }

    /** 挂断并返回语音对话写入的会话 */
    fun hangUp(): String? {
        closed = true
        conversation.close()
        return conversation.state.value.sessionId
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
