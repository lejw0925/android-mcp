package dev.androidmcp.effects

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import dev.androidmcp.data.SettingsRepository
import dev.androidmcp.events.ToolCallEvent
import dev.androidmcp.events.ToolCallEventBus
import dev.androidmcp.events.ToolCallStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 特效悬浮层服务（普通 Service，非前台）：持有一个全屏穿透窗口承载边缘粒子，
 * 以及一个底部小窗口承载模糊胶囊，随工具调用事件驱动。
 *
 * 仅使用 TYPE_APPLICATION_OVERLAY，必须获得 SYSTEM_ALERT_WINDOW 悬浮窗授权；
 * 未授权时拒绝显示实时特效，避免无障碍权限被意外用于悬浮 UI。
 * 生命周期由 [sync] 按设置开关与授权状态统一决定。
 */
@AndroidEntryPoint
class EffectOverlayService : Service() {

    @Inject
    lateinit var events: ToolCallEventBus

    @Inject
    lateinit var settings: SettingsRepository

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var edgeView: EdgeParticleView? = null
    private var pillView: BottomPillView? = null
    private var edgeEnabled = true
    private var pillEnabled = true

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // 跟踪设置开关：两个都关时自行停止
        serviceScope.launch {
            combine(settings.edgeEffectEnabled, settings.bottomPillEnabled) { e, p -> e to p }
                .collect { (e, p) ->
                    edgeEnabled = e
                    pillEnabled = p
                    if (!e) detachEdge()
                    if (!p) detachPill()
                    if (!e && !p) stopSelf()
                }
        }
        serviceScope.launch {
            events.events.collect { onToolEvent(it) }
        }
        serviceScope.launch {
            events.uiMarkers.collect { marker ->
                if (edgeEnabled) ensureEdgeAttached()?.showMarker(marker.point.x, marker.point.y)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onDestroy() {
        detachEdge()
        detachPill()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun onToolEvent(event: ToolCallEvent) {
        if (event.status == ToolCallStatus.RUNNING && edgeEnabled) {
            val view = ensureEdgeAttached()
            view?.burst(event.category.effectColor.toInt())
        }
        if (pillEnabled) {
            val view = ensurePillAttached()
            view?.showEvent(event)
        }
    }

    // ---------- 窗口挂载 ----------

    /** 悬浮特效必须显式拥有悬浮窗权限。 */
    private fun overlayTarget(): Pair<WindowManager, Int>? =
        if (Settings.canDrawOverlays(this)) {
            getSystemService(WindowManager::class.java) to WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            null
        }

    private fun ensureEdgeAttached(): EdgeParticleView? {
        edgeView?.takeIf { it.isAttachedToWindow }?.let { return it }
        val (wm, type) = overlayTarget() ?: return null
        val view = edgeView ?: EdgeParticleView(this).also { edgeView = it }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        )
        return try {
            wm.addView(view, params)
            view
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "边缘粒子窗口挂载失败", t)
            null
        }
    }

    private fun ensurePillAttached(): BottomPillView? {
        pillView?.takeIf { it.isAttachedToWindow }?.let { return it }
        val (wm, type) = overlayTarget() ?: return null
        val view = pillView ?: BottomPillView(this).also { pill ->
            pill.onHidden = { detachPill() }
            pillView = pill
        }
        // 不使用 FLAG_BLUR_BEHIND：部分 OEM 会把它扩展为整屏模糊。
        // 小窗口自身绘制局部磨砂质感，窗口外像素完全不受影响。
        val params = pillParams(type)
        return try {
            wm.addView(view, params)
            view
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "底部胶囊窗口挂载失败", t)
            null
        }
    }

    /** 底部居中小窗口参数，距底约 96dp。 */
    private fun pillParams(type: Int): WindowManager.LayoutParams {
        val density = resources.displayMetrics.density
        val width = minOf(
            resources.displayMetrics.widthPixels - (48f * density).toInt(),
            (360f * density).toInt(),
        )
        val flags = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        return WindowManager.LayoutParams(
            width,
            (48f * density).toInt(),
            type,
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = (96f * density).toInt()
        }
    }

    private fun detachEdge() {
        edgeView?.let(::removeFromWindow)
    }

    private fun detachPill() {
        pillView?.let(::removeFromWindow)
    }

    /** 视图只挂在本服务的应用悬浮窗 WindowManager 上。 */
    private fun removeFromWindow(view: View) {
        if (!view.isAttachedToWindow) return
        runCatching { getSystemService(WindowManager::class.java).removeView(view) }
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface SyncEntryPoint {
        fun settings(): SettingsRepository
    }

    companion object {
        private const val TAG = "EffectOverlayService"

        /**
         * 按设置开关与权限/无障碍可用性同步特效服务：
         * 任一特效开启且悬浮窗可用 → startService；否则 stopService。
         * 在 MCP 服务启停与设置页开关变更后调用。
         */
        fun sync(context: Context) {
            val app = context.applicationContext
            CoroutineScope(Dispatchers.Default).launch {
                val repo = EntryPointAccessors.fromApplication(app, SyncEntryPoint::class.java).settings()
                val enabled = repo.edgeEffectEnabled.first() || repo.bottomPillEnabled.first()
                val canShow = Settings.canDrawOverlays(app)
                val intent = Intent(app, EffectOverlayService::class.java)
                if (enabled && canShow) {
                    runCatching { app.startService(intent) }
                        .onFailure { android.util.Log.w(TAG, "特效服务启动失败", it) }
                } else {
                    runCatching { app.stopService(intent) }
                }
            }
        }
    }
}
