package com.danielealbano.androidremotecontrolmcp.services.tunnel

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

/**
 * Opt-in only: configure system adbd to listen on localhost, never LAN.
 *
 * AOSP supports service.adb.listen_addrs; Samsung/One UI may reject it. In that
 * case we FAIL CLOSED and restore the original properties. Never fall back to
 * service.adb.tcp.port=5555, which typically exposes ADB on every interface.
 */
internal object RootAdbManager {
    const val PORT = 5555
    private const val TAG = "MCP:RootADB"

    suspend fun enableLoopback(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val script = """
                #!/system/bin/sh
                if [ "$(id -u)" != "0" ]; then echo "Root permission denied"; exit 17; fi
                OLD_LISTEN="$(getprop service.adb.listen_addrs)"
                OLD_TCP="$(getprop service.adb.tcp.port)"
                rollback() {
                  setprop service.adb.listen_addrs "${'$'}OLD_LISTEN"
                  setprop service.adb.tcp.port "${'$'}OLD_TCP"
                  stop adbd
                  start adbd
                }
                setprop service.adb.listen_addrs tcp:localhost:5555 || exit 18
                setprop service.adb.tcp.port 0 || { rollback; exit 18; }
                stop adbd || { rollback; exit 19; }
                start adbd || { rollback; exit 19; }
                if [ "$(getprop service.adb.listen_addrs)" != "tcp:localhost:5555" ]; then
                  rollback
                  echo "One UI ignored loopback-only ADB property"
                  exit 20
                fi
                i=0
                valid=0
                while [ "${'$'}i" -lt 10 ]; do
                  i=$((i+1))
                  found=0
                  invalid=0
                  for table in /proc/net/tcp /proc/net/tcp6; do
                    [ -r "${'$'}table" ] || continue
                    while read sl addr remote st rest; do
                      case "${'$'}addr" in
                        *:15B3)
                          if [ "${'$'}st" = "0A" ]; then
                            case "${'$'}addr" in
                              0100007F:15B3|00000000000000000000000001000000:15B3) found=1 ;;
                              *) invalid=1 ;;
                            esac
                          fi
                        ;;
                      esac
                    done < "${'$'}table"
                  done
                  if [ "${'$'}invalid" = 1 ]; then break; fi
                  if [ "${'$'}found" = 1 ]; then valid=1; break; fi
                  sleep 1
                done
                if [ "${'$'}valid" != 1 ] || [ "${'$'}invalid" = 1 ]; then
                  rollback
                  echo "Refused: cannot prove ADB listens only on loopback"
                  exit 21
                fi
                echo "ROOT_ADB_LOOPBACK_OK"
            """.trimIndent()
            val process = ProcessBuilder("su", "-c", "sh")
                .redirectErrorStream(true)
                .start()
            process.outputStream.bufferedWriter().use { writer ->
                writer.write(script)
                writer.newLine()
            }
            if (!process.waitFor(40, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                error("Root authorization timed out; check the root manager")
            }
            val output = process.inputStream.bufferedReader().use { it.readText().takeLast(1200) }
            if (process.exitValue() != 0 || "ROOT_ADB_LOOPBACK_OK" !in output) {
                error("Root ADB not enabled: " + output.trim().take(300))
            }
            Socket().use {
                it.connect(InetSocketAddress("127.0.0.1", PORT), 2500)
            }
            Log.i(TAG, "ADB verified only on 127.0.0.1:5555")
            Unit
        }
    }
}
