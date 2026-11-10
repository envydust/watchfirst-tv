package com.nuvio.tv.ui.screens.livetv

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.SuccessResult
import coil3.toBitmap
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.screens.account.InputField
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Live red, used only on the "AO VIVO" badge. */
private val LiveBadgeRed = Color(0xFFE5173F)
private val FavoriteGold = Color(0xFFFFC107)
private val CardShape = RoundedCornerShape(14.dp)
private val ChipShape = RoundedCornerShape(50)
private val CardWidth = 196.dp
private val Gutter = 48.dp

/**
 * "TV ao vivo" for MAX members, the tab the WATCHFIRST PC and phone apps have, laid out like Disney+
 * "Canais Ao Vivo": one row of tall, art-led channel cards per category, with search, source filter and
 * favorites. OK plays the channel; holding OK opens its options. These addons never show on Home.
 */
@Composable
fun LiveTvScreen(
    onPlay: (MetaPreview) -> Unit,
    showBuiltInHeader: Boolean = true,
    viewModel: LiveTvViewModel = hiltViewModel(),
) {
    val colors = NuvioTheme.colors
    val access by viewModel.accessState.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val favorites by viewModel.favorites.collectAsStateWithLifecycle()

    if (!access.liveTvEnabled) {
        Box(Modifier.fillMaxSize().background(colors.Background), contentAlignment = Alignment.Center) {
            LiveTvMessage(
                title = "TV ao vivo",
                text = "A TV ao vivo está disponível no plano MAX. Fale com o administrador para mudar de plano.",
            )
        }
        return
    }

    var query by rememberSaveable { mutableStateOf("") }
    var filter by remember { mutableStateOf<LiveTvFilter>(LiveTvFilter.All) }
    var selected by remember { mutableStateOf<LiveChannel?>(null) }
    val visible = remember(state.channels, query, filter, favorites) {
        groupLiveChannels(filterLiveChannels(state.channels, query, filter, favorites))
    }
    val listState = rememberLazyListState()
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        // The sidebar hands focus to the screen; start on the first filter, as the other tabs do.
        repeat(4) {
            if (runCatching { firstFocus.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(32)
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().background(colors.Background),
        contentPadding = PaddingValues(top = 28.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        item(key = "live-header") {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = Gutter)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "TV ao vivo",
                        color = if (showBuiltInHeader) colors.TextPrimary else Color.Transparent,
                        fontSize = 30.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.width(16.dp))
                    LiveBadge()
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = buildString {
                            append(if (state.channels.size == 1) "1 canal" else "${state.channels.size} canais")
                            if (state.failedSources > 0) {
                                append(" · ")
                                append(
                                    if (state.failedSources == 1) "1 fonte fora do ar"
                                    else "${state.failedSources} fontes fora do ar",
                                )
                            }
                        },
                        color = colors.TextSecondary,
                        fontSize = 15.sp,
                        maxLines = 1,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "OK assiste · segure OK para favoritar",
                    color = colors.TextTertiary,
                    fontSize = 13.sp,
                )
            }
        }

        item(key = "live-filters") {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(horizontal = Gutter),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                item(key = "search") {
                    InputField(
                        value = query,
                        onValueChange = { query = it.take(60) },
                        placeholder = "Buscar canal ou categoria",
                        modifier = Modifier.width(300.dp),
                    )
                }
                item(key = "all") {
                    FilterChip(
                        label = "Todos",
                        selected = filter == LiveTvFilter.All,
                        modifier = Modifier.focusRequester(firstFocus),
                    ) { filter = LiveTvFilter.All }
                }
                item(key = "favorites") {
                    FilterChip(
                        label = "Favoritos",
                        selected = filter == LiveTvFilter.Favorites,
                        icon = Icons.Rounded.Star,
                        iconTint = FavoriteGold,
                    ) { filter = LiveTvFilter.Favorites }
                }
                if (state.addonNames.size > 1) {
                    items(state.addonNames, key = { "source:$it" }) { name ->
                        FilterChip(label = name, selected = filter == LiveTvFilter.Source(name)) {
                            filter = LiveTvFilter.Source(name)
                        }
                    }
                }
                item(key = "refresh") {
                    FilterChip(
                        label = if (state.loading) "Atualizando…" else "Atualizar",
                        selected = false,
                        icon = Icons.Rounded.Refresh,
                        onClick = { if (!state.loading) viewModel.refresh() },
                    )
                }
            }
        }

        when {
            state.totalSources == 0 && state.loaded -> item(key = "empty-sources") {
                LiveTvMessage(
                    title = "Nenhum canal liberado",
                    text = "Ainda não há addons ao vivo liberados para o seu plano. Quando o administrador liberar, os canais aparecem aqui.",
                )
            }
            !state.loaded && state.channels.isEmpty() -> item(key = "loading") { LiveRowsSkeleton() }
            state.channels.isEmpty() -> item(key = "failed") {
                LiveTvMessage(
                    title = "Não foi possível carregar os canais",
                    text = "Os addons ao vivo não responderam. Verifique sua conexão e escolha Atualizar.",
                )
            }
            visible.isEmpty() -> item(key = "no-match") {
                val noFavorites = filter == LiveTvFilter.Favorites && query.isBlank()
                LiveTvMessage(
                    title = if (noFavorites) "Nenhum favorito ainda" else "Nenhum canal encontrado",
                    text = if (noFavorites) "Segure OK em um canal para adicioná-lo aos favoritos." else "Tente buscar por outro nome.",
                )
            }
            else -> visible.forEach { group ->
                item(key = "shelf:" + group.category) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text = group.category,
                            color = colors.TextPrimary,
                            fontSize = 19.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = Gutter),
                        )
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            // Room for the focused card to grow without being clipped.
                            contentPadding = PaddingValues(horizontal = Gutter, vertical = 8.dp),
                        ) {
                            items(group.channels, key = { it.key }) { channel ->
                                LiveChannelCard(
                                    channel = channel,
                                    favorite = channel.favoriteKey in favorites,
                                    onClick = { onPlay(channel.meta) },
                                    onLongClick = { selected = channel },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    val current = selected
    if (current != null) {
        val favorite = current.favoriteKey in favorites
        NuvioDialog(
            onDismiss = { selected = null },
            title = current.meta.name,
            subtitle = current.subtitle,
            width = 460.dp,
        ) {
            DialogAction(
                label = "Assistir agora",
                icon = Icons.Rounded.PlayArrow,
                primary = true,
            ) {
                selected = null
                onPlay(current.meta)
            }
            DialogAction(
                label = if (favorite) "Remover dos favoritos" else "Adicionar aos favoritos",
                icon = if (favorite) Icons.Rounded.Star else Icons.Rounded.StarBorder,
            ) {
                viewModel.toggleFavorite(current)
                selected = null
            }
        }
    }
}

/** "⚡ AO VIVO" pill, as on Disney+. */
@Composable
private fun LiveBadge(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(5.dp))
            .background(LiveBadgeRed)
            .padding(start = 5.dp, end = 8.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Bolt, contentDescription = null, tint = Color.White, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(2.dp))
        Text("AO VIVO", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.4.sp)
    }
}

