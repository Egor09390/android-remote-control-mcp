package com.danielealbano.androidremotecontrolmcp.services.tunnel

import com.danielealbano.androidremotecontrolmcp.data.model.ServerConfig
import com.danielealbano.androidremotecontrolmcp.data.model.TunnelEndpoint
import com.danielealbano.androidremotecontrolmcp.data.model.TunnelProviderType
import com.danielealbano.androidremotecontrolmcp.data.model.TunnelStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import javax.inject.Inject
import javax.net.ssl.HttpsURLConnection

/** Official on-device CloudPub agent. MCP keeps its own OAuth/bearer authorization. */
class CloudPubTunnelProvider
    @Inject
    constructor(
        private val client: CloudPubClient,
    ) : TunnelProvider {
        private val _status = MutableStateFlow<TunnelStatus>(TunnelStatus.Disconnected)
        override val status: StateFlow<TunnelStatus> = _status.asStateFlow()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val mutex = Mutex()
        private var sessionJob: Job? = null

        @Volatile private var process: Process? = null

        override suspend fun start(localPort: Int, config: ServerConfig) = withContext(Dispatchers.IO) {
            mutex.withLock {
                check(sessionJob == null) { "CloudPub is already running" }
                if (config.cloudpubToken.isBlank()) {
                    _status.value = TunnelStatus.Error("CloudPub token is required")
                    return@withLock
                }
                val binary = client.binaryPath()
                if (binary == null) {
                    _status.value = TunnelStatus.Error("CloudPub Android ARM64 client is not packaged for this device")
                    return@withLock
                }
                _status.value = TunnelStatus.Connecting
                val configured = try {
                    client.configure(binary, config.cloudpubToken)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    false
                }
                if (!configured) {
                    _status.value = TunnelStatus.Error("Could not save CloudPub configuration")
                    return@withLock
                }
                sessionJob = scope.launch { supervise(binary, localPort) }
            }
        }

        private suspend fun supervise(binary: String, localPort: Int) {
            var backoff = RETRY_INITIAL_MS
            while (kotlin.coroutines.coroutineContext.isActive) {
                _status.value = TunnelStatus.Connecting
                try {
                    val exitCode = runSession(binary, localPort)
                    _status.value = TunnelStatus.Error("CloudPub exited (code $exitCode); reconnecting")
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Child output and exception messages can contain account data. Do not log them.
                    _status.value = TunnelStatus.Error("CloudPub connection failed; reconnecting")
                }
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(RETRY_MAX_MS)
            }
        }

        private suspend fun runSession(binary: String, localPort: Int): Int = coroutineScope {
            val proc = client.publish(binary, localPort)
            process = proc
            val endpoint = MutableStateFlow<String?>(null)
            val reader = launch(Dispatchers.IO) {
                proc.inputStream.bufferedReader().use { input ->
                    while (isActive) {
                        val line = input.readLine() ?: break
                        publishedUrl(line, localPort)?.let { endpoint.value = it }
                        if (line.contains("Network error", ignoreCase = true) || line.contains("Ошибка сети")) {
                            _status.value = TunnelStatus.Connecting
                        }
                    }
                }
            }
            val probe = launch(Dispatchers.IO) {
                while (isActive) {
                    val url = endpoint.value
                    if (url != null) {
                        _status.value = if (isHealthy(url)) {
                            TunnelStatus.Connected(
                                endpoints = listOf(TunnelEndpoint(url = url, valid = true)),
                                providerType = TunnelProviderType.CLOUDPUB,
                            )
                        } else {
                            TunnelStatus.Error("CloudPub public MCP endpoint is unavailable; checking again")
                        }
                    }
                    delay(PROBE_INTERVAL_MS)
                }
            }
            try {
                while (proc.isAlive) delay(PROCESS_POLL_MS)
                proc.exitValue()
            } finally {
                proc.destroy()
                if (proc.isAlive) proc.destroyForcibly()
                reader.cancel()
                probe.cancel()
                if (process === proc) process = null
            }
        }

        override suspend fun stop() {
            mutex.withLock {
                sessionJob?.cancel()
                process?.destroy()
                process?.let { if (it.isAlive) it.destroyForcibly() }
                sessionJob?.cancelAndJoin()
                sessionJob = null
                process = null
                _status.value = TunnelStatus.Disconnected
            }
        }

        private fun isHealthy(baseUrl: String): Boolean = runCatching {
            val connection = URI("$baseUrl/health").toURL().openConnection() as HttpsURLConnection
            try {
                connection.connectTimeout = PROBE_TIMEOUT_MS
                connection.readTimeout = PROBE_TIMEOUT_MS
                connection.instanceFollowRedirects = false
                connection.responseCode == 200 &&
                    connection.contentType?.contains("application/json", ignoreCase = true) == true &&
                    connection.inputStream.bufferedReader().use { reader ->
                        val body = StringBuilder()
                        val buffer = CharArray(MAX_HEALTH_LENGTH + 1)
                        while (body.length <= MAX_HEALTH_LENGTH) {
                            val count = reader.read(buffer, 0, buffer.size - body.length)
                            if (count < 0) break
                            body.append(buffer, 0, count)
                        }
                        body.length <= MAX_HEALTH_LENGTH && healthyResponse(body.toString())
                    }
            } finally {
                connection.disconnect()
            }
        }.getOrDefault(false)

        companion object {
            private const val RETRY_INITIAL_MS = 2_000L
            private const val RETRY_MAX_MS = 30_000L
            private const val PROCESS_POLL_MS = 500L
            private const val PROBE_INTERVAL_MS = 10_000L
            private const val PROBE_TIMEOUT_MS = 5_000
            private const val MAX_HEALTH_LENGTH = 4_096
            private val ANSI = Regex("\u001B\\[[0-9;]*[A-Za-z]")

            /** Read only a publication acknowledgement for our exact origin, never a help/login URL. */
            internal fun publishedUrl(line: String, localPort: Int): String? {
                val text = line.replace(ANSI, "")
                if (!text.contains("Service published:", ignoreCase = true) && !text.contains("Сервис опубликован:")) {
                    return null
                }
                val parts = text.split(" -> ", limit = 2)
                if (parts.size != 2 || !parts[0].trimEnd().endsWith(":$localPort")) return null
                val origin = parts[0].substringAfterLast(" ").trim()
                if (origin != "http://127.0.0.1:$localPort" && origin != "http://localhost:$localPort") return null
                val url = runCatching { URI(parts[1].trim()) }.getOrNull() ?: return null
                val host = url.host?.lowercase() ?: return null
                return if (
                    url.scheme == "https" && host.endsWith(".cloudpub.ru") &&
                    url.userInfo == null && url.query == null && url.fragment == null &&
                    (url.port == -1 || url.port == 443) && (url.path.isNullOrEmpty() || url.path == "/")
                ) {
                    "https://$host"
                } else {
                    null
                }
            }

            /** HTML interstitials, login pages and malformed responses must never count as connected. */
            internal fun healthyResponse(body: String): Boolean = runCatching {
                Json.parseToJsonElement(body).jsonObject["status"]?.jsonPrimitive?.content == "healthy"
            }.getOrDefault(false)
        }
    }
