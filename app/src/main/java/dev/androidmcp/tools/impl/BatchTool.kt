package dev.androidmcp.tools.impl

import dev.androidmcp.tools.McpTool
import dev.androidmcp.tools.ToolCategory
import dev.androidmcp.tools.errorResult
import dev.androidmcp.tools.inputSchema
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject

const val BATCH_TOOL_NAME = "batch_execute"

/**
 * 批量工具的协议声明。实际调度由 ToolRegistry 完成，确保子调用复用同一 MCP 会话，
 * 并经过与普通调用完全相同的开关、权限、事件与错误处理链路。
 */
class BatchTool @Inject constructor() : McpTool {
    override val name = BATCH_TOOL_NAME
    override val description =
        "按 calls 数组顺序逐个执行一组工具调用，并返回每一步的结果或错误原因。" +
            "每项格式为 {\"tool\":\"工具标识或中文名\",\"arguments\":{...}}；最多 20 项"
    override val category = ToolCategory.SYSTEM
    override val inputSchema: ToolSchema = inputSchema {
        objectArray(
            "calls",
            "按顺序执行的调用数组，每项包含 tool 与 arguments（也兼容 args）",
            required = true,
        )
        boolean("stop_on_error", "遇到第一项错误时停止，默认 false")
    }

    override suspend fun execute(args: JsonObject): CallToolResult =
        errorResult("批量工具只能通过 MCP 调度入口执行")
}
