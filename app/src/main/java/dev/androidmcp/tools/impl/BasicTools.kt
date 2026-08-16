package dev.androidmcp.tools.impl

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.widget.Toast
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.androidmcp.tools.McpTool
import dev.androidmcp.tools.ToolCategory
import dev.androidmcp.tools.errorResult
import dev.androidmcp.tools.inputSchema
import dev.androidmcp.tools.jsonResult
import dev.androidmcp.tools.reqStr
import dev.androidmcp.tools.textResult
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject

class DeviceInfoTool @Inject constructor(
    @ApplicationContext private val context: Context,
) : McpTool {
    override val name = "get_device_info"
    override val description = "获取设备信息：型号、Android 版本、屏幕、内存、存储、电量概览"
    override val category = ToolCategory.READ
    override val inputSchema: ToolSchema = inputSchema {}

    override suspend fun execute(args: JsonObject): CallToolResult {
        val dm = context.resources.displayMetrics
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val memInfo = android.app.ActivityManager.MemoryInfo().also { activityManager.getMemoryInfo(it) }
        val stat = StatFs(Environment.getDataDirectory().path)
        return jsonResult(buildJsonObject {
            put("model", "${Build.MANUFACTURER} ${Build.MODEL}")
            put("device", Build.DEVICE)
            put("android", Build.VERSION.RELEASE)
            put("sdk", Build.VERSION.SDK_INT)
            put("screen", "${dm.widthPixels}x${dm.heightPixels}@${dm.densityDpi}dpi")
            put("ram_total_mb", memInfo.totalMem / 1048576)
            put("ram_avail_mb", memInfo.availMem / 1048576)
            put("storage_total_gb", stat.totalBytes / 1073741824)
            put("storage_avail_gb", stat.availableBytes / 1073741824)
            put("locale", java.util.Locale.getDefault().toString())
            put("timezone", java.util.TimeZone.getDefault().id)
        })
    }
}

class BatteryTool @Inject constructor(
    @ApplicationContext private val context: Context,
) : McpTool {
    override val name = "get_battery"
    override val description = "获取电量、充电状态、电池健康与温度"
    override val category = ToolCategory.READ
    override val inputSchema: ToolSchema = inputSchema {}

    override suspend fun execute(args: JsonObject): CallToolResult {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return errorResult("无法读取电池状态")
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val statusText = when (status) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
            BatteryManager.BATTERY_STATUS_FULL -> "full"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not_charging"
            else -> "unknown"
        }
        return jsonResult(buildJsonObject {
            put("percent", if (scale > 0) level * 100 / scale else -1)
            put("status", statusText)
            put("temperature_c", intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0)
            put("voltage_mv", intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0))
        })
    }
}

class GetClipboardTool @Inject constructor(
    @ApplicationContext private val context: Context,
) : McpTool {
    override val name = "get_clipboard"
    override val description = "读取剪贴板文本"
    override val category = ToolCategory.READ
    override val defaultEnabled = false // 敏感：可能读到隐私内容
    override val inputSchema: ToolSchema = inputSchema {}

    override suspend fun execute(args: JsonObject): CallToolResult {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
        return if (text != null) textResult(text) else errorResult("剪贴板为空或不是文本")
    }
}

class SetClipboardTool @Inject constructor(
    @ApplicationContext private val context: Context,
) : McpTool {
    override val name = "set_clipboard"
    override val description = "写入剪贴板文本"
    override val category = ToolCategory.SYSTEM
    override val inputSchema: ToolSchema = inputSchema {
        string("text", "要写入剪贴板的文本", required = true)
    }

    override suspend fun execute(args: JsonObject): CallToolResult = withContext(Dispatchers.Main) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("mcp", args.reqStr("text")))
        textResult("已写入剪贴板")
    }
}

class ToastTool @Inject constructor(
    @ApplicationContext private val context: Context,
) : McpTool {
    override val name = "toast"
    override val description = "在屏幕上弹出 Toast 提示"
    override val category = ToolCategory.SYSTEM
    override val inputSchema: ToolSchema = inputSchema {
        string("text", "提示文本", required = true)
        boolean("long", "是否长时显示", required = false)
    }

    override suspend fun execute(args: JsonObject): CallToolResult = withContext(Dispatchers.Main) {
        val length = if (args["long"]?.toString()?.toBoolean() == true) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
        Toast.makeText(context, args.reqStr("text"), length).show()
        textResult("ok")
    }
}

class LaunchAppTool @Inject constructor(
    @ApplicationContext private val context: Context,
) : McpTool {
    override val name = "launch_app"
    override val description = "按包名启动应用（配合 list_apps 使用）"
    override val category = ToolCategory.SYSTEM
    override val inputSchema: ToolSchema = inputSchema {
        string("package", "目标应用包名", required = true)
    }

    override suspend fun execute(args: JsonObject): CallToolResult {
        val pkg = args.reqStr("package")
        val intent = context.packageManager.getLaunchIntentForPackage(pkg)
            ?: return errorResult("找不到应用或无启动入口: $pkg")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return textResult("已启动 $pkg")
    }
}

class OpenUrlTool @Inject constructor(
    @ApplicationContext private val context: Context,
) : McpTool {
    override val name = "open_url"
    override val description = "用系统默认浏览器/对应应用打开一个 URL"
    override val category = ToolCategory.SYSTEM
    override val inputSchema: ToolSchema = inputSchema {
        string("url", "要打开的 URL", required = true)
    }

    override suspend fun execute(args: JsonObject): CallToolResult {
        val url = args.reqStr("url")
        return runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            textResult("已打开 $url")
        }.getOrElse { errorResult("打开失败: ${it.message}") }
    }
}

class GetNetworkInfoTool @Inject constructor(
    @ApplicationContext private val context: Context,
) : McpTool {
    override val name = "get_network_info"
    override val description = "获取网络状态：WiFi 名称、局域网 IP、移动网络等"
    override val category = ToolCategory.READ
    override val inputSchema: ToolSchema = inputSchema {}

    override suspend fun execute(args: JsonObject): CallToolResult {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        val wifi = context.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
        val ips = dev.androidmcp.util.NetUtils.lanAddresses()
        return jsonResult(buildJsonObject {
            put("wifi_connected", caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true)
            put("cellular", caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) == true)
            put("wifi_ssid", wifi.connectionInfo?.ssid?.removeSurrounding("\"") ?: "unknown")
            put("lan_ips", kotlinx.serialization.json.JsonArray(ips.map { kotlinx.serialization.json.JsonPrimitive(it) }))
        })
    }
}
