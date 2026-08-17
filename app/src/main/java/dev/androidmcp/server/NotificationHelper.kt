package dev.androidmcp.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import dev.androidmcp.MainActivity
import dev.androidmcp.events.ToolCallEvent

object NotificationHelper {

    const val CHANNEL_SERVER = "mcp_server"
    const val CHANNEL_LIVE = "mcp_live_update"
    const val CHANNEL_PERMISSION = "mcp_permission"
    const val ID_SERVER = 1001
    const val ID_PERMISSION = 1003

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL_SERVER, "MCP 服务", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "MCP 前台服务常驻通知"
                },
                NotificationChannel(CHANNEL_LIVE, "工具调用实时状态", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "工具被调用时的 Live Update 实时状态"
                },
                NotificationChannel(CHANNEL_PERMISSION, "授权请求", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Agent 调用被权限拦截时引导授权"
                },
            ),
        )
    }

    private fun contentIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /**
     * 常驻服务通知。有工具执行中（[active] 非空）时切换为 Live Update：
     * ProgressStyle 不确定进度条 + 文案「⟡ <tool>（来自 <keyLabel>）」，
     * 并请求晋升为 Android 16+ 状态栏胶囊；无任务时回到基础端口/API Key 连接文案。
     */
    fun buildServerNotification(
        context: Context,
        port: Int,
        activeApiKeyCount: Int,
        active: ToolCallEvent?,
    ): Notification {
        val builder = NotificationCompat.Builder(context, CHANNEL_SERVER)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Android MCP 服务运行中")
            .setOngoing(true)
            .setContentIntent(contentIntent(context))
            .setSilent(true)
            // Android 16+ Live Update：请求晋升为状态栏胶囊（低版本系统忽略）
            .setRequestPromotedOngoing(true)
        if (active != null) {
            builder.setContentText("⟡ ${active.displayName}（来自 ${active.keyLabel}）")
                .setStyle(NotificationCompat.ProgressStyle().setProgressIndeterminate(true))
        } else {
            builder.setContentText("端口 $port · $activeApiKeyCount 个 API Key")
        }
        return builder.build()
    }

    fun update(context: Context, port: Int, activeApiKeyCount: Int, active: ToolCallEvent?) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.notify(ID_SERVER, buildServerNotification(context, port, activeApiKeyCount, active))
    }

    /** Agent 调用被权限拦截时的高优先级提醒，点击打开 App（弹窗由 App 内 pendingRequest 驱动）。 */
    fun notifyPermissionRequest(context: Context, request: dev.androidmcp.permission.AgentPermissionRequest) {
        val nm = context.getSystemService(NotificationManager::class.java)
        val caps = request.capabilities.joinToString("、") { it.title }
        nm.notify(
            ID_PERMISSION,
            NotificationCompat.Builder(context, CHANNEL_PERMISSION)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle("Agent 请求授权：${request.toolDisplayName}")
                .setContentText("来自 ${request.keyLabel}，需要：$caps")
                .setStyle(
                    NotificationCompat.BigTextStyle()
                        .bigText("来自 ${request.keyLabel} 的调用需要以下授权：$caps。点击打开 App 处理。"),
                )
                .setContentIntent(contentIntent(context))
                .setAutoCancel(true)
                .build(),
        )
    }
}
