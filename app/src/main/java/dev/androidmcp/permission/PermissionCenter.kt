package dev.androidmcp.permission

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.androidmcp.accessibility.A11yService
import dev.androidmcp.notification.NlService
import dev.androidmcp.server.NotificationHelper
import dev.androidmcp.shizuku.ShizukuManager
import dev.androidmcp.tools.McpTool
import dev.androidmcp.tools.impl.RequiresDndAccess
import dev.androidmcp.tools.impl.RequiresNotificationAccess
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 一项可被用户授权的能力：无障碍、悬浮窗、通知、Shizuku 等系统特殊授权，
 * 或按运行时权限分组（通讯录/短信/位置…）的普通授权。
 */
data class Capability(
    val id: String,
    val title: String,
    val reason: String,
    /** 非空表示这是一组运行时权限，授权方式是系统权限弹窗。 */
    val runtimePermissions: List<String> = emptyList(),
)

/** Agent 调用被权限拦截时产生的一次授权请求（驱动 App 内弹窗与通知）。 */
data class AgentPermissionRequest(
    val toolName: String,
    val keyLabel: String,
    val capabilities: List<Capability>,
    val toolDisplayName: String = toolName,
    val at: Long = System.currentTimeMillis(),
)

/**
 * 权限中枢：集中回答"某项能力是否已授权 / 去哪儿授权 / 哪些工具需要它"，
 * 并在 Agent 调用被权限拦截时生成待处理的授权请求（App 内弹窗 + 系统通知）。
 */
