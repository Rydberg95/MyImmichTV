package dev.myimmich.tv.ui.viewer

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import dev.myimmich.tv.api.AlbumDto
import dev.myimmich.tv.api.AssetDto
import dev.myimmich.tv.data.AppSettings
import dev.myimmich.tv.data.ServerConfig
import dev.myimmich.tv.remote.RemoteCommand
import dev.myimmich.tv.remote.RemoteController
import dev.myimmich.tv.remote.RemoteServer
import dev.myimmich.tv.remote.RemoteViewerState
import dev.myimmich.tv.repo.LibraryRepository
import dev.myimmich.tv.tls.TlsSupport
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import kotlin.random.Random

enum class SourceKind { TIMELINE, FAVORITES, ALBUM }

data class LibrarySource(val kind: SourceKind, val label: String = "", val albumId: String? = null)

@Composable
fun ViewerScreen(config: ServerConfig, settings: AppSettings, remote: RemoteController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    fun pinnedHttpClient(): okhttp3.OkHttpClient = TlsSupport.buildClient(
        certFingerprint = config.certFingerprint.takeIf { it.isNotBlank() },
        trustAny = config.trustAny,
    )

    val videoHttpClient = remember(config) {
        pinnedHttpClient().newBuilder()
            .addInterceptor { chain ->
                val r = chain.request().newBuilder()
                    .header("x-api-key", config.apiKey)
                    .build()
                chain.proceed(r)
            }
            .build()
    }

    val client = remember(config) {
        dev.myimmich.tv.api.ImmichClient(
            http = pinnedHttpClient(),
            serverUrl = config.serverUrl,
            apiKey = config.apiKey,
        )
    }
    val repo = remember(client) { LibraryRepository(client) }
    val imageLoader = remember(config) {
        ImageLoader.Builder(context)
            .components { add(dev.myimmich.tv.api.ImmichThumbFetcher.Factory(pinnedHttpClient())) }
            .build()
    }

    val intervalSec by settings.slideshowSeconds.collectAsStateWithLifecycle(initialValue = 10)
    val shufflePref by settings.slideshowShuffle.collectAsStateWithLifecycle(initialValue = false)

    val remoteServer = remember(config) { RemoteServer(context, config, remote) }
    var pairingShown by remember { mutableStateOf(false) }
    DisposableEffect(config) {
        remoteServer.start()
        onDispose { remoteServer.stop() }
    }

    var source by remember { mutableStateOf(LibrarySource(SourceKind.TIMELINE)) }
    var months by remember(source) { mutableStateOf<List<String>>(emptyList()) }
    var assetsByMonth by remember(source) { mutableStateOf<Map<String, List<AssetDto>>>(emptyMap()) }
    var loadingBucket by remember(source) { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val assets: List<AssetDto> = remember(months, assetsByMonth) {
        months.flatMap { m -> assetsByMonth[m].orEmpty() }
    }
    var index by remember(source) { mutableIntStateOf(0) }
    var infoShown by remember { mutableStateOf(false) }
    var stripShown by remember { mutableStateOf(false) }
    var menuShown by remember { mutableStateOf(false) }
    var menuRow by remember { mutableIntStateOf(0) }
    var menuCol by remember { mutableIntStateOf(0) }
    var albumCol by remember { mutableIntStateOf(0) }
    var albums by remember { mutableStateOf<List<AlbumDto>>(emptyList()) }
    var slideshowOn by remember { mutableStateOf(false) }
    var videoToggleTick by remember { mutableIntStateOf(0) }

    suspend fun loadMonth(bucket: String) {
        if (assetsByMonth.containsKey(bucket)) return
        loadingBucket = bucket
        try {
            val list = when (source.kind) {
                SourceKind.TIMELINE -> repo.monthAssets(bucket)
                SourceKind.FAVORITES -> repo.favoriteMonthAssets(bucket)
                SourceKind.ALBUM -> repo.albumMonthAssets(source.albumId ?: "", bucket)
            }
            assetsByMonth = assetsByMonth + (bucket to list)
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
        } finally {
            loadingBucket = null
        }
    }

    LaunchedEffect(menuShown) {
        if (menuShown && albums.isEmpty()) {
            albums = runCatching { repo.albums() }.getOrDefault(emptyList())
        }
    }

    LaunchedEffect(source) {
        error = null
        try {
            val buckets = when (source.kind) {
                SourceKind.TIMELINE -> repo.timelineBuckets()
                SourceKind.FAVORITES -> repo.favoriteBuckets()
                SourceKind.ALBUM -> repo.albumBuckets(source.albumId ?: "")
            }
            months = buckets.sortedByDescending { it.bucket }.map { it.bucket }
            if (months.isNotEmpty()) loadMonth(months.first())
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
        }
    }

    LaunchedEffect(index, assets.size, months.size, source) {
        if (months.isEmpty()) return@LaunchedEffect
        val pos = index.coerceIn(0, (assets.size - 1).coerceAtLeast(0))
        val unloaded = months.filter { !assetsByMonth.containsKey(it) }
        if (assets.isNotEmpty() && unloaded.isNotEmpty() && pos >= assets.size - 12) {
            loadMonth(unloaded.first())
        }
        for (offset in intArrayOf(-2, -1, 1, 2)) {
            val i = pos + offset
            if (i in assets.indices) {
                imageLoader.enqueue(
                    ImageRequest.Builder(context)
                        .data(dev.myimmich.tv.api.ImmichThumb(client.thumbnailUrl(assets[i].id)))
                        .memoryCacheKey("preview-" + assets[i].id)
                        .build()
                )
            }
        }
    }

    var remoteAsset by remember { mutableStateOf<AssetDto?>(null) }
    val currentAsset: AssetDto? = remoteAsset ?: assets.getOrNull(index.coerceIn(0, (assets.size - 1).coerceAtLeast(0)))

    fun moveManual(delta: Int) {
        if (assets.isEmpty()) return
        slideshowOn = false
        index = (index + delta).coerceIn(0, assets.size - 1)
    }

    fun advanceSlideshow() {
        if (assets.isEmpty()) return
        index = if (shufflePref && assets.size > 1) {
            var next = index
            while (next == index) next = Random.nextInt(assets.size)
            next
        } else {
            (index + 1) % assets.size
        }
    }


    val commandHandler by rememberUpdatedState(
        lambda@{ cmd: RemoteCommand ->
            when (cmd.type) {
                "show" -> {
                    val id = cmd.assetId
                    if (id != null) {
                        val pos = assets.indexOfFirst { it.id == id }
                        if (pos >= 0) {
                            remoteAsset = null
                            index = pos
                        } else {
                            remoteAsset = AssetDto(
                                id = id, type = cmd.assetType ?: "IMAGE", fileCreatedAt = null,
                                isFavorite = false, durationMs = null, livePhotoVideoId = null,
                                thumbhash = null, city = null, country = null,
                            )
                        }
                    }
                }
                "next" -> { moveManual(1) }
                "prev" -> { moveManual(-1) }
                "slideshow" -> { slideshowOn = cmd.value == 1 }
                "shuffle" -> { scope.launch { settings.setSlideshow(intervalSec, cmd.value == 1) } }
                "info" -> { infoShown = !infoShown }
            }
        }
    )
    LaunchedEffect(Unit) {
        remote.commands.collect { cmd -> commandHandler(cmd) }
    }

    LaunchedEffect(currentAsset?.id, source, slideshowOn, shufflePref, assets.size, index, remoteAsset) {
        val a = currentAsset
        remote.publish(
            RemoteViewerState(
                source = source.kind.name,
                label = if (source.kind == SourceKind.ALBUM) "Album - " + source.label else source.label,
                index = index,
                total = assets.size,
                assetId = a?.id,
                assetType = a?.type,
                slideshow = slideshowOn,
                shuffle = shufflePref,
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    LaunchedEffect(slideshowOn, index, assets.size, intervalSec, shufflePref) {
        if (!slideshowOn || assets.isEmpty()) return@LaunchedEffect
        val a = currentAsset ?: return@LaunchedEffect
        if (!a.isVideo) {
            delay(intervalSec * 1000L)
            advanceSlideshow()
        }
    }

    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    BackHandler(enabled = menuShown || stripShown || infoShown) {
        when {
            menuShown -> menuShown = false
            stripShown -> stripShown = false
            infoShown -> infoShown = false
        }
    }

    fun move(delta: Int) = moveManual(delta)

    if (pairingShown) {
        androidx.activity.compose.BackHandler { pairingShown = false }
        dev.myimmich.tv.ui.pairing.PairingScreen(server = remoteServer, pin = remote.pin)
        return
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                if (menuShown) {
                    when (e.key) {
                        Key.DirectionLeft -> {
                            if (menuRow == 0) menuCol = (menuCol - 1).coerceAtLeast(0)
                            else albumCol = (albumCol - 1).coerceAtLeast(0)
                            true
                        }
                        Key.DirectionRight -> {
                            if (menuRow == 0) menuCol = (menuCol + 1).coerceAtMost(3)
                            else albumCol = (albumCol + 1).coerceAtMost((albums.size - 1).coerceAtLeast(0))
                            true
                        }
                        Key.DirectionDown -> {
                            if (menuRow == 0 && albums.isNotEmpty()) menuRow = 1 else menuShown = false
                            true
                        }
                        Key.DirectionUp -> {
                            if (menuRow == 1) menuRow = 0 else menuShown = false
                            true
                        }
                        Key.DirectionCenter, Key.Enter, Key.Spacebar -> {
                            if (menuRow == 0) {
                                when (menuCol) {
                                    0 -> {
                                        source = LibrarySource(SourceKind.TIMELINE, "Timeline")
                                        index = 0
                                        menuShown = false
                                    }
                                    1 -> {
                                        source = LibrarySource(SourceKind.FAVORITES, "Favorites")
                                        index = 0
                                        menuShown = false
                                    }
                                    2 -> {
                                        menuRow = 1
                                        albumCol = 0
                                    }
                                    else -> {
                                        menuShown = false
                                        pairingShown = true
                                        menuRow = 0
                                        menuCol = 0
                                    }
                                }
                            } else {
                                val album = albums.getOrNull(albumCol)
                                if (album != null) {
                                    source = LibrarySource(SourceKind.ALBUM, album.albumName, album.id)
                                    index = 0
                                    menuShown = false
                                    menuRow = 0
                                }
                            }
                            true
                        }
                        else -> false
                    }
                } else when (e.key) {
                    Key.DirectionLeft -> { move(-1); true }
                    Key.DirectionRight -> { move(1); true }
                    Key.DirectionDown -> { stripShown = !stripShown; true }
                    Key.DirectionUp -> { menuShown = true; true }
                    Key.DirectionCenter, Key.Enter, Key.Spacebar -> {
                        val a = currentAsset
                        if (a != null && a.isVideo) videoToggleTick++ else infoShown = !infoShown
                        true
                    }
                    Key.MediaPlayPause -> { slideshowOn = !slideshowOn; true }
                    Key.MediaPlay -> { slideshowOn = true; true }
                    Key.MediaPause -> { slideshowOn = false; true }
                    else -> false
                }
            },
    ) {
        when {
            error != null && assets.isEmpty() -> CenteredMessage("Error: $error")
            assets.isEmpty() -> CenteredMessage(
                if (loadingBucket != null || months.isEmpty()) "Loading library…" else "This collection is empty"
            )
            else -> {
                val safeIndex = index.coerceIn(0, assets.size - 1)
                val current = assets[safeIndex]
                if (current.isVideo) {
                    VideoPlayer(
                        url = client.originalUrl(current.id),
                        httpClient = videoHttpClient,
                        modifier = Modifier.fillMaxSize(),
                        toggleTick = videoToggleTick,
                        onEnded = { if (slideshowOn) advanceSlideshow() },
                    )
                } else {
                    FullscreenAsset(current, client, imageLoader, infoShown)
                }
                AnimatedVisibility(
                    visible = stripShown,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier.align(Alignment.BottomCenter),
                ) {
                    Filmstrip(
                        assets = assets,
                        selectedIndex = safeIndex,
                        client = client,
                        imageLoader = imageLoader,
                    )
                }
                if (loadingBucket != null && !stripShown) {
                    Text(
                        "Loading more…",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF546E7A),
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(16.dp),
                    )
                }
            }
        }
        AnimatedVisibility(
            visible = menuShown,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            val albumListState = rememberLazyListState()
            LaunchedEffect(menuRow, albumCol, albums.size) {
                if (menuRow == 1 && albums.isNotEmpty()) {
                    albumListState.animateScrollToItem(albumCol.coerceIn(0, albums.size - 1))
                }
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xCC000000))
                    .padding(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    listOf(
                        "Timeline",
                        "Favorites",
                        "Albums",
                        "Remote",
                    ).forEachIndexed { col, item ->
                        val selected = when (col) {
                            0 -> source.kind == SourceKind.TIMELINE
                            1 -> source.kind == SourceKind.FAVORITES
                            2 -> source.kind == SourceKind.ALBUM
                            else -> false
                        }
                        val cursor = menuRow == 0 && menuCol == col
                        Text(
                            item,
                            style = MaterialTheme.typography.titleMedium,
                            color = when {
                                cursor -> Color(0xFF80DEEA)
                                selected -> Color(0xFF4DD0E1)
                                else -> Color(0xFFB0BEC5)
                            },
                            modifier = Modifier
                                .padding(horizontal = 8.dp)
                                .then(
                                    if (cursor || selected) Modifier.border(2.dp, Color(0xFF80DEEA)) else Modifier
                                )
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                        )
                    }
                }
                if (albums.isNotEmpty()) {
                    LazyRow(
                        state = albumListState,
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        itemsIndexed(albums, key = { _, a -> a.id }) { col, album ->
                            val cursor = menuRow == 1 && albumCol == col
                            Text(
                                album.albumName,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (cursor) Color(0xFF80DEEA) else Color(0xFFECEFF1),
                                modifier = Modifier
                                    .background(Color(0xFF263238))
                                    .then(
                                        if (cursor) Modifier.border(2.dp, Color(0xFF80DEEA))
                                        else Modifier.border(1.dp, Color(0xFF546E7A))
                                    )
                                    .padding(horizontal = 14.dp, vertical = 8.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CenteredMessage(text: String) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun FullscreenAsset(
    asset: AssetDto,
    client: dev.myimmich.tv.api.ImmichClient,
    imageLoader: ImageLoader,
    infoShown: Boolean,
) {
    var portrait by remember(asset.id) { mutableStateOf(false) }
    Crossfade(targetState = asset.id, animationSpec = tween(350), label = "photo") { id ->
        Box(Modifier.fillMaxSize()) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(dev.myimmich.tv.api.ImmichThumb(client.thumbnailUrl(id)))
                    .memoryCacheKey("preview-$id")
                    .build(),
                contentDescription = null,
                imageLoader = imageLoader,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (portrait && android.os.Build.VERSION.SDK_INT >= 31) Modifier.blur(40.dp) else Modifier
                    ),
            )
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(dev.myimmich.tv.api.ImmichThumb(client.thumbnailUrl(id)))
                    .memoryCacheKey("preview-$id")
                    .build(),
                contentDescription = null,
                imageLoader = imageLoader,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
                onSuccess = { result ->
                    val img = result.result.image
                    portrait = img.height > img.width
                },
            )
            AnimatedVisibility(
                visible = infoShown,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(32.dp),
            ) {
                InfoPanel(asset)
            }
        }
    }
}

@Composable
private fun InfoPanel(asset: AssetDto) {
    Column(
        modifier = Modifier
            .background(Color(0xB3000000))
            .padding(16.dp),
    ) {
        val dateText = asset.fileCreatedAt?.let {
            runCatching {
                OffsetDateTime.parse(it).format(DateTimeFormatter.ofPattern("EEE, d MMM yyyy  HH:mm"))
            }.getOrNull()
        }
        dateText?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
        listOfNotNull(asset.city, asset.country)
            .joinToString(", ")
            .takeIf { it.isNotBlank() }
            ?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Color(0xFFB0BEC5)) }
        if (asset.isVideo && asset.durationMs != null) {
            Text(
                formatDuration(asset.durationMs),
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF78909C),
            )
        }
    }
}

private fun formatDuration(ms: Long): String {
    val totalSec = ms / 1000
    val m = totalSec / 60
    val s = totalSec % 60
    return "%d:%02d".format(m, s)
}

@Composable
private fun Filmstrip(
    assets: List<AssetDto>,
    selectedIndex: Int,
    client: dev.myimmich.tv.api.ImmichClient,
    imageLoader: ImageLoader,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(selectedIndex) {
        if (selectedIndex >= 4) listState.animateScrollToItem(selectedIndex - 4)
    }
    LazyRow(
        state = listState,
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xAA000000))
            .padding(vertical = 12.dp, horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        itemsIndexed(assets, key = { _, a -> a.id }) { i, asset ->
            val selected = i == selectedIndex
            Box(
                modifier = Modifier
                    .width(160.dp)
                    .height(96.dp)
                    .padding(if (selected) 0.dp else 3.dp)
                    .background(Color.White.copy(alpha = if (selected) 1f else 0.25f)),
            ) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(dev.myimmich.tv.api.ImmichThumb(client.smallThumbUrl(asset.id)))
                        .memoryCacheKey("thumb-${asset.id}")
                        .build(),
                    contentDescription = null,
                    imageLoader = imageLoader,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().padding(2.dp),
                )
                if (asset.isVideo) {
                    Text(
                        "▶",
                        color = Color.White,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
            }
        }
    }
}
