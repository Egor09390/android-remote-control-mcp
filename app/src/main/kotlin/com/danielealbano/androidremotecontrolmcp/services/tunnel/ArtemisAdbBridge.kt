package com.danielealbano.androidremotecontrolmcp.services.tunnel

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Outbound-only ADB stream relay for an unchanged ARTEMIS engine on the private VPS network.
 *
 * Android Wireless Debugging must be enabled and paired once via Android system settings.
 * The bridge cannot grant its own ADB permissions and must never open the debugging
 * socket on a public interface.
 */
internal class ArtemisAdbBridge(
    private val client: OkHttpClient,
    private val scope: CoroutineScope,
    private val publicBaseUrl: String,
    private val secret: String,
    private val localPort: Int,
    private val path: String,
) {
    @Volatile private var stopped = false
    @Volatile private var websocket: WebSocket? = null
    @Volatile private var deviceSocket: Socket? = null
    private var readerJob: Job? = null
    private var retryJob: Job? = null
    private var attempts = 0
    private val socketLock = Any()

    fun start() {
        if (localPort !in 1..65535) return
        stopped = false
        attempts = 0
        connect()
    }

    fun stop() {
        stopped = true
        retryJob?.cancel()
        readerJob?.cancel()
        closeDevice()
        websocket?.cancel()
        websocket = null
    }

    private fun connect() {
        if (stopped) return
        val request = Request.Builder()
            .url("wss" + publicBaseUrl.removePrefix("https") + path)
            .header("Authorization", "Bearer " + secret)
            .build()
        websocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                attempts = 0
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                when (text) {
                    "open" -> openDevice(webSocket)
                    "close" -> closeDevice()
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                // OkHttp invokes messages sequentially on one reader thread, preserving ADB byte order.
                val local = deviceSocket ?: return
                try {
                    synchronized(socketLock) {
                        if (deviceSocket === local) {
                            local.getOutputStream().write(bytes.toByteArray())
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "ADB localhost write failed", e)
                    closeDevice()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "VPS ADB bridge disconnected", t)
                retry(webSocket)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                retry(webSocket)
            }
        })
    }

    private fun openDevice(ws: WebSocket) {
        readerJob?.cancel()
        closeDevice()
        readerJob = scope.launch(Dispatchers.IO) {
            val local = Socket()
            try {
                // On-device loopback only; the Android app never accepts inbound ADB connections.
                local.connect(InetSocketAddress("127.0.0.1", localPort), 5000)
                if (stopped || ws !== websocket) {
                    local.close()
                    return@launch
                }
                synchronized(socketLock) { deviceSocket = local }
                ws.send("ready")
                val input = local.getInputStream()
                val buffer = ByteArray(16 * 1024)
                while (!stopped && ws === websocket) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count > 0 && !ws.send(buffer.toByteString(0, count))) break
                }
            } catch (e: Exception) {
                Log.w(TAG, "Wireless debugging port is unavailable; enable and pair it", e)
            } finally {
                synchronized(socketLock) {
                    if (deviceSocket === local) deviceSocket = null
                }
                runCatching { local.close() }
            }
        }
    }

    private fun closeDevice() {
        val local = synchronized(socketLock) {
            val sock = deviceSocket
            deviceSocket = null
            sock
        }
        runCatching { local?.close() }
    }

    private fun retry(failed: WebSocket) {
        if (stopped || websocket !== failed) return
        closeDevice()
        readerJob?.cancel()
        retryJob?.cancel()
        val seconds = (1L shl attempts.coerceAtMost(5)).coerceAtMost(30L)
        attempts += 1
        retryJob = scope.launch(Dispatchers.IO) {
            delay(seconds * 1000)
            if (!stopped && websocket === failed) connect()
        }
    }

    private companion object {
        const val TAG = "MCP:ArtemisADB"
    }
}