@Singleton
class PermissionCenter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val shizuku: ShizukuManager,
    val tools: Set<@JvmSuppressWildcards McpTool>,
) {

    // ---------------- 能力清单 ----------------

    /** 系统特殊授权能力（固定清单）。 */
    val specialCapabilities: List<Capability> = listOf(
        Capability(
            "accessibility", "无障碍服务",
            "读取屏幕 UI 树、模拟点击/滑动/手势、无障碍截图，是 UI 自动化工具的基础",
        ),
        Capability(
            "overlay", "悬浮窗",
            "工具被调用时在屏幕边缘绘制彩色粒子光效，并在底部显示毛玻璃胶囊",
        ),
        Capability(
            "notifications", "通知",
            "前台服务常驻通知与 Live Update 实时状态（状态栏胶囊）",
        ),
        Capability(
            "notif_listener", "通知使用权",
            "读取与清除其他应用推送的通知",
        ),
        Capability(
            "dnd", "勿扰模式访问",
            "读取与切换系统勿扰模式",
        ),
        Capability(
            "battery", "后台常驻",
            "加入电池优化白名单，防止厂商 ROM 在后台杀掉 MCP 服务",
        ),
        Capability(
            "shizuku", "Shizuku",
            "以 shell（ADB）级权限执行命令、读写系统设置等高级操作",
        ),
    )

    /** 运行时权限 → 展示名/原因。 */
    private val runtimeMeta: Map<String, Pair<String, String>> = mapOf(
        Manifest.permission.READ_CONTACTS to ("通讯录" to "读取联系人，供查询联系人工具使用"),
        Manifest.permission.READ_SMS to ("短信" to "读取短信收件箱"),
        Manifest.permission.SEND_SMS to ("短信" to "发送短信"),
        Manifest.permission.READ_CALL_LOG to ("通话记录" to "读取通话记录"),
        Manifest.permission.CALL_PHONE to ("电话" to "直接拨打电话"),
        Manifest.permission.ACCESS_FINE_LOCATION to ("位置" to "获取精确地理位置"),
        Manifest.permission.ACCESS_COARSE_LOCATION to ("位置" to "获取大致地理位置"),
        Manifest.permission.READ_CALENDAR to ("日历" to "读取日历日程"),
        Manifest.permission.WRITE_CALENDAR to ("日历" to "写入日历日程"),
        Manifest.permission.CAMERA to ("相机" to "控制闪光灯等相机能力"),
        Manifest.permission.WRITE_SETTINGS to ("修改系统设置" to "修改亮度、旋转等系统设置项"),
    )

    /** 按权限组合并出的运行时能力（只包含工具实际声明的）。 */
    val runtimeCapabilities: List<Capability> by lazy {
        val byGroup = LinkedHashMap<String, MutableList<String>>() // title -> permissions
        tools.flatMap { it.requiredPermissions }.distinct().forEach { perm ->
            val title = runtimeMeta[perm]?.first ?: perm.substringAfterLast('.')
            byGroup.getOrPut(title) { mutableListOf() }.add(perm)
        }
        byGroup.map { (title, perms) ->
            val reason = perms.mapNotNull { runtimeMeta[it]?.second }.distinct().joinToString("；")
                .ifEmpty { "工具声明的运行时权限" }
            Capability(
                id = "runtime:$title",
                title = title,
                reason = reason,
                runtimePermissions = perms.sorted(),
            )
        }
    }

    val allCapabilities: List<Capability> get() = specialCapabilities + runtimeCapabilities

    private val _stateVersion = MutableStateFlow(0L)
    val stateVersion: StateFlow<Long> = _stateVersion

    /** 系统授权页或运行时权限弹窗返回后调用，以重新读取同步授权状态。 */
    fun refresh() {
        _stateVersion.value++
    }

    fun byId(id: String): Capability? = allCapabilities.firstOrNull { it.id == id }

    // ---------------- 状态检查 ----------------

    fun isGranted(cap: Capability): Boolean {
        if (cap.runtimePermissions.isNotEmpty()) {
            return cap.runtimePermissions.all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }
        }
        return when (cap.id) {
            "accessibility" -> A11yService.isEnabled(context)
            "overlay" -> Settings.canDrawOverlays(context)
            "notifications" ->
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
            "notif_listener" -> NlService.isEnabled(context)
            "dnd" -> context.getSystemService(NotificationManager::class.java)
                .isNotificationPolicyAccessGranted
            "battery" -> context.getSystemService(PowerManager::class.java)
                .isIgnoringBatteryOptimizations(context.packageName)
            "shizuku" -> shizuku.isReady()
            else -> false
        }
    }

    /** 跳系统授权页的 Intent；运行时权限返回 null（应走权限弹窗）。 */
    fun grantIntent(cap: Capability): Intent? {
        if (cap.runtimePermissions.isNotEmpty()) return null
        val pkg = "package:${context.packageName}"
        return when (cap.id) {
            "accessibility" -> A11yService.accessibilitySettingsIntent()
            "overlay" -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse(pkg))
            "notifications" -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            "notif_listener" -> Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            "dnd" -> Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
            "battery" -> Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse(pkg))
            "shizuku" -> context.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
            else -> null
        }
    }

    /** 需要该能力的工具名列表（授权页 tags / 弹窗展示用）。 */
    fun relatedTools(cap: Capability): List<String> = tools.filter { tool ->
        if (cap.runtimePermissions.isNotEmpty()) {
            tool.requiredPermissions.any { it in cap.runtimePermissions }
        } else when (cap.id) {
            "accessibility" -> tool.requiresAccessibility
            "shizuku" -> tool.requiresShizuku
            "notif_listener" -> tool is RequiresNotificationAccess
            "dnd" -> tool is RequiresDndAccess
            else -> false
        }
    }.map { it.displayName }.sorted()

    // ---------------- 工具调用前检查 ----------------

    /** 工具执行前缺失的能力清单（空 = 可执行）。 */
    fun missingFor(tool: McpTool): List<Capability> {
        val missing = mutableListOf<Capability>()
        if (tool.requiresAccessibility && !A11yService.isRunning()) {
            specialCapabilities.first { it.id == "accessibility" }.also(missing::add)
        }
        if (tool.requiresShizuku && !shizuku.isReady()) {
            specialCapabilities.first { it.id == "shizuku" }.also(missing::add)
        }
        if (tool is RequiresNotificationAccess && !NlService.isRunning()) {
            specialCapabilities.first { it.id == "notif_listener" }.also(missing::add)
        }
        if (tool is RequiresDndAccess &&
            !context.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted
        ) {
            specialCapabilities.first { it.id == "dnd" }.also(missing::add)
        }
        val missingRuntime = tool.requiredPermissions.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missingRuntime.isNotEmpty()) {
            runtimeCapabilities
                .filter { cap -> cap.runtimePermissions.any(missingRuntime::contains) }
                .forEach(missing::add)
        }
        return missing
    }

    // ---------------- Agent 触发的授权请求 ----------------

    private val _pendingRequest = MutableStateFlow<AgentPermissionRequest?>(null)
    val pendingRequest: StateFlow<AgentPermissionRequest?> = _pendingRequest

    /** 节流：同一 tool+能力组合 60 秒内只弹/通知一次。 */
    private val recentRequests = ConcurrentHashMap<String, Long>()

    /** Agent 调用被权限拦截时调用：App 内弹窗（若在前台）+ 系统通知引导用户授权。 */
    fun reportAgentBlocked(tool: McpTool, keyLabel: String, missing: List<Capability>) {
        if (missing.isEmpty()) return
        val signature = tool.name + ":" + missing.joinToString("+") { it.id }
        val now = System.currentTimeMillis()
        val last = recentRequests[signature] ?: 0L
        if (now - last < 60_000) return
        recentRequests[signature] = now

        val request = AgentPermissionRequest(
            toolName = tool.name,
            keyLabel = keyLabel,
            capabilities = missing,
            toolDisplayName = tool.displayName,
        )
        _pendingRequest.value = request
        NotificationHelper.notifyPermissionRequest(context, request)
    }

    fun dismissRequest() {
        _pendingRequest.value = null
    }
}
