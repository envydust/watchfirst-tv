package com.nuvio.tv.ui.screens.categories

import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.Collection
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.ui.screens.home.HomeRow
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeCategoryGroupsTest {

    private fun catalog(
        catalogId: String,
        name: String,
        type: String,
        addonName: String = "Cinemeta",
        addonId: String = "cinemeta"
    ) = HomeRow.Catalog(
        CatalogRow(
            addonId = addonId,
            addonName = addonName,
            addonBaseUrl = "https://$addonId.example",
            catalogId = catalogId,
            catalogName = name,
            type = ContentType.fromString(type),
            rawType = type,
            items = emptyList()
        )
    )

    @Test
    fun `groups by type and finds anime by name`() {
        assertEquals(HomeCategoryGroup.MOVIES, homeCategoryGroupOf("movie", "Populares"))
        assertEquals(HomeCategoryGroup.SERIES, homeCategoryGroupOf("series", "Populares"))
        assertEquals(HomeCategoryGroup.SERIES, homeCategoryGroupOf("tv", "Canais"))
        assertEquals(HomeCategoryGroup.ANIME, homeCategoryGroupOf("anime", "Temporada"))
        assertEquals(HomeCategoryGroup.ANIME, homeCategoryGroupOf("series", "Crunchyroll Lançamentos"))
        assertEquals(HomeCategoryGroup.ANIME, homeCategoryGroupOf("movie", "Top", "https://anime-kitsu.example"))
        assertEquals(HomeCategoryGroup.OTHER, homeCategoryGroupOf("channel", "Esportes"))
        assertEquals(HomeCategoryGroup.MOVIES, homeCategoryGroupOf("movie", "Animação"))
    }

    @Test
    fun `keeps Home order inside each group and skips collections`() {
        val rows = listOf(
            catalog("top", "populares", "movie"),
            catalog("top", "populares", "series"),
            HomeRow.CollectionRow(Collection(id = "c1", title = "Minha coleção")),
            catalog("trending", "Em alta", "anime", addonName = "Kitsu", addonId = "kitsu"),
            catalog("new", "Lançamentos", "movie"),
            HomeRow.PlaceholderCatalog(
                catalogKey = "x_channel_live",
                stableCatalogKey = "x_live",
                addonId = "x",
                addonName = "Extra",
                addonBaseUrl = "https://x.example",
                catalogId = "live",
                catalogName = "esportes",
                apiType = "channel",
                displayTitle = "esportes - Channel"
            )
        )

        val groups = groupHomeCategories(rows)

        assertEquals(
            listOf(HomeCategoryGroup.MOVIES, HomeCategoryGroup.SERIES, HomeCategoryGroup.ANIME, HomeCategoryGroup.OTHER),
            groups.map { it.first }
        )
        assertEquals(listOf("Populares", "Lançamentos"), groups[0].second.map { it.title })
        assertEquals(listOf("top"), groups[1].second.map { it.catalogId })
        assertEquals("series", groups[1].second.single().type)
        assertEquals("kitsu", groups[2].second.single().addonId)
        assertEquals("Esportes", groups[3].second.single().title)
    }
}
