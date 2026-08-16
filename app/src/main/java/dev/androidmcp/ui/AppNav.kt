package dev.androidmcp.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.androidmcp.permission.Capability
import dev.androidmcp.ui.components.animatedGeminiBrush
import dev.androidmcp.ui.components.geminiDispersion
import dev.androidmcp.ui.components.geminiGlowBorder
import dev.androidmcp.ui.dashboard.DashboardScreen
import dev.androidmcp.ui.feed.LiveFeedScreen
import dev.androidmcp.ui.permissions.PermissionsScreen
import dev.androidmcp.ui.settings.SettingsScreen
import dev.androidmcp.ui.tools.ToolsScreen
import dev.androidmcp.ui.tunnel.TunnelScreen

object Routes {
    const val DASHBOARD = "dashboard"
    const val TOOLS = "tools"
    const val FEED = "feed"
    const val SETTINGS = "settings"
    const val TUNNEL = "tunnel"
    const val PERMISSIONS = "permissions"
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(Routes.DASHBOARD, "服务", Icons.Default.Home),
    Tab(Routes.TOOLS, "工具", Icons.Default.Build),
    Tab(Routes.FEED, "动态", Icons.Default.List),
    Tab(Routes.SETTINGS, "设置", Icons.Default.Settings),
)

@Composable
fun AppNav(mainViewModel: MainViewModel = hiltViewModel()) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val pendingRequest by mainViewModel.pendingRequest.collectAsStateWithLifecycle()
    val density = LocalDensity.current
    var floatingBarHeightPx by remember { mutableIntStateOf(0) }
    val floatingBarContentPadding = with(density) { floatingBarHeightPx.toDp() }
    val showFloatingBar = tabs.any { it.route == currentRoute }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        Box(Modifier.fillMaxSize()) {
            NavHost(
                navController = navController,
                startDestination = Routes.DASHBOARD,
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding(),
            ) {
                composable(Routes.DASHBOARD) {
                    DashboardScreen(
                        onOpenTunnel = { navController.navigate(Routes.TUNNEL) },
                        bottomContentPadding = floatingBarContentPadding,
                    )
                }
                composable(Routes.TOOLS) { ToolsScreen(bottomContentPadding = floatingBarContentPadding) }
                composable(Routes.FEED) { LiveFeedScreen(bottomContentPadding = floatingBarContentPadding) }
                composable(Routes.SETTINGS) {
                    SettingsScreen(
                        onOpenPermissions = { navController.navigate(Routes.PERMISSIONS) },
                        bottomContentPadding = floatingBarContentPadding,
                    )
                }
                composable(Routes.PERMISSIONS) { PermissionsScreen(onBack = { navController.popBackStack() }) }
                composable(Routes.TUNNEL) { TunnelScreen(onBack = { navController.popBackStack() }) }
            }

            if (showFloatingBar) {
                FloatingTabBar(
                    currentRoute = currentRoute,
                    onSelect = { route ->
                        navController.navigate(route) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .onSizeChanged { size -> floatingBarHeightPx = size.height },
                )
            }
        }
    }

    // Agent 调用被权限拦截时的引导授权弹窗
    pendingRequest?.let { request ->
        AgentPermissionDialog(
            toolName = request.toolDisplayName,
            keyLabel = request.keyLabel,
            capabilities = request.capabilities,
            onGrant = { cap ->
                if (cap.runtimePermissions.isEmpty()) {
                    mainViewModel.permissionCenter.grantIntent(cap)?.let { intent ->
                        runCatching { context.startActivity(intent) }
                    }
                }
            },
            onDismiss = { mainViewModel.dismissRequest() },
        )
    }
}

/** 类 iOS 26 悬浮底栏：半透明胶囊 + Gemini 渐变描边，选中项渐变药丸高亮。 */
@Composable
private fun FloatingTabBar(
    currentRoute: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(32.dp)
    Surface(
        modifier = modifier
            .padding(horizontal = 20.dp)
            .padding(bottom = 12.dp)
            .navigationBarsPadding()
            .geminiGlowBorder(shape = shape, width = 1.dp, alpha = 0.5f),
        shape = shape,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
        tonalElevation = 6.dp,
        shadowElevation = 16.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEach { tab ->
                val selected = currentRoute == tab.route
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable { onSelect(tab.route) }
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .then(
                                if (selected) {
                                    Modifier
                                        .background(animatedGeminiBrush())
                                        .geminiDispersion(shape = CircleShape, strength = 0.45f)
                                } else {
                                    Modifier
                                },
                            )
                            .padding(horizontal = 14.dp, vertical = 5.dp),
                    ) {
                        Icon(
                            tab.icon,
                            contentDescription = tab.label,
                            tint = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        tab.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Agent 触发工具需要额外授权时的引导弹窗：列出缺失能力、原因，逐项给授权入口。 */
@Composable
private fun AgentPermissionDialog(
    toolName: String,
    keyLabel: String,
    capabilities: List<Capability>,
    onGrant: (Capability) -> Unit,
    onDismiss: () -> Unit,
) {
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Agent 请求授权") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "「$keyLabel」试图调用 `$toolName`，需要以下授权：",
                    style = MaterialTheme.typography.bodyMedium,
                )
                capabilities.forEach { cap ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                            .clickable {
                                if (cap.runtimePermissions.isNotEmpty()) {
                                    permissionLauncher.launch(cap.runtimePermissions.toTypedArray())
                                } else {
                                    onGrant(cap)
                                }
                            }
                            .padding(12.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(cap.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            Text(
                                if (cap.runtimePermissions.isNotEmpty()) "点击弹窗授权" else "去授权 →",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Text(
                            cap.reason,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("拒绝并关闭") }
        },
    )
}
