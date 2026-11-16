package io.github.smalltailqwq.shallowdrop.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.smalltailqwq.shallowdrop.R
import io.github.smalltailqwq.shallowdrop.core.contract.SettingsRepository
import io.github.smalltailqwq.shallowdrop.data.api.ApiBalance
import io.github.smalltailqwq.shallowdrop.data.api.BalanceApiException
import io.github.smalltailqwq.shallowdrop.data.api.DeepSeekBalanceApi
import io.github.smalltailqwq.shallowdrop.ui.common.uiViewModelFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class BalanceState(val loading: Boolean = false, val balance: ApiBalance? = null, val error: Int? = null)

class BalanceViewModel(
    private val settings: SettingsRepository,
    private val query: suspend (String) -> ApiBalance,
) : ViewModel() {
    private val current = MutableStateFlow(BalanceState())
    val state = current.asStateFlow()
    private var job: Job? = null

    fun refresh() {
        job?.cancel()
        current.value = BalanceState(loading = true)
        job = viewModelScope.launch {
            try {
                val key = settings.apiKey()
                if (key.isNullOrBlank()) {
                    current.value = BalanceState(error = R.string.api_key_not_set)
                    return@launch
                }
                val balance = query(key)
                if (settings.apiKey() == key) current.value = BalanceState(balance = balance)
                else refresh()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                current.value = BalanceState(error = if (error is BalanceApiException && error.status == 401)
                    R.string.balance_key_invalid else R.string.balance_failed)
            }
        }
    }

    companion object {
        val Factory = uiViewModelFactory { deps, _ -> BalanceViewModel(deps.settingsRepository, DeepSeekBalanceApi()::query) }
    }
}