@Composable
private fun FilterChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    iconTint: Color? = null,
    onClick: () -> Unit,
) {
    val colors = NuvioTheme.colors
    Surface(
        onClick = onClick,
        modifier = modifier.height(44.dp),
        shape = ClickableSurfaceDefaults.shape(ChipShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) colors.Secondary else colors.BackgroundCard,
            contentColor = if (selected) colors.OnSecondary else colors.TextPrimary,
            focusedContainerColor = Color.White,
            focusedContentColor = colors.OnSecondary,
            pressedContainerColor = Color.White,
            pressedContentColor = colors.OnSecondary,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, if (selected) Color.Transparent else colors.BorderMuted), shape = ChipShape),
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (selected || iconTint == null) androidx.tv.material3.LocalContentColor.current else iconTint,
                    modifier = Modifier.size(18.dp),
                )
            }
            Text(label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

/** Channel colours already read from their logos, so each logo is analysed once per session. */
private object ChannelTintCache {
    private val colors = HashMap<String, Color>()
    @Synchronized fun get(key: String): Color? = colors[key]
    @Synchronized fun put(key: String, color: Color) {
        if (colors.size > 400) colors.clear()
        colors[key] = color
    }
}

/** Disney-style channel card: tall, art-led, badge top left, logo and name bottom left. */
@Composable
private fun LiveChannelCard(
    channel: LiveChannel,
    favorite: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val colors = NuvioTheme.colors
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = Modifier.width(CardWidth).aspectRatio(0.96f),
        shape = ClickableSurfaceDefaults.shape(CardShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = colors.BackgroundCard,
            focusedContainerColor = colors.BackgroundCard,
            pressedContainerColor = colors.BackgroundCard,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.07f),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, colors.BorderMuted), shape = CardShape),
            focusedBorder = Border(BorderStroke(3.dp, Color.White), shape = CardShape),
            pressedBorder = Border(BorderStroke(3.dp, Color.White), shape = CardShape),
        ),
    ) {
        ChannelArt(channel = channel, modifier = Modifier.fillMaxSize())
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.42f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.88f),
                    ),
                ),
        )
        LiveBadge(modifier = Modifier.align(Alignment.TopStart).padding(12.dp))
        if (favorite) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(10.dp)
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Star, contentDescription = "Favorito", tint = FavoriteGold, modifier = Modifier.size(17.dp))
            }
        }
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
        ) {
            channel.imageUrl?.let { logo ->
                AsyncImage(
                    model = logo,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    alignment = Alignment.CenterStart,
                    modifier = Modifier.fillMaxWidth(0.62f).height(38.dp),
                )
                Spacer(Modifier.height(8.dp))
            }
            Text(
                text = channel.meta.name,
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = channel.subtitle,
                color = Color.White.copy(alpha = 0.74f),
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Background for a channel: its real artwork when the addon sends one, otherwise a field tinted with the
 * logo's own colour and the logo itself oversized and turned, bleeding off the corner.
 */
@Composable
private fun ChannelArt(channel: LiveChannel, modifier: Modifier = Modifier) {
    val colors = NuvioTheme.colors
    val logoUrl = channel.imageUrl
    val artworkUrl = (channel.meta.landscapePoster ?: channel.meta.background)?.takeIf { it.isNotBlank() && it != logoUrl }
    // Color.Unspecified in the cache means "analysed, no saturated colour" (white logos stay neutral).
    var tint by remember(logoUrl) { mutableStateOf(logoUrl?.let(ChannelTintCache::get)) }
    var logoResult by remember(logoUrl) { mutableStateOf<SuccessResult?>(null) }
    LaunchedEffect(logoResult) {
        val result = logoResult ?: return@LaunchedEffect
        if (tint != null || logoUrl == null) return@LaunchedEffect
        val extracted = withContext(Dispatchers.Default) { channelLogoTint(result) } ?: Color.Unspecified
        tint = extracted
        ChannelTintCache.put(logoUrl, extracted)
    }
    val base = tint?.takeIf { it != Color.Unspecified } ?: colors.SurfaceVariant

    BoxWithConstraints(modifier = modifier.background(colors.Surface)) {
        if (artworkUrl != null) {
            AsyncImage(
                model = artworkUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.linearGradient(
                            0f to lerp(base, Color.Black, 0.12f),
                            0.55f to lerp(base, Color.Black, 0.48f),
                            1f to lerp(base, colors.Background, 0.86f),
                        ),
                    ),
            )
            if (logoUrl != null) {
                AsyncImage(
                    model = logoUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = maxWidth * 0.32f, y = -maxHeight * 0.12f)
                        .requiredSize(maxWidth * 1.15f)
                        .rotate(-18f)
                        .graphicsLayer { alpha = 0.16f },
                )
            }
        }
        // Reads the logo once (hidden) to tint the card even when the logo is not drawn at full size.
        if (logoUrl != null && tint == null) {
            AsyncImage(
                model = logoUrl,
                contentDescription = null,
                modifier = Modifier.size(96.dp).graphicsLayer { alpha = 0f },
                onSuccess = { state -> logoResult = state.result },
            )
        }
    }
}

