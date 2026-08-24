package dev.myimmich.tv.ui.viewer

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import android.util.Log
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import dev.myimmich.tv.api.AlbumDto
import dev.myimmich.tv.api.AssetDetailDto
import dev.myimmich.tv.api.AssetDto
import dev.myimmich.tv.data.AppSettings
import dev.myimmich.tv.data.ServerConfig
import dev.myimmich.tv.remote.RemoteCommand
import dev.myimmich.tv.remote.RemoteController
import dev.myimmich.tv.remote.RemoteServer
import dev.myimmich.tv.remote.RemoteViewerState
import dev.myimmich.tv.remote.toAssetDto
import dev.myimmich.tv.repo.LibraryRepository
import dev.myimmich.tv.tls.TlsSupport
import dev.myimmich.tv.ui.theme.Palette
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.random.Random

enum class SourceKind { TIMELINE, FAVORITES, ALBUM, SEARCH }

data class LibrarySource(val kind: SourceKind, val label: String = "", val albumId: String? = null)

@Composable
fun ViewerScreen(
    config: ServerConfig,
    settings: AppSettings,
    remote: RemoteController,
    remoteServer: RemoteServer,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    fun pinnedHttpClient(): okhttp3.OkHttpClient = TlsSupport.buildClient(
        certFingerprint = config.certFingerprint.takeIf { it.isNotBlank() },
        trustAny = config.trustAny,
        extraAccepted = config.caPins,
    )

    val authedHttpClient = remember(config) {
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
            .components { add(dev.myimmich.tv.api.ImmichThumbFetcher.Factory(authedHttpClient)) }
            .build()
    }

    val intervalSec by settings.slideshowSeconds.collectAsStateWithLifecycle(initialValue = 10)
    val shufflePref by settings.slideshowShuffle.collectAsStateWithLifecycle(initialValue = false)
    val gridColumns by settings.gridColumns.collectAsStateWithLifecycle(initialValue = 7)

    var pairingShown by remember { mutableStateOf(false) }

    var source by remember { mutableStateOf(LibrarySource(SourceKind.TIMELINE)) }
    var months by remember(source) { mutableStateOf<List<String>>(emptyList()) }
    var assetsByMonth by remember(source) { mutableStateOf<Map<String, List<AssetDto>>>(emptyMap()) }
    var loadingBucket by remember(source) { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var certChanged by remember { mutableStateOf(false) }
    val assets: List<AssetDto> = remember(months, assetsByMonth) {
        months.flatMap { m -> assetsByMonth[m].orEmpty() }
    }

    /** True when a load failed because the server now presents an unpinned certificate. */
    fun isPinMismatch(e: Throwable): Boolean =
        generateSequence<Throwable>(e) { it.cause }.any {
            it.message?.contains("pin mismatch", ignoreCase = true) == true
        }
    var index by remember { mutableIntStateOf(0) }
    var infoShown by remember { mutableStateOf(false) }
    var stripShown by remember { mutableStateOf(false) }
    var menuShown by remember { mutableStateOf(false) }
    var menuRow by remember { mutableIntStateOf(0) }
    var menuCol by remember { mutableIntStateOf(0) }
    var albumCol by remember { mutableIntStateOf(0) }
    var albums by remember { mutableStateOf<List<AlbumDto>>(emptyList()) }
    var slideshowOn by remember { mutableStateOf(false) }
    var videoToggleTick by remember { mutableIntStateOf(0) }
    var videoError by remember { mutableStateOf<String?>(null) }

    /** Grid browse mode: the borderless photo mosaic (landing view for each source).
     *  False = the classic fullscreen viewer. `index` is the cursor in both modes. */
    var gridShown by remember { mutableStateOf(true) }
    val gridState = rememberLazyGridState()

    suspend fun loadMonth(bucket: String) {
        if (assetsByMonth.containsKey(bucket)) return
        loadingBucket = bucket
        try {
            val list = when (source.kind) {
                SourceKind.TIMELINE -> repo.monthAssets(bucket)
                SourceKind.FAVORITES -> repo.favoriteMonthAssets(bucket)
                SourceKind.ALBUM -> repo.albumMonthAssets(source.albumId ?: "", bucket)
                SourceKind.SEARCH -> return
            }
            assetsByMonth = assetsByMonth + (bucket to list)
        } catch (e: Exception) {
            if (isPinMismatch(e)) certChanged = true
            error = e.message ?: e.javaClass.simpleName
        } finally {
            loadingBucket = null
        }
    }

    // On menu open: fetch albums once and land the cursor on the tab of the
    // active source (album sources also pre-select their chip in the strip).
    var albumPositionPending by remember { mutableStateOf(false) }
    LaunchedEffect(menuShown) {
        if (menuShown) {
            menuRow = 0
            if (albums.isEmpty()) {
                albums = runCatching { repo.albums() }.getOrDefault(emptyList())
            }
            when (source.kind) {
                SourceKind.TIMELINE -> menuCol = 0
                SourceKind.FAVORITES -> menuCol = 1
                SourceKind.ALBUM -> {
                    if (albums.isNotEmpty()) {
                        menuCol = 2
                        albumCol = albums.indexOfFirst { it.id == source.albumId }.takeIf { it >= 0 } ?: albumCol
                    } else {
                        albumPositionPending = true
                    }
                }
                SourceKind.SEARCH -> {}
            }
        } else {
            albumPositionPending = false
        }
    }
    LaunchedEffect(albums.size) {
        if (albumPositionPending && albums.isNotEmpty()) {
            albumPositionPending = false
            menuCol = 2
            albumCol = albums.indexOfFirst { it.id == source.albumId }.takeIf { it >= 0 } ?: albumCol
        }
    }

    var retryTick by remember { mutableIntStateOf(0) }


    LaunchedEffect(error) {
        if (error != null) {
            delay(8000)
            retryTick++
        }
    }

    LaunchedEffect(index, assets.size, months.size, source, gridShown, gridColumns) {
        if (months.isEmpty()) return@LaunchedEffect
        val pos = index.coerceIn(0, (assets.size - 1).coerceAtLeast(0))
        val unloaded = months.filter { !assetsByMonth.containsKey(it) }
        val lookahead = if (gridShown) gridColumns * 4 else 12
        if (assets.isNotEmpty() && unloaded.isNotEmpty() && pos >= assets.size - lookahead) {
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
    var pendingShowId by remember { mutableStateOf<String?>(null) }
    var pendingShowBucket by remember { mutableStateOf<String?>(null) }
    var pendingSearchAssets by remember { mutableStateOf<List<AssetDto>?>(null) }

    fun overrideAsset(id: String, type: String?) = AssetDto(
        id = id, type = type ?: "IMAGE", fileCreatedAt = null,
        isFavorite = false, durationMs = null, livePhotoVideoId = null,
        thumbhash = null, city = null, country = null,
    )

    fun liveAssets(): List<AssetDto> = months.flatMap { m -> assetsByMonth[m].orEmpty() }

    suspend fun resolvePendingShow() {
        val id = pendingShowId ?: return
        pendingShowId = null
        val hint = pendingShowBucket
        pendingShowBucket = null
        if (hint != null && !assetsByMonth.containsKey(hint)) {
            if (!months.contains(hint)) {
                months = (months + hint).sortedByDescending { it }
            }
            loadMonth(hint)
        }
        val pos = liveAssets().indexOfFirst { it.id == id }
        if (pos >= 0) {
            remoteAsset = null
            index = pos
        }
    }

    LaunchedEffect(source, retryTick) {
        error = null
        if (source.kind == SourceKind.SEARCH) {
            months = listOf("results")
            assetsByMonth = mapOf("results" to (pendingSearchAssets.orEmpty()))
            return@LaunchedEffect
        }
        try {
            val buckets = when (source.kind) {
                SourceKind.TIMELINE -> repo.timelineBuckets()
                SourceKind.FAVORITES -> repo.favoriteBuckets()
                SourceKind.ALBUM -> repo.albumBuckets(source.albumId ?: "")
                SourceKind.SEARCH -> emptyList()
            }
            months = buckets.sortedByDescending { it.bucket }.map { it.bucket }
            if (months.isNotEmpty()) loadMonth(months.first())
            resolvePendingShow()
            certChanged = false
        } catch (e: Exception) {
            if (isPinMismatch(e)) certChanged = true
            error = e.message ?: e.javaClass.simpleName
        }
    }
    val currentAsset: AssetDto? = remoteAsset ?: assets.getOrNull(index.coerceIn(0, (assets.size - 1).coerceAtLeast(0)))

    LaunchedEffect(currentAsset?.id) { videoError = null }

    // richer info (filename, camera, EXIF) fetched on demand for the info overlay
    var assetDetail by remember { mutableStateOf<AssetDetailDto?>(null) }
    val detailCache = remember { mutableMapOf<String, AssetDetailDto>() }
    LaunchedEffect(currentAsset?.id, gridShown) {
        if (gridShown) return@LaunchedEffect   // detail is only for the fullscreen info overlay
        val a = currentAsset ?: run { assetDetail = null; return@LaunchedEffect }
        detailCache[a.id]?.let { assetDetail = it; return@LaunchedEffect }
        assetDetail = null
        runCatching { client.assetDetail(a.id) }
            .onSuccess {
                detailCache[a.id] = it
                if (currentAsset?.id == a.id) assetDetail = it
            }
            .onFailure { Log.w("ImmichTV", "asset detail failed for ${a.id}: ${it.message}") }
    }

    fun showGrid() {
        gridShown = true
        remoteAsset = null
        slideshowOn = false
        stripShown = false
        infoShown = false
    }

    fun moveManual(delta: Int) {
        if (assets.isEmpty()) return
        slideshowOn = false
        remoteAsset = null
        gridShown = false
        index = (index + delta).coerceIn(0, assets.size - 1)
    }

    fun advanceSlideshow() {
        if (assets.isEmpty()) return
        remoteAsset = null
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
                        videoError = null
                        gridShown = false   // phone-driven viewing is fullscreen
                        val ctx = cmd.context
                        fun jumpWithin(bucket: String?) {
                            val pos = liveAssets().indexOfFirst { it.id == id }
                            if (pos >= 0) {
                                remoteAsset = null
                                index = pos
                            } else {
                                remoteAsset = overrideAsset(id, cmd.assetType)
                                pendingShowId = id
                                pendingShowBucket = bucket
                                scope.launch { resolvePendingShow() }
                            }
                        }
                        when {
                            ctx != null && ctx.source == "search" && ctx.assets != null -> {
                                val list = ctx.assets.map { it.toAssetDto() }
                                pendingSearchAssets = list
                                months = listOf("results")
                                assetsByMonth = mapOf("results" to list)
                                remoteAsset = null
                                val pos = list.indexOfFirst { it.id == id }
                                if (pos >= 0) index = pos
                                source = LibrarySource(SourceKind.SEARCH, "Search results")
                            }
                            ctx != null && ctx.source == "album" && ctx.albumId != null -> {
                                if (source.kind == SourceKind.ALBUM && source.albumId == ctx.albumId) {
                                    jumpWithin(ctx.bucket)
                                } else {
                                    remoteAsset = overrideAsset(id, cmd.assetType)
                                    pendingShowId = id
                                    pendingShowBucket = ctx.bucket
                                    source = LibrarySource(SourceKind.ALBUM, ctx.albumName ?: "Album", ctx.albumId)
                                }
                            }
                            ctx != null && ctx.source == "favorites" -> {
                                if (source.kind == SourceKind.FAVORITES) {
                                    jumpWithin(ctx.bucket)
                                } else {
                                    remoteAsset = overrideAsset(id, cmd.assetType)
                                    pendingShowId = id
                                    pendingShowBucket = ctx.bucket
                                    source = LibrarySource(SourceKind.FAVORITES, "Favorites")
                                }
                            }
                            ctx != null && ctx.source == "timeline" -> {
                                if (source.kind == SourceKind.TIMELINE) {
                                    jumpWithin(ctx.bucket)
                                } else {
                                    remoteAsset = overrideAsset(id, cmd.assetType)
                                    pendingShowId = id
                                    pendingShowBucket = ctx.bucket
                                    source = LibrarySource(SourceKind.TIMELINE, "Timeline")
                                }
                            }
                            else -> jumpWithin(null)
                        }
                    }
                }
                "next" -> { moveManual(1) }
                "prev" -> { moveManual(-1) }
                "slideshow" -> {
                    slideshowOn = cmd.value == 1
                    if (cmd.value == 1) gridShown = false
                }
                "shuffle" -> { scope.launch { settings.setSlideshow(intervalSec, cmd.value == 1) } }
                "info" -> {
                    if (gridShown) gridShown = false
                    infoShown = !infoShown
                }
            }
        }
    )
    LaunchedEffect(Unit) {
        remote.commands.collect { cmd -> commandHandler(cmd) }
    }

    LaunchedEffect(currentAsset?.id, source, slideshowOn, shufflePref, assets.size, index, remoteAsset, certChanged) {
        val a = currentAsset
        remote.publish(
            RemoteViewerState(
                source = source.kind.name,
                label = source.label,
                index = index,
                total = assets.size,
                assetId = a?.id,
                assetType = a?.type,
                slideshow = slideshowOn,
                shuffle = shufflePref,
                certChanged = certChanged,
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    LaunchedEffect(slideshowOn, index, assets.size, intervalSec, shufflePref, gridShown) {
        if (!slideshowOn || gridShown || assets.isEmpty()) return@LaunchedEffect
        val a = currentAsset ?: return@LaunchedEffect
        if (!a.isVideo) {
            delay(intervalSec * 1000L)
            advanceSlideshow()
        }
    }

    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    BackHandler(enabled = menuShown || stripShown || infoShown || !gridShown) {
        when {
            menuShown -> menuShown = false
            stripShown -> stripShown = false
            infoShown -> infoShown = false
            else -> showGrid()   // fullscreen viewer → back to the grid
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
                            if (menuRow == 0 && menuCol == 2 && albums.isNotEmpty()) menuRow = 1 else menuShown = false
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
                                        gridShown = true
                                        menuShown = false
                                    }
                                    1 -> {
                                        source = LibrarySource(SourceKind.FAVORITES, "Favorites")
                                        index = 0
                                        gridShown = true
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
                                    gridShown = true
                                    menuShown = false
                                    menuRow = 0
                                }
                            }
                            true
                        }
                        else -> false
                    }
                } else if (gridShown) {
                    when (e.key) {
                        Key.DirectionLeft -> {
                            if (assets.isNotEmpty()) index = (index - 1).coerceIn(0, assets.size - 1)
                            true
                        }
                        Key.DirectionRight -> {
                            if (assets.isNotEmpty()) index = (index + 1).coerceIn(0, assets.size - 1)
                            true
                        }
                        Key.DirectionUp -> {
                            if (index >= gridColumns) index -= gridColumns else menuShown = true
                            true
                        }
                        Key.DirectionDown -> {
                            if (assets.isNotEmpty()) {
                                index =
                                    if (index + gridColumns < assets.size) index + gridColumns else assets.size - 1
                            }
                            true
                        }
                        Key.DirectionCenter, Key.Enter, Key.Spacebar -> {
                            if (assets.isNotEmpty()) {
                                remoteAsset = null
                                gridShown = false
                            }
                            true
                        }
                        Key.MediaPlayPause, Key.MediaPlay -> {
                            if (assets.isNotEmpty()) {
                                remoteAsset = null
                                gridShown = false
                                slideshowOn = true
                            }
                            true
                        }
                        Key.MediaPause -> { slideshowOn = false; true }
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
            certChanged && assets.isEmpty() && remoteAsset == null -> CertificateChangedMessage()
            error != null && assets.isEmpty() && remoteAsset == null -> CenteredMessage("Error: $error")
            assets.isEmpty() && remoteAsset == null -> CenteredMessage(
                if (loadingBucket != null || months.isEmpty()) "Loading library…" else "This collection is empty"
            )
            else -> {
                val safeIndex = if (assets.isEmpty()) 0 else index.coerceIn(0, assets.size - 1)
                if (gridShown) {
                    PhotoGrid(
                        assets = assets,
                        selectedIndex = safeIndex,
                        columns = gridColumns,
                        client = client,
                        imageLoader = imageLoader,
                        state = gridState,
                    )
                    LaunchedEffect(safeIndex, gridColumns) {
                        if (gridState.layoutInfo.visibleItemsInfo.none { it.index == safeIndex }) {
                            gridState.scrollToItem(safeIndex)
                        }
                    }
                } else {
                    val current = currentAsset ?: assets[safeIndex]
                    if (current.isVideo) {
                        val errorMessage = videoError
                        if (errorMessage != null) {
                            VideoErrorMessage(errorMessage)
                        } else {
                            VideoPlayer(
                                url = client.originalUrl(current.id),
                                httpClient = authedHttpClient,
                                modifier = Modifier.fillMaxSize(),
                                toggleTick = videoToggleTick,
                                onEnded = { if (slideshowOn) advanceSlideshow() },
                                onError = { message ->
                                    videoError = message
                                    if (slideshowOn) {
                                        scope.launch {
                                            delay(4000)
                                            if (videoError == message) advanceSlideshow()
                                        }
                                    }
                                },
                            )
                        }
                    } else {
                        FullscreenAsset(current, client, imageLoader, infoShown, assetDetail)
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
                }
                if (loadingBucket != null && !stripShown) {
                    Text(
                        "Loading more…",
                        style = MaterialTheme.typography.labelSmall,
                        color = Palette.TextSoft,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(16.dp)
                            .background(Color(0x9917130E), RoundedCornerShape(50))
                            .padding(horizontal = 12.dp, vertical = 5.dp),
                    )
                }
            }
        }
        AnimatedVisibility(
            visible = menuShown,
            enter = fadeIn() + slideInVertically { -it / 2 },
            exit = fadeOut() + slideOutVertically { -it / 2 },
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            val albumListState = rememberLazyListState()
            LaunchedEffect(menuRow, albumCol, albums.size, menuCol) {
                if (albums.isNotEmpty() && (menuRow == 1 || (menuRow == 0 && menuCol == 2))) {
                    albumListState.animateScrollToItem(albumCol.coerceIn(0, albums.size - 1))
                }
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            0f to Color(0xEE171208),
                            1f to Color(0x990C0A08),
                        )
                    )
                    .padding(horizontal = 32.dp)
                    .padding(top = 20.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
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
                        MenuTab(
                            label = item,
                            cursor = menuRow == 0 && menuCol == col,
                            selected = selected,
                        )
                    }
                }
                // Album strip is only shown while the Albums tab is focused
                AnimatedVisibility(
                    visible = menuCol == 2 && albums.isNotEmpty(),
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut(),
                ) {
                    LazyRow(
                        state = albumListState,
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        itemsIndexed(albums, key = { _, a -> a.id }) { col, album ->
                            AlbumChip(
                                name = album.albumName,
                                cursor = menuRow == 1 && albumCol == col,
                                active = source.kind == SourceKind.ALBUM && source.albumId == album.id,
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
        Text(text, style = MaterialTheme.typography.titleMedium, color = Palette.TextSoft)
    }
}

@Composable
private fun MenuTab(label: String, cursor: Boolean, selected: Boolean) {
    val scale by animateFloatAsState(if (cursor) 1.07f else 1f, label = "tabScale")
    Text(
        label,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = if (cursor || selected) FontWeight.SemiBold else FontWeight.Medium,
        color = when {
            cursor -> Palette.OnAccent
            selected -> Palette.AccentBright
            else -> Palette.Muted
        },
        modifier = Modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .background(
                when {
                    cursor -> Palette.Accent
                    selected -> Palette.Accent.copy(alpha = 0.12f)
                    else -> Palette.Surface2
                },
                RoundedCornerShape(50),
            )
            .then(
                if (selected && !cursor) {
                    Modifier.border(1.dp, Palette.Accent.copy(alpha = 0.5f), RoundedCornerShape(50))
                } else Modifier
            )
            .padding(horizontal = 24.dp, vertical = 9.dp),
    )
}

@Composable
private fun AlbumChip(name: String, cursor: Boolean, active: Boolean) {
    val scale by animateFloatAsState(if (cursor) 1.05f else 1f, label = "chipScale")
    Text(
        name,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = if (cursor || active) FontWeight.SemiBold else FontWeight.Normal,
        color = when {
            cursor -> Palette.OnAccent
            active -> Palette.AccentBright
            else -> Palette.TextSoft
        },
        modifier = Modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .background(
                when {
                    cursor -> Palette.Accent
                    active -> Palette.Accent.copy(alpha = 0.14f)
                    else -> Palette.Surface2
                },
                RoundedCornerShape(12.dp),
            )
            .then(
                if (active && !cursor) {
                    Modifier.border(1.dp, Palette.Accent.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                } else Modifier
            )
            .padding(horizontal = 16.dp, vertical = 9.dp),
    )
}

@Composable
private fun CertificateChangedMessage() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Server certificate changed",
            style = MaterialTheme.typography.titleLarge,
            color = Palette.Warn,
            textAlign = TextAlign.Center,
        )
        Text(
            "The certificate no longer matches the one confirmed at setup.\n" +
                "Open the phone remote and tap the banner to confirm the new one.",
            style = MaterialTheme.typography.bodyMedium,
            color = Palette.Muted,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 18.dp),
        )
    }
}

