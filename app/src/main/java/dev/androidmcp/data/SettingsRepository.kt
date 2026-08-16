package dev.androidmcp.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settingsStore by preferencesDataStore(name = "settings")

/**
 * 全局设置 + 工具级开关。工具开关用 stringSet 存"被禁用"的工具名（默认全部启用，
 * 敏感工具的默认关闭由工具自身的 [dev.androidmcp.tools.McpTool.defaultEnabled] 决定，
 * 这里再叠加一个 stringSet 记录"用户显式启用"的默认关闭工具）。
 */
@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val store = context.settingsStore

    val port: Flow<Int> = store.data.map { it[KEY_PORT] ?: 8080 }
    val autoStartOnBoot: Flow<Boolean> = store.data.map { it[KEY_AUTOSTART] ?: true }
    val edgeEffectEnabled: Flow<Boolean> = store.data.map { it[KEY_EDGE_EFFECT] ?: true }
    val bottomPillEnabled: Flow<Boolean> = store.data.map { it[KEY_BOTTOM_PILL] ?: true }
    val liveUpdateEnabled: Flow<Boolean> = store.data.map { it[KEY_LIVE_UPDATE] ?: true }
    val screenshotScale: Flow<Float> = store.data.map { (it[KEY_SHOT_SCALE] ?: "0.5").toFloatOrNull() ?: 0.5f }
    val screenshotQuality: Flow<Int> = store.data.map { it[KEY_SHOT_QUALITY] ?: 80 }
    val shizukuEnabled: Flow<Boolean> = store.data.map { it[KEY_SHIZUKU] ?: false }

    private val disabledTools: Flow<Set<String>> = store.data.map { it[KEY_DISABLED_TOOLS] ?: emptySet() }
    private val enabledSensitiveTools: Flow<Set<String>> = store.data.map { it[KEY_ENABLED_SENSITIVE] ?: emptySet() }

    suspend fun setPort(value: Int) = store.edit { it[KEY_PORT] = value.coerceIn(1024, 65535) }
    suspend fun setAutoStartOnBoot(value: Boolean) = store.edit { it[KEY_AUTOSTART] = value }
    suspend fun setEdgeEffectEnabled(value: Boolean) = store.edit { it[KEY_EDGE_EFFECT] = value }
    suspend fun setBottomPillEnabled(value: Boolean) = store.edit { it[KEY_BOTTOM_PILL] = value }
    suspend fun setLiveUpdateEnabled(value: Boolean) = store.edit { it[KEY_LIVE_UPDATE] = value }
    suspend fun setScreenshotScale(value: Float) = store.edit { it[KEY_SHOT_SCALE] = value.toString() }
    suspend fun setScreenshotQuality(value: Int) = store.edit { it[KEY_SHOT_QUALITY] = value.coerceIn(10, 100) }
    suspend fun setShizukuEnabled(value: Boolean) = store.edit { it[KEY_SHIZUKU] = value }

    /** 工具是否启用：默认关闭的敏感工具需显式启用；其余默认启用除非被禁用。 */
    suspend fun isToolEnabled(toolName: String, defaultEnabled: Boolean): Boolean {
        val disabled = disabledTools.first()
        val enabledSensitive = enabledSensitiveTools.first()
        return if (defaultEnabled) toolName !in disabled else toolName in enabledSensitive
    }

    fun toolEnabledFlow(toolName: String, defaultEnabled: Boolean): Flow<Boolean> =
        store.data.map { prefs ->
            val disabled = prefs[KEY_DISABLED_TOOLS] ?: emptySet()
            val enabledSensitive = prefs[KEY_ENABLED_SENSITIVE] ?: emptySet()
            if (defaultEnabled) toolName !in disabled else toolName in enabledSensitive
        }

    suspend fun setToolEnabled(toolName: String, defaultEnabled: Boolean, enabled: Boolean) {
        store.edit { prefs ->
            val disabled = (prefs[KEY_DISABLED_TOOLS] ?: emptySet()).toMutableSet()
            val enabledSensitive = (prefs[KEY_ENABLED_SENSITIVE] ?: emptySet()).toMutableSet()
            if (defaultEnabled) {
                if (enabled) disabled.remove(toolName) else disabled.add(toolName)
            } else {
                if (enabled) enabledSensitive.add(toolName) else enabledSensitive.remove(toolName)
            }
            prefs[KEY_DISABLED_TOOLS] = disabled
            prefs[KEY_ENABLED_SENSITIVE] = enabledSensitive
        }
    }

    companion object {
        private val KEY_PORT = intPreferencesKey("server_port")
        private val KEY_AUTOSTART = booleanPreferencesKey("auto_start_on_boot")
        private val KEY_EDGE_EFFECT = booleanPreferencesKey("effect_edge_particles")
        private val KEY_BOTTOM_PILL = booleanPreferencesKey("effect_bottom_pill")
        private val KEY_LIVE_UPDATE = booleanPreferencesKey("live_update_notification")
        private val KEY_SHOT_SCALE = stringPreferencesKey("screenshot_scale")
        private val KEY_SHOT_QUALITY = intPreferencesKey("screenshot_quality")
        private val KEY_SHIZUKU = booleanPreferencesKey("shizuku_enabled")
        private val KEY_DISABLED_TOOLS = stringSetPreferencesKey("disabled_tools")
        private val KEY_ENABLED_SENSITIVE = stringSetPreferencesKey("enabled_sensitive_tools")
    }
}
