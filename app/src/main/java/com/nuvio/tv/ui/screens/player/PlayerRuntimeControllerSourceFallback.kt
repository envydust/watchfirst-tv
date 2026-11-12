package com.nuvio.tv.ui.screens.player

import android.util.Log
import android.widget.Toast
import com.nuvio.tv.domain.model.Stream
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// WATCHFIRST (member feedback): when a source does not play, move on to another source instead of
// asking the same server again and again; when none is left, keep the error on screen with the
// in-player source list one click away. Reuses the player's own source list (loadSourceStreams)
// and source switch (switchToSourceStream).

/** Automatic source changes allowed for one episode or movie before handing over to the member. */
internal const val MAX_AUTO_SOURCE_FALLBACKS = 4
private const val SOURCE_FALLBACK_START_DELAY_MS = 300L
private const val SOURCE_FALLBACK_POLL_INTERVAL_MS = 500L
private const val SOURCE_FALLBACK_POLL_COUNT = 40
internal const val SOURCE_FALLBACK_TRYING_MESSAGE = "Esta fonte não carregou. Tentando outra…"
internal const val SOURCE_FALLBACK_GAVE_UP_MESSAGE = "Nenhuma fonte carregou. Escolha outra na lista."

/** Keys that identify one source both in the source list and while it plays. */
internal fun sourceIdentityKeys(
    url: String?,
    infoHash: String?,
    fileIdx: Int?,
    addonName: String?,
    streamName: String?,
    description: String?
): Set<String> = buildSet {
    url?.trim()?.takeIf { it.isNotEmpty() }?.let { add("url:$it") }
    infoHash?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.let { add("hash:$it:${fileIdx ?: -1}") }
    if (!addonName.isNullOrBlank() && !streamName.isNullOrBlank()) {
        add("meta:${addonName.trim()}|${streamName.trim()}|${description?.trim().orEmpty()}")
    }
}

/** Same naming as the player's state: a stream without a name shows its addon's name. */
internal fun Stream.fallbackIdentityKeys(): Set<String> = sourceIdentityKeys(
    url = getStreamUrl(),
    infoHash = getEffectiveInfoHash(),
    fileIdx = getEffectiveFileIdx(),
    addonName = addonName,
    streamName = name ?: addonName,
    description = description
)

/** Sources the player can open by itself (not links that only open in a browser). */
internal fun Stream.isAutoFallbackCandidate(): Boolean =
    !isExternal() && (
        !getStreamUrl().isNullOrBlank() ||
            isTorrent() ||
            isDirectDebrid() ||
            youTubeIdToResolve() != null ||
            serverTarget != null
        )

/** The first source, in list order, that the player can open and that has not failed yet. */
internal fun nextAutoFallbackSource(streams: List<Stream>, hasFailed: (Stream) -> Boolean): Stream? =
    streams.firstOrNull { it.isAutoFallbackCandidate() && !hasFailed(it) }

private fun PlayerRuntimeController.sourceKeysOf(stream: Stream): Set<String> {
    val keys = stream.fallbackIdentityKeys().toMutableSet()
    val url = stream.getStreamUrl()?.takeIf { it.isNotBlank() } ?: return keys
    runCatching {
        PlayerMediaSourceFactory.normalizePlaybackRequest(url, stream.behaviorHints?.proxyHeaders?.request).url
    }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }?.let { keys += "url:$it" }
    return keys
}

private fun PlayerRuntimeController.isFailedSource(stream: Stream): Boolean =
    sourceKeysOf(stream).any { it in failedSourceKeys }

private fun PlayerRuntimeController.markCurrentSourceFailed() {
    val state = _uiState.value
    failedSourceKeys += sourceIdentityKeys(
        url = currentStreamUrl,
        infoHash = state.currentStreamInfoHash ?: currentInfoHash,
        fileIdx = state.currentStreamFileIdx ?: currentFileIdx,
        addonName = currentAddonName ?: state.currentStreamAddonName,
        streamName = state.currentStreamName,
        description = currentStreamDescription ?: state.currentStreamDescription
    )
}

/**
 * Called when the current source cannot be played, after the player's own retries. Starts the next
 * source that has not failed yet and returns true; when none is left (or after
 * [MAX_AUTO_SOURCE_FALLBACKS] changes for this title) shows the error with the source list button.
 * Returns false when there is no title to look sources up for: the caller shows its own error.
 */
