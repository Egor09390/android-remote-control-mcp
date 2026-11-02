package com.danielealbano.androidremotecontrolmcp.services.tunnel

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/** Runs the official Android executable from the package manager's executable native directory. */
class CloudPubClient
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) {
        fun binaryPath(): String? =
            File(context.applicationInfo.nativeLibraryDir, "libcloudpub.so")
                .takeIf { it.isFile && it.canExecute() }
                ?.absolutePath

        suspend fun configure(binary: String, token: String): Boolean {
            val process = builder(binary, listOf("set", "token", token))
                .redirectOutput(ProcessBuilder.Redirect.to(File("/dev/null")))
                .start()
            return try {
                runInterruptible(Dispatchers.IO) {
                    process.waitFor(CONFIGURE_TIMEOUT_SECONDS, TimeUnit.SECONDS) && process.exitValue() == 0
                }
            } finally {
                if (process.isAlive) process.destroyForcibly()
                // Contains the API token and persistent agent identity; never export or reset on stop.
                File(configDirectory(), "client.toml").apply {
                    setReadable(false, false)
                    setWritable(false, false)
                    setReadable(true, true)
                    setWritable(true, true)
                }
            }
        }

        fun publish(binary: String, localPort: Int): Process =
            builder(binary, listOf("publish", "--name", "Android MCP", "--auth", "none", "http", "127.0.0.1:$localPort"))
                .start()

        private fun configDirectory(): File = File(context.noBackupFilesDir, "cloudpub").apply { mkdirs() }

        private fun builder(binary: String, command: List<String>): ProcessBuilder {
            val directory = configDirectory()
            return ProcessBuilder(
                listOf(binary, "--conf", File(directory, "client.toml").absolutePath, "--log-level", "error") + command,
            ).apply {
                directory(directory)
                redirectErrorStream(true)
                // Rust's dirs crate needs explicit Android app-private cache/config paths.
                environment()["XDG_CONFIG_HOME"] = context.noBackupFilesDir.absolutePath
                environment()["XDG_CACHE_HOME"] = context.cacheDir.absolutePath
                environment()["TMPDIR"] = context.cacheDir.absolutePath
                environment()["NO_COLOR"] = "1"
            }
        }

        companion object {
            private const val CONFIGURE_TIMEOUT_SECONDS = 10L
        }
    }
