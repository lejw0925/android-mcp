package dev.androidmcp.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.androidmcp.ui.components.GeminiButton
import dev.androidmcp.ui.components.GlowOrb
import dev.androidmcp.ui.components.geminiDispersion
import dev.androidmcp.ui.components.geminiGlowBorder
import dev.androidmcp.ui.keys.ApiKeysSection
import dev.androidmcp.ui.theme.GeminiPalette

@Composable
fun DashboardScreen(
    onOpenTunnel: () -> Unit,
    bottomContentPadding: Dp = 0.dp,
    viewModel: DashboardViewModel = hiltViewModel(),
) {
    val state by viewModel.serverState.collectAsStateWithLifecycle()
    val activeTool by viewModel.activeTool.collectAsStateWithLifecycle()
    val connectionAddresses by viewModel.connectionAddresses.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val cardShape = RoundedCornerShape(28.dp)

    Box(modifier = Modifier.fillMaxSize()) {
        GlowOrb(
            color = GeminiPalette.Blue,
            alpha = 0.35f,
            blurRadius = 90.dp,
            modifier = Modifier.size(320.dp).offset(x = (-80).dp, y = (-60).dp),
        )
        GlowOrb(
            color = GeminiPalette.Purple,
            alpha = 0.30f,
            blurRadius = 100.dp,
            modifier = Modifier.size(280.dp).align(Alignment.TopEnd).offset(x = 90.dp, y = 120.dp),
        )
        GlowOrb(
            color = GeminiPalette.Pink,
            alpha = 0.20f,
            blurRadius = 110.dp,
            modifier = Modifier.size(260.dp).align(Alignment.BottomStart).offset(x = (-60).dp, y = 40.dp),
        )

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
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                "ANDROID MCP",
                style = MaterialTheme.typography.labelMedium,
                color = GeminiPalette.Secondary,
            )
            Text(
                "服务状态",
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
            )

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .geminiGlowBorder(shape = cardShape, alpha = if (state.running) 0.9f else 0.35f)
                    .geminiDispersion(shape = cardShape, strength = 0.6f),
                shape = cardShape,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (state.running) "服务运行中" else "服务已停止",
                                style = MaterialTheme.typography.titleLarge,
                                color = if (state.running) GeminiPalette.Blue else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                "端口 ${state.port} · ${state.activeApiKeys} 个 API Key",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        GeminiButton(
                            text = if (state.running) "停止" else "启动服务",
                            onClick = { viewModel.toggleServer() },
                            modifier = Modifier.geminiDispersion(shape = RoundedCornerShape(24.dp)),
                        )
                    }
                    if (state.running && activeTool != null) {
                        Text(
                            "⟡ 正在执行 ${activeTool!!.displayName}（来自 ${activeTool!!.keyLabel}）",
                            color = GeminiPalette.Secondary,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    state.lastError?.let { Text("启动失败: $it", color = MaterialTheme.colorScheme.error) }
                }
            }

            if (state.running && connectionAddresses.isNotEmpty()) {
                OutlinedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .geminiGlowBorder(shape = cardShape, width = 1.dp, alpha = 0.45f),
                    shape = cardShape,
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("连接地址", style = MaterialTheme.typography.titleMedium)
                        connectionAddresses.forEach { address ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    address,
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontFamily = FontFamily.Monospace,
                                    color = GeminiPalette.Primary,
                                )
                                IconButton(
                                    onClick = { clipboard.setText(AnnotatedString(address)) },
                                    modifier = Modifier.geminiDispersion(shape = RoundedCornerShape(16.dp), strength = 0.5f),
                                ) {
                                    Icon(Icons.Default.Share, contentDescription = "复制地址")
                                }
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        FilledTonalButton(
                            onClick = {
                                clipboard.setText(
                                    AnnotatedString(viewModel.clientConfigJson(connectionAddresses.first())),
                                )
                            },
                            modifier = Modifier.geminiDispersion(shape = RoundedCornerShape(20.dp), strength = 0.55f),
                        ) { Text("复制客户端配置 JSON") }
                        Text(
                            "也可通过 USB 使用: adb forward tcp:${state.port} tcp:${state.port}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            ApiKeysSection()

            OutlinedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .geminiGlowBorder(shape = cardShape, width = 1.dp, alpha = 0.45f)
                    .geminiDispersion(shape = cardShape, strength = 0.6f),
                shape = cardShape,
                onClick = onOpenTunnel,
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("远程隧道", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "cloudflared / frpc 内网穿透，让外网 Agent 也能连上手机",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
