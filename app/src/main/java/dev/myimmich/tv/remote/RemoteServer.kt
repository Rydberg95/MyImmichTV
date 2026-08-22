package dev.myimmich.tv.remote

import android.content.Context
import dev.myimmich.tv.api.AssetDto
import dev.myimmich.tv.api.ImmichClient
import dev.myimmich.tv.data.ServerConfig
import dev.myimmich.tv.tls.TlsSupport
import io.ktor.http.ContentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.writeFully
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.header
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.Inet4Address
import java.net.NetworkInterface

class RemoteServer(
    private val context: Context,
    private val config: ServerConfig,
    private val controller: RemoteController,
) {
    private var engine: EmbeddedServer<*, *>? = null
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val httpClient: OkHttpClient = TlsSupport.buildClient(
        certFingerprint = config.certFingerprint.takeIf { it.isNotBlank() },
        trustAny = config.trustAny,
    )

    private val client = ImmichClient(
        http = httpClient,
        serverUrl = config.serverUrl,
        apiKey = config.apiKey,
    )

    private var webAssets: Map<String, ByteArray> = emptyMap()

    fun start() {
        if (engine != null) return
        loadWebAssets()
        android.util.Log.d("ImmichTV", "Remote server on ${lanIpAddress()}:${controller.serverPort} PIN=${controller.pin}")
        val port = controller.serverPort
        engine = embeddedServer(CIO, port = port, host = "0.0.0.0") {
            module()
        }.also { it.start(wait = false) }
    }

    fun stop() {
        engine?.stop(500, 1500)
        engine = null
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
        return "http://$ip:${controller.serverPort}/#${controller.pin}"
    }

    private fun Application.module() {
        install(io.ktor.server.plugins.contentnegotiation.ContentNegotiation) {
            json(json)
        }
        routing {
            get("/") {
                call.serveStatic("index.html", "text/html")
            }
            get("/app.js") {
                call.serveStatic("app.js", "application/javascript")
            }
            get("/style.css") {
                call.serveStatic("style.css", "text/css")
            }
            get("/favicon.ico") {
                call.respondBytes(ByteArray(0), ContentType.Image.XIcon)
            }
            post("/api/pair") {
                val body = call.receiveText()
                val submitted = runCatching {
                    json.decodeFromString<Map<String, String>>(body)["pin"].orEmpty()
                }.getOrNull().orEmpty()
                val token = controller.tryPair(submitted)
                if (token != null) {
                    call.respond(
                        PairResponse(token = token, serverName = "My Immich TV")
                    )
                } else {
                    call.respond(HttpStatusCode.Forbidden)
                }
            }
            get("/api/pair/{pin}") {
                val submitted = call.parameters["pin"].orEmpty()
                val token = controller.tryPair(submitted)
                if (token != null) {
                    call.respond(
                        PairResponse(token = token, serverName = "My Immich TV")
                    )
                } else {
                    call.respond(HttpStatusCode.Forbidden)
                }
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
            get("/r/{token}/buckets") {
                if (!authorized(call)) return@get
                val albumId = call.request.queryParameters["album"]
                val favorite = call.request.queryParameters["favorite"] == "true"
                val buckets = withContext(Dispatchers.IO) {
                    client.timeBuckets(
                        buildMap {
                            put("size", "MONTH")
                            put("isArchived", "false")
                            if (albumId != null) put("albumId", albumId)
                            if (favorite) put("isFavorite", "true")
                        }
                    )
                }
                call.respond(buckets)
            }
            get("/r/{token}/assets") {
                if (!authorized(call)) return@get
                val bucket = call.request.queryParameters["bucket"] ?: run {
                    call.respond(HttpStatusCode.BadRequest); return@get
                }
                val albumId = call.request.queryParameters["album"]
                val favorite = call.request.queryParameters["favorite"] == "true"
                val assets = withContext(Dispatchers.IO) {
                    client.bucketAssets(
                        buildMap {
                            put("size", "MONTH")
                            put("timeBucket", bucket)
                            put("isArchived", "false")
                            if (albumId != null) put("albumId", albumId)
                            if (favorite) put("isFavorite", "true")
                        }
                    )
                }
                call.respond(assets.map { it.toRemote() })
            }
            get("/r/{token}/albums") {
                if (!authorized(call)) return@get
                val albums = withContext(Dispatchers.IO) { client.albums() }
                call.respond(albums.map { RemoteAlbum(it.id, it.albumName, it.assetCount) })
            }
            get("/r/{token}/search") {
                if (!authorized(call)) return@get
                val query = call.request.queryParameters["q"].orEmpty()
                if (query.isBlank()) {
                    call.respond(emptyList<RemoteAsset>()); return@get
                }
                val assets = withContext(Dispatchers.IO) { client.smartSearch(query) }
                call.respond(assets.map { it.toRemote() })
            }
            get("/r/{token}/people") {
                if (!authorized(call)) return@get
                val people = withContext(Dispatchers.IO) { client.people() }
                call.respond(people.map { RemotePerson(it.id, it.name ?: "Unknown") })
            }
            get("/r/{token}/thumb/{id}") {
                if (!authorized(call)) return@get
                val id = call.parameters["id"] ?: run {
                    call.respond(HttpStatusCode.BadRequest); return@get
                }
                val size = call.request.queryParameters["size"] ?: "preview"
                val url = if (size == "thumbnail") client.smallThumbUrl(id) else client.thumbnailUrl(id)
                proxyImage(call, url)
            }
            get("/r/{token}/original/{id}") {
                if (!authorized(call)) return@get
                val id = call.parameters["id"] ?: run {
                    call.respond(HttpStatusCode.BadRequest); return@get
                }
                proxyOriginal(call, client.originalUrl(id))
            }
        }
    }

    private fun authorized(call: ApplicationCall): Boolean {
        val token = call.parameters["token"]
        return controller.isAuthorized(token)
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

    private suspend fun proxyImage(call: ApplicationCall, url: String) {
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(url)
                .header("x-api-key", config.apiKey)
                .build()
            httpClient.newCall(request).execute().use { resp ->
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

    private suspend fun proxyOriginal(call: ApplicationCall, url: String) {
        withContext(Dispatchers.IO) {
            val rangeHeader = call.request.header("Range")
            val builder = Request.Builder()
                .url(url)
                .header("x-api-key", config.apiKey)
            if (rangeHeader != null) {
                builder.header("Range", rangeHeader)
            }
            val response = httpClient.newCall(builder.build()).execute()
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
