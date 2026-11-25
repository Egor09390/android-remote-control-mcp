package com.danielealbano.androidremotecontrolmcp.mcp.tools

import com.danielealbano.androidremotecontrolmcp.data.model.ServerConfig
import com.danielealbano.androidremotecontrolmcp.data.model.ToolPermissionsConfig
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/** MCP tools backed by the official ARTEMIS engine running alongside the app on this phone. */
class LocalArtemisTools private constructor(
    private val token: String,
    private val startHeadless: (() -> String?)?,
    private val currentConfig: suspend () -> ServerConfig,
) {
    fun register(registrar: LoggedToolRegistrar, prefix: String, permissions: ToolPermissionsConfig) {
        for (tool in if (startHeadless == null) legacyTools else deviceTools) {
            if (!permissions.isToolEnabled(tool.name)) continue
            registrar.addTool(tool.name, prefix + tool.name, tool.description, tool.schema) { call ->
                val config = currentConfig()
                val args = call.arguments ?: JsonObject(emptyMap())
                when {
                    config.localArtemisToken != token || !config.allowsArtemis() ->
                        errorResult("Local ARTEMIS is disabled by the current Android MCP settings.")
                    !config.toolPermissionsConfig.isToolEnabled(tool.name) ||
                        args.keys.any { !config.toolPermissionsConfig.isParamEnabled(tool.name, it) } ->
                        errorResult("This ARTEMIS tool or parameter is disabled.")
                    else -> {
                        val preparation = startHeadless?.invoke()
                        if (preparation == null) callLocal(tool.name, args) else errorResult(preparation)
                    }
                }
            }
        }
    }

    private suspend fun callLocal(name: String, arguments: JsonObject): CallToolResult =
        suspendCancellableCoroutine { continuation ->
            val body = buildJsonObject {
                put("name", name)
                put("arguments", arguments)
            }
            val request = Request.Builder()
                .url("http://127.0.0.1:18765/tool")
                .header("Authorization", "Bearer $token")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            val call = http.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resume(errorResult("Local ARTEMIS is starting or needs setup. Check ARTEMIS on this phone."))
                }

                override fun onResponse(call: Call, response: Response) {
                    continuation.resume(response.use(::readResult))
                }
            })
        }

    private fun readResult(response: Response): CallToolResult {
        if (response.code == 401) return errorResult("Local ARTEMIS token does not match.")
        if (!response.isSuccessful) return errorResult("Local ARTEMIS returned HTTP ${response.code}.")
        val result = runCatching {
            val source = response.body.source()
            require(!source.request(MAX_RESPONSE_BYTES + 1))
            McpJson.decodeFromString<CallToolResult>(source.readUtf8())
        }.getOrElse { return errorResult("Invalid response from local ARTEMIS.") }
        return McpToolUtils.untrustedResult(result.content).copy(isError = result.isError)
    }

    companion object {
        private const val MAX_RESPONSE_BYTES = 1_048_576L
        private fun errorResult(message: String) = McpToolUtils.textResult(message).copy(isError = true)
        private val tokenFormat = Regex("[0-9a-f]{64}")
        private val http = OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .followRedirects(false)
            .retryOnConnectionFailure(false)
            .connectTimeout(2, TimeUnit.SECONDS)
            .callTimeout(25, TimeUnit.SECONDS)
            .build()

        fun isValidToken(token: String): Boolean = tokenFormat.matches(token)

        fun forConfig(
            config: ServerConfig,
            startHeadless: (() -> String?)? = null,
            currentConfig: suspend () -> ServerConfig,
        ): LocalArtemisTools? =
            if (config.allowsArtemis()) LocalArtemisTools(config.localArtemisToken, startHeadless, currentConfig) else null

        private fun ServerConfig.allowsArtemis(): Boolean =
            isValidToken(localArtemisToken) &&
                (oauthEnabled || (bearerTokenEnabled && bearerToken.isNotEmpty())) &&
                !privacyModeConfig.enabled

        private val deviceTools = listOf(
            Tool("artemis_status", "Check or start the device action backend. Standard Android MCP tools are ready before ARTEMIS."),
            Tool("artemis_screen", "Capture a screenshot through ARTEMIS. The MCP client chooses the next action."),
            Tool(
                "artemis_tap",
                "Tap coordinates normalized to 0–1000 on each axis of the ARTEMIS screenshot.",
                required = listOf("x", "y"),
                fields = mapOf("x" to "Horizontal coordinate.", "y" to "Vertical coordinate."),
                integerRanges = mapOf("x" to 0..1000, "y" to 0..1000),
            ),
            Tool(
                "artemis_swipe",
                "Swipe along a path selected by the MCP client. Coordinates are normalized to 0–1000.",
                required = listOf("x1", "y1", "x2", "y2"),
                fields = mapOf(
                    "x1" to "Start x.", "y1" to "Start y.", "x2" to "End x.", "y2" to "End y.",
                    "duration_ms" to "Duration in milliseconds. Defaults to 350.",
                ),
                integerRanges = mapOf(
                    "x1" to 0..1000, "y1" to 0..1000, "x2" to 0..1000, "y2" to 0..1000,
                    "duration_ms" to 100..3000,
                ),
            ),
            Tool(
                "artemis_type_text",
                "Append text in the currently focused Android field.",
                required = listOf("text"),
                fields = mapOf("text" to "Text to enter."),
            ),
            Tool(
                "artemis_press_key",
                "Press one Android navigation key.",
                required = listOf("key"),
                fields = mapOf("key" to "back, home, or enter."),
            ),
        )

        private val legacyTools = listOf(
            Tool("artemis_status", "Check that ARTEMIS and the paired Android device are available."),
            Tool(
                "artemis_run_task",
                "Start a background ARTEMIS UI task on this phone. Returns a trace_id to check or stop it.",
                required = listOf("task_desc"),
                fields = mapOf(
                    "task_desc" to "Self-contained UI task and when to stop.",
                    "model" to "Flash for routine tasks or Pro for multi-step tasks. Defaults to Flash.",
                    "locked_app_package" to "Optional Android package to restrict the task to.",
                ),
            ),
            Tool(
                "artemis_manage_task",
                "Check the status of an ARTEMIS task or stop it.",
                required = listOf("trace_id", "action"),
                fields = mapOf("trace_id" to "Task ID from artemis_run_task.", "action" to "status or stop."),
            ),
            Tool(
                "artemis_inspect_trace",
                "Read the ARTEMIS execution summary or search the task history.",
                required = listOf("trace_id", "action"),
                fields = mapOf(
                    "trace_id" to "Task ID from artemis_run_task.",
                    "action" to "view_summary or search.",
                    "query" to "Keywords to look up when action is search.",
                ),
            ),
        )
    }

    private class Tool(
        val name: String,
        val description: String,
        required: List<String> = emptyList(),
        fields: Map<String, String> = emptyMap(),
        integerRanges: Map<String, IntRange> = emptyMap(),
    ) {
        val schema = ToolSchema(
            properties = buildJsonObject {
                fields.forEach { (field, label) ->
                    putJsonObject(field) {
                        val range = integerRanges[field]
                        put("type", if (range == null) "string" else "integer")
                        if (range != null) {
                            put("minimum", range.first)
                            put("maximum", range.last)
                        }
                        put("description", label)
                    }
                }
            },
            required = required,
        )
    }
}
