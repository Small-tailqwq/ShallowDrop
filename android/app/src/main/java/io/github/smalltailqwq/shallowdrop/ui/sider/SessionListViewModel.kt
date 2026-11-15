package io.github.smalltailqwq.shallowdrop.ui.sider

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.smalltailqwq.shallowdrop.R
import io.github.smalltailqwq.shallowdrop.core.contract.ChatRepository
import io.github.smalltailqwq.shallowdrop.ui.chat.reply.UiText
import io.github.smalltailqwq.shallowdrop.ui.common.uiViewModelFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 侧栏会话列表：分组、重命名、删除。 */
class SessionListViewModel(private val repository: ChatRepository) : ViewModel() {

    /** 每分钟刷新一次 now，跨过零点时分组随之变化 */
    private val clock = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(60_000)
        }
    }

    val groups: StateFlow<List<SessionGroup>?> = combine(repository.sessions, clock) { sessions, now ->
        groupSessions(sessions.sortedByDescending { it.updatedAt }, now)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val eventChannel = Channel<UiText>(Channel.BUFFERED)
    val events: Flow<UiText> = eventChannel.receiveAsFlow()

    fun rename(sessionId: String, title: String) {
        val value = title.trim()
        if (value.isEmpty()) return
        viewModelScope.launch {
            try {
                repository.rename(sessionId, value)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                eventChannel.trySend(UiText.Res(R.string.unknown_error))
            }
        }
    }

    fun delete(sessionId: String) {
        viewModelScope.launch {
            try {
                repository.delete(sessionId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                eventChannel.trySend(UiText.Res(R.string.session_delete_failed))
            }
        }
    }

    companion object {
        val Factory = uiViewModelFactory { deps, _ -> SessionListViewModel(deps.chatRepository) }
    }
}
