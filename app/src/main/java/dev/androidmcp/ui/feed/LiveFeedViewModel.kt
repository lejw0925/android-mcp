package dev.androidmcp.ui.feed

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.androidmcp.events.ToolCallEvent
import dev.androidmcp.events.ToolCallEventBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class LiveFeedViewModel @Inject constructor(
    private val events: ToolCallEventBus,
) : ViewModel() {
    val history: StateFlow<List<ToolCallEvent>> = events.history
    val active: StateFlow<ToolCallEvent?> = events.active
    fun clear() = events.clearHistory()

    /** 导出调用历史为 CSV 到用户选择的 Uri（SAF CreateDocument）。 */
    fun exportCsv(uri: Uri, context: Context) {
        val appContext = context.applicationContext
        viewModelScope.launch(Dispatchers.IO) {
            val message = runCatching {
                val csv = buildCsv(history.value)
                appContext.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(csv.toByteArray(Charsets.UTF_8))
                } ?: error("无法打开输出流")
                "已导出 ${history.value.size} 条记录"
            }.getOrElse { "导出失败: ${it.message}" }
            withContext(Dispatchers.Main) {
                Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun buildCsv(events: List<ToolCallEvent>): String {
        val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        val sb = StringBuilder(
            "id,tool_id,tool_name,category,key_label,session_id,parent_call_id,args," +
                "started_at,status,duration_ms,error,result\n",
        )
        // history 最新在前，导出按时间正序
        events.asReversed().forEach { e ->
            sb.append(
                listOf(
                    e.id,
                    e.tool,
                    e.displayName,
                    e.category.name,
                    e.keyLabel,
                    e.sessionId ?: "",
                    e.parentCallId ?: "",
                    e.argsSummary,
                    timeFormat.format(Date(e.startedAt)),
                    e.status.name,
                    e.durationMs?.toString() ?: "",
                    e.error ?: "",
                    e.resultContent ?: "",
                ).joinToString(",") { csvEscape(it) },
            ).append('\n')
        }
        return sb.toString()
    }

    /** CSV 转义：含引号/逗号/换行时双引号包裹，内部引号翻倍。 */
    private fun csvEscape(value: String): String =
        if (value.any { it == '"' || it == ',' || it == '\n' || it == '\r' }) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else {
            value
        }
}
