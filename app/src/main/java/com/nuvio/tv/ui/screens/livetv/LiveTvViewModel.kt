package com.nuvio.tv.ui.screens.livetv

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.watchfirst.WatchfirstAccess
import com.nuvio.tv.core.watchfirst.WatchfirstAccessState
import com.nuvio.tv.core.watchfirst.WatchfirstLiveTv
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.repository.AddonRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class LiveTvViewModel @Inject constructor(
    addonRepository: AddonRepository,
    private val access: WatchfirstAccess,
    private val repository: LiveTvRepository,
) : ViewModel() {
    val accessState: StateFlow<WatchfirstAccessState> = access.state
    val state: StateFlow<LiveTvUiState> = repository.state

    /** The live addons installed on this profile; null until the installed list is first read. */
    private val sources: StateFlow<List<Addon>?> =
        combine(addonRepository.getInstalledAddons(), WatchfirstLiveTv.liveKeys) { installed, live ->
            liveAddons(installed, live)
        }
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _favorites = MutableStateFlow(access.loadFavoriteChannels())
    val favorites: StateFlow<Set<String>> = _favorites.asStateFlow()

    init {
        viewModelScope.launch {
            sources.collect { list -> if (list != null) repository.ensureLoaded(list) }
        }
    }

    fun refresh() {
        sources.value?.let(repository::refresh)
    }

    fun toggleFavorite(channel: LiveChannel) {
        val current = _favorites.value
        val next = if (channel.favoriteKey in current) current - channel.favoriteKey else current + channel.favoriteKey
        _favorites.value = next
        access.saveFavoriteChannels(next)
    }
}
