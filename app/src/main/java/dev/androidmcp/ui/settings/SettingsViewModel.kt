package dev.androidmcp.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.androidmcp.data.SettingsRepository
import dev.androidmcp.effects.EffectOverlayService
import dev.androidmcp.shizuku.ShizukuManager
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    val shizuku: ShizukuManager,
) : ViewModel() {

    val port = settings.port.stateIn(viewModelScope, SharingStarted.Eagerly, 8080)
    val autoStart = settings.autoStartOnBoot.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val edgeEffect = settings.edgeEffectEnabled.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val bottomPill = settings.bottomPillEnabled.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val liveUpdate = settings.liveUpdateEnabled.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val shizukuEnabled = settings.shizukuEnabled.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setPort(value: Int) = viewModelScope.launch { settings.setPort(value) }
    fun setAutoStart(value: Boolean) = viewModelScope.launch { settings.setAutoStartOnBoot(value) }
    fun setEdgeEffect(value: Boolean) = viewModelScope.launch {
        // 特效严格要求显式悬浮窗授权；没有授权时拒绝开启。
        settings.setEdgeEffectEnabled(value && Settings.canDrawOverlays(context))
        EffectOverlayService.sync(context)
    }

    fun setBottomPill(value: Boolean) = viewModelScope.launch {
        settings.setBottomPillEnabled(value && Settings.canDrawOverlays(context))
        EffectOverlayService.sync(context)
    }
    fun setLiveUpdate(value: Boolean) = viewModelScope.launch { settings.setLiveUpdateEnabled(value) }
    fun setShizukuEnabled(value: Boolean) = viewModelScope.launch {
        // Shizuku 没有 binder 或尚未授权时绝不启用 shell 工具。
        val ready = shizuku.isReady()
        settings.setShizukuEnabled(value && ready)
        if (value && !ready) requestShizukuAuthorization()
    }

    fun requestShizukuAuthorization() {
        if (!shizuku.isInstalled()) {
            context.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                ?.let { runCatching { context.startActivity(it) } }
            return
        }
        if (shizuku.pingBinder()) shizuku.requestPermission()
        else context.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
            ?.let { runCatching { context.startActivity(it) } }
    }

    fun isIgnoringBatteryOptimizations(): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /** 请求将本应用加入电池优化白名单；不可用时打开系统白名单设置页。 */
    fun requestBatteryOptimizationExemption(): Boolean {
        val requestIntent = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val fallbackIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val intent = if (requestIntent.resolveActivity(context.packageManager) != null) {
            requestIntent
        } else if (fallbackIntent.resolveActivity(context.packageManager) != null) {
            fallbackIntent
        } else {
            return false
        }
        return runCatching {
            context.startActivity(intent)
            true
        }.getOrElse {
            if (intent == requestIntent && fallbackIntent.resolveActivity(context.packageManager) != null) {
                runCatching {
                    context.startActivity(fallbackIntent)
                    true
                }.getOrDefault(false)
            } else {
                false
            }
        }
    }
}
