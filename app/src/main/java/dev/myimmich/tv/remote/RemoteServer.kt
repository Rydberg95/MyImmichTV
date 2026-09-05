package dev.myimmich.tv.remote

import android.content.Context
import android.util.Log
import dev.myimmich.tv.api.AssetDto
import dev.myimmich.tv.api.ImmichClient
import dev.myimmich.tv.data.AppSettings
import dev.myimmich.tv.data.ServerConfig
import dev.myimmich.tv.tls.TlsSupport
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.header
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.Inet4Address
import java.net.NetworkInterface

class RemoteServer(
    private val context: Context,
    private val controller: RemoteController,
    private val settings: AppSettings,
) {
    private var engine: EmbeddedServer<*, *>? = null
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var currentConfig: ServerConfig? = null

    @Volatile
    private var proxyClient: ImmichClient? = null

    @Volatile
    private var proxyHttp: OkHttpClient? = null

    private var pendingUrl: String? = null
    private var pendingKey: String? = null
    private var pendingFingerprint: String? = null
    private var pendingCaPins: List<String> = emptyList()
    private var pendingCaIssuer: String? = null
    private var pendingCaFingerprint: String? = null

    private var webAssets: Map<String, ByteArray> = emptyMap()

    fun start() {
        if (engine != null) return
        loadWebAssets()
        Log.d("ImmichTV", "Remote server on ${lanIpAddress()}:${controller.serverPort} PIN=${controller.pin}")
        val port = controller.serverPort
        engine = embeddedServer(CIO, port = port, host = "0.0.0.0") {
            module()
        }.also { it.start(wait = false) }
    }

    fun stop() {
        engine?.stop(500, 1500)
        engine = null
    }

    fun setConfig(config: ServerConfig?) {
        currentConfig = config
        if (config == null) {
            proxyClient = null
            proxyHttp = null
        } else {
            val http = TlsSupport.buildClient(
                certFingerprint = config.certFingerprint.takeIf { it.isNotBlank() },
                trustAny = config.trustAny,
                extraAccepted = config.caPins,
            )
            proxyHttp = http
            proxyClient = ImmichClient(http, config.serverUrl, config.apiKey)
        }
    }

    private fun loadWebAssets() {
        val map = HashMap<String, ByteArray>()
        for (name in listOf("index.html", "app.js", "style.css")) {
            runCatching {
                context.assets.open("web/$name").use { it.readBytes() }
            }.onSuccess { map[name] = it }
        }
        webAssets = map
    }

    fun lanIpAddress(): String? {
        return runCatching {
            NetworkInterface.getNetworkInterfaces().asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.asSequence() }
                .filterIsInstance<Inet4Address>()
                .filter { it.isSiteLocalAddress }
                .firstOrNull()
                ?.hostAddress
        }.getOrNull()
    }

    fun pairingUrl(): String {
        val ip = lanIpAddress() ?: "192.168.50.1"
        // PIN as a query param (not a #fragment): some phone camera/scanner
        // apps strip fragments when opening a scanned URL; the SPA accepts both.
        return "http://$ip:${controller.serverPort}/?pin=${controller.pin}"
    }

    private fun Application.module() {
        install(ContentNegotiation) {
            json(json)
        }
        routing {
            get("/") { call.serveStatic("index.html", "text/html") }
            get("/app.js") { call.serveStatic("app.js", "application/javascript") }
            get("/style.css") { call.serveStatic("style.css", "text/css") }
            get("/favicon.ico") { call.respondBytes(ByteArray(0), ContentType.Image.XIcon) }

            get("/api/status") {
                call.respond(AppStatusDto(configured = currentConfig != null, name = "My Immich TV"))
            }

            post("/api/pair") {
                val body = call.receiveText()
                val submitted = runCatching {
                    json.decodeFromString<Map<String, String>>(body)["pin"].orEmpty()
                }.getOrNull().orEmpty()
                val token = controller.tryPair(submitted)
                if (token != null) {
                    call.respond(PairResponse(token = token, serverName = "My Immich TV"))
                } else {
                    call.respond(HttpStatusCode.Forbidden)
                }
            }

            post("/setup/submit") {
                val body = call.receiveText()
                val dto = runCatching {
                    json.decodeFromString<SetupSubmitDto>(body)
                }.getOrNull()
                if (dto == null || !controller.checkPin(dto.pin)) {
                    call.respond(HttpStatusCode.Forbidden)
                    return@post
                }
                submitSetup(dto.url, dto.apiKey)
                call.respond(HttpStatusCode.Accepted)
            }

            post("/setup/confirm") {
                val body = call.receiveText()
                val dto = runCatching {
                    json.decodeFromString<SetupPinDto>(body)
                }.getOrNull()
                if (dto == null || !controller.checkPin(dto.pin)) {
                    call.respond(HttpStatusCode.Forbidden)
                    return@post
                }
                val url = pendingUrl
                val key = pendingKey
                val fp = pendingFingerprint
                if (url == null || key == null) {
                    call.respond(HttpStatusCode.Conflict)
                    return@post
                }
                validateAndSave(url, key, fp, pendingCaPins)
                call.respond(HttpStatusCode.Accepted)
            }

            post("/setup/cancel") {
                val body = call.receiveText()
                val dto = runCatching {
                    json.decodeFromString<SetupPinDto>(body)
                }.getOrNull()
                if (dto == null || !controller.checkPin(dto.pin)) {
                    call.respond(HttpStatusCode.Forbidden)
                    return@post
                }
                clearPending()
                call.respond(HttpStatusCode.OK)
            }

            get("/setup/status") {
                val state = controller.setupState.value
                call.respond(
                    if (currentConfig != null) state.copy(configured = true, phase = "DONE")
                    else state
                )
            }
            get("/r/{token}/state") {
                if (!authorized(call)) return@get
                val state = controller.viewerState.value
                if (state != null) call.respond(state)
                else call.respond(
                    RemoteViewerState(source = "none", label = "Idle", index = 0, total = 0)
                )
            }
            post("/r/{token}/command") {
                if (!authorized(call)) return@post
                val body = call.receiveText()
                val command = runCatching {
                    json.decodeFromString<RemoteCommand>(body)
                }.getOrNull()
                if (command != null) {
                    controller.send(command)
                    call.respond(HttpStatusCode.OK)
                } else {
                    call.respond(HttpStatusCode.BadRequest)
                }
            }
            get("/r/{token}/settings") {
                if (!authorized(call)) return@get
                call.respond(
                    SettingsDto(
                        slideshowSeconds = settings.slideshowSeconds.first(),
                        dreamSeconds = settings.dreamSeconds.first(),
                        dreamIncludeVideos = settings.dreamIncludeVideos.first(),
                        dreamShowInfo = settings.dreamShowInfo.first(),
                        gridColumns = settings.gridColumns.first(),
                        dreamSources = settings.dreamSources.first(),
                    )
                )
            }
            post("/r/{token}/settings") {
                if (!authorized(call)) return@post
                val body = call.receiveText()
                val dto = runCatching {
                    json.decodeFromString<SettingsUpdateDto>(body)
                }.getOrNull()
                if (dto == null) {
                    call.respond(HttpStatusCode.BadRequest)
                    return@post
                }
                if (dto.slideshowSeconds != null) settings.setSlideshowSeconds(dto.slideshowSeconds)
                if (dto.dreamSeconds != null) settings.setDreamSeconds(dto.dreamSeconds)
                if (dto.dreamIncludeVideos != null) settings.setDreamIncludeVideos(dto.dreamIncludeVideos)
                if (dto.dreamShowInfo != null) settings.setDreamShowInfo(dto.dreamShowInfo)
                if (dto.gridColumns != null) settings.setGridColumns(dto.gridColumns)
                if (dto.dreamSources != null) settings.setDreamSources(dto.dreamSources)
                call.respond(
                    SettingsDto(
                        slideshowSeconds = settings.slideshowSeconds.first(),
                        dreamSeconds = settings.dreamSeconds.first(),
                        dreamIncludeVideos = settings.dreamIncludeVideos.first(),
                        dreamShowInfo = settings.dreamShowInfo.first(),
                        gridColumns = settings.gridColumns.first(),
                        dreamSources = settings.dreamSources.first(),
                    )
                )
            }
            post("/r/{token}/cert/reprobe") {
                if (!authorized(call)) return@post
                val cfg = currentConfig ?: run {
                    call.respond(HttpStatusCode.ServiceUnavailable); return@post
                }
                call.respond(certCheck(cfg))
            }
            post("/r/{token}/cert/confirm") {
                if (!authorized(call)) return@post
                val cfg = currentConfig ?: run {
                    call.respond(HttpStatusCode.ServiceUnavailable); return@post
                }
                val probe = TlsSupport.probe("${cfg.serverUrl}/api/server/ping")
                when (probe) {
                    is TlsSupport.ProbeResult.Error ->
                        call.respond(CertConfirmDto(ok = false, error = probe.message))
                    else -> {
                        // trust what the server presents right now: new leaf + its issuing CA
                        val caPins = probe.chain.drop(1)
                            .flatMap { listOf(it.fingerprint, it.spkiFingerprint) }
                            .filter { it.isNotBlank() }
                            .distinct()
                        val updated = cfg.copy(
                            certFingerprint = probe.cert.fingerprint,
                            caPins = caPins,
                        )
                        settings.saveServer(updated)
                        setConfig(updated)
                        Log.d("ImmichTV", "Certificate re-confirmed: leaf ${probe.cert.fingerprint}, ${caPins.size} CA pins")
                        call.respond(CertConfirmDto(ok = true))
                    }
                }
            }
            get("/r/{token}/buckets") {
                val client = authorizedClient(call) ?: return@get
                val albumId = call.request.queryParameters["album"]
                val favorite = call.request.queryParameters["favorite"] == "true"
                val buckets = client.timeBuckets(
                    buildMap {
                        put("size", "MONTH")
                        put("isArchived", "false")
                        if (albumId != null) put("albumId", albumId)
                        if (favorite) put("isFavorite", "true")
                    }
                )
                call.respond(buckets)
            }
            get("/r/{token}/assets") {
                val client = authorizedClient(call) ?: return@get
                val bucket = call.request.queryParameters["bucket"] ?: run {
                    call.respond(HttpStatusCode.BadRequest); return@get
                }
                val albumId = call.request.queryParameters["album"]
                val favorite = call.request.queryParameters["favorite"] == "true"
                val assets = client.bucketAssets(
                    buildMap {
                        put("size", "MONTH")
                        put("timeBucket", bucket)
                        put("isArchived", "false")
                        if (albumId != null) put("albumId", albumId)
                        if (favorite) put("isFavorite", "true")
                    }
                )
                call.respond(assets.map { it.toRemote() })
            }
            get("/r/{token}/albums") {
                val client = authorizedClient(call) ?: return@get
                val albums = client.albums()
                call.respond(albums.map { RemoteAlbum(it.id, it.albumName, it.assetCount, it.albumThumbnailAssetId) })
            }
            get("/r/{token}/search") {
                val client = authorizedClient(call) ?: return@get
                val query = call.request.queryParameters["q"].orEmpty()
                if (query.isBlank()) {
                    call.respond(emptyList<RemoteAsset>()); return@get
                }
                val assets = client.smartSearch(query)
                call.respond(assets.map { it.toRemote() })
            }
            get("/r/{token}/people") {
                val client = authorizedClient(call) ?: return@get
                val people = client.people()
                call.respond(people.map { RemotePerson(it.id, it.name ?: "Unknown") })
            }
            get("/r/{token}/thumb/{id}") {
                val config = currentConfig
                if (!authorized(call) || config == null) {
                    call.respond(HttpStatusCode.ServiceUnavailable); return@get
                }
                val id = call.parameters["id"] ?: run {
                    call.respond(HttpStatusCode.BadRequest); return@get
                }
                val size = call.request.queryParameters["size"] ?: "preview"
                val client = proxyClient ?: run {
                    call.respond(HttpStatusCode.ServiceUnavailable); return@get
                }
                val url = if (size == "thumbnail") client.smallThumbUrl(id) else client.thumbnailUrl(id)
                proxyImage(call, url, config)
            }
            get("/r/{token}/original/{id}") {
                val config = currentConfig
                if (!authorized(call) || config == null) {
                    call.respond(HttpStatusCode.ServiceUnavailable); return@get
                }
                val id = call.parameters["id"] ?: run {
                    call.respond(HttpStatusCode.BadRequest); return@get
                }
                proxyOriginal(call, proxyClient!!.originalUrl(id), config)
            }
        }
    }

    private fun authorized(call: ApplicationCall): Boolean {
        val token = call.parameters["token"]
        return controller.isAuthorized(token)
    }

    private suspend fun authorizedClient(call: ApplicationCall): ImmichClient? {
        if (!authorized(call)) {
            call.respond(HttpStatusCode.Forbidden)
            return null
        }
        val client = proxyClient
        if (client == null) {
            call.respond(HttpStatusCode.ServiceUnavailable)
            return null
        }
        return client
    }

    private fun clearPending() {
        pendingUrl = null
        pendingKey = null
        pendingFingerprint = null
        pendingCaPins = emptyList()
        pendingCaIssuer = null
        pendingCaFingerprint = null
        controller.updateSetup(SetupState())
    }

    private fun maskKey(key: String): String {
        if (key.length <= 4) return "...."
        return "\u2022".repeat(8) + key.takeLast(4)
    }

    private fun submitSetup(url: String, apiKey: String) {
        val trimmed = url.trim().trimEnd('/')
        scope.launch {
            if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
                controller.updateSetup(SetupState(phase = "ERROR", error = "URL must start with http:// or https://"))
                return@launch
            }
            controller.updateSetup(
                SetupState(phase = "PROBING", url = trimmed, apiKeyMasked = maskKey(apiKey))
            )
            val probe = TlsSupport.probe("$trimmed/api/server/ping")
            when (probe) {
                is TlsSupport.ProbeResult.Trusted -> validateAndSave(trimmed, apiKey, null, emptyList())
                is TlsSupport.ProbeResult.Untrusted -> {
                    pendingUrl = trimmed
                    pendingKey = apiKey
                    pendingFingerprint = probe.cert.fingerprint
                    // pin the issuing CA (DER + SPKI) so short-lived leaf rotations stay trusted
                    pendingCaPins = probe.chain.drop(1)
                        .flatMap { listOf(it.fingerprint, it.spkiFingerprint) }
                        .filter { it.isNotBlank() }
                        .distinct()
                    pendingCaIssuer = probe.top?.subject
                    pendingCaFingerprint = probe.top?.spkiFingerprint
                    controller.updateSetup(
                        SetupState(
                            phase = "AWAITING_CONFIRM",
                            url = trimmed,
                            apiKeyMasked = maskKey(apiKey),
                            fingerprint = probe.cert.fingerprint,
                            subject = probe.cert.subject,
                            issuer = probe.cert.issuer,
                            caIssuer = pendingCaIssuer,
                            caFingerprint = pendingCaFingerprint,
                        )
                    )
                }
                is TlsSupport.ProbeResult.Error -> {
                    controller.updateSetup(
                        SetupState(phase = "ERROR", url = trimmed, error = probe.message)
                    )
                }
            }
        }
    }

    private fun validateAndSave(url: String, apiKey: String, certFingerprint: String?, caPins: List<String>) {
        scope.launch {
            controller.updateSetup(
                SetupState(phase = "CONNECTING", url = url, apiKeyMasked = maskKey(apiKey))
            )
            try {
                val http = TlsSupport.buildClient(certFingerprint, false, caPins)
                val client = ImmichClient(http, url, apiKey)
                val user = client.me()
                settings.saveServer(
                    ServerConfig(
                        serverUrl = url,
                        apiKey = apiKey,
                        certFingerprint = certFingerprint.orEmpty(),
                        trustAny = false,
                        caPins = caPins,
                    )
                )
                setConfig(
                    ServerConfig(
                        serverUrl = url,
                        apiKey = apiKey,
                        certFingerprint = certFingerprint.orEmpty(),
                        trustAny = false,
                        caPins = caPins,
                    )
                )
                controller.updateSetup(
                    SetupState(
                        phase = "DONE",
                        url = url,
                        apiKeyMasked = maskKey(apiKey),
                        configured = true,
                    )
                )
                Log.d("ImmichTV", "Setup complete for ${user.name ?: user.email}")
            } catch (e: Exception) {
                controller.updateSetup(
                    SetupState(
                        phase = "ERROR",
                        url = url,
                        apiKeyMasked = maskKey(apiKey),
                        error = e.message ?: e.javaClass.simpleName,
                    )
                )
            }
        }
    }

    /**
     * Probes the configured server and reports whether its presented chain still matches
     * the stored pins. [CertCheckDto.changed] drives the phone's recovery sheet.
     */
    private fun certCheck(cfg: ServerConfig): CertCheckDto {
        val accepted = buildList {
            if (cfg.certFingerprint.isNotBlank()) add(cfg.certFingerprint)
            addAll(cfg.caPins)
        }
        return when (val probe = TlsSupport.probe("${cfg.serverUrl}/api/server/ping")) {
            is TlsSupport.ProbeResult.Error -> CertCheckDto(changed = false, error = probe.message)
            else -> CertCheckDto(
                changed = !TlsSupport.chainMatches(probe, accepted),
                fingerprint = probe.cert.fingerprint,
                subject = probe.cert.subject,
                issuer = probe.cert.issuer,
                caIssuer = probe.top?.subject,
                caFingerprint = probe.top?.spkiFingerprint,
            )
        }
    }

    private suspend fun ApplicationCall.serveStatic(name: String, mime: String) {
        val bytes = webAssets[name]
        if (bytes != null) {
            response.header("Cache-Control", "no-cache")
            respondBytes(bytes, ContentType.parse(mime))
        } else {
            respond(HttpStatusCode.NotFound)
        }
    }

    private suspend fun proxyImage(call: ApplicationCall, url: String, config: ServerConfig) {
        withContext(Dispatchers.IO) {
            val http = proxyHttp ?: run {
                call.respond(HttpStatusCode.ServiceUnavailable); return@withContext
            }
            val request = Request.Builder()
                .url(url)
                .header("x-api-key", config.apiKey)
                .build()
            http.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    call.respond(HttpStatusCode.InternalServerError)
                    return@withContext
                }
                val bytes = resp.body?.bytes() ?: ByteArray(0)
                val mime = resp.header("Content-Type") ?: "image/jpeg"
                call.response.header("Cache-Control", "max-age=86400")
                call.respondBytes(bytes, ContentType.parse(mime))
            }
        }
    }

    private suspend fun proxyOriginal(call: ApplicationCall, url: String, config: ServerConfig) {
        withContext(Dispatchers.IO) {
            val http = proxyHttp ?: run {
                call.respond(HttpStatusCode.ServiceUnavailable); return@withContext
            }
            val rangeHeader = call.request.header("Range")
            val builder = Request.Builder()
                .url(url)
                .header("x-api-key", config.apiKey)
            if (rangeHeader != null) {
                builder.header("Range", rangeHeader)
            }
            val response = http.newCall(builder.build()).execute()
            val body = response.body
            if (!response.isSuccessful || body == null) {
                response.close()
                call.respond(HttpStatusCode.InternalServerError)
                return@withContext
            }
            val status = if (response.code == 206) HttpStatusCode.PartialContent else HttpStatusCode.OK
            val mime = response.header("Content-Type") ?: "application/octet-stream"
            val length = body.contentLength()
            response.header("Content-Range")?.let { call.response.header("Content-Range", it) }
            response.header("Accept-Ranges")?.let { call.response.header("Accept-Ranges", it) }
            call.response.header("Cache-Control", "no-cache")
            val upstream = body.byteStream()
            call.respond(object : OutgoingContent.WriteChannelContent() {
                override val contentLength = if (length >= 0) length else null
                override val contentType = ContentType.parse(mime)
                override val status = status
                override suspend fun writeTo(channel: io.ktor.utils.io.ByteWriteChannel) {
                    val buffer = ByteArray(64 * 1024)
                    try {
                        while (true) {
                            val read = upstream.read(buffer)
                            if (read < 0) break
                            channel.writeFully(java.nio.ByteBuffer.wrap(buffer, 0, read))
                        }
                        channel.flush()
                    } finally {
                        response.close()
                    }
                }
            })
        }
    }

    private fun AssetDto.toRemote() = RemoteAsset(
        id = id,
        type = type,
        fileCreatedAt = fileCreatedAt,
        isFavorite = isFavorite,
        durationMs = durationMs,
        livePhotoVideoId = livePhotoVideoId,
        thumbhash = thumbhash,
        city = city,
        country = country,
    )
}
