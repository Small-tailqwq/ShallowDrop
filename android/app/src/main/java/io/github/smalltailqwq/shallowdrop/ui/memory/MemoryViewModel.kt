package io.github.smalltailqwq.shallowdrop.ui.memory

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.smalltailqwq.shallowdrop.R
import io.github.smalltailqwq.shallowdrop.core.contract.ChatRepository
import io.github.smalltailqwq.shallowdrop.core.contract.MemoryRepository
import io.github.smalltailqwq.shallowdrop.core.contract.SettingsRepository
import io.github.smalltailqwq.shallowdrop.core.model.MemoryExchange
import io.github.smalltailqwq.shallowdrop.core.model.MemoryFullException
import io.github.smalltailqwq.shallowdrop.ui.UiDependencies
import io.github.smalltailqwq.shallowdrop.ui.chat.reply.UiText
import io.github.smalltailqwq.shallowdrop.ui.common.uiViewModelFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/** 记忆页的提问面板：已完成的问答，以及正在等待回答的提问。 */
data class AskState(val exchanges: List<MemoryExchange> = emptyList(), val pending: String? = null)

class MemoryViewModel(
    private val repository: MemoryRepository,
    private val settingsRepository: SettingsRepository,
    private val chatRepository: ChatRepository,
) : ViewModel() {
    val settings = settingsRepository.settings
    val memory = repository.memory
    val updating = repository.updating

    private val askState = MutableStateFlow(AskState())
    val ask: StateFlow<AskState> = askState.asStateFlow()

    private val eventChannel = Channel<UiText>(Channel.BUFFERED)
    val events: Flow<UiText> = eventChannel.receiveAsFlow()

    private fun save(block: suspend () -> Unit) = viewModelScope.launch {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: MemoryFullException) {
            eventChannel.trySend(UiText.Res(R.string.memory_full))
        } catch (e: Exception) {
            Log.w(TAG, "保存记忆失败", e)
            eventChannel.trySend(UiText.Res(R.string.memory_save_failed))
        }
    }

    fun setEnabled(enabled: Boolean) = save { settingsRepository.setMemoryEnabled(enabled) }

    fun add(text: String) = save { repository.add(text) }

    fun edit(entryId: String, text: String) = save { repository.edit(entryId, text) }

    fun remove(entryId: String) = save { repository.remove(entryId) }

    fun clear() = save {
        repository.clear()
        askState.value = AskState()
    }

    fun ask(question: String) {
        val content = question.trim()
        val current = askState.value
        if (content.isEmpty() || current.pending != null) return
        askState.value = current.copy(pending = content)
        viewModelScope.launch {
            try {
                val exchange = repository.ask(content, current.exchanges)
                askState.value = AskState(current.exchanges + exchange)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "记忆提问失败", e)
                askState.value = current
                eventChannel.trySend(UiText.Res(R.string.memory_request_failed))
            }
        }
    }

    /** 原对话还在时交给 [open] 跳转，否则提示已删除。 */
    fun openConversation(sessionId: String, messageId: String?, open: (String, String?) -> Unit) = viewModelScope.launch {
        if (chatRepository.sessions.first().any { it.id == sessionId }) {
            open(sessionId, messageId)
        } else {
            eventChannel.trySend(UiText.Res(R.string.memory_conversation_missing))
        }
    }

    companion object {
        private const val TAG = "MemoryViewModel"

        val Factory = uiViewModelFactory { deps: UiDependencies, _ ->
            MemoryViewModel(deps.memoryRepository, deps.settingsRepository, deps.chatRepository)
        }
    }
}
