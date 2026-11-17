package io.github.smalltailqwq.shallowdrop.ui

import android.app.Application
import android.net.Uri
import java.io.File
import io.github.smalltailqwq.shallowdrop.App
import io.github.smalltailqwq.shallowdrop.core.contract.ChatRepository
import io.github.smalltailqwq.shallowdrop.core.contract.ExportLinkDownloader
import io.github.smalltailqwq.shallowdrop.core.contract.HistoryBackup
import io.github.smalltailqwq.shallowdrop.core.contract.MemoryRepository
import io.github.smalltailqwq.shallowdrop.core.contract.SettingsRepository
import io.github.smalltailqwq.shallowdrop.core.model.ApiException
import io.github.smalltailqwq.shallowdrop.core.model.ReplyErrorKind
import io.github.smalltailqwq.shallowdrop.data.settings.DataStoreSettingsRepository
import io.github.smalltailqwq.shallowdrop.BuildConfig
import io.github.smalltailqwq.shallowdrop.data.api.DeepSeekSpeechApi
import io.github.smalltailqwq.shallowdrop.data.voice.ApiSpeechInput
import io.github.smalltailqwq.shallowdrop.data.voice.ApiSpeechOutput
import io.github.smalltailqwq.shallowdrop.data.voice.AudioTrackPlayer
import io.github.smalltailqwq.shallowdrop.data.voice.MicrophoneSource
import io.github.smalltailqwq.shallowdrop.data.voice.PcmPlayer
import io.github.smalltailqwq.shallowdrop.data.voice.PcmSource
import io.github.smalltailqwq.shallowdrop.data.voice.TeePcmPlayer
import io.github.smalltailqwq.shallowdrop.data.voice.WavFileSource
import io.github.smalltailqwq.shallowdrop.core.contract.SettingsRepository as Settings
import io.github.smalltailqwq.shallowdrop.domain.voice.SpeechInput
import io.github.smalltailqwq.shallowdrop.domain.voice.SpeechOutput
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
    /** 下载官方 App 导出的历史对话链接 */
    val exportLinkDownloader: ExportLinkDownloader,
    /** 校验 API Key（GET /models）；失败抛 ApiException */
    val verifyApiKey: suspend (String) -> Unit,
    /** 设置首次读取完成；之前不决定起始页面 */
    val settingsLoaded: StateFlow<Boolean>,
    val locate: suspend (io.github.smalltailqwq.shallowdrop.core.model.LocationPreference, java.util.Locale) -> String,
    /** 语音对话的识别（无状态，可共用） */
    val speechInput: SpeechInput,
    /** 语音对话的合成；每次进入语音页新建，离开时释放 */
    val createSpeechOutput: () -> SpeechOutput,
    val memoryRepository: MemoryRepository,
    /** 附件本地原件，用于消息里的图片缩略图 */
    val attachmentFile: (String) -> File? = { null },
) {
    /** 从其他应用「打开方式 / 分享」进来的历史对话 ZIP，设置页接手后置空 */
    val incomingImport = MutableStateFlow<Uri?>(null)

    companion object {
        /** 集成切换点：true 时对话与历史使用 Fake */
        const val USE_FAKE = false

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
                    exportLinkDownloader = (application as App).container.exportLinkDownloader,
                    verifyApiKey = ::fakeVerify,
                    settingsLoaded = settings.loaded,
                    locate = (application as App).container.locationProvider::locate,
                    speechInput = speechInput(application, (application as App).container.speechApi, settings),
                    createSpeechOutput = { speechOutput(application, (application as App).container.speechApi, settings) },
                    memoryRepository = (application as App).container.memoryRepository,
                )
            }
            val container = (application as App).container
            val settings = container.settingsRepository
            return UiDependencies(
                settingsRepository = settings,
                chatRepository = container.chatRepository,
                historyBackup = container.historyBackup,
                exportLinkDownloader = container.exportLinkDownloader,
                verifyApiKey = { container.chatApi.verifyApiKey(it) },
                settingsLoaded = settings.loaded,
                locate = container.locationProvider::locate,
                speechInput = speechInput(application, container.speechApi, settings),
                createSpeechOutput = { speechOutput(application, container.speechApi, settings) },
                memoryRepository = container.memoryRepository,
                attachmentFile = container.attachmentStore::file,
            )
        }

        /**
         * 语音识别：麦克风录音。debug 构建下若存在 外部文件目录/voice-test/，改用 WavFileSource：
         * 推送 input.wav（16kHz 单声道 16-bit）即当作一次说话，供没有麦克风的模拟器测试完整链路与插话。
         * 删除该目录即恢复麦克风。
         */
        private fun speechInput(application: Application, api: DeepSeekSpeechApi, settings: Settings) =
            ApiSpeechInput(api, settings::apiKey, openSource = {
                val testDir = if (BuildConfig.DEBUG) application.getExternalFilesDir(null)?.resolve("voice-test") else null
                if (testDir != null && testDir.isDirectory) WavFileSource(testDir.resolve("input.wav")) as PcmSource else MicrophoneSource.open()
            })

        /** debug 构建下若存在 voice-test 目录，同时把播放的 PCM 记录到 缓存目录/tts.pcm（run-as 可读），便于检查音质 */
        private fun speechOutput(application: Application, api: DeepSeekSpeechApi, settings: Settings) =
            ApiSpeechOutput(api, settings::apiKey, voice = { settings.settings.value.ttsVoice }, createPlayer = {
                val dir = if (BuildConfig.DEBUG) application.getExternalFilesDir(null)?.resolve("voice-test") else null
                val player = AudioTrackPlayer()
                if (dir != null && dir.isDirectory) TeePcmPlayer(player, application.cacheDir.resolve("tts.pcm")) as PcmPlayer else player
            })

        /** 以 sk- 开头视为有效，其余模拟 401 */
        private suspend fun fakeVerify(apiKey: String) {
            delay(800)
            if (!apiKey.startsWith("sk-")) {
                throw ApiException(ReplyErrorKind.Http, "Authentication Fails", status = 401)
            }
        }
    }
}
