package com.simpletools.sensorlab.feature.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.simpletools.sensorlab.AppContainer
import com.simpletools.sensorlab.core.charts.ZoomWindow
import com.simpletools.sensorlab.core.database.ChannelAggregates
import com.simpletools.sensorlab.core.database.SampleEntity
import com.simpletools.sensorlab.core.database.SessionEntity
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface DetailState {
    data object Loading : DetailState
    data object NotFound : DetailState
    data class Error(val message: String?) : DetailState
    data class Loaded(
        val session: SessionEntity,
        val aggregates: ChannelAggregates?,
        /** Samples of the visible window only, decimated for drawing. */
        val chart: List<SampleEntity>,
        val window: ZoomWindow,
    ) : DetailState
}

class SessionDetailViewModel(private val id: String, private val container: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow<DetailState>(DetailState.Loading)
    val state: StateFlow<DetailState> = _state.asStateFlow()
    private var reloadJob: Job? = null

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
                val window = ZoomWindow.full(0.0, s.durationMs / 1000.0)
                _state.value = DetailState.Loaded(
                    session = s,
                    aggregates = container.sessions.channelAggregates(id).takeIf { s.sampleCount > 0 },
                    chart = container.sessions.samplesInRange(id, window.start, window.end + 1.0),
                    window = window,
                )
            } catch (e: Exception) {
                _state.value = DetailState.Error(e.message)
            }
        }
    }

    fun zoomIn(anchor: Double = 0.5) = zoomBy(2.0, anchor)
    fun zoomOut() = zoomBy(0.5, 0.5)
    fun resetZoom() = moveTo { it.reset() }

    /** Pinch and drag from the chart: [zoom] is the gesture's scale factor, [anchor] and [panFraction] in 0..1 of the chart width. */
    fun gesture(zoom: Double, anchor: Double, panFraction: Double) {
        moveTo { it.zoomed(zoom, anchor).panned(panFraction) }
    }

    private fun zoomBy(factor: Double, anchor: Double) = moveTo { it.zoomed(factor, anchor) }

    private fun moveTo(change: (ZoomWindow) -> ZoomWindow) {
        val loaded = _state.value as? DetailState.Loaded ?: return
        val next = change(loaded.window)
        if (next == loaded.window) return
        _state.update { (it as? DetailState.Loaded)?.copy(window = next) ?: it }
        reloadJob?.cancel()
        reloadJob = viewModelScope.launch {
            val chart = container.sessions.samplesInRange(id, next.start, next.end + 1e-6)
            _state.update { (it as? DetailState.Loaded)?.copy(chart = chart) ?: it }
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
