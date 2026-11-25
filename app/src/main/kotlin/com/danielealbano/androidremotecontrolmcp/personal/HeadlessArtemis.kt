package com.danielealbano.androidremotecontrolmcp.personal

import android.content.Context
import android.os.Process
import com.danielealbano.androidremotecontrolmcp.BuildConfig
import com.danielealbano.androidremotecontrolmcp.data.repository.SettingsRepository
import com.danielealbano.androidremotecontrolmcp.mcp.tools.LocalArtemisTools
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.DigestInputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

enum class ArtemisStatus(val label: String) {
    Waiting("ARTEMIS запускается по команде MCP"),
    Downloading("Скачиваю среду ARTEMIS…"),
    Installing("Устанавливаю ARTEMIS…"),
    Starting("Запускаю ARTEMIS…"),
    Ready("ARTEMIS готов"),
    RootRequired("Подтвердите root в менеджере"),
    AdbError("Телефон отклонил локальное подключение ADB"),
    RuntimeError("Сбой запуска ARTEMIS. Попробуйте ещё раз"),
    NetworkError("Не удалось скачать ARTEMIS. Проверьте интернет"),
}

/** Manages the on-device CLI as part of the MCP foreground service lifecycle. */
@Singleton
class HeadlessArtemis @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val root: RootShell,
) {
    private val requested = MutableStateFlow(false)
    private val retry = MutableStateFlow(0L)
    private val _status = MutableStateFlow(ArtemisStatus.Waiting)
    val status = _status.asStateFlow()
    private val lease = Mutex()
    private val base get() = File(context.noBackupFilesDir, "artemis")
    private val stateDir get() = File(base, "state")

    fun requestStart(): String? {
        requested.value = true
        return when (_status.value) {
            ArtemisStatus.Ready -> null
            ArtemisStatus.RootRequired, ArtemisStatus.AdbError, ArtemisStatus.RuntimeError, ArtemisStatus.NetworkError ->
                "ARTEMIS could not start on this phone. Use the standard Android MCP tools."
            else -> "The phone is preparing ARTEMIS. The standard Android MCP tools are ready."
        }
    }

    fun retry() {
        retry.value += 1
    }

    suspend fun ensureBridgeToken() {
        val token = settings.getServerConfig().localArtemisToken
        if (!LocalArtemisTools.isValidToken(token)) {
            val bytes = ByteArray(32).apply { SecureRandom().nextBytes(this) }
            settings.updateLocalArtemisToken(bytes.toHex())
        }
    }

    suspend fun supervise(token: String) {
        combine(requested, settings.serverConfig, retry) { wanted, config, attempt ->
            val allowed = !config.privacyModeConfig.enabled &&
                (config.oauthEnabled || (config.bearerTokenEnabled && config.bearerToken.isNotBlank()))
            (wanted && allowed) to attempt
        }.distinctUntilChanged().collectLatest { (wanted, _) ->
            if (!wanted) {
                _status.value = ArtemisStatus.Waiting
            } else {
                lease.withLock { openRuntime(token) }
            }
        }
    }

    private suspend fun openRuntime(token: String) {
        try {
            check(base.mkdirs() || base.isDirectory)
            check(stateDir.mkdirs() || stateDir.isDirectory)
            val image = installRuntime()
            File(stateDir, "bridge_token").writeText(token)
            _status.value = ArtemisStatus.Starting
            runSupervisor(image)
        } catch (e: CancellationException) {
            _status.value = ArtemisStatus.Waiting
            throw e
        } catch (_: Exception) {
            if (_status.value == ArtemisStatus.Downloading) {
                _status.value = ArtemisStatus.NetworkError
            } else {
                _status.value = ArtemisStatus.RuntimeError
            }
        }
    }

    private suspend fun installRuntime(): File {
        val hash = BuildConfig.ARTEMIS_RUNTIME_SHA256
        val image = File(base, "rootfs-$hash")
        if (File(image, "artemis-launcher").isFile) return image
        _status.value = ArtemisStatus.Downloading
        val archive = File(base, "runtime.tar.gz")
        val digest = MessageDigest.getInstance("SHA-256")
        val request = Request.Builder().url(RUNTIME_URL).build()
        try {
            http.newCall(request).execute().use { response ->
                check(response.isSuccessful)
                DigestInputStream(response.body.byteStream(), digest).use { input ->
                    archive.outputStream().buffered().use { output ->
                        val buffer = ByteArray(128 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                    }
                }
            }
            check(digest.digest().toHex() == hash)
            _status.value = ArtemisStatus.Installing
            val incoming = File(base, "extract-${UUID.randomUUID()}")
            val result = root.execute(
                """
                set -eu
                umask 022
                incoming=${quote(incoming.path)}
                mkdir "${'$'}incoming"
                trap 'rm -rf -- "${'$'}incoming"' EXIT
                printf '%s  %s\n' ${quote(hash)} ${quote(archive.path)} | sha256sum -c -
                tar -xzf ${quote(archive.path)} -C "${'$'}incoming"
                test -x "${'$'}incoming/artemis-launcher"
                mv "${'$'}incoming" ${quote(image.path)}
                """.trimIndent(),
                timeoutSeconds = 540,
            )
            check(result.code == 0)
            return image
        } finally {
            archive.delete()
        }
    }

    private suspend fun runSupervisor(image: File) = coroutineScope {
        val script = File(base, "supervise.sh")
        context.assets.open("artemis-supervise.sh").use { input -> script.outputStream().use { input.copyTo(it) } }
        val stop = File(base, "stop-${UUID.randomUUID()}")
        val command = listOf(
            "/system/bin/sh", script.path, image.path, stateDir.path, Process.myUid().toString(),
            Process.myPid().toString(), stop.path,
        ).joinToString(" ", transform = ::quote)
        val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        val reader: Job = launch(Dispatchers.IO) {
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    when {
                        line == "ARTEMIS_ROOT_REQUIRED" -> _status.value = ArtemisStatus.RootRequired
                        line.startsWith("ARTEMIS_ADB_") && line != "ARTEMIS_ADB_READY" -> _status.value = ArtemisStatus.AdbError
                        line == "ARTEMIS_RUNTIME_UNSUPPORTED" -> _status.value = ArtemisStatus.RuntimeError
                        "Uvicorn running on http://127.0.0.1:18765" in line -> _status.value = ArtemisStatus.Ready
                    }
                }
            }
        }
        try {
            runInterruptible { process.waitFor() }
            reader.join()
            if (_status.value == ArtemisStatus.Starting || _status.value == ArtemisStatus.Ready) {
                _status.value = ArtemisStatus.RuntimeError
            }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                stop.createNewFile()
                if (!process.waitFor(20, TimeUnit.SECONDS)) process.destroy()
                process.inputStream.close()
                stop.delete()
            }
        }
    }

    companion object {
        val available get() = BuildConfig.PERSONAL_BUILD && BuildConfig.ARTEMIS_RUNTIME_SHA256.isNotEmpty()
        private const val RUNTIME_URL = "https://github.com/Egor09390/android-remote-control-mcp/releases/download/artemis-mcp-preview/artemis-runtime-ubuntu24.04-arm64.tar.gz"
        private val http = OkHttpClient.Builder().readTimeout(45, TimeUnit.SECONDS).build()
        private fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"
        private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    }
}
