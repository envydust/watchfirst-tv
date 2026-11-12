package com.nuvio.tv.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.data.mediaserver.ServerCatalog
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.skipStep
import com.nuvio.tv.domain.model.supportsExtra
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.CatalogRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.text.Normalizer
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

// WATCHFIRST (member feedback): search inside one "Ver tudo" list instead of the global search.

/** Most pages read automatically while a list is filtered without the addon's own search. */
internal const val MAX_LIST_SEARCH_PAGES = 8

private const val MAX_LIST_QUERY_LENGTH = 60
private const val LIST_SEARCH_DEBOUNCE_MS = 400L
private val COMBINING_MARKS = Regex("\\p{Mn}+")

/** Lower case without accents, so "acao" finds "Ação". */
internal fun String.listSearchKey(): String =
    COMBINING_MARKS.replace(Normalizer.normalize(lowercase(Locale.ROOT), Normalizer.Form.NFD), "").trim()

/** The titles in [items] whose name contains [query], ignoring case and accents. */
internal fun filterListByQuery(items: List<MetaPreview>, query: String): List<MetaPreview> {
    val needle = query.listSearchKey()
    if (needle.isEmpty()) return items
    return items.filter { it.name.listSearchKey().contains(needle) }
}

/** Matches already loaded first, then the addon's search results that are not among them. */
internal fun mergeListSearchResults(local: List<MetaPreview>, remote: List<MetaPreview>?): List<MetaPreview> {
    if (remote.isNullOrEmpty()) return local
    val seen = local.mapTo(HashSet()) { "${it.apiType}:${it.id}" }
    return local + remote.filter { seen.add("${it.apiType}:${it.id}") }
}

/** The catalog behind a list when its addon manifest declares a "search" extra for it. */
internal fun findSearchableCatalog(
    addons: List<Addon>,
    addonId: String,
    addonBaseUrl: String,
    type: String,
    catalogId: String
): CatalogDescriptor? {
    val baseUrl = addonBaseUrl.trim().trimEnd('/')
    val addon = addons.firstOrNull { it.id == addonId && it.baseUrl.trim().trimEnd('/') == baseUrl }
        ?: addons.firstOrNull { it.id == addonId }
        ?: return null
    return addon.catalogs
        .firstOrNull { it.id == catalogId && it.apiType.equals(type, ignoreCase = true) }
        ?.takeIf { it.supportsExtra("search") }
}

/** The list being searched; equal for every page of the same catalog. */
internal data class ListSearchTarget(
    val addonId: String,
    val addonName: String,
    val addonBaseUrl: String,
    val catalogId: String,
    val catalogName: String,
    val type: String,
    val extraArgs: Map<String, String>
)

/** The addon's answer for the current query; [results] stays null until it arrives. */
data class RemoteListSearchState(
    val results: List<MetaPreview>? = null,
    val searching: Boolean = false,
    val failed: Boolean = false
)

/** Query and addon search for one "Ver tudo" screen (scoped to its back stack entry). */
@HiltViewModel
class CatalogListSearchViewModel @Inject constructor(
    addonRepository: AddonRepository,
    private val catalogRepository: CatalogRepository
) : ViewModel() {

    private val target = MutableStateFlow<ListSearchTarget?>(null)

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _remote = MutableStateFlow(RemoteListSearchState())
    val remote: StateFlow<RemoteListSearchState> = _remote.asStateFlow()

    private var autoPagesRead = 0

    private val searchableCatalog: StateFlow<CatalogDescriptor?> =
        combine(target, addonRepository.getInstalledAddons()) { listTarget, addons ->
            listTarget?.let {
                findSearchableCatalog(addons, it.addonId, it.addonBaseUrl, it.type, it.catalogId)
            }
        }
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** True when the addon itself can search this list. */
    val addonSearchAvailable: StateFlow<Boolean> = searchableCatalog
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    init {
        viewModelScope.launch {
            combine(
                _query.map { it.trim() }.distinctUntilChanged(),
                searchableCatalog,
                target
            ) { trimmed, catalog, listTarget -> Triple(trimmed, catalog, listTarget) }
                .collectLatest { (trimmed, catalog, listTarget) ->
                    if (trimmed.length < 2 || catalog == null || listTarget == null) {
                        _remote.value = RemoteListSearchState()
                        return@collectLatest
                    }
                    _remote.value = RemoteListSearchState(searching = true)
                    delay(LIST_SEARCH_DEBOUNCE_MS)
                    val items = searchAddon(listTarget, catalog, trimmed)
                    _remote.value = RemoteListSearchState(
                        results = items,
                        searching = false,
                        failed = items == null
                    )
                }
        }
    }

    /** Points the search at [row]; addon search only for addon catalogs ([allowAddonSearch]). */
    fun bindList(row: CatalogRow?, allowAddonSearch: Boolean) {
        target.value = if (row == null || !allowAddonSearch || ServerCatalog.isServerAddon(row.addonBaseUrl)) {
            null
        } else {
            ListSearchTarget(
                addonId = row.addonId,
                addonName = row.addonName,
                addonBaseUrl = row.addonBaseUrl,
                catalogId = row.catalogId,
                catalogName = row.catalogName,
                type = row.apiType,
                extraArgs = row.extraArgs
            )
        }
    }

    fun onQueryChange(value: String) {
        val next = value.take(MAX_LIST_QUERY_LENGTH)
        _query.value = next
        if (next.isBlank()) autoPagesRead = 0
    }

    fun clearQuery() = onQueryChange("")

    /** Reserves one automatic page read for the active search; false once the limit is reached. */
    fun claimAutoPage(): Boolean {
        if (autoPagesRead >= MAX_LIST_SEARCH_PAGES) return false
        autoPagesRead += 1
        return true
    }

    private suspend fun searchAddon(
        listTarget: ListSearchTarget,
        catalog: CatalogDescriptor,
        query: String
    ): List<MetaPreview>? {
        val result = try {
            catalogRepository.getCatalog(
                addonBaseUrl = listTarget.addonBaseUrl,
                addonId = listTarget.addonId,
                addonName = listTarget.addonName,
                catalogId = listTarget.catalogId,
                catalogName = listTarget.catalogName,
                type = listTarget.type,
                skip = 0,
                skipStep = catalog.skipStep(),
                extraArgs = listTarget.extraArgs + ("search" to query),
                supportsSkip = false
            ).first { it !is NetworkResult.Loading }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
        return when (result) {
            is NetworkResult.Success -> result.data.items
            else -> null
        }
    }
}
