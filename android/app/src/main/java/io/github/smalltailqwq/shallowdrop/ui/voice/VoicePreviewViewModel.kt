package io.github.smalltailqwq.shallowdrop.ui.voice

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.smalltailqwq.shallowdrop.core.contract.SettingsRepository
import io.github.smalltailqwq.shallowdrop.core.model.TtsVoice
import io.github.smalltailqwq.shallowdrop.domain.voice.SpeechOutput
import io.github.smalltailqwq.shallowdrop.ui.common.uiViewModelFactory
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** 设置页的音色选择与试听。选中即保存；试听用自己的播放器朗读一句示例。 */
class VoicePreviewViewModel(
    private val settings: SettingsRepository,
    private val output: SpeechOutput,
) : ViewModel() {
    val voice: StateFlow<TtsVoice> = settings.settings
        .map { it.ttsVoice }
        .stateIn(viewModelScope, SharingStarted.Eagerly, settings.settings.value.ttsVoice)

    private val speakingState = MutableStateFlow<TtsVoice?>(null)

    /** 正在试听的音色 */
    val speaking: StateFlow<TtsVoice?> = speakingState.asStateFlow()

    private var job: Job? = null

    /** 试听中的响度，驱动光球 */
    fun level(): Float = output.level

    fun select(voice: TtsVoice) {
        stop()
        viewModelScope.launch { settings.setTtsVoice(voice) }
    }

    /** 试听；正在试听同一个音色时再点为停止 */
    fun audition(voice: TtsVoice, sample: String) {
        val wasSpeaking = speakingState.value == voice
        stop()
        if (wasSpeaking) return
        job = viewModelScope.launch {
            // 播放器按当前设置取音色：先保存，并等设置生效再朗读
            settings.setTtsVoice(voice)
            withTimeoutOrNull(SETTLE_MS) { settings.settings.first { it.ttsVoice == voice } } ?: return@launch
            if (!output.prepare(Locale.getDefault().toLanguageTag())) return@launch
            speakingState.value = voice
            try {
                output.enqueue(sample)
                output.awaitIdle()
            } finally {
                if (speakingState.value == voice) speakingState.value = null
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        output.stop()
        speakingState.value = null
    }

    override fun onCleared() {
        output.release()
    }

    companion object {
        private const val SETTLE_MS = 2_000L

        val Factory = uiViewModelFactory { deps, _ -> VoicePreviewViewModel(deps.settingsRepository, deps.createSpeechOutput()) }
    }
}
