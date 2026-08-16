package dev.androidmcp.ui.tunnel

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.androidmcp.tunnel.BinaryState
import dev.androidmcp.tunnel.Binaries
import dev.androidmcp.tunnel.TunnelState
import dev.androidmcp.ui.components.geminiDispersion

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TunnelScreen(onBack: () -> Unit, vm: TunnelViewModel = hiltViewModel()) {
    val port by vm.port.collectAsStateWithLifecycle()
    // 待确认的开启请求（"cf" / "frpc"），先弹安全提示
    var pendingEnable by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "REMOTE ACCESS",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            letterSpacing = 1.5.sp,
                        )
                        Text("远程隧道", style = MaterialTheme.typography.titleLarge)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                            tint = Color.White,
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "通过内网穿透把本机 MCP 服务（127.0.0.1:$port）暴露到公网，供远程 Agent 连接。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            CloudflaredCard(vm = vm, onRequestEnable = { pendingEnable = "cf" })
            FrpcCard(vm = vm, onRequestEnable = { pendingEnable = "frpc" })
        }
    }

    pendingEnable?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingEnable = null },
            title = { Text("安全提示") },
            text = {
                Text(
                    "开启隧道将把本机 MCP 服务（127.0.0.1:$port）暴露到公网，" +
                        "任何持有公网地址的人都可能尝试连接。请确认 API Key 已妥善保管，" +
                        "并在用完后及时关闭隧道。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingEnable = null
                    if (target == "cf") vm.enableCloudflared() else vm.enableFrpc()
                }) { Text("我已了解，开启") }
            },
            dismissButton = {
                TextButton(onClick = { pendingEnable = null }) { Text("取消") }
            },
        )
    }
}

// ---------------- cloudflared 卡片 ----------------

@Composable
private fun CloudflaredCard(vm: TunnelViewModel, onRequestEnable: () -> Unit) {
    val config by vm.cfConfig.collectAsStateWithLifecycle()
    val state by vm.cloudflared.state.collectAsStateWithLifecycle()
    val binState by vm.cfBinState.collectAsStateWithLifecycle()
    val logs by vm.cloudflared.logs.collectAsStateWithLifecycle()
    val busy = state is TunnelState.Running || state is TunnelState.Starting || state == TunnelState.Extracting
    val checked = config.enabled && state !is TunnelState.Stopped && state !is TunnelState.Error

    TunnelCard(
        title = "Cloudflare Tunnel",
        subtitle = "cloudflared ${Binaries.CLOUDFLARED.version} · 无需自建服务器",
        checked = checked,
        onToggle = { if (it) onRequestEnable() else vm.disableCloudflared() },
    ) {
        // 模式选择：quick 一键临时隧道 / named 用 token 跑命名隧道
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = config.mode == "quick",
                onClick = { vm.setCfMode("quick") },
                label = { Text("快速隧道") },
                enabled = !busy,
            )
            FilterChip(
                selected = config.mode == "named",
                onClick = { vm.setCfMode("named") },
                label = { Text("命名隧道") },
                enabled = !busy,
            )
        }
        if (config.mode == "named") {
            OutlinedTextField(
                value = config.token,
                onValueChange = vm::setCfToken,
                label = { Text("Tunnel Token") },
                placeholder = { Text("Cloudflare Zero Trust 控制台获取") },
                singleLine = true,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            Text(
                "快速隧道会生成随机的 trycloudflare.com 临时域名，重启后域名变化。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        StatusSection(state = state, binState = binState, runningHint = "命名隧道已连接（域名请在 Cloudflare 控制台查看）")
        LogSection(logs = logs)
    }
}

// ---------------- frpc 卡片 ----------------

@Composable
private fun FrpcCard(vm: TunnelViewModel, onRequestEnable: () -> Unit) {
    val config by vm.frpcConfig.collectAsStateWithLifecycle()
    val state by vm.frpc.state.collectAsStateWithLifecycle()
    val binState by vm.frpcBinState.collectAsStateWithLifecycle()
    val logs by vm.frpc.logs.collectAsStateWithLifecycle()
    val busy = state is TunnelState.Running || state is TunnelState.Starting || state == TunnelState.Extracting
    val checked = config.enabled && state !is TunnelState.Stopped && state !is TunnelState.Error

    TunnelCard(
        title = "frpc（自建 frps）",
        subtitle = "frp ${Binaries.FRPC.version} · 连接自己的 frps 服务器",
        checked = checked,
        onToggle = { if (it) onRequestEnable() else vm.disableFrpc() },
    ) {
        OutlinedTextField(
            value = config.serverAddr,
            onValueChange = vm::setFrpcAddr,
            label = { Text("服务器地址") },
            placeholder = { Text("frps 服务器域名或 IP") },
            singleLine = true,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PortField(
                label = "服务器端口",
                value = config.serverPort,
                onValue = vm::setFrpcPort,
                enabled = !busy,
                modifier = Modifier.weight(1f),
            )
            PortField(
                label = "远程端口",
                value = config.remotePort,
                onValue = vm::setFrpcRemote,
                enabled = !busy,
                modifier = Modifier.weight(1f),
            )
        }
        OutlinedTextField(
            value = config.token,
            onValueChange = vm::setFrpcToken,
            label = { Text("Token（可选）") },
            placeholder = { Text("frps 的 auth.token") },
            singleLine = true,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        )
        StatusSection(state = state, binState = binState, runningHint = "代理已建立")
        LogSection(logs = logs)
    }
}

// ---------------- 通用小组件 ----------------

/** 卡片骨架：标题 + 副标题 + 开关，内容区由调用方填充。 */
@Composable
private fun TunnelCard(
    title: String,
    subtitle: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(28.dp)
    Card(
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier
            .fillMaxWidth()
            .geminiDispersion(shape = shape, strength = 0.55f),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = checked,
                    onCheckedChange = onToggle,
                    modifier = Modifier.geminiDispersion(
                        shape = RoundedCornerShape(20.dp),
                        strength = 0.8f,
                    ),
                )
            }
            content()
        }
    }
}

