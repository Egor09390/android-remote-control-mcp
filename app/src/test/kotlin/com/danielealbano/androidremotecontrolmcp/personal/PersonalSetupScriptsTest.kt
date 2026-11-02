package com.danielealbano.androidremotecontrolmcp.personal

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class PersonalSetupScriptsTest {
    @TempDir lateinit var temp: File
    private val pkg = "com.example.control.ownvps"

    private fun runScript(script: String): String {
        val process = ProcessBuilder("sh", "-c", "id() { echo 0; }; chown() { :; }; restorecon() { :; };\n" + script)
            .redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(0, process.waitFor(), output)
        return output
    }

    @Test
    fun `backup and restore preserve account state but never logs or arbitrary app files`() {
        val data = File(temp, "data with spaces").apply { mkdirs() }
        val files = File(data, "files/datastore").apply { mkdirs() }
        val cloud = File(data, "no_backup/cloudpub").apply { mkdirs() }
        File(files, "settings.preferences_pb").writeText("private-settings")
        File(files, "oauth_clients.preferences_pb").writeText("authorized-client")
        File(cloud, "client.toml").writeText("private-cloudpub-identity")
        File(data, "files/screenshot.png").writeText("must-not-be-backed-up")
        val root = File(temp, "root").path
        assertTrue(runScript(PersonalSetupScripts.backup(data.path, pkg, 10, false, 12345, root)).contains("BACKUP_OK"))
        data.deleteRecursively()
        data.mkdirs()
        assertTrue(runScript(PersonalSetupScripts.backup(data.path, pkg, 10, true, 12345, root)).contains("RESTORED_OK"))
        assertEquals("private-settings", File(data, "files/datastore/settings.preferences_pb").readText())
        assertEquals("authorized-client", File(data, "files/datastore/oauth_clients.preferences_pb").readText())
        assertEquals("private-cloudpub-identity", File(data, "no_backup/cloudpub/client.toml").readText())
        assertFalse(File(data, "files/screenshot.png").exists())
    }

    @Test
    fun `existing settings are never overwritten by a stale backup`() {
        val data = File(temp, "data").apply { mkdirs() }
        File(data, "files/datastore").mkdirs()
        val prefs = File(data, "files/datastore/settings.preferences_pb")
        prefs.writeText("old")
        val root = File(temp, "root").path
        runScript(PersonalSetupScripts.backup(data.path, pkg, 0, false, 12345, root))
        prefs.writeText("new-live-settings")
        val output = runScript(PersonalSetupScripts.backup(data.path, pkg, 0, true, 12345, root))
        assertFalse(output.contains("RESTORED_OK"))
        assertEquals("new-live-settings", prefs.readText())
    }

    @Test
    fun `interrupted snapshot swap recovers the last complete backup`() {
        val root = File(temp, "root")
        val previous = File(root, "user-0/$pkg/previous").apply { mkdirs() }
        File(previous, "settings.preferences_pb").writeText("last-complete")
        val data = File(temp, "data").apply { mkdirs() }
        runScript(PersonalSetupScripts.backup(data.path, pkg, 0, true, 12345, root.path))
        assertEquals("last-complete", File(data, "files/datastore/settings.preferences_pb").readText())
    }

    @Test
    fun `grant script targets the current user preserves other services and grants location in order`() {
        val bin = File(temp, "bin").apply { mkdirs() }
        val outputFile = File(temp, "commands")
        val settings = File(bin, "settings")
        settings.writeText("#!/bin/sh\nif [ \"\$3\" = get ]; then echo 'other.app/.Reader'; else printf '%s\\n' \"\$*\" >> '${outputFile.path}'; fi\n")
        settings.setExecutable(true)
        listOf("pm", "cmd", "dumpsys", "am").forEach { name ->
            File(bin, name).apply { writeText("#!/bin/sh\nprintf '%s\\n' \"\$*\" >> '${outputFile.path}'\n"); setExecutable(true) }
        }
        val script = PersonalSetupScripts.grants(pkg, 10, listOf("android.permission.ACCESS_BACKGROUND_LOCATION", "android.permission.CAMERA", "android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION"))
        runScript("PATH='${bin.path}':\$PATH\n" + script)
        val commands = outputFile.readText()
        assertTrue(commands.contains("--user 10"))
        assertFalse(commands.contains("--user 0"))
        assertTrue(commands.contains("other.app/.Reader:$pkg/"))
        assertTrue(commands.indexOf("ACCESS_COARSE_LOCATION") < commands.indexOf("ACCESS_FINE_LOCATION"))
        assertTrue(commands.indexOf("ACCESS_FINE_LOCATION") < commands.indexOf("ACCESS_BACKGROUND_LOCATION"))
        assertFalse(commands.contains("setenforce"))
    }

    @Test
    fun `shell values cannot inject commands`() {
        val value = "space ' quote \$(touch SHOULD_NOT_EXIST)"
        val proc = ProcessBuilder("sh", "-c", "printf '%s' " + PersonalSetupScripts.quote(value)).start()
        assertEquals(value, proc.inputStream.bufferedReader().readText())
        assertEquals(0, proc.waitFor())
        assertThrows(IllegalArgumentException::class.java) { PersonalSetupScripts.grants("bad;package", 0, emptyList()) }
    }
}
