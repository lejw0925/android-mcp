package dev.androidmcp.tools.impl

import dev.androidmcp.shizuku.ShizukuManager
import dev.androidmcp.tools.McpTool
import dev.androidmcp.tools.ToolCategory
import dev.androidmcp.tools.errorResult
import dev.androidmcp.tools.inputSchema
import dev.androidmcp.tools.intOr
import dev.androidmcp.tools.jsonResult
import dev.androidmcp.tools.reqStr
import dev.androidmcp.tools.str
import dev.androidmcp.tools.textResult
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject

/** settings key 合法性（防命令注入）。 */
private val SAFE_SETTINGS_KEY = Regex("^[A-Za-z0-9._-]+$")

/** 包名合法性（防命令注入）。 */
private val SAFE_PACKAGE = Regex("^[A-Za-z0-9._]+$")

private val SETTINGS_NAMESPACES = setOf("system", "secure", "global")

/** shell 单引号包裹（内部单引号转义为 '\''）。 */
private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

class RunShellTool @Inject constructor(
    private val shizuku: ShizukuManager,
) : McpTool {
    override val name = "run_shell"
    override val description =
        "以 Shizuku（shell/ADB）权限执行任意 shell 命令，等同 adb shell。command 必填；timeout_ms 默认 10000、上限 60000。" +
            "返回 exitCode/stdout/stderr，单路输出超过 64KB 会被截断并标注"
    override val category = ToolCategory.SHELL
    override val requiresShizuku = true
    override val defaultEnabled = false // 高危：等同 adb shell
    override val inputSchema: ToolSchema = inputSchema {
        string("command", "要执行的 shell 命令", required = true)
        integer("timeout_ms", "超时毫秒，默认 10000，上限 60000")
    }

    override suspend fun execute(args: JsonObject): CallToolResult {
        val timeout = args.intOr("timeout_ms", 10_000).coerceIn(1_000, 60_000).toLong()
        val result = shizuku.execShell(args.reqStr("command"), timeout)
        return jsonResult(buildJsonObject {
            put("exit_code", result.exitCode)
            put("timed_out", result.timedOut)
            put("stdout", result.stdout)
            put("stderr", result.stderr)
        })
    }
}

class GetLogcatTool @Inject constructor(
    private val shizuku: ShizukuManager,
) : McpTool {
    override val name = "get_logcat"
    override val description =
        "抓取最近的 logcat 日志（经 Shizuku 等同 adb shell logcat -d）。lines 默认 200、上限 2000；" +
            "filter 可选，仅保留包含该子串的行（不区分大小写）"
    override val category = ToolCategory.SHELL
    override val requiresShizuku = true
    override val defaultEnabled = false
    override val inputSchema: ToolSchema = inputSchema {
        integer("lines", "日志行数，默认 200，上限 2000")
        string("filter", "行过滤子串（不区分大小写），可选")
    }

    override suspend fun execute(args: JsonObject): CallToolResult {
        val lines = args.intOr("lines", 200).coerceIn(1, 2000)
        val result = shizuku.execShell("logcat -d -t $lines", 30_000)
        if (result.timedOut) return errorResult("logcat 执行超时")
        if (result.exitCode != 0) {
            return errorResult("logcat 失败: ${result.stderr.ifBlank { result.stdout }.take(500)}")
        }
        val filter = args.str("filter")
        val output = if (filter.isNullOrBlank()) {
            result.stdout
        } else {
            result.stdout.lines()
                .filter { it.contains(filter, ignoreCase = true) }
                .joinToString("\n")
        }
        return textResult(output.ifBlank { "(无日志输出)" })
    }
}

