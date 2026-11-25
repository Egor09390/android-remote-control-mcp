package com.danielealbano.androidremotecontrolmcp.services.mcp

import com.danielealbano.androidremotecontrolmcp.BuildConfig
import com.danielealbano.androidremotecontrolmcp.data.model.ToolPermissionsConfig
import com.danielealbano.androidremotecontrolmcp.data.repository.ServerLogRepository
import com.danielealbano.androidremotecontrolmcp.mcp.InFlightToolCalls
import com.danielealbano.androidremotecontrolmcp.mcp.tools.GetScreenHistoryHandler
import com.danielealbano.androidremotecontrolmcp.mcp.tools.LocalArtemisTools
import com.danielealbano.androidremotecontrolmcp.mcp.tools.LoggedToolRegistrar
import com.danielealbano.androidremotecontrolmcp.mcp.tools.ReferenceCountedToolCallIndicator
import com.danielealbano.androidremotecontrolmcp.mcp.tools.registerAppManagementTools
import com.danielealbano.androidremotecontrolmcp.mcp.tools.registerCameraTools
import com.danielealbano.androidremotecontrolmcp.mcp.tools.registerFileTools
import com.danielealbano.androidremotecontrolmcp.mcp.tools.registerGestureTools
import com.danielealbano.androidremotecontrolmcp.mcp.tools.registerIntentTools
import com.danielealbano.androidremotecontrolmcp.mcp.tools.registerLocationTools
import com.danielealbano.androidremotecontrolmcp.mcp.tools.registerNodeActionTools
import com.danielealbano.androidremotecontrolmcp.mcp.tools.registerNotificationTools
import com.danielealbano.androidremotecontrolmcp.mcp.tools.registerScreenIntrospectionTools
import com.danielealbano.androidremotecontrolmcp.mcp.tools.registerSharingTools
import com.danielealbano.androidremotecontrolmcp.mcp.tools.registerSystemActionTools
import com.danielealbano.androidremotecontrolmcp.mcp.tools.registerTextInputTools
import com.danielealbano.androidremotecontrolmcp.mcp.tools.registerTouchActionTools
import com.danielealbano.androidremotecontrolmcp.mcp.tools.registerUtilityTools
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityToolCallIndicator
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ScreenHistory
import io.modelcontextprotocol.kotlin.sdk.server.Server
import javax.inject.Inject

/**
 * Registers every MCP tool on the SDK server with the tools' dependencies. Kept out of [McpServerService] so the
 * service stays focused on its lifecycle.
 */