/** 数字端口输入框：本地保留中间态文本，合法时才落盘。 */
@Composable
private fun PortField(
    label: String,
    value: Int,
    onValue: (Int) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { new ->
            text = new.filter { it.isDigit() }.take(5)
            text.toIntOrNull()?.let(onValue)
        },
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}

/** 状态行：组件释放 / 启动中 / 运行中+公网地址+复制 / 错误 / 停止。 */
@Composable
private fun StatusSection(state: TunnelState, binState: BinaryState, runningHint: String) {
    val shape = RoundedCornerShape(16.dp)
    Card(
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier
            .fillMaxWidth()
            .geminiDispersion(
                shape = shape,
                strength = if (state is TunnelState.Running) 0.85f else 0.4f,
            ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            when (state) {
        TunnelState.Extracting -> {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    "正在校验内置隧道组件…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        TunnelState.Starting -> {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Text("启动中…", style = MaterialTheme.typography.bodyMedium)
            }
        }
        is TunnelState.Running -> {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "● 运行中",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (state.publicUrl != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SelectionContainer(modifier = Modifier.weight(1f)) {
                            Text(
                                state.publicUrl,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        CopyButton(text = state.publicUrl)
                    }
                } else {
                    Text(
                        runningHint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        is TunnelState.Error -> {
            Text(
                "错误：${state.message}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        TunnelState.Stopped -> {
            when (binState) {
                is BinaryState.NotInstalled -> Text(
                    "已停止（组件已内置，首次开启将自动校验）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                is BinaryState.Error -> Text(
                    "组件不可用：${binState.reason}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                else -> Text(
                    "已停止",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
        }
    }
}

@Composable
private fun CopyButton(text: String) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    TextButton(onClick = {
        clipboard.setText(AnnotatedString(text))
        copied = true
    }) {
        Icon(
            Icons.Default.ContentCopy,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
        )
        Text(if (copied) " 已复制" else " 复制")
    }
}

/** 日志折叠区：默认收起，展开显示最近日志（等宽字体）。 */
@Composable
private fun LogSection(logs: List<String>) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 4.dp),
        ) {
            Icon(
                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "运行日志（${logs.size} 行）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (expanded) {
            if (logs.isEmpty()) {
                Text(
                    "暂无日志",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                SelectionContainer {
                    Text(
                        logs.joinToString("\n"),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