internal fun PlayerRuntimeController.tryNextSourceAfterError(
    errorMessage: String,
    allowSwitchToMpv: Boolean = false
): Boolean {
    if (sourceFallbackJob?.isActive == true) return true
    // Never start another source while the app is in the background, nor for cloud files.
    if (isInBackground) return false
    if (contentType.equals("cloud", ignoreCase = true)) return false
    val request = currentSourceStreamsRequest() ?: return false
    val itemKey = buildSourceRequestKey(
        type = request.type,
        videoId = request.videoId,
        season = request.season,
        episode = request.episode
    )
    if (itemKey != autoSourceFallbackItemKey) {
        autoSourceFallbackItemKey = itemKey
        autoSourceFallbackCount = 0
        failedSourceKeys.clear()
    }
    markCurrentSourceFailed()
    cancelNextEpisodeAutoPlayOnFatalError()
    if (autoSourceFallbackCount >= MAX_AUTO_SOURCE_FALLBACKS) {
        showSourceFallbackGaveUp(errorMessage, allowSwitchToMpv)
        return true
    }
    // The full list is already known and every source in it failed: nothing left to try.
    val listComplete = sourceStreamsCacheRequestKey == itemKey && sourceStreamsFetchCompleted
    if (listComplete && nextAutoFallbackSource(_uiState.value.sourceAllStreams) { isFailedSource(it) } == null) {
        showSourceFallbackGaveUp(errorMessage, allowSwitchToMpv)
        return true
    }
    autoSourceFallbackCount += 1
    Log.w(
        PlayerRuntimeController.TAG,
        "SOURCE_FALLBACK ${autoSourceFallbackCount}/$MAX_AUTO_SOURCE_FALLBACKS after: ${errorMessage.take(200)}"
    )
    errorRetryJob?.cancel()
    errorRetryJob = null
    showRecoveryOverlay()
    setLoadingStatus(phase = "source_fallback", message = SOURCE_FALLBACK_TRYING_MESSAGE)
    Toast.makeText(context, SOURCE_FALLBACK_TRYING_MESSAGE, Toast.LENGTH_LONG).show()

    sourceFallbackJob = scope.launch {
        // Leave the failing player's callback first, and let the notice show.
        delay(SOURCE_FALLBACK_START_DELAY_MS)
        loadSourceStreams(forceRefresh = false)
        var next: Stream? = null
        var polls = 0
        while (true) {
            // The list belongs to this title only once the source search has switched to it.
            val listReady = sourceStreamsCacheRequestKey == itemKey
            if (listReady) {
                next = nextAutoFallbackSource(_uiState.value.sourceAllStreams) { isFailedSource(it) }
            }
            if (next != null || (listReady && sourceStreamsFetchCompleted) || polls >= SOURCE_FALLBACK_POLL_COUNT) break
            delay(SOURCE_FALLBACK_POLL_INTERVAL_MS)
            polls++
        }
        sourceFallbackJob = null
        val stream = next
        if (stream == null) {
            showSourceFallbackGaveUp(errorMessage, allowSwitchToMpv)
            return@launch
        }
        // Tried now, so it is never picked again for this title, whatever happens next.
        failedSourceKeys += sourceKeysOf(stream)
        Log.i(
            PlayerRuntimeController.TAG,
            "SOURCE_FALLBACK switching to addon=${stream.addonName} name=${stream.name?.take(80)}"
        )
        switchToSourceStream(stream)
    }
    return true
}

/** No source left to try: keep the error, with a button that opens the source list. */
private fun PlayerRuntimeController.showSourceFallbackGaveUp(errorMessage: String, allowSwitchToMpv: Boolean) {
    val message = if (errorMessage.isBlank()) {
        SOURCE_FALLBACK_GAVE_UP_MESSAGE
    } else {
        "$SOURCE_FALLBACK_GAVE_UP_MESSAGE\n\n$errorMessage"
    }
    sourceFallbackGaveUpMessage = message
    Log.w(PlayerRuntimeController.TAG, "SOURCE_FALLBACK gave up after $autoSourceFallbackCount source changes")
    _uiState.update {
        it.copy(
            error = message,
            showPickSourceErrorAction = true,
            showSwitchToMpvErrorAction = allowSwitchToMpv,
            isBuffering = false,
            showLoadingOverlay = false,
            showPauseOverlay = false,
            loadingIssueReportVisible = false,
            loadingIssueElapsedMs = 0L,
            playbackEnded = false,
            postPlayMode = null
        )
    }
}

/** "Escolher outra fonte" on the error screen: hide the error and open the source list. */
internal fun PlayerRuntimeController.openSourceListFromError() {
    _uiState.update { it.copy(error = null, showPickSourceErrorAction = false) }
    showSourcesPanel()
}

/** The source list was closed without a choice after every source failed: bring the error back. */
internal fun PlayerRuntimeController.restoreSourceFallbackErrorIfNeeded() {
    val message = sourceFallbackGaveUpMessage ?: return
    if (_uiState.value.error != null) return
    _uiState.update {
        it.copy(
            error = message,
            showPickSourceErrorAction = true,
            showControls = false,
            showLoadingOverlay = false
        )
    }
}

/** A source was chosen (by the member or by the fallback): any pending fallback is over. */
internal fun PlayerRuntimeController.clearSourceFallbackForNewSource() {
    sourceFallbackJob?.cancel()
    sourceFallbackJob = null
    sourceFallbackGaveUpMessage = null
    // A chosen source gets its own one-shot retries, even when its link looks like the last one.
    retryGuardsStreamUrl = null
    if (_uiState.value.showPickSourceErrorAction) {
        _uiState.update { it.copy(showPickSourceErrorAction = false) }
    }
}
