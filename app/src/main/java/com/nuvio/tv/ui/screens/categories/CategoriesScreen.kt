@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.categories

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Tv
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.screens.home.HomeViewModel
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay

private val CardShape = RoundedCornerShape(14.dp)
private val Gutter = 48.dp

private sealed interface CategoryGridItem {
    val key: String

    data class Header(val group: HomeCategoryGroup, val count: Int) : CategoryGridItem {
        override val key: String get() = "group:${group.name}"
    }

    data class Entry(val entry: HomeCategoryEntry) : CategoryGridItem {
        override val key: String get() = "entry:${entry.key}"
    }
}

private fun HomeCategoryGroup.icon(): ImageVector = when (this) {
    HomeCategoryGroup.MOVIES -> Icons.Filled.Movie
    HomeCategoryGroup.SERIES -> Icons.Filled.Tv
    HomeCategoryGroup.ANIME -> Icons.Filled.AutoAwesome
    HomeCategoryGroup.OTHER -> Icons.AutoMirrored.Filled.ViewList
}

/**
 * WATCHFIRST "Categorias": every list Home shows, in Home order, grouped as Filmes, Séries, Animes
 * and Outras listas. OK opens the list's "Ver tudo" screen; left from the first column opens the
 * sidebar (MainActivity does that when focus cannot move further left).
 */
@Composable
fun CategoriesScreen(
    viewModel: HomeViewModel,
    onOpenCatalog: (catalogId: String, addonId: String, type: String) -> Unit,
    showBuiltInHeader: Boolean = true
) {
    val colors = NuvioTheme.colors
    val uiState by viewModel.uiState.collectAsState()
    val groups = remember(uiState.homeRows) { groupHomeCategories(uiState.homeRows) }
    val gridItems = remember(groups) {
        buildList<CategoryGridItem> {
            groups.forEach { (group, entries) ->
                add(CategoryGridItem.Header(group, entries.size))
                entries.forEach { add(CategoryGridItem.Entry(it)) }
            }
        }
    }
    val hasEntries = gridItems.isNotEmpty()
    val gridState = rememberLazyGridState()
    val focusRequesters = remember { HashMap<String, FocusRequester>() }
    fun requesterFor(key: String): FocusRequester = focusRequesters.getOrPut(key) { FocusRequester() }
    // The list opened last, so Back from "Ver tudo" lands on it again.
    var lastFocusedKey by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(hasEntries) {
        if (!hasEntries) return@LaunchedEffect
        val savedIndex = gridItems.indexOfFirst {
            it is CategoryGridItem.Entry && it.entry.key == lastFocusedKey
        }
        val targetIndex = if (savedIndex >= 0) {
            savedIndex
        } else {
            gridItems.indexOfFirst { it is CategoryGridItem.Entry }
        }
        val target = gridItems.getOrNull(targetIndex) as? CategoryGridItem.Entry ?: return@LaunchedEffect
        if (gridState.layoutInfo.visibleItemsInfo.none { it.index == targetIndex }) {
            // Keep the group title above the first card in view.
            gridState.scrollToItem(if (targetIndex == 1) 0 else targetIndex)
        }
        // The sidebar hands focus over while it closes; retry for a few frames.
        repeat(6) {
            withFrameNanos { }
            if (runCatching { requesterFor(target.entry.key).requestFocus() }.isSuccess) return@LaunchedEffect
            delay(32)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.Background)
    ) {
        Column(modifier = Modifier.padding(start = Gutter, end = Gutter, top = 28.dp, bottom = 6.dp)) {
            Text(
                text = "Categorias",
                color = if (showBuiltInHeader) colors.TextPrimary else Color.Transparent,
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Todas as listas do Início · OK abre a lista completa",
                color = colors.TextTertiary,
                fontSize = 13.sp
            )
        }

        when {
            hasEntries -> LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Adaptive(minSize = 260.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = Gutter, end = Gutter, top = 4.dp, bottom = 48.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                items(
                    items = gridItems,
                    key = { it.key },
                    span = { item ->
                        if (item is CategoryGridItem.Header) GridItemSpan(maxLineSpan) else GridItemSpan(1)
                    },
                    contentType = { item -> if (item is CategoryGridItem.Header) "header" else "entry" }
                ) { item ->
                    when (item) {
                        is CategoryGridItem.Header -> CategoryGroupHeader(group = item.group, count = item.count)
                        is CategoryGridItem.Entry -> CategoryCard(
                            entry = item.entry,
                            modifier = Modifier
                                .focusRequester(requesterFor(item.entry.key))
                                .onFocusChanged { state ->
                                    if (state.isFocused || state.hasFocus) lastFocusedKey = item.entry.key
                                },
                            onClick = {
                                lastFocusedKey = item.entry.key
                                onOpenCatalog(item.entry.catalogId, item.entry.addonId, item.entry.type)
                            }
                        )
                    }
                }
            }

            uiState.isLoading -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                LoadingIndicator()
            }

            else -> Column(modifier = Modifier.padding(horizontal = Gutter, vertical = 24.dp)) {
                Text(
                    text = "Nenhuma lista no Início ainda",
                    color = colors.TextPrimary,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "As categorias aparecem aqui assim que o Início terminar de carregar.",
                    color = colors.TextSecondary,
                    fontSize = 15.sp
                )
            }
        }
    }
}

@Composable
private fun CategoryGroupHeader(group: HomeCategoryGroup, count: Int) {
    val colors = NuvioTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 18.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = group.icon(),
            contentDescription = null,
            tint = colors.Secondary,
            modifier = Modifier.size(24.dp)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = group.title,
            color = colors.TextPrimary,
            fontSize = 21.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = if (count == 1) "1 lista" else "$count listas",
            color = colors.TextSecondary,
            fontSize = 14.sp
        )
    }
}

@Composable
private fun CategoryCard(
    entry: HomeCategoryEntry,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val colors = NuvioTheme.colors
    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(88.dp),
        shape = ClickableSurfaceDefaults.shape(CardShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = colors.BackgroundCard,
            contentColor = colors.TextPrimary,
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black,
            pressedContainerColor = Color.White,
            pressedContentColor = Color.Black
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, colors.BorderMuted), shape = CardShape)
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 18.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.title,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (entry.addonName.isNotBlank()) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = entry.addonName,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = LocalContentColor.current.copy(alpha = 0.65f)
                    )
                }
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}
