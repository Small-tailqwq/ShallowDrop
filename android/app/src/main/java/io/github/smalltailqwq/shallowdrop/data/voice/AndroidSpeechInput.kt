package io.github.smalltailqwq.shallowdrop.data.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import io.github.smalltailqwq.shallowdrop.domain.voice.AsrError
import io.github.smalltailqwq.shallowdrop.domain.voice.AsrEvent
import io.github.smalltailqwq.shallowdrop.domain.voice.AsrException
import io.github.smalltailqwq.shallowdrop.domain.voice.SpeechInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn

/**
 * 系统语音识别（SpeechRecognizer）。识别服务由设备提供，例如小米的 com.xiaomi.mibrain.speech、
 * Google 的语音服务；部分模拟器没有任何识别服务，此时 [isAvailable] 为 false。
 * 开启中间结果，边说边显示；一句话结束（服务端点检测）后给出最终结果。SpeechRecognizer 只能在主线程使用。
 */
class AndroidSpeechInput(private val context: Context) : SpeechInput {

    override fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    override fun listen(languageTag: String): Flow<AsrEvent> = callbackFlow {
        val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                trySend(AsrEvent.Ready)
            }

            override fun onRmsChanged(rmsdB: Float) {
                // 常见范围约 -2..10 dB，换算到 0..1
                trySend(AsrEvent.Level(((rmsdB + 2f) / 12f).coerceIn(0f, 1f)))
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults.firstResult()
                if (!text.isNullOrBlank()) trySend(AsrEvent.Partial(text))
            }

            override fun onResults(results: Bundle?) {
                val text = results.firstResult()
                if (text.isNullOrBlank()) {
                    close(AsrException(AsrError.NoSpeech))
                } else {
                    trySend(AsrEvent.Final(text))
                    close()
                }
            }

            override fun onError(error: Int) {
                close(AsrException(mapError(error), "SpeechRecognizer error $error"))
            }

            override fun onBeginningOfSpeech() = Unit

            override fun onBufferReceived(buffer: ByteArray?) = Unit

            override fun onEndOfSpeech() = Unit

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }
        recognizer.startListening(intent)
        awaitClose {
            recognizer.cancel()
            recognizer.destroy()
        }
    }.flowOn(Dispatchers.Main)

    private fun Bundle?.firstResult(): String? =
        this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

    private fun mapError(error: Int): AsrError = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> AsrError.NoSpeech
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> AsrError.Busy
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> AsrError.Permission
        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
        SpeechRecognizer.ERROR_SERVER,
        SpeechRecognizer.ERROR_SERVER_DISCONNECTED,
        -> AsrError.Network
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> AsrError.Unavailable
        else -> AsrError.Other
    }
}
