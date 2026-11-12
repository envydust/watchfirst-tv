package com.nuvio.tv.ui.screens

import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.CatalogExtra
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CatalogListSearchTest {

    private fun meta(id: String, name: String, type: ContentType = ContentType.MOVIE) = MetaPreview(
        id = id,
        type = type,
        name = name,
        poster = null,
        posterShape = PosterShape.POSTER,
        background = null,
        logo = null,
        description = null,
        releaseInfo = null,
        imdbRating = null,
        genres = emptyList()
    )

    private fun addon(id: String, baseUrl: String, catalogs: List<CatalogDescriptor>) = Addon(
        id = id,
        name = id,
        version = "1.0.0",
        description = null,
        logo = null,
        baseUrl = baseUrl,
        catalogs = catalogs,
        types = listOf(ContentType.MOVIE),
        resources = emptyList()
    )

    @Test
    fun `search key ignores case and accents`() {
        assertEquals("acao", "Ação".listSearchKey())
        assertEquals("pokemon", "  POKÉMON ".listSearchKey())
        assertEquals("coracao valente", "Coração Valente".listSearchKey())
    }

    @Test
    fun `filter matches without accents and keeps order`() {
        val items = listOf(meta("1", "Ação Total"), meta("2", "Comédia"), meta("3", "Reação em Cadeia"))
        assertEquals(listOf("1", "3"), filterListByQuery(items, "acao").map { it.id })
        assertEquals(listOf("2"), filterListByQuery(items, "COMEDIA").map { it.id })
        assertEquals(items, filterListByQuery(items, "   "))
    }

    @Test
    fun `merge keeps loaded matches first and drops duplicates`() {
        val local = listOf(meta("1", "Alpha"), meta("2", "Beta"))
        val remote = listOf(meta("2", "Beta"), meta("3", "Gamma"), meta("2", "Beta", ContentType.SERIES))
        assertEquals(
            listOf("movie:1", "movie:2", "movie:3", "series:2"),
            mergeListSearchResults(local, remote).map { "${it.apiType}:${it.id}" }
        )
        assertEquals(local, mergeListSearchResults(local, null))
    }

    @Test
    fun `searchable catalog needs a search extra`() {
        val searchable = CatalogDescriptor(
            type = ContentType.MOVIE,
            id = "top",
            name = "Top",
            extra = listOf(CatalogExtra(name = "search"), CatalogExtra(name = "skip"))
        )
        val plain = CatalogDescriptor(type = ContentType.SERIES, id = "top", name = "Top")
        val shortForm = CatalogDescriptor(
            type = ContentType.MOVIE,
            id = "new",
            name = "New",
            extraSupported = listOf("search")
        )
        val addons = listOf(addon("a", "https://a.example/", listOf(searchable, plain, shortForm)))

        assertNotNull(findSearchableCatalog(addons, "a", "https://a.example", "movie", "top"))
        assertNull(findSearchableCatalog(addons, "a", "https://a.example", "series", "top"))
        assertNotNull(findSearchableCatalog(addons, "a", "https://a.example", "movie", "new"))
        assertNull(findSearchableCatalog(addons, "b", "https://a.example", "movie", "top"))
    }
}
