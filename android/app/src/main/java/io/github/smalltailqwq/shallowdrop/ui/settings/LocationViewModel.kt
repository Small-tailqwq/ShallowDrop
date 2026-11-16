package io.github.smalltailqwq.shallowdrop.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.smalltailqwq.shallowdrop.core.model.LocationPreference
import io.github.smalltailqwq.shallowdrop.ui.common.uiViewModelFactory
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class LocationPreview(val loading: Boolean = false, val place: String? = null, val error: String? = null)

class LocationViewModel(private val locate: suspend (LocationPreference, Locale) -> String) : ViewModel() {
    private val current = MutableStateFlow(LocationPreview())
    val state = current.asStateFlow()
    private var job: Job? = null

    fun clear() { job?.cancel(); current.value = LocationPreview() }

    fun preview(preference: LocationPreference, locale: Locale) {
        clear()
        current.value = LocationPreview(loading = true)
        job = viewModelScope.launch {
            try {
                current.value = LocationPreview(place = locate(preference, locale))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                current.value = LocationPreview(error = e.message)
            }
        }
    }

    companion object {
        val Factory = uiViewModelFactory { deps, _ -> LocationViewModel(deps.locate) }
    }
}
