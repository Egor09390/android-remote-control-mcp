package com.danielealbano.androidremotecontrolmcp.services.tunnel

import android.util.Base64
import android.util.Log
import com.danielealbano.androidremotecontrolmcp.data.model.ServerConfig
import com.danielealbano.androidremotecontrolmcp.data.model.TunnelEndpoint
import com.danielealbano.androidremotecontrolmcp.data.model.TunnelProviderType
import com.danielealbano.androidremotecontrolmcp.data.model.TunnelStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.net.URI
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@Serializable
internal data class VpsTunnelPacket(
    val type: String,
    val id: String,
    val method: String = "",
    val path: String = "",
    val headers: Map<String, String> = emptyMap(),
    val body: String = "",
    val status: Int = 200,
)

/** Phone-initiated WSS reverse tunnel; MCP auth remains enforced on the phone. */
class SelfHostedTunnelProvider @Inject constructor() : TunnelProvider {
    private val state = MutableStateFlow<TunnelStatus>(TunnelStatus.Disconnected)
    override val status: StateFlow<TunnelStatus> = state.asStateFlow()
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val websocketClient = OkHttpClient.Builder().pingInterval(25, TimeUnit.SECONDS).build()
    private val localClient = OkHttpClient.Builder().callTimeout(180, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true }
    @Volatile private var stopped = true
    @Volatile private var socket: WebSocket? = null
    private var baseUrl = ""
    private var secret = ""
    private var port = 8080
    private var retries = 0
    private var reconnectJob: Job? = null
    private val artemisBridges = mutableListOf<ArtemisAdbBridge>()

    override suspend fun start(localPort: Int, config: ServerConfig) {
        mutex.withLock {
            check(stopped) { "VPS tunnel is already running" }
            val url = config.selfHostedTunnelUrl.trim().trimEnd('/')
            val uri = runCatching { URI(url) }.getOrNull()
            if (uri?.scheme != "https" || uri.host.isNullOrBlank() ||
                !uri.rawPath.isNullOrEmpty() || uri.rawQuery != null ||
                uri.rawFragment != null || uri.userInfo != null
            ) {
                state.value = TunnelStatus.Error("Enter valid VPS HTTPS domain without /mcp")
                return
            }
            if (config.selfHostedTunnelToken.length < 32) {
                state.value = TunnelStatus.Error("Missing VPS tunnel secret")
                return
            }
            baseUrl = url
            secret = config.selfHostedTunnelToken
            port = localPort
            stopped = false
            retries = 0
            state.value = TunnelStatus.Connecting
            connect()
            // Start a distinct outbound binary tunnel for the official Artemis ADB backend.
            // Port 0 disables a bridge. Enabling the port alone cannot grant ADB permissions.
            if (config.artemisAdbPort in 1..65535) {
                ArtemisAdbBridge(
                    websocketClient, scope, baseUrl, secret, config.artemisAdbPort, "/adb/device",
                ).also { artemisBridges.add(it); it.start() }
            }
            if (config.artemisPairingPort in 1..65535) {
                ArtemisAdbBridge(
                    websocketClient, scope, baseUrl, secret, config.artemisPairingPort, "/adb/pair",
                ).also { artemisBridges.add(it); it.start() }
            }
        }
    }

    private fun connect() {
        if (stopped) return
        val request = Request.Builder()
            .url("wss" + baseUrl.removePrefix("https") + "/agent")
            .header("Authorization", "Bearer " + secret)
            .build()
        socket = websocketClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (stopped) {
                    webSocket.close(1000, "Stopped")
                    return
                }
                retries = 0
                state.value = TunnelStatus.Connected(
                    endpoints = listOf(TunnelEndpoint(baseUrl, valid = true)),
                    providerType = TunnelProviderType.SELF_HOSTED,
                )
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (stopped) return
                scope.launch {
                    val message = runCatching { json.decodeFromString<VpsTunnelPacket>(text) }.getOrNull()
                        ?: return@launch
                    if (message.type == "request") forward(webSocket, message)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "VPS tunnel disconnected: " + t.javaClass.simpleName)
                reconnect(webSocket)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                reconnect(webSocket)
            }
        })
    }

    private fun reconnect(closed: WebSocket) {
        if (stopped || socket !== closed) return
        state.value = TunnelStatus.Connecting
        reconnectJob?.cancel()
        val seconds = (1L shl retries.coerceAtMost(5)).coerceAtMost(30)
        retries++
        reconnectJob = scope.launch {
            delay(seconds * 1000)
            if (!stopped && socket === closed) connect()
        }
    }

    private fun forward(websocket: WebSocket, packet: VpsTunnelPacket) {
        var statusCode = 502
        var headers = emptyMap<String, String>()
        var body = ByteArray(0)
        try {
            require(packet.path.startsWith("/") && !packet.path.startsWith("//"))
            val payload = Base64.decode(packet.body, Base64.DEFAULT)
            require(payload.size <= MAX_BODY)
            val method = packet.method.uppercase()
            val hasBody = method in setOf("POST", "PUT", "PATCH") || payload.isNotEmpty()
            val req = Request.Builder().url("http://127.0.0.1:" + port + packet.path)
            packet.headers.forEach { (name, value) ->
                if (name.lowercase() !in HOP_HEADERS) req.header(name, value)
            }
            localClient.newCall(
                req.method(method, if (hasBody) payload.toRequestBody(null) else null).build(),
            ).execute().use { result ->
                statusCode = result.code
                headers = result.headers.toMultimap()
                    .filterKeys { it.lowercase() !in HOP_HEADERS }
                    .mapValues { it.value.firstOrNull().orEmpty() }
                body = result.body?.bytes() ?: ByteArray(0)
                if (body.size > MAX_BODY) {
                    statusCode = 502
                    headers = emptyMap()
                    body = ByteArray(0)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "VPS MCP forwarding failed: " + e.javaClass.simpleName)
        }
        if (!stopped) {
            websocket.send(
                json.encodeToString(
                    VpsTunnelPacket(
                        type = "response", id = packet.id, status = statusCode,
                        headers = headers, body = Base64.encodeToString(body, Base64.NO_WRAP),
                    ),
                ),
            )
        }
    }

    override suspend fun stop() {
        mutex.withLock {
            stopped = true
            reconnectJob?.cancel()
            artemisBridges.forEach { it.stop() }
            artemisBridges.clear()
            socket?.cancel()
            socket = null
            state.value = TunnelStatus.Disconnected
        }
    }

    private companion object {
        const val TAG = "MCP:SelfHosted"
        const val MAX_BODY = 12 * 1024 * 1024
        val HOP_HEADERS = setOf("host", "connection", "upgrade", "proxy-connection",
            "keep-alive", "transfer-encoding", "te", "trailer", "content-length")
    }
}
