package com.palmerintech.firetube.local

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.palmerintech.firetube.AppContainer
import com.palmerintech.firetube.extractor.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

sealed interface LocalAudioState {
    data object Permission : LocalAudioState
    data object Loading : LocalAudioState
    data class Ready(val tracks: List<Track>) : LocalAudioState
    data object Failed : LocalAudioState
}

@OptIn(FlowPreview::class)
@androidx.media3.common.util.UnstableApi
class LocalAudioViewModel(private val container: AppContainer) : ViewModel() {
    private val mutableState = MutableStateFlow<LocalAudioState>(LocalAudioState.Loading)
    val state = mutableState.asStateFlow()
    private var scan: Job? = null

    init { viewModelScope.launch { container.localAudio.changes.debounce(300).collect { refresh() } } }

    fun refresh() {
        scan?.cancel()
        scan = viewModelScope.launch {
            if (!container.localAudio.hasPermission()) {
                mutableState.value = LocalAudioState.Permission
                return@launch
            }
            mutableState.value = LocalAudioState.Loading
            try {
                val tracks = container.localAudio.scan()
                // Keep missing tracks in the library so playlists/history never lose their references.
                container.library.saveTracks(tracks)
                mutableState.value = LocalAudioState.Ready(tracks)
            } catch (e: CancellationException) { throw e
            } catch (_: SecurityException) { mutableState.value = LocalAudioState.Permission
            } catch (_: Exception) { mutableState.value = LocalAudioState.Failed }
        }
    }
}
