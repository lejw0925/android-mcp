package dev.androidmcp.tools

import dev.androidmcp.accessibility.A11yService
import dev.androidmcp.data.SettingsRepository
import dev.androidmcp.events.ToolCallEventBus
import dev.androidmcp.tools.impl.BATCH_TOOL_NAME
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 工具注册表：持有全部工具，为每个 MCP 会话构建 Server 实例，
 * 并在调用管线上叠加：工具开关检查 → 运行时权限检查 → 事件上报 → 执行 → 计时。
 */
@Singleton
class ToolRegistry @Inject constructor(
    private val settings: SettingsRepository,
    private val events: ToolCallEventBus,
    private val permissionCenter: dev.androidmcp.permission.PermissionCenter,
    val tools: Set<@JvmSuppressWildcards McpTool>,
) {
    private val toolMap: Map<String, McpTool> = tools.associateBy { it.name }

    fun all(): List<McpTool> = tools.sortedWith(compareBy({ it.category.ordinal }, { it.name }))

    /** 构建一个会话级 MCP Server（每个 Streamable HTTP 会话一个）。 */
    fun createServer(keyLabel: String): Server {
        val server = Server(
            serverInfo = Implementation(name = "android-mcp", version = "0.1.0"),
            options = ServerOptions(
                capabilities = ServerCapabilities(
                    tools = ServerCapabilities.Tools(listChanged = false),
                ),
            ),
        )
        all().forEach { tool ->
            server.addTool(
                name = tool.name,
                title = tool.displayName,
                description = buildDescription(tool),
                inputSchema = tool.inputSchema,
            ) { request ->
                dispatch(
                    tool = tool,
                    args = request.arguments ?: JsonObject(emptyMap()),
                    keyLabel = keyLabel,
                    sessionId = sessionId,
                )
            }
        }
        return server
    }

    private fun buildDescription(tool: McpTool): String = buildString {
        append(tool.displayName).append("：").append(tool.description)
        if (!tool.defaultEnabled) append("（敏感工具，默认关闭，需在 App 工具页启用）")
        if (tool.requiresAccessibility) append("（需要无障碍服务）")
        if (tool.requiresShizuku) append("（需要 Shizuku）")
    }

    private suspend fun dispatch(
        tool: McpTool,
        args: JsonObject,
        keyLabel: String,
        sessionId: String?,
        parentCallId: String? = null,
    ): CallToolResult {
        val event = events.emitStart(
            tool = tool.name,
            displayName = tool.displayName,
            keyLabel = keyLabel,
            sessionId = sessionId,
            parentCallId = parentCallId,
            argsSummary = summarizeArgs(args),
            category = tool.category,
        )
        val started = System.currentTimeMillis()
        return try {
            val result = when {
                !settings.isToolEnabled(tool.name, tool.defaultEnabled) -> {
                    errorResult("工具「${tool.displayName}」(${tool.name}) 已在 App 中被禁用，请在「工具」页启用")
                }
                permissionCenter.missingFor(tool).isNotEmpty() -> {
                    val missing = permissionCenter.missingFor(tool)
                    permissionCenter.reportAgentBlocked(tool, keyLabel, missing)
                    val caps = missing.joinToString("、") { it.title }
                    errorResult("工具「${tool.displayName}」需要授权：$caps。已请求用户在 App 中授权，请稍后重试")
                }
                tool.name == BATCH_TOOL_NAME -> executeBatch(args, keyLabel, sessionId, event.id)
                else -> {
                    if (tool.requiresAccessibility) A11yService.clearInteractionPoint()
                    withContext(Dispatchers.IO) { tool.execute(args) }.also {
                        A11yService.consumeInteractionPoint()?.let { point ->
                            events.emitUiMarker(event, point)
                        }
                    }
                }
            }
            val isErr = result.isError == true
            val content = resultContent(result)
            events.emitFinish(event, if (isErr) content.ifBlank { "工具返回错误" } else null, content)
            result
        } catch (t: Throwable) {
            val message = t.message ?: t.javaClass.simpleName
            events.emitFinish(event, message, "错误: $message")
            errorResult(message)
        }.also {
            val elapsed = System.currentTimeMillis() - started
            android.util.Log.d("ToolRegistry", "${tool.name} took ${elapsed}ms")
        }
    }

    private suspend fun executeBatch(
        args: JsonObject,
        keyLabel: String,
        sessionId: String?,
        parentCallId: String,
    ): CallToolResult {
        val calls = args["calls"] as? JsonArray ?: return errorResult("缺少必填参数: calls")
        if (calls.isEmpty()) return errorResult("calls 不能为空")
        if (calls.size > MAX_BATCH_CALLS) return errorResult("calls 最多允许 $MAX_BATCH_CALLS 项")
        val stopOnError = args.boolOr("stop_on_error", false)
        var successCount = 0
        var errorCount = 0
        val records = mutableListOf<JsonObject>()

        for ((index, element) in calls.withIndex()) {
            val item = element as? JsonObject
            val requestedName = item?.str("tool")
            val tool = requestedName?.let(::findByNameOrDisplayName)
            val callArgs = ((item?.get("arguments") ?: item?.get("args")) as? JsonObject)
                ?: JsonObject(emptyMap())
            val result = when {
                item == null -> errorResult("第 ${index + 1} 项不是对象")
                requestedName.isNullOrBlank() -> errorResult("第 ${index + 1} 项缺少 tool")
                tool == null -> errorResult("未知工具: $requestedName")
                tool.name == BATCH_TOOL_NAME -> errorResult("批量调用中不允许再次调用批量工具")
                else -> dispatch(tool, callArgs, keyLabel, sessionId, parentCallId)
            }
            val isError = result.isError == true
            if (isError) errorCount++ else successCount++
            records += buildJsonObject {
                put("index", index + 1)
                put("tool", requestedName ?: "")
                tool?.let {
                    put("tool_id", it.name)
                    put("name", it.displayName)
                }
                put("ok", !isError)
                val content = resultContent(result).take(MAX_BATCH_RESULT_CHARS)
                if (isError) put("error", content) else put("result", content)
            }
            if (isError && stopOnError) break
        }

        val output = buildJsonObject {
            put("success_count", successCount)
            put("error_count", errorCount)
            put("stopped_early", stopOnError && errorCount > 0 && records.size < calls.size)
            putJsonArray("calls") { records.forEach(::add) }
        }.toString()
        return CallToolResult(
            content = listOf(TextContent(text = output)),
            isError = errorCount > 0,
        )
    }

    private fun findByNameOrDisplayName(value: String): McpTool? =
        toolMap[value] ?: tools.firstOrNull { it.displayName == value }

    private fun resultContent(result: CallToolResult): String = result.content.joinToString("\n") { content ->
        when (content) {
            is TextContent -> content.text
            is ImageContent -> "[图片 ${content.mimeType}，Base64 ${content.data.length} 字符]"
            else -> content.toString()
        }
    }

    private fun summarizeArgs(args: JsonObject): String {
        if (args.isEmpty()) return ""
        return args.entries.joinToString(", ") { (k, v) ->
            val value = v.toString().let { if (it.length > 40) it.take(40) + "…" else it }
            "$k=$value"
        }.take(160)
    }

    private companion object {
        const val MAX_BATCH_CALLS = 20
        const val MAX_BATCH_RESULT_CHARS = 4_000
    }
}