class SettingsGetTool @Inject constructor(
    private val shizuku: ShizukuManager,
) : McpTool {
    override val name = "settings_get"
    override val description =
        "读取系统设置值（经 Shizuku 等同 adb shell settings get）。namespace 必填（system/secure/global），key 必填；值为空返回 (null)"
    override val category = ToolCategory.SHELL
    override val requiresShizuku = true
    override val defaultEnabled = false
    override val inputSchema: ToolSchema = inputSchema {
        string("namespace", "命名空间", required = true, enum = listOf("system", "secure", "global"))
        string("key", "设置键名", required = true)
    }

    override suspend fun execute(args: JsonObject): CallToolResult {
        val ns = args.reqStr("namespace")
        if (ns !in SETTINGS_NAMESPACES) return errorResult("namespace 必须是 system/secure/global")
        val key = args.reqStr("key")
        if (!SAFE_SETTINGS_KEY.matches(key)) return errorResult("key 含非法字符（仅允许字母数字 . _ -）")
        val result = shizuku.execShell("settings get $ns $key", 5_000)
        if (result.timedOut) return errorResult("读取超时")
        if (result.exitCode != 0) return errorResult("读取失败: ${result.stderr.trim().take(300)}")
        return textResult(result.stdout.trim().ifEmpty { "(null)" })
    }
}

class SettingsPutTool @Inject constructor(
    private val shizuku: ShizukuManager,
) : McpTool {
    override val name = "settings_put"
    override val description =
        "写入系统设置值（经 Shizuku 等同 adb shell settings put）。namespace 必填（system/secure/global），key/value 必填"
    override val category = ToolCategory.SHELL
    override val requiresShizuku = true
    override val defaultEnabled = false
    override val inputSchema: ToolSchema = inputSchema {
        string("namespace", "命名空间", required = true, enum = listOf("system", "secure", "global"))
        string("key", "设置键名", required = true)
        string("value", "设置值", required = true)
    }

    override suspend fun execute(args: JsonObject): CallToolResult {
        val ns = args.reqStr("namespace")
        if (ns !in SETTINGS_NAMESPACES) return errorResult("namespace 必须是 system/secure/global")
        val key = args.reqStr("key")
        if (!SAFE_SETTINGS_KEY.matches(key)) return errorResult("key 含非法字符（仅允许字母数字 . _ -）")
        val value = args.reqStr("value")
        val result = shizuku.execShell("settings put $ns $key ${shellQuote(value)}", 5_000)
        if (result.timedOut) return errorResult("写入超时")
        if (result.exitCode != 0) {
            return errorResult("写入失败: ${result.stderr.trim().ifBlank { result.stdout.trim() }.take(300)}")
        }
        return textResult("已设置 $ns/$key = $value")
    }
}

class PmCommandTool @Inject constructor(
    private val shizuku: ShizukuManager,
) : McpTool {
    override val name = "pm_command"
    override val description =
        "执行常用 pm 命令（经 Shizuku 等同 adb shell pm）。action 必填：list_packages（package 可选，作包名过滤前缀）/" +
            "disable/enable/clear（这三个 package 必填；disable 停用应用、enable 恢复、clear 清除应用数据，危险操作）"
    override val category = ToolCategory.SHELL
    override val requiresShizuku = true
    override val defaultEnabled = false
    override val inputSchema: ToolSchema = inputSchema {
        string("action", "pm 动作", required = true, enum = listOf("list_packages", "disable", "enable", "clear"))
        string("package", "目标包名（list_packages 时为过滤前缀）")
    }

    override suspend fun execute(args: JsonObject): CallToolResult {
        val action = args.reqStr("action")
        val pkg = args.str("package")
        if (pkg != null && !SAFE_PACKAGE.matches(pkg)) return errorResult("package 含非法字符（仅允许字母数字 . _）")
        val cmd = when (action) {
            "list_packages" -> if (pkg != null) "pm list packages $pkg" else "pm list packages"
            "disable", "enable", "clear" -> {
                if (pkg == null) return errorResult("action=$action 需要 package 参数")
                "pm $action $pkg"
            }
            else -> return errorResult("不支持的 action: $action")
        }
        val result = shizuku.execShell(cmd, 30_000)
        if (result.timedOut) return errorResult("pm 命令执行超时")
        val output = result.stdout.trim()
        if (result.exitCode != 0) {
            return errorResult("pm 命令失败: ${result.stderr.trim().ifBlank { output }.take(500)}")
        }
        return textResult(output.ifBlank { "完成（无输出）" })
    }
}
