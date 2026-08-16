package dev.androidmcp.ui.keys

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.androidmcp.ui.components.geminiDispersion
import dev.androidmcp.ui.components.geminiGlowBorder
import dev.androidmcp.util.QrCode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ApiKeysSection(
    modifier: Modifier = Modifier,
    viewModel: ApiKeysViewModel = hiltViewModel(),
) {
    val keys by viewModel.keys.collectAsStateWithLifecycle()
    val pendingNewKey by viewModel.pendingNewKey.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    var showCreateDialog by remember { mutableStateOf(false) }
    val cardShape = RoundedCornerShape(20.dp)

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Agent 密钥", style = MaterialTheme.typography.titleLarge)
        Text(
            "密钥仅在创建时完整显示一次；请只复制到受信任的 Agent 配置中。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = { showCreateDialog = true }) {
            Icon(Icons.Default.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(
                "创建密钥",
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 6.dp),
            )
        }

        keys.forEach { key ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .geminiGlowBorder(shape = cardShape, width = 1.dp, alpha = 0.45f)
                    .geminiDispersion(shape = cardShape, strength = 0.55f),
                shape = cardShape,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(key.label, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "id: ${key.id} · 创建于 ${formatTime(key.createdAt)}" +
                                (key.lastUsedAt?.let { " · 最近使用 ${formatTime(it)}" } ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = key.enabled,
                        onCheckedChange = { viewModel.setEnabled(key.id, it) },
                    )
                    IconButton(onClick = { viewModel.revoke(key.id) }) {
                        Icon(Icons.Default.Delete, contentDescription = "删除")
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        var label by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text("创建 API 密钥") },
            text = {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("备注名（如 kimi-code-laptop）") },
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.create(label)
                        showCreateDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(contentColor = MaterialTheme.colorScheme.onPrimary),
                ) { Text("创建") }
            },
            dismissButton = { TextButton(onClick = { showCreateDialog = false }) { Text("取消") } },
        )
    }

    pendingNewKey?.let { newKey ->
        val configJson = viewModel.clientConfigJson(viewModel.currentAddress(), newKey.plaintext)
        AlertDialog(
            onDismissRequest = { viewModel.dismissNewKey() },
            title = { Text("新密钥：${newKey.meta.label}") },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Image(
                        bitmap = QrCode.encode(configJson),
                        contentDescription = "连接配置二维码",
                        modifier = Modifier
                            .size(200.dp)
                            .clip(RoundedCornerShape(16.dp)),
                    )
                    Text(
                        newKey.plaintext,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        "请立即复制保存，关闭后将无法再次查看完整密钥。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { clipboard.setText(AnnotatedString(configJson)) }) {
                        Text("复制配置 JSON")
                    }
                    Button(
                        onClick = {
                            clipboard.setText(AnnotatedString(newKey.plaintext))
                            viewModel.dismissNewKey()
                        },
                        colors = ButtonDefaults.buttonColors(contentColor = MaterialTheme.colorScheme.onPrimary),
                    ) { Text("复制并关闭") }
                }
            },
        )
    }
}

@Deprecated("密钥页已合并到服务页，请使用 ApiKeysSection")
@Composable
fun ApiKeysScreen(viewModel: ApiKeysViewModel = hiltViewModel()) {
    ApiKeysSection(viewModel = viewModel)
}

private fun formatTime(ts: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ts))