private const val TINT_SAMPLE = 48
private const val HUE_BUCKETS = 24

/**
 * Hue histogram over the opaque, saturated pixels of a small software copy of the logo (coil can hand back
 * hardware bitmaps, which can't be read directly). Returns the average colour of the heaviest hue bucket.
 */
private fun channelLogoTint(result: SuccessResult): Color? = runCatching {
    val source = result.image.toBitmap()
    val software = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && source.config == Bitmap.Config.HARDWARE) {
        source.copy(Bitmap.Config.ARGB_8888, false)
    } else {
        source
    }
    val small = Bitmap.createScaledBitmap(software, TINT_SAMPLE, TINT_SAMPLE, true)
    val pixels = IntArray(TINT_SAMPLE * TINT_SAMPLE)
    small.getPixels(pixels, 0, TINT_SAMPLE, 0, 0, TINT_SAMPLE, TINT_SAMPLE)
    val weight = DoubleArray(HUE_BUCKETS)
    val sumR = DoubleArray(HUE_BUCKETS)
    val sumG = DoubleArray(HUE_BUCKETS)
    val sumB = DoubleArray(HUE_BUCKETS)
    val hsv = FloatArray(3)
    for (p in pixels) {
        if (p ushr 24 and 0xFF < 200) continue
        val r = p shr 16 and 0xFF
        val g = p shr 8 and 0xFF
        val b = p and 0xFF
        android.graphics.Color.RGBToHSV(r, g, b, hsv)
        if (hsv[1] < 0.28f || hsv[2] < 0.22f) continue
        val bucket = ((hsv[0] / 360f) * HUE_BUCKETS).toInt().coerceIn(0, HUE_BUCKETS - 1)
        val w = (hsv[1] * hsv[2]).toDouble()
        weight[bucket] += w
        sumR[bucket] += r * w
        sumG[bucket] += g * w
        sumB[bucket] += b * w
    }
    if (small !== software) small.recycle()
    if (software !== source) software.recycle()
    val best = weight.indices.maxByOrNull { weight[it] } ?: return@runCatching null
    if (weight[best] < 4.0) return@runCatching null
    Color(
        red = (sumR[best] / weight[best] / 255.0).toFloat().coerceIn(0f, 1f),
        green = (sumG[best] / weight[best] / 255.0).toFloat().coerceIn(0f, 1f),
        blue = (sumB[best] / weight[best] / 255.0).toFloat().coerceIn(0f, 1f),
    )
}.getOrNull()

