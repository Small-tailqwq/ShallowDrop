package io.github.smalltailqwq.shallowdrop.domain.voice

import kotlinx.coroutines.flow.Flow

/*
 * 语音对话的识别（ASR）与合成（TTS）接口。默认实现见 data/voice：DeepSeek 语音接口（/v1/tools/asr、/v1/tools/tts），
 * 与 Web 版一致，有 API Key 即可使用，不依赖设备自带的语音服务。
 */

sealed interface AsrEvent {
    /** 识别器已就绪，可以开始说话 */
    data object Ready : AsrEvent

    /** 检测到开口（插话监听据此立即打断朗读） */
    data object SpeechStarted : AsrEvent

    /** 输入音量，0..1，用于动画 */
    data class Level(val value: Float) : AsrEvent

    /** 边说边出的中间结果（整句累计） */
    data class Partial(val text: String) : AsrEvent

    /** 一句话已说完，正在识别（整句上传的 ASR 在此期间没有中间结果） */
    data object Recognizing : AsrEvent

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

    /** 设备没有可用的识别服务或无法打开麦克风 */
    Unavailable,

    /** 语音服务拒绝请求（API Key 无效、余额不足等），重试也不会成功 */
    Rejected,
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
     * @param bargeIn 插话监听（回复播放期间）：开口判定更严格以避开回声，且不会因长时间无人说话而超时
     */
    fun listen(languageTag: String, bargeIn: Boolean = false): Flow<AsrEvent>

    /** 释放麦克风并丢弃尚未处理的录音（静音、挂断时调用）；下次聆听重新打开 */
    fun release() = Unit
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
