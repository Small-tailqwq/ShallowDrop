package io.github.smalltailqwq.shallowdrop.ui.chat

import android.net.Uri
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.smalltailqwq.shallowdrop.R
import io.github.smalltailqwq.shallowdrop.core.contract.ChatRepository
import io.github.smalltailqwq.shallowdrop.core.contract.ConversationState
import io.github.smalltailqwq.shallowdrop.core.contract.SendOptions
import io.github.smalltailqwq.shallowdrop.core.contract.SettingsRepository
import io.github.smalltailqwq.shallowdrop.core.model.AppSettings
import io.github.smalltailqwq.shallowdrop.core.model.ApiException
import io.github.smalltailqwq.shallowdrop.core.model.ChatModel
import io.github.smalltailqwq.shallowdrop.core.model.FileAttachment
import io.github.smalltailqwq.shallowdrop.ui.UiDependencies
import io.github.smalltailqwq.shallowdrop.ui.chat.reply.UiText
import io.github.smalltailqwq.shallowdrop.ui.chat.reply.errorCodeMessage
import io.github.smalltailqwq.shallowdrop.ui.common.uiViewModelFactory
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 输入框中的附件；attachment 为 null 时正在导入，failed 为导入失败。 */
data class DraftAttachment(
    val key: String,
    val uri: Uri,
    val isImage: Boolean,
    val attachment: FileAttachment? = null,
    val failed: Boolean = false,
) {
    val ready: Boolean get() = attachment != null
}

data class Draft(val text: String = "", val attachments: List<DraftAttachment> = emptyList())

