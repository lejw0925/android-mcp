package dev.androidmcp.ui.feed

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.androidmcp.events.ToolCallEvent
import dev.androidmcp.events.ToolCallStatus
import dev.androidmcp.ui.components.geminiDispersion
import dev.androidmcp.ui.theme.GeminiPalette
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LiveFeedScreen(
    bottomContentPadding: Dp = 0.dp,
    viewModel: LiveFeedViewModel = hiltViewModel(),
) {
    val history by viewModel.history.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv"),
    ) { uri ->
        if (uri != null) viewModel.exportCsv(uri, context)
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(bottom = 12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "ACTIVITY STREAM",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    letterSpacing = 1.5.sp,
                )
                Text(
                    "实时动态",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            IconButton(
                onClick = {
                    val name = "mcp_calls_" +
                        SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date()) + ".csv"
                    exportLauncher.launch(name)
                },
                enabled = history.isNotEmpty(),
            ) {
                Icon(Icons.Default.FileDownload, contentDescription = "导出 CSV")
            }
            TextButton(onClick = { viewModel.clear() }) { Text("清空") }
        }
        if (history.isEmpty()) {
            Row(
                modifier = Modifier.padding(top = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier
                        .width(12.dp)
                        .height(12.dp)
                        .background(GeminiPalette.Purple.copy(alpha = 0.55f), RoundedCornerShape(50)),
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "暂无工具调用。Agent 连接后，这里的流水会实时滚动。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        "调用记录仅保存在本设备",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(bottom = bottomContentPadding),
        ) {
            itemsIndexed(history, key = { _, it -> it.id }) { index, event ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min),
                ) {
                    TimelineRail(
                        status = event.status,
                        isFirst = index == 0,
                        isLast = index == history.lastIndex,
                    )
                    EventCard(
                        event = event,
                        timeLabel = timeFormat.format(Date(event.startedAt)),
                        modifier = Modifier
                            .weight(1f)
                            .padding(bottom = 8.dp),
                    )
                }
            }
        }
    }
}

/** 时间轴轨道：竖线连接上下节点，圆点表示状态；RUNNING 用 Gemini 渐变呼吸。 */
@Composable
private fun TimelineRail(
    status: ToolCallStatus,
    isFirst: Boolean,
    isLast: Boolean,
) {
    val transition = rememberInfiniteTransition(label = "rail_pulse")
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1200), RepeatMode.Reverse),
        label = "pulse",
    )
    val lineColor = MaterialTheme.colorScheme.outlineVariant
    val dotColor = when (status) {
        ToolCallStatus.RUNNING -> GeminiPalette.Blue
        ToolCallStatus.SUCCESS -> MaterialTheme.colorScheme.tertiary
        ToolCallStatus.ERROR -> MaterialTheme.colorScheme.error
    }
    val dotBrush = Brush.linearGradient(
        listOf(GeminiPalette.Blue, GeminiPalette.Purple, GeminiPalette.Pink),
    )

    Box(
        modifier = Modifier
            .width(24.dp)
            .fillMaxHeight()
            .drawBehind {
                val cx = size.width / 2f
                val dotY = 20.dp.toPx()
                val dotRadius = 5.dp.toPx()
                val stroke = 1.5.dp.toPx()
                if (!isFirst) drawLine(lineColor, Offset(cx, 0f), Offset(cx, dotY - dotRadius), strokeWidth = stroke)
                if (!isLast) drawLine(lineColor, Offset(cx, dotY + dotRadius), Offset(cx, size.height), strokeWidth = stroke)
                when (status) {
                    ToolCallStatus.RUNNING -> {
                        // 呼吸：透明度与半径随脉冲变化，外加一圈弥散光晕
                        val a = 0.55f + 0.45f * pulse
                        val r = dotRadius * (0.8f + 0.35f * pulse)
                        drawCircle(dotColor.copy(alpha = 0.25f * a), radius = r * 2.2f, center = Offset(cx, dotY))
                        drawCircle(brush = dotBrush, radius = r, center = Offset(cx, dotY), alpha = a)
                    }
                    else -> drawCircle(dotColor, radius = dotRadius, center = Offset(cx, dotY))
                }
            },
    )
}

/** 事件卡片：左侧状态色竖条 + 状态胶囊徽标。 */
@Composable
private fun EventCard(
    event: ToolCallEvent,
    timeLabel: String,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable(event.id) { mutableStateOf(false) }
    val shape = RoundedCornerShape(16.dp)
    val statusColor = when (event.status) {
        ToolCallStatus.RUNNING -> MaterialTheme.colorScheme.primary
        ToolCallStatus.SUCCESS -> MaterialTheme.colorScheme.tertiary
        ToolCallStatus.ERROR -> MaterialTheme.colorScheme.error
    }
    val statusLabel = when (event.status) {
        ToolCallStatus.RUNNING -> "执行中"
        ToolCallStatus.SUCCESS -> "✓ ${event.durationMs ?: 0}ms"
        ToolCallStatus.ERROR -> "✗ 失败"
    }
    val dispersionStrength = when (event.status) {
        ToolCallStatus.RUNNING -> 1.15f
        ToolCallStatus.SUCCESS -> 0.4f
        ToolCallStatus.ERROR -> 0.55f
    }

    Card(
        onClick = { expanded = !expanded },
        shape = shape,
        modifier = modifier.geminiDispersion(shape = shape, strength = dispersionStrength),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .drawBehind {
                    // 左侧 3dp 状态色竖条
                    drawRect(
                        color = statusColor,
                        topLeft = Offset(0f, 0f),
                        size = androidx.compose.ui.geometry.Size(3.dp.toPx(), size.height),
                    )
                }
                .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    event.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Surface(
                    shape = RoundedCornerShape(50),
                    color = statusColor.copy(alpha = 0.15f),
                    contentColor = statusColor,
                ) {
                    Text(
                        statusLabel,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "收起详情" else "展开详情",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                "$timeLabel · 来自 ${event.keyLabel}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (event.argsSummary.isNotEmpty()) {
                Text(
                    event.argsSummary,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!expanded) event.error?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (expanded) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                DetailLine("工具标识", event.tool, monospace = true)
                event.sessionId?.let { DetailLine("会话", it, monospace = true) }
                event.parentCallId?.let { DetailLine("所属批量调用", it, monospace = true) }
                if (event.argsSummary.isNotEmpty()) {
                    DetailBlock("调用参数", event.argsSummary, isError = false)
                }
                event.resultContent?.takeIf { it.isNotBlank() }?.let {
                    DetailBlock("返回内容", it, isError = event.status == ToolCallStatus.ERROR)
                }
                if (event.resultContent.isNullOrBlank()) event.error?.let {
                    DetailBlock("错误原因", it, isError = true)
                }
            }
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String, monospace: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SelectionContainer {
            Text(
                value,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun DetailBlock(label: String, value: String, isError: Boolean) {
    Column(
        modifier = Modifier.padding(top = 6.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SelectionContainer {
            Text(
                value,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
