package com.danielealbano.androidremotecontrolmcp.mcp.tools

import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import com.danielealbano.androidremotecontrolmcp.privacy.PrivacyToolGate
import com.danielealbano.androidremotecontrolmcp.services.accessibility.CompactTreeFormatter
import com.danielealbano.androidremotecontrolmcp.services.accessibility.MultiWindowResult
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ScreenHistory
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ScreenObservation
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ScreenStateFormatOptions
import com.danielealbano.androidremotecontrolmcp.services.accessibility.WindowData
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.time.Instant

internal class GetScreenHistoryHandler(
    private val history: ScreenHistory,
    private val formatter: CompactTreeFormatter,
    private val privacy: PrivacyToolGate,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        val includeNodes = McpToolUtils.optionalBoolean(arguments, "include_nodes", false)
        val defaultLimit = if (includeNodes) DEFAULT_TREE_LIMIT else DEFAULT_LIMIT
        val limit = McpToolUtils.optionalLong(arguments, "limit", defaultLimit.toLong())
        if (limit !in 1L..ScreenHistory.CAPACITY.toLong()) {
            throw McpToolException.InvalidParams("limit must be between 1 and ${ScreenHistory.CAPACITY}")
        }
        val records = history.recent(limit.toInt()).map { toJson(it, includeNodes) }
        return McpToolUtils.untrustedTextResult(buildJsonArray { records.forEach { add(it) } }.toString())
    }

    private suspend fun toJson(observation: ScreenObservation, includeNodes: Boolean): JsonObject {
        val tree = if (includeNodes) formatTree(observation) else null
        return buildJsonObject {
            put("observed_at", Instant.ofEpochMilli(observation.wallMs).toString())
            put("package_id", observation.window.packageName)
            put("window_id", observation.window.id)
            put("evidence", if (observation.focused) "focused_application_window" else "window_state_event")
            observation.sourceClass?.let { put("source_class", it) }
            put("tree_available", observation.tree != null)
            if (tree != null) put("historical_tree", tree)
        }
    }

    private suspend fun formatTree(observation: ScreenObservation): String? {
        val snapshot = observation.tree ?: return null
        val window = WindowData(
            windowId = observation.window.id,
            windowType = "APPLICATION",
            packageName = observation.window.packageName,
            title = null,
            activityName = null,
            layer = 0,
            focused = true,
            tree = snapshot.root,
        )
        val result = privacy.tree(MultiWindowResult(windows = listOf(window), degraded = false)).result
        val text = formatter.formatMultiWindow(
            result,
            snapshot.screenInfo,
            ScreenStateFormatOptions(
                includeOffscreen = false,
                includeHierarchy = false,
                elementNote = "note:historical node bounds in screen pixels. Read the current screen before acting.",
            ),
        )
        return if (text.length > TREE_TEXT_LIMIT) {
            text.take(TREE_TEXT_LIMIT).substringBeforeLast('\n') + "\nnote:historical tree truncated"
        } else {
            text
        }
    }

    fun register(registrar: LoggedToolRegistrar, prefix: String) {
        registrar.addTool(
            toolName = TOOL_NAME,
            name = "$prefix$TOOL_NAME",
            description =
                "Read recent Android window events in time order from the past 60 seconds. " +
                    "Check after an action if the target is already gone from the live screen. " +
                    "A window state event reports the app that emitted it. Focus evidence confirms " +
                    "that an application window was observed in focus. Set include_nodes=true to inspect " +
                    "a recorded accessibility tree when available. Read ${prefix}get_screen_state before " +
                    "acting on any coordinates or IDs.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("limit") {
                        put("type", "integer")
                        put("minimum", 1)
                        put("maximum", ScreenHistory.CAPACITY)
                        put("description", "Most recent events to return. Defaults to 8, or 3 with include_nodes.")
                    }
                    putJsonObject("include_nodes") {
                        put("type", "boolean")
                        put("default", false)
                        put("description", "Include historical node labels, IDs, and bounds when available.")
                    }
                },
                required = emptyList(),
            ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "get_screen_history"
        private const val DEFAULT_LIMIT = 8
        private const val DEFAULT_TREE_LIMIT = 3
        private const val TREE_TEXT_LIMIT = 4_096
    }
}
