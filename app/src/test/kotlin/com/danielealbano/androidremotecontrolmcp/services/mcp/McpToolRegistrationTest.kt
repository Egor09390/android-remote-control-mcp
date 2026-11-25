package com.danielealbano.androidremotecontrolmcp.services.mcp

import com.danielealbano.androidremotecontrolmcp.data.model.ServerConfig
import com.danielealbano.androidremotecontrolmcp.data.model.ToolPermissionsConfig
import com.danielealbano.androidremotecontrolmcp.integration.McpIntegrationTestHelper
import com.danielealbano.androidremotecontrolmcp.mcp.tools.LocalArtemisTools
import com.danielealbano.androidremotecontrolmcp.mcp.tools.McpToolUtils
import com.danielealbano.androidremotecontrolmcp.services.accessibility.CompactTreeFormatter
import com.danielealbano.androidremotecontrolmcp.services.accessibility.WebViewNodeMerger
import com.danielealbano.androidremotecontrolmcp.services.screencapture.ScreenshotRedactor
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("McpToolRegistration")
class McpToolRegistrationTest {
    private val deps = McpIntegrationTestHelper.createMockDependencies()
    private val registration =
        McpToolRegistration(
            deps.serverLog,
            deps.inFlightToolCalls,
            AccessibilityToolDeps(
                deps.actionExecutor,
                deps.accessibilityServiceProvider,
                deps.treeParser,
                deps.elementFinder,
                deps.nodeCache,
                deps.typeInputController,
            ),
            ScreenToolDeps(
                deps.screenCaptureProvider,
                CompactTreeFormatter(),
                deps.screenshotAnnotator,
                deps.screenshotEncoder,
                deps.screenStateSnapshotCache,
                WebViewNodeMerger(),
            ),
            PrivacyToolDeps(deps.privacyToolGate, deps.placeholderSubstitutor, ScreenshotRedactor()),
            DeviceToolDeps(
                deps.storageLocationProvider,
                deps.fileOperationProvider,
                deps.appManager,
                deps.cameraProvider,
                deps.intentDispatcher,
                deps.locationProvider,
            ),
            InboxToolDeps(deps.notificationProvider, deps.sharedContentInbox, deps.ephemeralFileLinkService),
        )

    /** The tool names [McpToolRegistration.registerAll] puts on a fresh SDK server. */
    private fun registered(
        deviceSlug: String = "",
        perms: ToolPermissionsConfig = ToolPermissionsConfig(),
        config: ServerConfig = ServerConfig(),
        startHeadless: (() -> String?)? = null,
        treeFirst: Boolean = false,
    ): Set<String> {
        val server =
            Server(
                serverInfo = Implementation(name = "test", version = "test"),
                options =
                    ServerOptions(
                        capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false)),
                    ),
            )
        registration.registerAll(
            server,
            McpToolUtils.buildToolNamePrefix(deviceSlug),
            perms,
            ServerConfig.DEFAULT_FILE_SIZE_LIMIT_MB,
            toolCallIndicatorEnabled = false,
            localArtemis = LocalArtemisTools.forConfig(config, startHeadless) { config },
            treeFirst = treeFirst,
        ) { McpIntegrationTestHelper.TEST_BASE_URL }
        return server.tools.keys.toSet()
    }

    @Test
    fun `registers the same tools as the integration wiring`() {
        val expected = McpIntegrationTestHelper.createSdkServer(deps).tools.keys

        val actual = registered()

        assertTrue(expected.isNotEmpty())
        assertEquals(expected, actual)
    }

    @Test
    fun `every tool carries the device prefix`() {
        val prefix = McpToolUtils.buildToolNamePrefix(DEVICE_SLUG)

        val actual = registered(deviceSlug = DEVICE_SLUG)

        assertEquals(McpIntegrationTestHelper.createSdkServer(deps, DEVICE_SLUG).tools.keys, actual)
        assertTrue(actual.all { it.startsWith(prefix) }, "$actual")
    }

    @Test
    fun `a disabled tool is left out`() {
        val all = registered()
        val name = McpToolUtils.buildToolNamePrefix("") + DISABLED_TOOL

        val actual = registered(perms = ToolPermissionsConfig(disabledTools = setOf(DISABLED_TOOL)))

        assertTrue(name in all, "$all")
        assertFalse(name in actual, "$actual")
        assertEquals(all - name, actual)
    }

    @Test
    fun `ARTEMIS tools require opt in and authenticated MCP`() {
        val config = ServerConfig(localArtemisToken = "a".repeat(64))
        val baseline = registered()
        val artemis = setOf("status", "run_task", "manage_task", "inspect_trace")
            .map { "android_artemis_$it" }
            .toSet()

        assertEquals(baseline + artemis, registered(config = config))
        assertEquals(baseline, registered(config = config.copy(oauthEnabled = false)))
        assertEquals(
            baseline,
            registered(config = config.copy(privacyModeConfig = config.privacyModeConfig.copy(enabled = true))),
        )
    }

    @Test
    fun `headless route exposes device actions to the MCP client without autonomous task tools`() {
        val config = ServerConfig(localArtemisToken = "a".repeat(64))
        val newTools = registered(config = config, startHeadless = { null }) - registered()

        assertEquals(
            setOf("status", "tap", "swipe", "type_text", "press_key")
                .map { "android_artemis_$it" }.toSet(),
            newTools,
        )
    }

    @Test
    fun `tree first build exposes a screenshot only while screenshot permission is enabled`() {
        val prefix = McpToolUtils.buildToolNamePrefix(DEVICE_SLUG)
        val baseline = registered(deviceSlug = DEVICE_SLUG)
        val screenshotsDisabled = ToolPermissionsConfig(
            disabledParams = mapOf("get_screen_state" to setOf("include_screenshot")),
        )

        assertEquals(baseline + "${prefix}capture_screenshot", registered(deviceSlug = DEVICE_SLUG, treeFirst = true))
        assertEquals(baseline, registered(deviceSlug = DEVICE_SLUG, perms = screenshotsDisabled, treeFirst = true))
    }

    private companion object {
        const val DEVICE_SLUG = "pixel"
        const val DISABLED_TOOL = "press_home"
    }
}