/** 对话页状态；sessionId 为 null 表示新对话（欢迎页）。 */
data class ChatUiState(
    val sessionId: String? = null,
    val conversation: ConversationState? = null,
    val draft: Draft = Draft(),
    val settings: AppSettings = AppSettings(),
) {
    val generating: Boolean get() = conversation?.generating == true
    val isEmpty: Boolean get() = conversation?.turns.isNullOrEmpty()
    val canSend: Boolean
        get() = !generating &&
            (draft.text.isNotBlank() || draft.attachments.isNotEmpty()) &&
            draft.attachments.all { it.ready }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModel(
    private val repository: ChatRepository,
    private val settingsRepository: SettingsRepository,
    private val handle: SavedStateHandle,
) : ViewModel() {
    private val sessionId: StateFlow<String?> = handle.getStateFlow(KEY_SESSION, null)

    /** 每个会话（含新对话 null）各自保留草稿 */
    private val drafts = MutableStateFlow<Map<String?, Draft>>(emptyMap())

    private val conversation: Flow<ConversationState?> = sessionId.flatMapLatest { id ->
        if (id == null) flowOf(null) else repository.observeConversation(id)
    }

    val state: StateFlow<ChatUiState> = combine(
        sessionId,
        conversation,
        drafts,
        settingsRepository.settings,
    ) { id, conv, draftMap, settings ->
        ChatUiState(id, conv?.takeIf { it.session.id == id }, draftMap[id] ?: Draft(), settings)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatUiState())

    private val eventChannel = Channel<UiText>(Channel.BUFFERED)

    /** 一次性提示（Snackbar） */
    val events: Flow<UiText> = eventChannel.receiveAsFlow()

    init {
        // 当前会话被删除（侧栏或设置页全部删除）后回到新对话
        viewModelScope.launch {
            // 只有上一次列表结果里存在、这一次消失的会话才算被删除：
            // 刚新建的会话可能晚于一次旧的列表结果到达，此时它从未在列表中出现过，不能当作已删除。
            // 首次结果例外（previous 为 null）：进程重建后恢复的会话 id 若已不存在，直接回到新对话。
            var previous: Set<String>? = null
            repository.sessions.map { list -> list.map { it.id }.toSet() }.collect { ids ->
                val id = sessionId.value
                val before = previous
                if (id != null && id !in ids && (before == null || id in before)) openSession(null)
                previous = ids
            }
        }
    }

    fun openSession(id: String?) {
        handle[KEY_SESSION] = id
    }

    private fun options(): SendOptions {
        val settings = settingsRepository.settings.value
        return SendOptions(thinking = settings.thinking, search = settings.search, model = settings.model)
    }

    private fun updateDraft(id: String? = sessionId.value, transform: (Draft) -> Draft) {
        drafts.update { it + (id to transform(it[id] ?: Draft())) }
    }

    fun onTextChange(text: String) = updateDraft { it.copy(text = text) }

    fun toggleThinking() = viewModelScope.launch {
        settingsRepository.setThinking(!settingsRepository.settings.value.thinking)
    }

    /** 顶栏切换模型：写入设置，对之后的提问与重新生成生效 */
    fun setModel(model: ChatModel) = viewModelScope.launch { settingsRepository.setModel(model) }

    fun toggleSearch() = viewModelScope.launch {
        settingsRepository.setSearch(!settingsRepository.settings.value.search)
    }

    fun addAttachments(uris: List<Uri>, isImage: Boolean) {
        val target = sessionId.value
        for (uri in uris) {
            val item = DraftAttachment(UUID.randomUUID().toString(), uri, isImage)
            updateDraft(target) { it.copy(attachments = it.attachments + item) }
            importAttachment(target, item)
        }
    }

    private fun importAttachment(target: String?, item: DraftAttachment) {
        viewModelScope.launch {
            val result = runCatchingNonCancel { repository.addAttachment(item.uri) }
            updateDraft(target) { draft ->
                draft.copy(
                    attachments = draft.attachments.map {
                        if (it.key != item.key) it else it.copy(attachment = result.getOrNull(), failed = result.isFailure)
                    },
                )
            }
            result.exceptionOrNull()?.let { eventChannel.trySend(errorText(it, R.string.upload_failed)) }
        }
    }

    fun retryAttachment(key: String) {
        val target = sessionId.value
        val item = drafts.value[target]?.attachments?.firstOrNull { it.key == key } ?: return
        val reset = item.copy(failed = false, attachment = null)
        updateDraft(target) { d -> d.copy(attachments = d.attachments.map { if (it.key == key) reset else it }) }
        importAttachment(target, reset)
    }

    fun removeAttachment(key: String) = updateDraft { d -> d.copy(attachments = d.attachments.filterNot { it.key == key }) }

    fun send() {
        val current = state.value
        if (!current.canSend) return
        val target = current.sessionId
        val draft = current.draft
        updateDraft(target) { Draft() }
        viewModelScope.launch {
            runCatchingNonCancel {
                repository.send(target, draft.text.trim(), draft.attachments.mapNotNull { it.attachment }, options())
            }.onSuccess { newId ->
                if (target == null) openSession(newId)
            }.onFailure {
                // 发送失败时恢复草稿
                updateDraft(target) { draft }
                eventChannel.trySend(errorText(it, R.string.unknown_error))
            }
        }
    }

    fun stop() {
        sessionId.value?.let(repository::stop)
    }

    private fun launchAction(block: suspend (String) -> Unit) {
        val id = sessionId.value ?: return
        viewModelScope.launch {
            runCatchingNonCancel { block(id) }.onFailure { eventChannel.trySend(errorText(it, R.string.unknown_error)) }
        }
    }

    fun regenerate(messageId: String) = launchAction { repository.regenerate(it, messageId, options()) }

    fun editAndResend(messageId: String, text: String) = launchAction {
        repository.editAndResend(it, messageId, text.trim(), options())
    }

    fun continueReply(messageId: String) = launchAction { repository.continueReply(it, messageId) }

    fun switchBranch(messageId: String, index: Int) = launchAction { repository.switchBranch(it, messageId, index) }

    private fun errorText(error: Throwable, fallback: Int): UiText {
        Log.w(TAG, "操作失败", error)
        if (error is ApiException) {
            errorCodeMessage(error.code)?.let { return UiText.Res(it) }
        }
        return error.message?.takeIf { it.isNotBlank() }?.let { UiText.Raw(it) } ?: UiText.Res(fallback)
    }

    private inline fun <T> runCatchingNonCancel(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    companion object {
        private const val TAG = "ChatViewModel"
        private const val KEY_SESSION = "sessionId"

        val Factory = uiViewModelFactory { deps, handle ->
            ChatViewModel(deps.chatRepository, deps.settingsRepository, handle)
        }
    }
}