@Composable
private fun VideoErrorMessage(message: String) {
    val lines = message.split('\n')
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            lines.first(),
            style = MaterialTheme.typography.titleMedium,
            color = Palette.Text,
            textAlign = TextAlign.Center,
        )
        lines.drop(1).forEach { line ->
            Text(
                line,
                style = MaterialTheme.typography.bodyMedium,
                color = Palette.Muted,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}

@Composable
private fun FullscreenAsset(
    asset: AssetDto,
    client: dev.myimmich.tv.api.ImmichClient,
    imageLoader: ImageLoader,
    infoShown: Boolean,
    detail: AssetDetailDto? = null,
) {
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
                        if (android.os.Build.VERSION.SDK_INT >= 31) Modifier.blur(40.dp) else Modifier
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
            )
            AnimatedVisibility(
                visible = infoShown,
                enter = fadeIn() + slideInVertically { it / 3 },
                exit = fadeOut() + slideOutVertically { it / 3 },
                modifier = Modifier.align(Alignment.BottomStart),
            ) {
                InfoPanel(asset, detail)
            }
        }
    }
}

@Composable
private fun InfoPanel(asset: AssetDto, detail: AssetDetailDto?) {
    val exif = detail?.exifInfo
    // localDateTime = wall clock in the photo's own timezone (what Immich's web UI shows);
    // fileCreatedAt/bucket dates are UTC wall-clocks and display wrong outside UTC
    val dateText = detail?.localDateTime?.let { dev.myimmich.tv.api.parseWallClock(it) }
        ?.format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy  ·  HH:mm"))
        ?: (detail?.fileCreatedAt ?: asset.fileCreatedAt)?.let { formatInfoDate(it) }
    val place = listOfNotNull(exif?.city ?: asset.city, exif?.country ?: asset.country)
        .joinToString(", ")
        .takeIf { it.isNotBlank() }
    val camera = listOfNotNull(exif?.make, exif?.model)
        .joinToString(" ")
        .takeIf { it.isNotBlank() }
    // some cameras report "0.0 mm f/0.0" when the lens is unknown — hide that junk
    val junkLens = Regex("^[\\d.\\s]+mm\\s*f/[\\d.\\s]+$")
    val lens = exif?.lensModel?.takeIf { it.isNotBlank() && !junkLens.matches(it) }
    val exposure = formatExposure(exif?.exposureTime)
    val settings = listOfNotNull(
        exif?.fNumber?.let { "f/${trimNum(it)}" },
        exposure?.let { "$it s" },
        exif?.iso?.let { "ISO $it" },
        exif?.focalLength?.let { "${trimNum(it)} mm" },
    ).joinToString("  ·  ").takeIf { it.isNotBlank() }
    val resolution = exif?.exifImageWidth?.let { w ->
        exif.exifImageHeight?.let { h ->
            "${w} × $h  ·  " + trimNum(w * h / 1_000_000.0) + " MP"
        }
    }
    val duration = if (asset.isVideo) detail?.duration.toJsonDouble()?.let { formatDuration((it * 1000).toLong()) }
        ?: asset.durationMs?.let { formatDuration(it) }
    else null

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.45f to Color(0x66000000),
                    1f to Color(0xE6000000),
                )
            )
            .padding(horizontal = 48.dp, vertical = 20.dp),
    ) {
        if (duration != null) {
            Tag("VIDEO · $duration")
        }
        if (asset.isFavorite) {
            Tag("FAVORITE")
        }
        dateText?.let {
            Text(
                it,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Medium,
                color = Color.White,
            )
        }
        if (!settings.isNullOrBlank() || !resolution.isNullOrBlank()) {
            Text(
                listOfNotNull(settings, resolution).joinToString("\n"),
                style = MaterialTheme.typography.bodyLarge,
                color = Palette.TextSoft,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        camera?.let {
            Text(
                if (lens != null) "$camera  ·  $lens" else camera,
                style = MaterialTheme.typography.bodyMedium,
                color = Palette.Muted,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        place?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyLarge,
                color = Palette.TextSoft,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        detail?.originalFileName?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = Palette.MutedDim,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun Tag(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = Palette.Accent,
        modifier = Modifier
            .padding(bottom = 6.dp)
            .background(Palette.Accent.copy(alpha = 0.16f), RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

private fun trimNum(v: Double): String =
    if (v == v.toLong().toDouble()) v.toLong().toString() else String.format("%.1f", v)

/** Bucket dates may lack a timezone offset (album responses do); handle both. */
private fun formatInfoDate(raw: String): String? = runCatching {
    OffsetDateTime.parse(raw)
        .format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy  ·  HH:mm"))
}.recoverCatching {
    LocalDateTime.parse(raw)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy  ·  HH:mm"))
}.getOrNull()

private fun formatExposure(el: kotlinx.serialization.json.JsonElement?): String? {
    val prim = el as? JsonPrimitive ?: return null
    return when {
        prim.isString -> prim.content
        else -> prim.doubleOrNull?.let { v ->
            when {
                v > 0 && v < 1 -> "1/${Math.round(1 / v)}"
                else -> trimNum(v)
            }
        }
    }
}

private fun kotlinx.serialization.json.JsonElement?.toJsonDouble(): Double? =
    (this as? JsonPrimitive)?.doubleOrNull

private fun formatDuration(ms: Long): String {
    val totalSec = ms / 1000
    val m = totalSec / 60
    val s = totalSec % 60
    return "%d:%02d".format(m, s)
}

/** Borderless edge-to-edge mosaic of square thumbnails; `index` is the d-pad cursor. */
@Composable
private fun PhotoGrid(
    assets: List<AssetDto>,
    selectedIndex: Int,
    columns: Int,
    client: dev.myimmich.tv.api.ImmichClient,
    imageLoader: ImageLoader,
    state: LazyGridState,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        state = state,
        modifier = Modifier.fillMaxSize(),
    ) {
        items(assets.size, key = { assets[it].id }) { i ->
            val asset = assets[i]
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f),
            ) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(dev.myimmich.tv.api.ImmichThumb(client.smallThumbUrl(asset.id)))
                        .memoryCacheKey("thumb-${asset.id}")
                        .build(),
                    contentDescription = null,
                    imageLoader = imageLoader,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                if (asset.isVideo) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(30.dp)
                            .background(Color(0x8C000000), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("▶", color = Palette.Text, fontSize = 12.sp)
                    }
                }
                if (i == selectedIndex) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .border(3.dp, Palette.Accent),
                    )
                }
            }
        }
    }
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
            .background(Color(0xCC0C0A08))
            .padding(vertical = 12.dp, horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        itemsIndexed(assets, key = { _, a -> a.id }) { i, asset ->
            val selected = i == selectedIndex
            Box(
                modifier = Modifier
                    .width(168.dp)
                    .height(100.dp)
                    .padding(3.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Palette.Surface)
                    .then(
                        if (selected) Modifier.border(2.dp, Palette.Accent, RoundedCornerShape(8.dp))
                        else Modifier
                    ),
            ) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(dev.myimmich.tv.api.ImmichThumb(client.smallThumbUrl(asset.id)))
                        .memoryCacheKey("thumb-${asset.id}")
                        .build(),
                    contentDescription = null,
                    imageLoader = imageLoader,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                if (asset.isVideo) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(32.dp)
                            .background(Color(0x8C000000), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("▶", color = Palette.Text, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}
