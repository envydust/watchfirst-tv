package com.nuvio.tv.ui.screens.livetv

import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.watchfirst.watchfirstAddonUrlKey
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.skipStep
import com.nuvio.tv.domain.model.supportsExtra
import com.nuvio.tv.domain.repository.CatalogRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// Same limits as the WATCHFIRST PC and phone apps.
private const val MAX_PAGES_PER_CATALOG = 5
private const val MAX_GENRES_PER_CATALOG = 40
private const val PARALLEL_REQUESTS = 4

data class LiveChannel(
    val meta: MetaPreview,
    val addonName: String,
    val addonBaseUrl: String,
    val category: String,
) {
    val favoriteKey: String get() = "${meta.apiType}:${meta.id}"
    val key: String get() = "$addonBaseUrl|$favoriteKey"
    val imageUrl: String? get() = meta.logo ?: meta.poster ?: meta.landscapePoster ?: meta.background
    /** Category and addon, the facts the live addons actually send. */
    val subtitle: String get() = listOf(category, addonName).filter { it.isNotBlank() }.distinct().joinToString(" · ")
}

data class LiveTvUiState(
    val loading: Boolean = false,
    val channels: List<LiveChannel> = emptyList(),
    val addonNames: List<String> = emptyList(),
    val totalSources: Int = 0,
    val failedSources: Int = 0,
    val loaded: Boolean = false,
)

sealed interface LiveTvFilter {
    data object All : LiveTvFilter
    data object Favorites : LiveTvFilter
    data class Source(val name: String) : LiveTvFilter
}

data class LiveCatalogRequest(val catalog: CatalogDescriptor, val genre: String?)

data class LiveChannelGroup(val category: String, val channels: List<LiveChannel>)

/** Installed, enabled addons the administrator marked as live for this member. */
fun liveAddons(installed: List<Addon>, liveKeys: Set<String>): List<Addon> =
    if (liveKeys.isEmpty()) emptyList()
    else installed.filter { it.enabled && watchfirstAddonUrlKey(it.baseUrl) in liveKeys }

/**
 * Catalogs that can be listed without user input. Catalogs that require a genre are requested once per
 * genre option; catalogs that require anything else (search, for example) are skipped.
 */
fun liveCatalogRequests(catalogs: List<CatalogDescriptor>): List<LiveCatalogRequest> =
    catalogs.flatMap { catalog ->
        val required = catalog.extra.filter { it.isRequired }
        val genre = catalog.extra.firstOrNull { it.name.equals("genre", ignoreCase = true) }
        when {
            required.any { !it.name.equals("genre", ignoreCase = true) } -> emptyList()
            genre != null && genre.isRequired ->
                genre.options.orEmpty().take(MAX_GENRES_PER_CATALOG).map { LiveCatalogRequest(catalog, it) }
            else -> listOf(LiveCatalogRequest(catalog, null))
        }
    }

fun liveChannelCategory(meta: MetaPreview, request: LiveCatalogRequest): String =
    request.genre?.trim()?.takeIf { it.isNotEmpty() }
        ?: meta.genres.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        ?: request.catalog.name.trim().takeIf { it.isNotEmpty() }
        ?: "Canais"

fun filterLiveChannels(
    channels: List<LiveChannel>,
    query: String,
    filter: LiveTvFilter,
    favorites: Set<String>,
): List<LiveChannel> {
    val needle = query.trim().lowercase()
    return channels.filter { channel ->
        val matchesFilter = when (filter) {
            LiveTvFilter.All -> true
            LiveTvFilter.Favorites -> channel.favoriteKey in favorites
            is LiveTvFilter.Source -> channel.addonName == filter.name
        }
        matchesFilter && (
            needle.isEmpty() ||
                channel.meta.name.lowercase().contains(needle) ||
                channel.category.lowercase().contains(needle)
            )
    }
}

/** Groups channels by category, keeping the order in which categories first appear. */
fun groupLiveChannels(channels: List<LiveChannel>): List<LiveChannelGroup> {
    val groups = LinkedHashMap<String, MutableList<LiveChannel>>()
    channels.forEach { groups.getOrPut(it.category) { mutableListOf() }.add(it) }
    return groups.map { (category, items) -> LiveChannelGroup(category, items) }
}

