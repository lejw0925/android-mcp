package dev.androidmcp.server

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import dev.androidmcp.data.SettingsRepository
import dev.androidmcp.effects.EffectOverlayService
import dev.androidmcp.events.ToolCallEventBus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * MCP 前台服务：托管 [McpServerManager]，常驻通知展示端口/API Key 连接数/当前执行工具。
 *
 * 启动方式：context.startForegroundService(Intent(..., ACTION_START))。
 */
@AndroidEntryPoint
class McpServerService : LifecycleService() {

    @Inject
    lateinit var serverManager: McpServerManager

    @Inject
    lateinit var settings: SettingsRepository

    @Inject
    lateinit var events: ToolCallEventBus

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.ensureChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            else -> startServer()
        }
        return START_STICKY
    }

    private fun startServer() {
        lifecycleScope.launch {
            val port = settings.port.first()
            val notification: Notification =
                NotificationHelper.buildServerNotification(this@McpServerService, port, 0, null)
            ServiceCompat.startForeground(
                this@McpServerService,
                NotificationHelper.ID_SERVER,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
            serverManager.start(port)
            observeEvents()
            // 服务启动后同步特效悬浮层（按设置开关与权限决定起停）
            EffectOverlayService.sync(this@McpServerService)
        }
    }

    private fun observeEvents() {
        lifecycleScope.launch {
            events.events.collect {
                // 直接取事件总线的 active：并发调用时不会被无关的完成事件误清
                val active = events.active.value
                val state = serverManager.state.value
                if (settings.liveUpdateEnabled.first()) {
                    NotificationHelper.update(this@McpServerService, state.port, state.activeApiKeys, active)
                }
            }
        }
    }

    override fun onDestroy() {
        EffectOverlayService.sync(this)
        serverManager.stop()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "dev.androidmcp.action.START_SERVER"
        const val ACTION_STOP = "dev.androidmcp.action.STOP_SERVER"

        fun start(context: Context) {
            val intent = Intent(context, McpServerService::class.java).setAction(ACTION_START)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, McpServerService::class.java).setAction(ACTION_STOP))
        }
    }
}
