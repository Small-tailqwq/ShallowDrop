package io.github.smalltailqwq.shallowdrop.data.voice

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import io.github.smalltailqwq.shallowdrop.domain.voice.SpeechOutput
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * 系统语音合成（TextToSpeech）。按片段排队朗读（QUEUE_ADD），用未完成的 utterance 集合判断是否读完；
 * [stop] 清空集合，旧 utterance 迟到的回调只会移除不存在的 id，不影响新的队列。
 */
class AndroidSpeechOutput(private val context: Context) : SpeechOutput {
    private var tts: TextToSpeech? = null
    private val pending = ConcurrentHashMap.newKeySet<String>()
    private val pendingCount = MutableStateFlow(0)
    private val counter = AtomicLong()

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = Unit

        override fun onDone(utteranceId: String?) = finish(utteranceId)

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) = finish(utteranceId)

        override fun onError(utteranceId: String?, errorCode: Int) = finish(utteranceId)

        override fun onStop(utteranceId: String?, interrupted: Boolean) = finish(utteranceId)
    }

    private fun finish(id: String?) {
        if (id != null && pending.remove(id)) pendingCount.value = pending.size
    }

    override suspend fun prepare(languageTag: String): Boolean = withContext(Dispatchers.Main) {
        val engine = tts ?: create()?.also { tts = it } ?: return@withContext false
        engine.setOnUtteranceProgressListener(listener)
        engine.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        val result = engine.setLanguage(Locale.forLanguageTag(languageTag))
        // 引擎不支持该语言时保留其默认语言，仍可朗读
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            engine.setLanguage(Locale.getDefault())
        }
        true
    }

    private suspend fun create(): TextToSpeech? = suspendCancellableCoroutine { cont ->
        val holder = arrayOfNulls<TextToSpeech>(1)
        holder[0] = TextToSpeech(context) { status ->
            val engine = holder[0]
            if (status == TextToSpeech.SUCCESS && engine != null) {
                if (cont.isActive) cont.resume(engine) else engine.shutdown()
            } else {
                engine?.shutdown()
                if (cont.isActive) cont.resume(null)
            }
        }
        cont.invokeOnCancellation { holder[0]?.shutdown() }
    }

    override fun enqueue(text: String) {
        val engine = tts ?: return
        val id = "u${counter.incrementAndGet()}"
        pending.add(id)
        pendingCount.value = pending.size
        if (engine.speak(text, TextToSpeech.QUEUE_ADD, null, id) != TextToSpeech.SUCCESS) finish(id)
    }

    override suspend fun awaitIdle() {
        pendingCount.first { it == 0 }
    }

    override fun stop() {
        pending.clear()
        pendingCount.value = 0
        tts?.stop()
    }

    override fun release() {
        stop()
        tts?.shutdown()
        tts = null
    }
}
