package com.nuvio.tv.ui.screens.categories

import com.nuvio.tv.domain.model.stableKey
import com.nuvio.tv.ui.screens.home.HomeRow

// WATCHFIRST (member feedback): every Home list in one place, grouped as Filmes, Séries and Animes,
// so a list far down Home opens with a couple of clicks instead of a long scroll.

internal enum class HomeCategoryGroup(val title: String) {
    MOVIES("Filmes"),
    SERIES("Séries"),
    ANIME("Animes"),
    OTHER("Outras listas")
}

/** One Home list, with what the "Ver tudo" route needs to open it. */
internal data class HomeCategoryEntry(
    val key: String,
    val title: String,
    val addonName: String,
    val addonId: String,
    val catalogId: String,
    val type: String
)

private val ANIME_HINTS = listOf("anime", "crunchyroll", "kitsu", "anilist", "myanimelist")

/** Animes by catalog type or by name (Crunchyroll, Kitsu…); otherwise by the catalog's content type. */
internal fun homeCategoryGroupOf(type: String, vararg names: String): HomeCategoryGroup {
    val normalizedType = type.trim().lowercase()
    val text = names.joinToString(" ").lowercase()
    if (normalizedType == "anime" || ANIME_HINTS.any { it in text }) return HomeCategoryGroup.ANIME
    return when (normalizedType) {
        "movie", "movies", "film", "films" -> HomeCategoryGroup.MOVIES
        "series", "tv", "show", "shows", "tvshow" -> HomeCategoryGroup.SERIES
        else -> HomeCategoryGroup.OTHER
    }
}

private fun HomeRow.toCategoryEntry(): Pair<HomeCategoryGroup, HomeCategoryEntry>? = when (this) {
    is HomeRow.Catalog -> {
        val entry = HomeCategoryEntry(
            key = row.stableKey(),
            title = row.catalogName.replaceFirstChar { it.uppercase() },
            addonName = row.addonName,
            addonId = row.addonId,
            catalogId = row.catalogId,
            type = row.apiType
        )
        homeCategoryGroupOf(
            row.apiType,
            row.catalogName,
            row.catalogId,
            row.addonName,
            row.addonId,
            row.addonBaseUrl
        ) to entry
    }
    is HomeRow.PlaceholderCatalog -> {
        val entry = HomeCategoryEntry(
            key = stableCatalogKey,
            title = catalogName.replaceFirstChar { it.uppercase() },
            addonName = addonName,
            addonId = addonId,
            catalogId = catalogId,
            type = apiType
        )
        homeCategoryGroupOf(apiType, catalogName, displayTitle, catalogId, addonName, addonId, addonBaseUrl) to entry
    }
    // Collections open their own folders, not a "Ver tudo" list.
    is HomeRow.CollectionRow -> null
}

/** The catalog rows Home shows, in Home order, grouped Filmes → Séries → Animes → Outras listas. */
internal fun groupHomeCategories(rows: List<HomeRow>): List<Pair<HomeCategoryGroup, List<HomeCategoryEntry>>> {
    val grouped = rows.mapNotNull { it.toCategoryEntry() }.distinctBy { it.second.key }
    return HomeCategoryGroup.entries.mapNotNull { group ->
        grouped.filter { it.first == group }
            .map { it.second }
            .takeIf { it.isNotEmpty() }
            ?.let { group to it }
    }
}
