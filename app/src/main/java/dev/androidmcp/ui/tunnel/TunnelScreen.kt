package dev.androidmcp.ui.tunnel

import android.content.Intent
import androidx.compose.foundation.Image
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
import androidx.compose.material3.Button
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.net.toUri
import dev.androidmcp.tunnel.BinaryState
import dev.androidmcp.tunnel.Binaries
import dev.androidmcp.tunnel.CloudflaredConfig
import dev.androidmcp.tunnel.LoginState
import dev.androidmcp.tunnel.TunnelState
import dev.androidmcp.ui.components.geminiDispersion
import dev.androidmcp.util.QrCode
import kotlinx.coroutines.delay

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
    val loggedIn by vm.cloudflared.loggedIn.collectAsStateWithLifecycle()
    val loginState by vm.cloudflared.loginState.collectAsStateWithLifecycle()
    val binState by vm.cfBinState.collectAsStateWithLifecycle()
    val logs by vm.cloudflared.logs.collectAsStateWithLifecycle()
    val busy = state is TunnelState.Running || state is TunnelState.Starting || state == TunnelState.Extracting
    val checked = config.enabled && state !is TunnelState.Stopped && state !is TunnelState.Error
    val setupBusy = loginState is LoginState.WaitingAuth || loginState is LoginState.Working
    // 运行已有固定域名隧道只需该隧道的凭据，不需要保留账户级 cert.pem。
    val customReady = config.tunnelId.isNotBlank() && config.routedHostname == config.hostname
    val canToggle = !setupBusy && (checked || config.mode == "quick" || customReady)

    TunnelCard(
        title = "Cloudflare Tunnel",
        subtitle = "cloudflared ${Binaries.CLOUDFLARED.version} · 无需自建服务器",
        checked = checked,
        enabled = canToggle,
        onToggle = { if (it) onRequestEnable() else vm.disableCloudflared() },
    ) {
        // 模式选择：quick 一键临时隧道 / custom 由手机本机管理固定子域名。
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = config.mode == "quick",
                onClick = { vm.setCfMode("quick") },
                label = { Text("快速隧道") },
                enabled = !busy && !setupBusy,
            )
            FilterChip(
                selected = config.mode == "custom",
                onClick = { vm.setCfMode("custom") },
                label = { Text("固定域名") },
                enabled = !busy && !setupBusy,
            )
        }
        if (config.mode == "custom") {
            CustomDomainSection(
                vm = vm,
                config = config,
                busy = busy,
                loggedIn = loggedIn,
                loginState = loginState,
            )
        } else {
            Text(
                "快速隧道会生成随机的 trycloudflare.com 临时域名，重启后域名变化。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        StatusSection(state = state, binState = binState, runningHint = "固定域名隧道已连接")
        LogSection(logs = logs)
    }
}