/** A fixed number of workers instead of a coroutine for every catalog. */
internal suspend fun <T, R> boundedCatalogMap(items: List<T>, parallelism: Int, block: suspend (T) -> R): List<R> =
    coroutineScope {
        require(parallelism > 0)
        val cursorLock = Mutex()
        var cursor = 0
        val results = MutableList<Result<R>?>(items.size) { null }
        List(minOf(parallelism, items.size)) {
            async {
                while (true) {
                    val index = cursorLock.withLock { if (cursor < items.size) cursor++ else null } ?: break
                    results[index] = Result.success(block(items[index]))
                }
            }
        }.awaitAll()
        results.map { checkNotNull(it).getOrThrow() }
    }

/**
 * Loads the channels of the live addons, as the PC and phone "TV ao vivo" tabs do. Kept for the whole
 * session, so going back to the tab does not reload every catalog.
 */
@Singleton
class LiveTvRepository @Inject constructor(
    private val catalogRepository: CatalogRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(LiveTvUiState())
    val state: StateFlow<LiveTvUiState> = _state.asStateFlow()
    private var job: Job? = null
    private var loadedSources: List<String>? = null

    fun ensureLoaded(sources: List<Addon>) {
        val urls = sources.map { it.baseUrl }
        if (urls == loadedSources && (job?.isActive == true || _state.value.loaded)) return
        refresh(sources)
    }

    fun refresh(sources: List<Addon>) {
        loadedSources = sources.map { it.baseUrl }
        job?.cancel()
        _state.value = _state.value.copy(
            loading = sources.isNotEmpty(),
            addonNames = sources.map { it.displayName }.distinct(),
            totalSources = sources.size,
            failedSources = 0,
            loaded = sources.isEmpty(),
            channels = if (sources.isEmpty()) emptyList() else _state.value.channels,
        )
        if (sources.isEmpty()) return
        job = scope.launch {
            val work = sources.flatMapIndexed { index, addon ->
                liveCatalogRequests(addon.catalogs).map { index to it }
            }
            val results = boundedCatalogMap(work, PARALLEL_REQUESTS) { (index, request) ->
                try {
                    loadCatalog(sources[index], request)
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (error: Exception) {
                    null
                }
            }
            val attempted = IntArray(sources.size)
            val succeeded = IntArray(sources.size)
            val unique = LinkedHashMap<String, LiveChannel>()
            results.forEachIndexed { index, result ->
                val sourceIndex = work[index].first
                attempted[sourceIndex]++
                if (result != null) {
                    succeeded[sourceIndex]++
                    result.forEach { channel -> if (channel.key !in unique) unique[channel.key] = channel }
                }
            }
            val failed = sources.indices.count {
                sources[it].catalogs.isEmpty() || (attempted[it] > 0 && succeeded[it] == 0)
            }
            _state.value = _state.value.copy(
                loading = false,
                channels = unique.values.toList(),
                failedSources = failed,
                loaded = true,
            )
        }
    }

    private suspend fun loadCatalog(addon: Addon, request: LiveCatalogRequest): List<LiveChannel>? {
        val catalog = request.catalog
        val supportsSkip = catalog.supportsExtra("skip")
        val step = catalog.skipStep()
        val extra = request.genre?.let { mapOf("genre" to it) } ?: emptyMap()
        val items = LinkedHashMap<String, MetaPreview>()
        var skip = 0
        var pages = 0
        while (pages < MAX_PAGES_PER_CATALOG) {
            val result = catalogRepository.getCatalog(
                addonBaseUrl = addon.baseUrl,
                addonId = addon.id,
                addonName = addon.displayName,
                catalogId = catalog.id,
                catalogName = catalog.name,
                type = catalog.apiType,
                skip = skip,
                skipStep = step,
                extraArgs = extra,
                supportsSkip = supportsSkip,
            ).first { it !is NetworkResult.Loading }
            val row = when (result) {
                is NetworkResult.Success -> result.data
                // The first page failing means this catalog is down; a later page failing just ends it.
                else -> if (pages == 0) return null else break
            }
            pages++
            var gotNew = false
            row.items.forEach { meta ->
                val key = "${meta.apiType}:${meta.id}"
                if (key !in items) {
                    items[key] = meta
                    gotNew = true
                }
            }
            if (!supportsSkip || !gotNew || !row.hasMore || row.nextSkip <= skip) break
            skip = row.nextSkip
        }
        return items.values.map { meta ->
            LiveChannel(
                meta = meta,
                addonName = addon.displayName,
                addonBaseUrl = addon.baseUrl,
                category = liveChannelCategory(meta, request),
            )
        }
    }
}
