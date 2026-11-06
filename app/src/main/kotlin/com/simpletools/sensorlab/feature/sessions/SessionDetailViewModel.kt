package com.simpletools.sensorlab.feature.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.simpletools.sensorlab.AppContainer
import com.simpletools.sensorlab.core.database.ChannelAggregates
import com.simpletools.sensorlab.core.database.SampleEntity
import com.simpletools.sensorlab.core.database.SessionEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface DetailState {
    data object Loading : DetailState
    data object NotFound : DetailState
    data class Error(val message: String?) : DetailState
    data class Loaded(
        val session: SessionEntity,
        val aggregates: ChannelAggregates?,
        val chart: List<SampleEntity>,
    ) : DetailState
}

class SessionDetailViewModel(private val id: String, private val container: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow<DetailState>(DetailState.Loading)
    val state: StateFlow<DetailState> = _state.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            _state.value = DetailState.Loading
            try {
                val s = container.sessions.get(id)
                if (s == null) {
                    _state.value = DetailState.NotFound
                    return@launch
                }
                _state.value = DetailState.Loaded(
                    s,
                    container.sessions.channelAggregates(id).takeIf { s.sampleCount > 0 },
                    container.sessions.chartSamples(id, s.sampleCount),
                )
            } catch (e: Exception) {
                _state.value = DetailState.Error(e.message)
            }
        }
    }

    fun rename(name: String) { viewModelScope.launch { container.sessions.rename(id, name); load() } }
    fun saveNote(note: String) { viewModelScope.launch { container.sessions.setNote(id, note); load() } }

    companion object {
        fun factory(id: String, container: AppContainer) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = SessionDetailViewModel(id, container) as T
        }
    }
}
