package com.danielealbano.androidremotecontrolmcp.personal

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/** No commands or output are logged: backup operations can touch account secrets. */
class RootShell @Inject constructor() {
    data class Result(val code: Int, val output: String)

    suspend fun execute(script: String): Result = withContext(Dispatchers.IO) {
        val process = ProcessBuilder("su", "-c", "sh").redirectErrorStream(true).start()
        try {
            coroutineScope {
                val reader = async(Dispatchers.IO) {
                    runCatching { process.inputStream.bufferedReader().use { it.readText().takeLast(16_384) } }.getOrDefault("")
                }
                try {
                    process.outputStream.bufferedWriter().use { it.write(script); it.newLine() }
                    val ended = runInterruptible { process.waitFor(45, TimeUnit.SECONDS) }
                    if (!ended) { process.destroyForcibly(); process.inputStream.close() }
                    Result(if (ended) process.exitValue() else -1, reader.await())
                } finally {
                    if (process.isAlive) process.destroyForcibly()
                    process.inputStream.close()
                }
            }
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }
}
