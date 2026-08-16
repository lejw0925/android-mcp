package dev.androidmcp.ui.permissions

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.androidmcp.accessibility.A11yService
import dev.androidmcp.permission.Capability
import dev.androidmcp.ui.components.geminiDispersion

/**
 * 授权中心：逐项展示能力授权状态、需要它的原因、相关工具 tags，并提供授权入口。
 * 状态在页面恢复前台时刷新（从系统授权页返回后立即反映）。
 */
@Composable
fun PermissionsScreen(
    onBack: () -> Unit,
    viewModel: PermissionsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val center = viewModel.permissionCenter
    val version by center.stateVersion.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        center.refresh()
    }

    val runtimeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { center.refresh() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = Color.White,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "ACCESS CONTROL",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    letterSpacing = 1.5.sp,
                )
                Text(
                    "权限与授权",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            IconButton(onClick = center::refresh) {
                Icon(Icons.Default.Refresh, contentDescription = "刷新授权状态")
            }
        }
        Text(
            "Agent 调用需要这些授权才能完整工作。未授权的项会在被调用时提醒你。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        key(version) {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(center.allCapabilities, key = { it.id }) { cap ->
                    val granted = center.isGranted(cap)
                    val showRestrictedSettingsGuide =
                        cap.id == "accessibility" &&
                            !granted &&
                            A11yService.shouldShowRestrictedSettingsGuide(context)
                    CapabilityCard(
                        cap = cap,
                        granted = granted,
                        showRestrictedSettingsGuide = showRestrictedSettingsGuide,
                        relatedTools = center.relatedTools(cap),
                        onOpenAppManagement = {
                            runCatching { context.startActivity(A11yService.appManagementIntent(context)) }
                        },
                        onGrant = {
                            if (cap.runtimePermissions.isNotEmpty()) {
                                runtimeLauncher.launch(cap.runtimePermissions.toTypedArray())
                            } else {
                                center.grantIntent(cap)?.let { runCatching { context.startActivity(it) } }
                            }
                        },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CapabilityCard(
    cap: Capability,
    granted: Boolean,
    showRestrictedSettingsGuide: Boolean,
    relatedTools: List<String>,
    onOpenAppManagement: () -> Unit,
    onGrant: () -> Unit,
) {
    val statusColor = if (granted) Color(0xFF65D69A) else MaterialTheme.colorScheme.error
    val shape = RoundedCornerShape(20.dp)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .geminiDispersion(shape = shape, strength = if (granted) 0.3f else 0.55f),
        shape = shape,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 状态圆点
                Box(
                    Modifier
                        .size(10.dp)
                        .background(statusColor, CircleShape),
                )
                Text(
                    cap.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(start = 10.dp).weight(1f),
                )
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = statusColor.copy(alpha = 0.15f),
                    contentColor = statusColor,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        if (granted) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                        Text(
                            when {
                                granted -> "已授权"
                                showRestrictedSettingsGuide -> "需两步授权"
                                else -> "未授权"
                            },
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
            Text(
                cap.reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (cap.id == "accessibility") {
                Text(
                    if (showRestrictedSettingsGuide) {
                        "系统仍在限制侧载应用。先打开应用信息，在更多菜单或页面底部点「解除设置限制／允许受限设置」，确认后再回来开启无障碍，否则返回列表时会被系统自动关闭。"
                    } else {
                        "请在系统无障碍列表中开启「Android MCP 无障碍服务」。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (relatedTools.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    relatedTools.forEach { tool ->
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                        ) {
                            Text(
                                tool,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
            }
            if (!granted) {
                Row(
                    modifier = Modifier.align(Alignment.End),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (showRestrictedSettingsGuide) {
                        TextButton(onClick = onOpenAppManagement) {
                            Text("1. 解除设置限制")
                        }
                    }
                    TextButton(onClick = onGrant) {
                        Text(
                            when {
                                cap.runtimePermissions.isNotEmpty() -> "弹窗授权"
                                showRestrictedSettingsGuide -> "2. 开启无障碍"
                                else -> "去授权 →"
                            },
                        )
                    }
                }
            }
        }
    }
}
