package dev.androidmcp.tools.impl

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.media.AudioManager
import android.util.Base64
import android.view.accessibility.AccessibilityNodeInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.androidmcp.accessibility.A11yService
import dev.androidmcp.data.SettingsRepository
import dev.androidmcp.tools.McpTool
import dev.androidmcp.tools.SchemaBuilder
import dev.androidmcp.tools.ToolCategory
import dev.androidmcp.tools.boolOr
import dev.androidmcp.tools.errorResult
import dev.androidmcp.tools.imageResult
import dev.androidmcp.tools.inputSchema
import dev.androidmcp.tools.intOr
import dev.androidmcp.tools.jsonResult
import dev.androidmcp.tools.reqInt
import dev.androidmcp.tools.reqStr
import dev.androidmcp.tools.str
import dev.androidmcp.tools.textResult
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.ByteArrayOutputStream
import javax.inject.Inject

// ---------- 本文件工具共用的辅助 ----------

/** 取无障碍服务实例，未连接时抛异常（ToolRegistry 已先做 isRunning 检查，这里是双保险）。 */
private fun a11y(): A11yService =
    A11yService.get() ?: throw IllegalStateException("无障碍服务未开启，请在系统设置中为本应用开启")

/** 元素选择器：四个条件可任意组合，与关系。 */
private class Selector(val text: String?, val textContains: String?, val id: String?, val desc: String?) {
    fun hasAny() = text != null || textContains != null || id != null || desc != null
}

private fun selectorFrom(args: JsonObject) =
    Selector(args.str("text"), args.str("text_contains"), args.str("id"), args.str("desc"))

private fun SchemaBuilder.selectorParams() {
    string("text", "精确匹配节点文本")
    string("text_contains", "节点文本包含该子串")
    string("id", "View 资源 ID 短名（不含包名前缀），如 login_btn")
    string("desc", "contentDescription 包含该子串")
}

/** key_event 支持的全部按键。 */
private val KEY_EVENT_KEYS = listOf(
    "back", "home", "recents", "notifications", "quick_settings",
    "power_dialog", "lock_screen", "volume_up", "volume_down", "mute",
)

/** global_action / key_event 全局动作部分支持的动作。 */
private val GLOBAL_ACTIONS = listOf(
    "back", "home", "recents", "notifications", "quick_settings",
    "power_dialog", "lock_screen", "split_screen", "screenshot",
)

// ---------- 读取类 ----------

class ScreenshotTool @Inject constructor(
    private val settings: SettingsRepository,
) : McpTool {
    override val name = "screenshot"
    override val description =
        "截取当前屏幕，返回缩放后的 JPEG 图片（image content）。scale/quality 不传时取 App 设置中的默认值；scale 越小图片越省空间"
    override val category = ToolCategory.READ
    override val requiresAccessibility = true
    override val inputSchema: ToolSchema = inputSchema {
        number("scale", "缩放比例 0.05~1.0，默认取 App 设置（默认 0.5）")
        integer("quality", "JPEG 质量 10~100，默认取 App 设置（默认 80）")
    }

    override suspend fun execute(args: JsonObject): CallToolResult {
        val scale = (args["scale"]?.jsonPrimitive?.doubleOrNull?.toFloat()
            ?: settings.screenshotScale.first()).coerceIn(0.05f, 1f)
        val quality = args.intOr("quality", settings.screenshotQuality.first()).coerceIn(10, 100)
        val bitmap = a11y().screenshot(scale, quality)
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return imageResult(Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP))
    }
}

class GetUiTreeTool @Inject constructor() : McpTool {
    override val name = "get_ui_tree"
    override val description =
        "获取当前屏幕 UI 控件树（紧凑 JSON）。节点字段：t=文本 d=内容描述 id=资源ID短名 c=类名短名 " +
            "b=边界\"l,t,r,b\" f=标志(k可点击 l可长按 s可滚动 e可编辑 f可聚焦 c已勾选 n可用) children=子节点。" +
            "超过 800 个节点会截断并带 truncated=true"
    override val category = ToolCategory.READ
    override val requiresAccessibility = true
    override val inputSchema: ToolSchema = inputSchema {
        integer("max_depth", "最大遍历深度，默认 30，范围 1~50")
    }

    override suspend fun execute(args: JsonObject): CallToolResult =
        jsonResult(a11y().dumpUiTree(args.intOr("max_depth", 30).coerceIn(1, 50)))
}

