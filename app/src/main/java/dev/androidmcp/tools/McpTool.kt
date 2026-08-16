package dev.androidmcp.tools

import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** 工具类别：决定边缘粒子特效的颜色与 UI 分组。 */
enum class ToolCategory(val displayName: String, val effectColor: Long) {
    SCREEN_INPUT("屏幕与输入", 0xFF26C6DA),   // 青
    READ("读取与感知", 0xFF42A5F5),           // 蓝
    COMMUNICATION("通信", 0xFFAB47BC),        // 紫
    SYSTEM("系统控制", 0xFFFFA726),           // 橙
    FILES("文件", 0xFF66BB6A),                // 绿
    SHELL("Shell", 0xFFEF5350),               // 红
}

interface McpTool {
    val name: String
    /** 面向用户与支持 title 的 MCP 客户端展示；[name] 保持协议兼容的 ASCII 标识。 */
    val displayName: String get() = toolDisplayName(name)
    val description: String
    val category: ToolCategory

    /** 需要的 Android 运行时权限（Manifest.permission.* 常量）。 */
    val requiredPermissions: List<String> get() = emptyList()

    /** 是否依赖无障碍服务。 */
    val requiresAccessibility: Boolean get() = false

    /** 是否依赖 Shizuku。 */
    val requiresShizuku: Boolean get() = false

    /** 敏感工具默认关闭，需在 App 中显式启用。 */
    val defaultEnabled: Boolean get() = true

    val inputSchema: ToolSchema

    suspend fun execute(args: JsonObject): CallToolResult
}

/** MCP 工具标识必须稳定，中文名称集中维护，避免 UI 各处出现不一致翻译。 */
fun toolDisplayName(name: String): String = TOOL_DISPLAY_NAMES[name] ?: name

private val TOOL_DISPLAY_NAMES = mapOf(
    "batch_execute" to "顺序执行工具组",
    "screenshot" to "截取屏幕",
    "get_ui_tree" to "获取界面结构",
    "find_element" to "查找界面元素",
    "get_current_app" to "获取当前应用",
    "wait_for" to "等待界面文本",
    "click" to "点击",
    "long_click" to "长按",
    "input_text" to "输入文本",
    "swipe" to "滑动",
    "scroll" to "滚动",
    "gesture" to "执行手势",
    "key_event" to "按键操作",
    "global_action" to "全局操作",
    "get_battery" to "获取电池状态",
    "get_clipboard" to "读取剪贴板",
    "get_device_info" to "获取设备信息",
    "get_network_info" to "获取网络信息",
    "launch_app" to "启动应用",
    "open_url" to "打开网址",
    "set_clipboard" to "写入剪贴板",
    "toast" to "显示提示",
    "dismiss_notification" to "清除通知",
    "list_sms" to "读取短信列表",
    "make_call" to "拨打电话",
    "media_control" to "媒体控制",
    "now_playing" to "获取正在播放",
    "query_contacts" to "查询联系人",
    "read_call_log" to "读取通话记录",
    "read_notifications" to "读取通知",
    "send_sms" to "发送短信",
    "delete_file" to "删除文件",
    "list_files" to "列出文件",
    "read_file" to "读取文件",
    "write_file" to "写入文件",
    "get_logcat" to "读取系统日志",
    "pm_command" to "执行软件包命令",
    "run_shell" to "执行 Shell 命令",
    "settings_get" to "读取系统设置",
    "settings_put" to "写入系统设置",
    "dnd" to "勿扰模式",
    "flashlight" to "手电筒",
    "get_brightness" to "获取屏幕亮度",
    "get_location" to "获取位置",
    "get_volume" to "获取音量",
    "list_apps" to "列出应用",
    "list_sensors" to "列出传感器",
    "open_app_settings" to "打开应用设置",
    "read_sensor" to "读取传感器",
    "ringer_mode" to "铃声模式",
    "set_alarm" to "设置闹钟",
    "set_brightness" to "设置屏幕亮度",
    "set_timer" to "设置计时器",
    "set_volume" to "设置音量",
    "speak" to "语音朗读",
    "vibrate" to "振动",
    "wake_screen" to "唤醒屏幕",
)

// ---------- Schema DSL ----------

class SchemaBuilder {
    private val properties = mutableMapOf<String, JsonObject>()
    private val requiredList = mutableListOf<String>()

    private fun add(name: String, required: Boolean, body: JsonObject) {
        properties[name] = body
        if (required) requiredList += name
    }

    fun string(name: String, description: String = "", required: Boolean = false, enum: List<String>? = null) =
        add(name, required, buildJsonObject {
            put("type", "string")
            if (description.isNotEmpty()) put("description", description)
            if (enum != null) putJsonArray("enum") { enum.forEach { add(it) } }
        })

    fun integer(name: String, description: String = "", required: Boolean = false) =
        add(name, required, buildJsonObject {
            put("type", "integer")
            if (description.isNotEmpty()) put("description", description)
        })

    fun number(name: String, description: String = "", required: Boolean = false) =
        add(name, required, buildJsonObject {
            put("type", "number")
            if (description.isNotEmpty()) put("description", description)
        })

    fun boolean(name: String, description: String = "", required: Boolean = false) =
        add(name, required, buildJsonObject {
            put("type", "boolean")
            if (description.isNotEmpty()) put("description", description)
        })

    /** 简易对象数组参数，如坐标点列表：[{x:1,y:2},...] */
    fun objectArray(name: String, description: String = "", required: Boolean = false) =
        add(name, required, buildJsonObject {
            put("type", "array")
            if (description.isNotEmpty()) put("description", description)
            putJsonObject("items") { put("type", "object") }
        })

    fun build(): ToolSchema = ToolSchema(
        properties = buildJsonObject { properties.forEach { (k, v) -> put(k, v) } },
        required = requiredList.ifEmpty { null },
    )
}

fun inputSchema(block: SchemaBuilder.() -> Unit): ToolSchema = SchemaBuilder().apply(block).build()

// ---------- 参数提取 ----------

fun JsonObject.str(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull
fun JsonObject.reqStr(name: String): String =
    str(name) ?: throw IllegalArgumentException("缺少必填参数: $name")
fun JsonObject.intOr(name: String, default: Int): Int = this[name]?.jsonPrimitive?.intOrNull ?: default
fun JsonObject.reqInt(name: String): Int =
    this[name]?.jsonPrimitive?.int ?: throw IllegalArgumentException("缺少必填参数: $name")
fun JsonObject.doubleOr(name: String, default: Double): Double =
    this[name]?.jsonPrimitive?.doubleOrNull ?: default
fun JsonObject.boolOr(name: String, default: Boolean): Boolean =
    this[name]?.jsonPrimitive?.let { runCatching { it.boolean }.getOrNull() } ?: default

// ---------- 结果构造 ----------

fun textResult(text: String): CallToolResult =
    CallToolResult(content = listOf(TextContent(text = text)))

fun errorResult(message: String): CallToolResult =
    CallToolResult(content = listOf(TextContent(text = "错误: $message")), isError = true)

fun jsonResult(json: JsonObject): CallToolResult = textResult(json.toString())

fun imageResult(base64: String, mimeType: String = "image/jpeg"): CallToolResult =
    CallToolResult(content = listOf(ImageContent(data = base64, mimeType = mimeType)))

fun JsonPrimitive?.orNull(): String? = this?.contentOrNull
