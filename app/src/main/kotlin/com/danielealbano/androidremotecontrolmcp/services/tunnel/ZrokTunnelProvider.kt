package com.danielealbano.androidremotecontrolmcp.services.tunnel

import android.content.Context
import android.os.Build
import android.util.Log
import com.danielealbano.androidremotecontrolmcp.data.model.ServerConfig
import com.danielealbano.androidremotecontrolmcp.data.model.TunnelEndpoint
import com.danielealbano.androidremotecontrolmcp.data.model.TunnelProviderType
import com.danielealbano.androidremotecontrolmcp.data.model.TunnelStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * Native Android arm64 zrok2 v2 public HTTP share.
 *
 * The zrok2 executable is built against Android Bionic by CI and installed as a
 * native library under the app's executable nativeLibraryDir (not copied into
 * app-private writable storage). The environment identity lives in app-private
 * filesDir/zrok-home/.zrok2, never on shared/external storage.
 *
 * Public shares are separate from private OpenZiti shares: ChatGPT cannot use
 * a zrok private share because it does not run a zrok access client.
 */
class ZrokTunnelProvider
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) : TunnelProvider {
        private val _status = MutableStateFlow<TunnelStatus>(TunnelStatus.Disconnected)
        override val status: StateFlow<TunnelStatus> = _status.asStateFlow()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val mutex = Mutex()
        private var process: Process? = null
        private var readerJob: Job? = null
        private var monitorJob: Job? = null

        override suspend fun start(localPort: Int, config: ServerConfig) {
            mutex.withLock {
                check(process == null) { "zrok share is already running" }
                if (Build.SUPPORTED_ABIS?.contains("arm64-v8a") != true) {
                    _status.value = TunnelStatus.Error("zrok2 requires Android arm64")
                    return
                }
                val binary = File(context.applicationInfo.nativeLibraryDir, "libzrok2.so")
                if (!binary.isFile || !binary.canExecute()) {
                    _status.value = TunnelStatus.Error("zrok2 Android binary is missing or not executable")
                    return
                }
                val name = config.zrokReservedName.trim()
                if (name.isNotEmpty() && !RESERVED_NAME.matches(name)) {
                    _status.value = TunnelStatus.Error("zrok name must be 3–40 lowercase letters, digits or hyphens")
                    return
                }
                _status.value = TunnelStatus.Connecting

                val home = File(context.filesDir, "zrok-home")
                if (!home.isDirectory && !home.mkdirs()) {
                    _status.value = TunnelStatus.Error("Cannot create private zrok environment")
                    return
                }
                val environment = File(home, ".zrok2/environment.json")
                if (!environment.isFile) {
                    val token = config.zrokEnableToken.trim()
                    if (token.isEmpty()) {
                        _status.value = TunnelStatus.Error("Enter your zrok2 account enable token")
                        return
                    }
                    // The token is never put in a log line or shared URL. The one-time
                    // subprocess runs under the app's UID and a private HOME.
                    val exit = runSetup(binary, home, listOf("enable", token))
                    if (exit != 0 || !environment.isFile) {
                        _status.value = TunnelStatus.Error("zrok2 environment enrollment failed (check account token and network)")
                        return
                    }
                }

                if (name.isNotEmpty()) {
                    // Creating an already-reserved name fails harmlessly; share public
                    // below is authoritative for whether we own the requested name.
                    runSetup(binary, home, listOf("create", "name", "-n", "public", name))
                }
                try {
                    val args = mutableListOf(binary.absolutePath, "share", "public", "--headless")
                    if (name.isNotEmpty()) args.addAll(listOf("-n", "public:$name"))
                    args.add("http://127.0.0.1:$localPort")
                    val pb = ProcessBuilder(args)
                        .directory(home)
                        .redirectErrorStream(true)
                    pb.environment()["HOME"] = home.absolutePath
                    pb.environment()["TMPDIR"] = context.cacheDir.absolutePath
                    val proc = pb.start()
                    process = proc
                    readerJob = scope.launch {
                        try {
                            proc.inputStream.bufferedReader().use { reader ->
                                while (isActive) {
                                    val line = reader.readLine() ?: break
                                    // Never log raw zrok2 output: it can include identity or tokens.
                                    val url = URL_REGEX.find(line)?.value
                                    if (url != null && process === proc) {
                                        _status.value = TunnelStatus.Connected(
                                            endpoints = listOf(TunnelEndpoint(url, true)),
                                            providerType = TunnelProviderType.ZROK,
                                        )
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            if (isActive) Log.w(TAG, "zrok2 output reader stopped", e)
                        }
                    }
                    monitorJob = scope.launch {
                        val exitCode = try { proc.waitFor() } catch (_: InterruptedException) { -1 }
                        if (process === proc) {
                            process = null
                            _status.value = TunnelStatus.Error(
                                "zrok2 share exited (code $exitCode). Check account, network and reserved name.",
                            )
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Could not start zrok2", e)
                    process = null
                    _status.value = TunnelStatus.Error("Could not launch zrok2 Android binary")
                }
            }
        }

        private fun runSetup(binary: File, home: File, command: List<String>): Int {
            return try {
                val proc = ProcessBuilder(listOf(binary.absolutePath) + command)
                    .directory(home)
                    .redirectErrorStream(true)
                    .redirectOutput(File(context.cacheDir, "zrok-setup-output.tmp"))
                    .apply {
                        environment()["HOME"] = home.absolutePath
                        environment()["TMPDIR"] = context.cacheDir.absolutePath
                    }.start()
                val finished = proc.waitFor(60, TimeUnit.SECONDS)
                if (!finished) {
                    proc.destroyForcibly()
                    -1
                } else {
                    proc.exitValue()
                }
            } catch (e: Exception) {
                Log.w(TAG, "zrok2 setup command failed", e)
                -1
            } finally {
                // Setup logs may contain account material; do not retain them on disk.
                File(context.cacheDir, "zrok-setup-output.tmp").delete()
            }
        }

        override suspend fun stop() {
            mutex.withLock {
                val proc = process
                process = null
                readerJob?.cancel()
                readerJob = null
                monitorJob?.cancel()
                monitorJob = null
                if (proc != null) {
                    proc.destroy()
                    if (!proc.waitFor(5, TimeUnit.SECONDS)) proc.destroyForcibly()
                }
                _status.value = TunnelStatus.Disconnected
            }
        }

        companion object {
            private const val TAG = "MCP:Zrok"
            private val RESERVED_NAME = Regex("[a-z0-9](?:[a-z0-9-]{1,38}[a-z0-9])")
            // Only accept a public HTTPS URL from the official zrok cloud frontend.
            private val URL_REGEX = Regex("https://[a-zA-Z0-9-]+\\.share\\.zrok\\.io")
        }
    }
