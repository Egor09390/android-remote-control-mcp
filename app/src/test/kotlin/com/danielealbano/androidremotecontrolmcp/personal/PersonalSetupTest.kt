package com.danielealbano.androidremotecontrolmcp.personal

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.PowerManager
import com.danielealbano.androidremotecontrolmcp.data.repository.SettingsRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.confirmVerified
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class PersonalSetupTest {
    @TempDir lateinit var temp: File

    @Test
    fun `root denial never reads or creates settings before restoration can run`() = runBlocking {
        val context = mockk<Context>(relaxed = true)
        val manager = mockk<PackageManager>(relaxed = true)
        val power = mockk<PowerManager>(relaxed = true)
        every { context.packageName } returns "com.example.control"
        every { context.applicationInfo } returns ApplicationInfo().apply { dataDir = temp.path }
        every { context.packageManager } returns manager
        every { context.getSystemService(PowerManager::class.java) } returns power
        every { manager.getPackageInfo(any<String>(), any<PackageManager.PackageInfoFlags>()) } returns
            PackageInfo().apply { requestedPermissions = emptyArray() }
        val settings = mockk<SettingsRepository>(relaxed = true)
        val shell = mockk<RootShell>()
        coEvery { shell.execute(any()) } returns RootShell.Result(17, "")
        val setup = PersonalSetup(context, settings, shell)
        setup.initialize()
        val state = withTimeout(5_000) { setup.state.first { !it.busy } }
        assertFalse(state.initialized)
        assertFalse(state.items.first { it.id == "root" }.granted)
        assertNotNull(state.message)
        assertFalse(File(temp, "files/datastore/settings.preferences_pb").exists())
        confirmVerified(settings)
    }
}