class FindElementTool @Inject constructor() : McpTool {
    override val name = "find_element"
    override val description =
        "按条件查找界面元素，返回匹配节点的 text/desc/id/class/bounds/flags JSON 数组。" +
            "text/text_contains/id/desc 至少传一个，多条件为与关系"
    override val category = ToolCategory.READ
    override val requiresAccessibility = true
    override val inputSchema: ToolSchema = inputSchema {
        selectorParams()
    }

    override suspend fun execute(args: JsonObject): CallToolResult {
        val sel = selectorFrom(args)
        if (!sel.hasAny()) return errorResult("至少需要 text/text_contains/id/desc 之一")
        val service = a11y()
        val nodes = service.findNodes(sel.text, sel.textContains, sel.id, sel.desc)
        if (nodes.isEmpty()) return textResult("未找到匹配元素")
        return jsonResult(buildJsonObject {
            put("count", nodes.size)
            putJsonArray("elements") { nodes.forEach { add(service.nodeSummary(it)) } }
        })
    }
}

class GetCurrentAppTool @Inject constructor(
    @ApplicationContext private val context: Context,
) : McpTool {
    override val name = "get_current_app"
    override val description = "获取当前前台应用的包名、应用名（桌面显示名）和窗口标题"
    override val category = ToolCategory.READ
    override val requiresAccessibility = true
    override val inputSchema: ToolSchema = inputSchema {}

    override suspend fun execute(args: JsonObject): CallToolResult {
        val (pkg, title) = a11y().currentApp()
        if (pkg == null) return errorResult("当前无活跃窗口")
        val label = runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString()
        }.getOrNull()
        return jsonResult(buildJsonObject {
            put("package", pkg)
            put("app_name", label)
            put("window_title", title)
        })
    }
}

class WaitForTool @Inject constructor() : McpTool {
    override val name = "wait_for"
    override val description =
        "等待指定文本在屏幕上出现或消失，每 300ms 轮询一次。appear=true 等待出现，false 等待消失；超时返回失败"
    override val category = ToolCategory.READ
    override val requiresAccessibility = true
    override val inputSchema: ToolSchema = inputSchema {
        string("text", "要等待的文本（包含匹配）", required = true)
        boolean("appear", "true=等待出现（默认），false=等待消失")
        integer("timeout_ms", "超时毫秒数，默认 5000，上限 30000")
    }

    override suspend fun execute(args: JsonObject): CallToolResult {
        val text = args.reqStr("text")
        val appear = args.boolOr("appear", true)
        val timeoutMs = args.intOr("timeout_ms", 5000).coerceIn(0, 30000).toLong()
        val ok = a11y().waitFor(text, appear, timeoutMs)
        return if (ok) {
            textResult(if (appear) "文本已出现: $text" else "文本已消失: $text")
        } else {
            errorResult("等待超时（${timeoutMs}ms）: $text")
        }
    }
}

// ---------- 屏幕输入类 ----------

class ClickTool @Inject constructor() : McpTool {
    override val name = "click"
    override val description =
        "点击界面元素。两种方式：1) 传 text/text_contains/id/desc 选择器（优先节点点击动作，不可点击则点其中心坐标）；2) 传 x,y 像素坐标直接点击。传了选择器时优先用选择器"
    override val category = ToolCategory.SCREEN_INPUT
    override val requiresAccessibility = true
    override val inputSchema: ToolSchema = inputSchema {
        selectorParams()
        integer("x", "点击横坐标（像素），需与 y 一起传")
        integer("y", "点击纵坐标（像素），需与 x 一起传")
    }

    override suspend fun execute(args: JsonObject): CallToolResult {
        val service = a11y()
        val sel = selectorFrom(args)
        return when {
            sel.hasAny() -> when (service.clickNode(sel.text, sel.textContains, sel.id, sel.desc)) {
                null -> errorResult("未找到匹配元素")
                true -> textResult("已点击")
                false -> errorResult("点击失败")
            }
            args["x"] != null && args["y"] != null -> {
                val x = args.reqInt("x")
                val y = args.reqInt("y")
                if (service.tap(x.toFloat(), y.toFloat())) textResult("已点击 ($x, $y)") else errorResult("坐标点击失败")
            }
            else -> errorResult("请提供选择器（text/text_contains/id/desc）或 x,y 坐标")
        }
    }
}