@Composable
private fun CustomDomainSection(
    vm: TunnelViewModel,
    config: CloudflaredConfig,
    busy: Boolean,
    loggedIn: Boolean,
    loginState: LoginState,
) {
    val context = LocalContext.current
    val setupBusy = loginState is LoginState.WaitingAuth || loginState is LoginState.Working
    val canEdit = !busy && !setupBusy
    val configured = config.tunnelId.isNotBlank() && config.routedHostname == config.hostname
    var hostnameDraft by remember(config.hostname) { mutableStateOf(config.hostname) }
    var tunnelNameDraft by remember(config.tunnelName) { mutableStateOf(config.tunnelName) }
    val targetChanged = hostnameDraft != config.hostname || tunnelNameDraft != config.tunnelName
    var showDisconnectConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(loginState) {
        if (loginState is LoginState.Success) {
            delay(1_500)
            vm.acknowledgeCfLogin()
        }
    }

    // 登录等待期间字段不可编辑；收起它们以让二维码和操作按钮完整落在首屏。
    if (!setupBusy) {
        OutlinedTextField(
            value = hostnameDraft,
            onValueChange = { hostnameDraft = it },
            label = { Text("固定子域名") },
            placeholder = { Text("mcp.example.com") },
            singleLine = true,
            enabled = canEdit,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = tunnelNameDraft,
            onValueChange = { tunnelNameDraft = it },
            label = { Text("本机隧道名称") },
            placeholder = { Text("androidmcp") },
            singleLine = true,
            enabled = canEdit,
            modifier = Modifier.fillMaxWidth(),
        )
        if (targetChanged) {
            Button(onClick = { vm.saveCfCustomTarget(hostnameDraft, tunnelNameDraft) }) {
                Text("保存子域名设置")
            }
        }
    }

    if (!loggedIn) {
        Text(
            if (configured) {
                "固定子域名已保留，可直接启动隧道。重新连接账户仅在创建或改绑时需要。"
            } else {
                "域名需已托管到 Cloudflare；连接账户后由手机创建隧道和 DNS CNAME。"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when (loginState) {
            is LoginState.WaitingAuth -> {
                Text(
                    "用另一台已登录 Cloudflare 的设备扫描二维码，或在本机浏览器完成授权。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                val authorizationQr = remember(loginState.url) { QrCode.encode(loginState.url) }
                Image(
                    bitmap = authorizationQr,
                    contentDescription = "Cloudflare 授权二维码",
                    modifier = Modifier
                        .size(160.dp)
                        .clip(RoundedCornerShape(12.dp)),
                )
                SelectionContainer {
                    Text(loginState.url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, loginState.url.toUri()))
                        }
                    }) { Text("打开浏览器") }
                    TextButton(onClick = vm::cancelCfLogin) { Text("取消") }
                }
            }
            LoginState.Working -> {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text("正在准备 Cloudflare 授权…", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = vm::cancelCfLogin) { Text("取消") }
                }
            }
            is LoginState.Error -> {
                Text(
                    "操作失败：${loginState.message}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Button(onClick = vm::loginCloudflared, enabled = !busy && !targetChanged) { Text("重新登录 Cloudflare") }
            }
            LoginState.Idle, LoginState.Success -> {
                Button(onClick = vm::loginCloudflared, enabled = !busy && !targetChanged) { Text("登录 Cloudflare") }
            }
        }
    } else {
        Text(
            "Cloudflare 账户已连接。账户授权只用于创建或改绑；运行已绑定隧道只使用该隧道凭据。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when (loginState) {
            LoginState.Working -> {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text("正在创建或更新隧道…", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = vm::cancelCfLogin) { Text("取消") }
                }
            }
            is LoginState.Error -> Text(
                "操作失败：${loginState.message}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            LoginState.Success -> Text(
                "本机隧道配置已更新。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
            LoginState.Idle, is LoginState.WaitingAuth -> Unit
        }
        Button(
            onClick = vm::provisionCloudflared,
            enabled = !busy && !setupBusy && !targetChanged,
        ) {
            Text(if (configured) "检查固定域名绑定" else "创建并绑定子域名")
        }
        TextButton(
            onClick = { showDisconnectConfirm = true },
            enabled = !busy && !setupBusy,
        ) { Text("移除账户授权") }
    }

    if (showDisconnectConfirm) {
        AlertDialog(
            onDismissRequest = { showDisconnectConfirm = false },
            title = { Text("移除本机账户授权？") },
            text = {
                Text(
                    "只会删除此手机上的 Cloudflare 账户证书。已保存的子域名、隧道 UUID 和该隧道凭据会保留，" +
                        "现有隧道可继续运行或重启；创建或改绑时需重新登录。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDisconnectConfirm = false
                    vm.disconnectCloudflaredAccount()
                }) { Text("移除授权") }
            },
            dismissButton = {
                TextButton(onClick = { showDisconnectConfirm = false }) { Text("取消") }
            },
        )
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
    enabled: Boolean = true,
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
                    enabled = enabled,
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
