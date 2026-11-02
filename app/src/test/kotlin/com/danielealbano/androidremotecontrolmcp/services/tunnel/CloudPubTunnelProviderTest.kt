package com.danielealbano.androidremotecontrolmcp.services.tunnel

import com.danielealbano.androidremotecontrolmcp.data.model.ServerConfig
import com.danielealbano.androidremotecontrolmcp.data.model.TunnelStatus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CloudPubTunnelProviderTest {
    @Test
    fun `parses only our publication in English and Russian output`() {
        assertEquals(
            "https://s23.cloudpub.ru",
            CloudPubTunnelProvider.publishedUrl(
                "Service published: http://127.0.0.1:61001 -> https://s23.cloudpub.ru:443",
                61001,
            ),
        )
        assertEquals(
            "https://s23.cloudpub.ru",
            CloudPubTunnelProvider.publishedUrl(
                "Сервис опубликован: http://localhost:8080 -> https://s23.cloudpub.ru",
                8080,
            ),
        )
    }

    @Test
    fun `does not mistake login pages other origins or unsafe URLs for MCP`() {
        val prefix = "Service published: http://127.0.0.1:8080 -> "
        listOf(
            "https://cloudpub.ru/dashboard",
            "https://s23.cloudpub.ru.attacker.example",
            "http://s23.cloudpub.ru",
            "https://user:password@s23.cloudpub.ru",
            "https://s23.cloudpub.ru/?token=secret",
            "https://s23.cloudpub.ru:8443",
        ).forEach { assertNull(CloudPubTunnelProvider.publishedUrl(prefix + it, 8080)) }
        assertNull(CloudPubTunnelProvider.publishedUrl(prefix + "https://s23.cloudpub.ru", 61001))
        assertNull(CloudPubTunnelProvider.publishedUrl("Upgrade at https://s23.cloudpub.ru", 8080))
    }

    @Test
    fun `a browser warning or error page never passes the readiness check`() {
        assertTrue(CloudPubTunnelProvider.healthyResponse("""{"status":"healthy","version":"1"}"""))
        assertFalse(CloudPubTunnelProvider.healthyResponse("<html>Click continue</html>"))
        assertFalse(CloudPubTunnelProvider.healthyResponse("""{"status":"offline"}"""))
        assertFalse(CloudPubTunnelProvider.healthyResponse("{}"))
    }

    @Test
    fun `missing token cannot start or configure the native client`() = runTest {
        val client = mockk<CloudPubClient>()
        val provider = CloudPubTunnelProvider(client)
        provider.start(8080, ServerConfig())
        assertTrue(provider.status.value is TunnelStatus.Error)
        coVerify(exactly = 0) { client.configure(any(), any()) }
        provider.stop()
        assertEquals(TunnelStatus.Disconnected, provider.status.value)
    }

    @Test
    fun `configuration failure never reports connected or leaks the token`() = runTest {
        val client = mockk<CloudPubClient>()
        every { client.binaryPath() } returns "/native/libcloudpub.so"
        coEvery { client.configure(any(), any()) } throws IllegalStateException("secret-cloudpub-token")
        val provider = CloudPubTunnelProvider(client)
        provider.start(8080, ServerConfig(cloudpubToken = "secret-cloudpub-token"))
        assertTrue(provider.status.value is TunnelStatus.Error)
        assertFalse(provider.status.value.toString().contains("secret-cloudpub-token"))
        provider.stop()
    }
}