class LongClickTool @Inject constructor() : McpTool {
    override val name = "long_click"
    override val description =
        "长按界面元素。两种方式：1) 传 text/text_contains/id/desc 选择器（优先节点长按动作，不支持则长按其中心坐标）；2) 传 x,y 像素坐标直接长按。传了选择器时优先用选择器"
    override val category = ToolCategory.SCREEN_INPUT
    override val requiresAccessibility = true
    override val inputSchema: ToolSchema = inputSchema {
        selectorParams()
        integer("x", "长按横坐标（像素），需与 y 一起传")
        integer("y", "长按纵坐标（像素），需与 x 一起传")
    }

    override suspend fun execute(args: JsonObject): CallToolResult {
        val service = a11y()
        val sel = selectorFrom(args)
        return when {
            sel.hasAny() -> {
                val node = service.findNodes(sel.text, sel.textContains, sel.id, sel.desc).firstOrNull()
                    ?: return errorResult("未找到匹配元素")
                service.rememberNodeInteraction(node)
                val ok = if (node.isLongClickable) {
                    node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
                } else {
                    val r = Rect()
                    node.getBoundsInScreen(r)
                    !r.isEmpty && service.longPress(r.exactCenterX(), r.exactCenterY())
                }
                if (ok) textResult("已长按") else errorResult("长按失败")
            }
            args["x"] != null && args["y"] != null -> {
                val x = args.reqInt("x")
                val y = args.reqInt("y")
                if (service.longPress(x.toFloat(), y.toFloat())) textResult("已长按 ($x, $y)") else errorResult("坐标长按失败")
            }
            else -> errorResult("请提供选择器（text/text_contains/id/desc）或 x,y 坐标")
        }
    }
}

class InputTextTool @Inject constructor() : McpTool {
    override val name = "input_text"
    override val description =
        "向输入框输入文本。默认写入当前聚焦的输入框；也可用 text_contains/id 指定目标输入框。clear=true 时先清空再输入"
    override val category = ToolCategory.SCREEN_INPUT
    override val requiresAccessibility = true
    override val inputSchema: ToolSchema = inputSchema {
        string("text", "要输入的文本", required = true)
        boolean("clear", "输入前清空已有内容，默认 false")
        string("text_contains", "目标输入框文本包含子串（可选，用于定位输入框）")
        string("id", "目标输入框资源 ID 短名（可选，用于定位输入框）")
    }

    override suspend fun execute(args: JsonObject): CallToolResult {
        val ok = a11y().inputText(
            text = args.reqStr("text"),
            clear = args.boolOr("clear", false),
            textContains = args.str("text_contains"),
            id = args.str("id"),
        )
        return if (ok) textResult("已输入文本") else errorResult("未找到输入框或输入失败")
    }
}

class SwipeTool @Inject constructor() : McpTool {
    override val name = "swipe"
    override val description = "从 (x1,y1) 直线滑动到 (x2,y2)，坐标为像素"
    override val category = ToolCategory.SCREEN_INPUT
    override val requiresAccessibility = true
    override val inputSchema: ToolSchema = inputSchema {
        integer("x1", "起点横坐标（像素）", required = true)
        integer("y1", "起点纵坐标（像素）", required = true)
        integer("x2", "终点横坐标（像素）", required = true)
        integer("y2", "终点纵坐标（像素）", required = true)
        integer("duration_ms", "滑动时长（毫秒），默认 300")
    }

    override suspend fun execute(args: JsonObject): CallToolResult {
        val ok = a11y().swipe(
            args.reqInt("x1").toFloat(), args.reqInt("y1").toFloat(),
            args.reqInt("x2").toFloat(), args.reqInt("y2").toFloat(),
            args.intOr("duration_ms", 300).toLong(),
        )
        return if (ok) textResult("已滑动") else errorResult("滑动失败")
    }
}

class ScrollTool @Inject constructor() : McpTool {
    override val name = "scroll"
    override val description =
        "滚动屏幕内容。up=内容向上滚（显示下方内容，等效上滑）；down=内容向下滚；left=内容向左滚（显示右侧内容）；right=内容向右滚。" +
            "优先对可滚动控件执行滚动动作，失败时自动用全屏滑动手势兜底"
    override val category = ToolCategory.SCREEN_INPUT
    override val requiresAccessibility = true
    override val inputSchema: ToolSchema = inputSchema {
        string("direction", "滚动方向", required = true, enum = listOf("up", "down", "left", "right"))
    }

