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
    private val currentConfig: suspend () -> ServerConfig,
) {
    fun register(registrar: LoggedToolRegistrar, prefix: String, permissions: ToolPermissionsConfig) {
        for (tool in tools) {
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
                    else -> callLocal(tool.name, args)
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

        fun forConfig(config: ServerConfig, currentConfig: suspend () -> ServerConfig): LocalArtemisTools? =
            if (config.allowsArtemis()) LocalArtemisTools(config.localArtemisToken, currentConfig) else null

        private fun ServerConfig.allowsArtemis(): Boolean =
            isValidToken(localArtemisToken) &&
                (oauthEnabled || (bearerTokenEnabled && bearerToken.isNotEmpty())) &&
                !privacyModeConfig.enabled

        private val tools = listOf(
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
    ) {
        val schema = ToolSchema(
            properties = buildJsonObject {
                fields.forEach { (field, label) ->
                    putJsonObject(field) {
                        put("type", "string")
                        put("description", label)
                    }
                }
            },
            required = required,
        )
    }
}
