package dev.androidmcp.ui.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.androidmcp.ui.components.geminiDispersion

@Composable
fun SettingsScreen(
    onOpenPermissions: () -> Unit,
    bottomContentPadding: Dp = 0.dp,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val port by viewModel.port.collectAsStateWithLifecycle()
    val autoStart by viewModel.autoStart.collectAsStateWithLifecycle()
    val edgeEffect by viewModel.edgeEffect.collectAsStateWithLifecycle()
    val bottomPill by viewModel.bottomPill.collectAsStateWithLifecycle()
    val liveUpdate by viewModel.liveUpdate.collectAsStateWithLifecycle()
    val shizukuEnabled by viewModel.shizukuEnabled.collectAsStateWithLifecycle()
    val shizukuBinder by viewModel.shizuku.binderReady.collectAsStateWithLifecycle()
    val shizukuPermission by viewModel.shizuku.permissionGranted.collectAsStateWithLifecycle()

    // 从系统设置页返回时重新读取系统授权状态。
    var effectOverlayAvailable by remember { mutableStateOf(true) }
    var batteryOptimizationExempt by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        effectOverlayAvailable = Settings.canDrawOverlays(context)
        batteryOptimizationExempt = viewModel.isIgnoringBatteryOptimizations()
        onPauseOrDispose { }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(
                start = 16.dp,
                top = 16.dp,
                end = 16.dp,
                bottom = 16.dp + bottomContentPadding,
            ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column {
            Text(
                "PREFERENCES",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                letterSpacing = 1.5.sp,
            )
            Text("设置", style = MaterialTheme.typography.headlineSmall)
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .geminiDispersion(strength = 0.45f)
                .clickable(onClick = onOpenPermissions),
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("权限与授权", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "查看能力授权状态与相关工具",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text("→", style = MaterialTheme.typography.titleLarge)
            }
        }

        Card(Modifier.fillMaxWidth().geminiDispersion(strength = 0.35f)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("服务", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = port.toString(),
                    onValueChange = { it.toIntOrNull()?.let(viewModel::setPort) },
                    label = { Text("监听端口（重启服务后生效）") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                SettingSwitch("开机自启", "设备重启后自动启动 MCP 服务", autoStart, viewModel::setAutoStart)
                Text(
                    if (batteryOptimizationExempt) "已加入后台白名单" else "未加入后台白名单",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (batteryOptimizationExempt) Color(0xFF65D69A) else MaterialTheme.colorScheme.error,
                )
                TextButton(onClick = viewModel::requestBatteryOptimizationExemption) {
                    Text("请求后台常驻白名单", color = MaterialTheme.colorScheme.primary)
                }
            }
        }

        Card(Modifier.fillMaxWidth().geminiDispersion(strength = 0.35f)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("实时反馈与特效", style = MaterialTheme.typography.titleMedium)
                SettingSwitch("Live Update 通知", "状态栏实时显示当前执行的工具", liveUpdate, viewModel::setLiveUpdate)
                SettingSwitch(
                    "边缘粒子光效", "工具被调用时屏幕边缘的彩色粒子（需悬浮窗权限）",
                    edgeEffect, viewModel::setEdgeEffect, enabled = effectOverlayAvailable,
                )
                SettingSwitch(
                    "底部模糊胶囊", "屏幕底部毛玻璃滚动显示最近调用（需悬浮窗权限）",
                    bottomPill, viewModel::setBottomPill, enabled = effectOverlayAvailable,
                )
                if (!effectOverlayAvailable) {
                    Text(
                        "未授予悬浮窗权限，实时特效已被拒绝启用。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    TextButton(onClick = {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}"),
                            ),
                        )
                    }) {
                        Text("去开启悬浮窗权限", color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }

        Card(Modifier.fillMaxWidth().geminiDispersion(strength = 0.35f)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Shizuku（高级工具）", style = MaterialTheme.typography.titleMedium)
                Text(
                    "状态：" + when {
                        !viewModel.shizuku.isInstalled() -> "未安装 Shizuku"
                        !shizukuBinder -> "已安装，服务未运行"
                        !shizukuPermission -> "运行中，未授权本应用"
                        else -> "就绪"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SettingSwitch(
                    "启用 Shizuku 工具",
                    "run_shell / logcat / settings 等 shell 级工具",
                    shizukuEnabled,
                    viewModel::setShizukuEnabled,
                    enabled = shizukuBinder && shizukuPermission,
                )
                if (!shizukuBinder || !shizukuPermission) {
                    TextButton(onClick = viewModel::requestShizukuAuthorization) {
                        Text(if (viewModel.shizuku.isInstalled()) "打开 Shizuku 并授权" else "安装 Shizuku 后再授权")
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
    HorizontalDivider()
}