    override suspend fun execute(args: JsonObject): CallToolResult {
        val service = a11y()
        val direction = args.reqStr("direction")
        val (w, h) = service.screenSize()
        val ok = when (direction) {
            "up" -> service.scrollNode(forward = true)
            "down" -> service.scrollNode(forward = false)
            "left" -> service.swipe(w * 0.8f, h / 2f, w * 0.2f, h / 2f, 400)
            "right" -> service.swipe(w * 0.2f, h / 2f, w * 0.8f, h / 2f, 400)
            else -> return errorResult("direction 必须是 up/down/left/right")
        }
        return if (ok) textResult("已滚动 $direction") else errorResult("滚动失败")
    }
}

class GestureTool @Inject constructor() : McpTool {
    override val name = "gesture"
    override val description =
        "按给定路径执行单笔画滑动手势。points 为途经坐标点数组 [{\"x\":100,\"y\":200},...]，至少 2 个点；duration_ms 为手势总时长"
    override val category = ToolCategory.SCREEN_INPUT
    override val requiresAccessibility = true
    override val inputSchema: ToolSchema = inputSchema {
        objectArray("points", "路径点数组，如 [{\"x\":100,\"y\":200},{\"x\":100,\"y\":600}]，至少 2 个点", required = true)
        integer("duration_ms", "手势总时长（毫秒），默认 600")
    }

    override suspend fun execute(args: JsonObject): CallToolResult {
        val arr = args["points"] as? JsonArray ?: return errorResult("缺少必填参数: points")
        val points = arr.mapNotNull { el ->
            (el as? JsonObject)?.let { o ->
                val x = o["x"]?.jsonPrimitive?.doubleOrNull?.toFloat()
                val y = o["y"]?.jsonPrimitive?.doubleOrNull?.toFloat()
                if (x != null && y != null) x to y else null
            }
        }
        if (points.size < 2) return errorResult("points 至少需要 2 个有效坐标点")
        val ok = a11y().gesture(listOf(points), args.intOr("duration_ms", 600).toLong())
        return if (ok) textResult("手势已执行（${points.size} 个点）") else errorResult("手势执行失败")
    }
}

class KeyEventTool @Inject constructor(
    @ApplicationContext private val context: Context,
) : McpTool {
    override val name = "key_event"
    override val description =
        "执行按键/系统动作。back/home/recents/notifications/quick_settings/power_dialog/lock_screen 走无障碍全局动作；" +
            "volume_up/volume_down 调节媒体音量；mute 静音/取消静音"
    override val category = ToolCategory.SCREEN_INPUT
    override val requiresAccessibility = true
    override val inputSchema: ToolSchema = inputSchema {
        string("key", "按键名", required = true, enum = KEY_EVENT_KEYS)
    }

    override suspend fun execute(args: JsonObject): CallToolResult {
        val key = args.reqStr("key")
        return when (key) {
            "volume_up" -> {
                adjustVolume(AudioManager.ADJUST_RAISE)
                textResult("已执行 volume_up")
            }
            "volume_down" -> {
                adjustVolume(AudioManager.ADJUST_LOWER)
                textResult("已执行 volume_down")
            }
            "mute" -> {
                adjustVolume(AudioManager.ADJUST_MUTE)
                textResult("已执行 mute")
            }
            else -> {
                if (a11y().global(key)) textResult("已执行 $key") else errorResult("执行失败或不支持的按键: $key")
            }
        }
    }

    private fun adjustVolume(direction: Int) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0)
    }
}

class GlobalActionTool @Inject constructor() : McpTool {
    override val name = "global_action"
    override val description =
        "执行无障碍全局动作（与 key_event 的全局动作同源，供显式调用）。screenshot 为系统全局截图动作，与 screenshot 工具的位图截图不同"
    override val category = ToolCategory.SCREEN_INPUT
    override val requiresAccessibility = true
    override val inputSchema: ToolSchema = inputSchema {
        string("action", "全局动作名", required = true, enum = GLOBAL_ACTIONS)
    }

    override suspend fun execute(args: JsonObject): CallToolResult {
        val action = args.reqStr("action")
        return if (a11y().global(action)) textResult("已执行 $action") else errorResult("执行失败或不支持的动作: $action")
    }
}