@Composable
private fun DialogAction(
    label: String,
    icon: ImageVector,
    primary: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = NuvioTheme.colors
    val shape = RoundedCornerShape(12.dp)
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(52.dp),
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (primary) colors.Secondary else colors.BackgroundCard,
            contentColor = if (primary) colors.OnSecondary else colors.TextPrimary,
            focusedContainerColor = Color.White,
            focusedContentColor = colors.OnSecondary,
            pressedContainerColor = Color.White,
            pressedContentColor = colors.OnSecondary,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
            Text(label, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun LiveTvMessage(title: String, text: String) {
    val colors = NuvioTheme.colors
    Column(
        modifier = Modifier.padding(horizontal = Gutter, vertical = 32.dp).widthIn(max = 560.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, color = colors.TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Text(text, color = colors.TextSecondary, fontSize = 15.sp, lineHeight = 22.sp)
    }
}

@Composable
private fun LiveRowsSkeleton() {
    val colors = NuvioTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
        repeat(2) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    Modifier.padding(horizontal = Gutter).width(160.dp).height(20.dp)
                        .clip(RoundedCornerShape(6.dp)).background(colors.BackgroundCard),
                )
                Row(
                    modifier = Modifier.padding(horizontal = Gutter, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    repeat(5) { SkeletonCard(CardWidth) }
                }
            }
        }
    }
}

@Composable
private fun SkeletonCard(width: Dp) {
    Box(
        Modifier.width(width).aspectRatio(0.96f)
            .clip(CardShape).background(NuvioTheme.colors.BackgroundCard),
    )
}
