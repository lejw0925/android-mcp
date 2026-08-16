package dev.androidmcp.ui.tools

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import dev.androidmcp.accessibility.A11yService
import dev.androidmcp.notification.NlService
import dev.androidmcp.tools.McpTool
import dev.androidmcp.tools.impl.RequiresDndAccess
import dev.androidmcp.tools.impl.RequiresNotificationAccess
import dev.androidmcp.ui.components.geminiDispersion

@Composable
fun ToolsScreen(
    bottomContentPadding: Dp = 0.dp,
    viewModel: ToolsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val permissionVersion by viewModel.permissionState.collectAsStateWithLifecycle()
    var permissionTarget by remember { mutableStateOf<McpTool?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { viewModel.refreshPermissions() }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refreshPermissions()
    }

    val grouped = viewModel.tools.groupBy { it.category }

    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Fixed(2),
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalItemSpacing = 10.dp,
        contentPadding = PaddingValues(bottom = bottomContentPadding + 16.dp),
    ) {
        item(span = StaggeredGridItemSpan.FullLine) {
            Column(modifier = Modifier.padding(top = 24.dp, bottom = 4.dp)) {
                Text(
                    "TOOLKIT",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    letterSpacing = 1.5.sp,
                )
                Text(
                    "工具与权限",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        grouped.forEach { (category, tools) ->
            item(span = StaggeredGridItemSpan.FullLine, key = "header_${category.name}") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 10.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .height(3.dp)
                            .fillMaxWidth(0.12f)
                            .clip(RoundedCornerShape(2.dp))
                            .background(
                                Brush.horizontalGradient(
                                    listOf(
                                        Color(category.effectColor),
                                        Color(category.effectColor).copy(alpha = 0f),
                                    ),
                                ),
                            ),
                    )
                    Text(
                        category.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        color = Color(category.effectColor),
                    )
                    Text(
                        "${tools.size} 项",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(tools, key = { it.name }) { tool ->
                ToolCard(
                    tool = tool,
                    viewModel = viewModel,
                    permissionVersion = permissionVersion,
                    onGrantClick = {
                        val special = specialPermissionIntent(context, tool)
                        if (special != null) {
                            context.startActivity(special)
                        } else if (tool.requiredPermissions.isNotEmpty()) {
                            permissionTarget = tool
                            permissionLauncher.launch(tool.requiredPermissions.toTypedArray())
                        }
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ToolCard(
    tool: McpTool,
    viewModel: ToolsViewModel,
    permissionVersion: Long,
    onGrantClick: () -> Unit,
) {
    val context = LocalContext.current
    val enabled by viewModel.enabledFlow(tool).collectAsStateWithLifecycle(initialValue = tool.defaultEnabled)
    val missing = remember(permissionVersion, tool, context) {
        tool.requiredPermissions.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
    }
    val needsA11y = remember(permissionVersion, tool, context) {
        tool.requiresAccessibility && !A11yService.isEnabled(context)
    }
    val showRestrictedSettingsGuide =
        needsA11y && A11yService.shouldShowRestrictedSettingsGuide(context)
    // 通知使用权 / DND 策略：系统特殊授权，NlService 未运行或策略未授予时给跳转 chip
    val needsNotificationAccess = remember(permissionVersion, tool, context) {
        tool is RequiresNotificationAccess && !NlService.isEnabled(context)
    }
    val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    val needsDndAccess = remember(permissionVersion, tool, nm) {
        tool is RequiresDndAccess && !nm.isNotificationPolicyAccessGranted
    }

    val shape = RoundedCornerShape(20.dp)
    val categoryColor = Color(tool.category.effectColor)

    Card(
        shape = shape,
        modifier = Modifier
            .fillMaxWidth()
            .geminiDispersion(shape = shape, strength = 0.65f),
    ) {
        Column {
            // 顶部分类色渐变细条
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .background(
                        Brush.horizontalGradient(
                            listOf(categoryColor, categoryColor.copy(alpha = 0.15f)),
                        ),
                    ),
            )
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        tool.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(checked = enabled, onCheckedChange = { viewModel.setEnabled(tool, it) })
                }
                Text(
                    tool.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                if (missing.isNotEmpty() || needsA11y || needsNotificationAccess || needsDndAccess || !tool.defaultEnabled) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(0.dp),
                        modifier = Modifier.padding(top = 6.dp),
                    ) {
                        if (!tool.defaultEnabled) {
                            AssistChip(
                                onClick = {},
                                label = { Text("默认关闭", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                            )
                        }
                        if (needsA11y) {
                            if (showRestrictedSettingsGuide) {
                                AssistChip(
                                    onClick = { context.startActivity(A11yService.appManagementIntent(context)) },
                                    label = {
                                        Text(
                                            "1. 解除设置限制",
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    },
                                )
                            }
                            AssistChip(
                                onClick = {
                                    context.startActivity(A11yService.accessibilitySettingsIntent())
                                },
                                label = {
                                    Text(
                                        if (showRestrictedSettingsGuide) {
                                            "2. 开启无障碍"
                                        } else {
                                            "需开启无障碍"
                                        },
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                },
                            )
                        }
                        if (needsNotificationAccess) {
                            AssistChip(
                                onClick = { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
                                label = { Text("去授权通知使用权", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                            )
                        }
                        if (needsDndAccess) {
                            AssistChip(
                                onClick = { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) },
                                label = { Text("去授权勿扰权限", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                            )
                        }
                        if (missing.isNotEmpty()) {
                            TextButton(onClick = onGrantClick) {
                                Text("授权 ${missing.size} 项权限", color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** WRITE_SETTINGS 等特殊权限需要跳系统页授予。 */
private fun specialPermissionIntent(context: Context, tool: McpTool): Intent? = when {
    Manifest.permission.WRITE_SETTINGS in tool.requiredPermissions && !Settings.System.canWrite(context) ->
        Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}"))
    else -> null
}
