package io.github.smalltailqwq.shallowdrop.domain.voice

import kotlinx.coroutines.flow.Flow

/*
 * 语音对话的识别（ASR）与合成（TTS）接口。默认实现见 data/voice（系统 SpeechRecognizer / TextToSpeech），
 * 以后接入离线模型或云端引擎只需替换实现。
 */

sealed interface AsrEvent {
    /** 识别器已就绪，可以开始说话 */
    data object Ready : AsrEvent

    /** 输入音量，0..1，用于动画 */
    data class Level(val value: Float) : AsrEvent

    /** 边说边出的中间结果（整句累计） */
    data class Partial(val text: String) : AsrEvent

    /** 一句话结束后的最终结果；之后 Flow 结束 */
    data class Final(val text: String) : AsrEvent
}

enum class AsrError {
    /** 没听到说话或没识别出内容；继续聆听即可 */
    NoSpeech,

    /** 识别器忙，稍后重试 */
    Busy,

    /** 缺少录音权限 */
    Permission,

    /** 识别服务需要联网但网络不可用 */
    Network,

    /** 设备没有可用的识别服务 */
    Unavailable,
    Other,
}

class AsrException(val error: AsrError, message: String? = null) : Exception(message ?: error.name)

interface SpeechInput {
    /** 设备上是否有可用的识别服务 */
    fun isAvailable(): Boolean

    /**
     * 识别一句话：收到 [AsrEvent.Final] 后结束。取消收集即停止识别。
     * 失败抛 [AsrException]。
     * @param languageTag BCP 47，如 zh-CN
     */
    fun listen(languageTag: String): Flow<AsrEvent>
}

interface SpeechOutput {
    /** 初始化引擎并返回是否可用；可重复调用 */
    suspend fun prepare(languageTag: String): Boolean

    /** 追加一段朗读，不打断正在播放的内容 */
    fun enqueue(text: String)

    /** 挂起直到队列播放完毕（或被 [stop]） */
    suspend fun awaitIdle()

    /** 立即停止并清空队列 */
    fun stop()

    fun release()
}