class McpToolRegistration
    @Inject
    constructor(
        private val serverLogRepository: ServerLogRepository,
        private val inFlightToolCalls: InFlightToolCalls,
        private val accessibility: AccessibilityToolDeps,
        private val screen: ScreenToolDeps,
        private val privacy: PrivacyToolDeps,
        private val device: DeviceToolDeps,
        private val inbox: InboxToolDeps,
    ) {
        /** Registers every MCP tool on [server]; [currentBaseUrl] is the URL the sharing tools hand out. */
        fun registerAll(
            server: Server,
            toolNamePrefix: String,
            perms: ToolPermissionsConfig,
            fileSizeLimitMb: Int,
            toolCallIndicatorEnabled: Boolean,
            localArtemis: LocalArtemisTools? = null,
            treeFirst: Boolean = BuildConfig.PERSONAL_BUILD,
            screenHistory: ScreenHistory? = null,
            currentBaseUrl: () -> String,
        ) {
            val toolCallIndicator = ReferenceCountedToolCallIndicator(AccessibilityToolCallIndicator())
            toolCallIndicator.setEnabled(toolCallIndicatorEnabled)
            val registrar =
                LoggedToolRegistrar(
                    server,
                    serverLogRepository,
                    toolCallIndicator,
                    inFlightToolCalls,
                )
            registerScreenTools(registrar, toolNamePrefix, perms, treeFirst, screenHistory != null)
            if (screenHistory != null) {
                GetScreenHistoryHandler(screenHistory, screen.compactTreeFormatter, privacy.privacyToolGate)
                    .register(registrar, toolNamePrefix)
            }
            registerAccessibilityToolBundle(registrar, toolNamePrefix, perms)
            val d = device
            registerFileTools(registrar, d.storageLocationProvider, d.fileOperationProvider, toolNamePrefix, perms)
            registerAppManagementTools(
                registrar,
                d.appManager,
                privacy.privacyToolGate,
                toolNamePrefix,
                perms,
                historyAvailable = screenHistory != null,
            )
            registerCameraTools(registrar, d.cameraProvider, d.fileOperationProvider, toolNamePrefix, perms)
            registerIntentTools(registrar, d.intentDispatcher, toolNamePrefix, perms)
            registerNotificationTools(
                registrar,
                inbox.notificationProvider,
                privacy.privacyToolGate,
                privacy.placeholderSubstitutor,
                toolNamePrefix,
                perms,
            )
            registerLocationTools(registrar, d.locationProvider, privacy.privacyToolGate, toolNamePrefix, perms)
            registerSharingBundle(registrar, toolNamePrefix, perms, fileSizeLimitMb, currentBaseUrl)
            localArtemis?.register(registrar, toolNamePrefix, perms)
        }

        private fun registerAccessibilityToolBundle(
            registrar: LoggedToolRegistrar,
            toolNamePrefix: String,
            perms: ToolPermissionsConfig,
        ) {
            val a = accessibility
            registerSystemActionTools(
                registrar,
                a.actionExecutor,
                a.accessibilityServiceProvider,
                toolNamePrefix,
                perms,
            )
            registerTouchActionTools(registrar, a.actionExecutor, toolNamePrefix, perms)
            registerGestureTools(registrar, a.actionExecutor, toolNamePrefix, perms)
            registerNodeActionTools(
                registrar,
                a.treeParser,
                a.elementFinder,
                a.actionExecutor,
                a.accessibilityServiceProvider,
                a.nodeCache,
                privacy.privacyToolGate,
                privacy.placeholderSubstitutor,
                toolNamePrefix,
                perms,
            )
            registerTextInputTools(
                registrar,
                a.treeParser,
                a.elementFinder,
                a.actionExecutor,
                a.accessibilityServiceProvider,
                a.typeInputController,
                a.nodeCache,
                privacy.privacyToolGate,
                privacy.placeholderSubstitutor,
                toolNamePrefix,
                perms,
            )
            registerUtilityTools(
                registrar,
                a.treeParser,
                a.elementFinder,
                a.accessibilityServiceProvider,
                a.nodeCache,
                privacy.privacyToolGate,
                privacy.placeholderSubstitutor,
                toolNamePrefix,
                perms,
            )
        }

        private fun registerScreenTools(
            registrar: LoggedToolRegistrar,
            toolNamePrefix: String,
            perms: ToolPermissionsConfig,
            treeFirst: Boolean,
            historyAvailable: Boolean,
        ) {
            registerScreenIntrospectionTools(
                registrar,
                accessibility.treeParser,
                accessibility.accessibilityServiceProvider,
                screen.screenCaptureProvider,
                screen.compactTreeFormatter,
                screen.screenshotAnnotator,
                screen.screenshotEncoder,
                accessibility.nodeCache,
                screen.screenStateSnapshotCache,
                screen.webViewNodeMerger,
                privacy.privacyToolGate,
                privacy.screenshotRedactor,
                toolNamePrefix,
                perms,
                treeFirst,
                historyAvailable,
            )
        }

        private fun registerSharingBundle(
            registrar: LoggedToolRegistrar,
            toolNamePrefix: String,
            perms: ToolPermissionsConfig,
            fileSizeLimitMb: Int,
            currentBaseUrl: () -> String,
        ) {
            registerSharingTools(
                registrar,
                inbox.sharedContentInbox,
                inbox.ephemeralFileLinkService,
                device.fileOperationProvider,
                fileSizeLimitMb,
                currentBaseUrl,
                privacy.privacyToolGate,
                toolNamePrefix,
                perms,
            )
        }
    }
