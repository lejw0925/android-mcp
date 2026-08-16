package dev.androidmcp.events

import dev.androidmcp.tools.ToolCategory
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

enum class ToolCallStatus { RUNNING, SUCCESS, ERROR }

data class UiPoint(val x: Float, val y: Float)

data class UiMarkerEvent(
    val toolCallId: String,
    val point: UiPoint,
)

data class ToolCallEvent(
    val id: String,
    val tool: String,
    val displayName: String = tool,
    val keyLabel: String,
    val sessionId: String? = null,
    val parentCallId: String? = null,
    val argsSummary: String,
    val startedAt: Long,
    val status: ToolCallStatus,
    /** 工具类别：决定边缘粒子特效颜色与 UI 分组。 */
    val category: ToolCategory,
    val durationMs: Long? = null,
    val error: String? = null,
    val resultContent: String? = null,
)

/**
 * 工具调用事件总线：驱动实时动态页、Live Update 通知、边缘粒子与底部胶囊特效。
 */
@Singleton
class ToolCallEventBus @Inject constructor() {

    /** 实时事件流（开始与结束都会发射）。 */
    private val _events = MutableSharedFlow<ToolCallEvent>(extraBufferCapacity = 128)
    val events: SharedFlow<ToolCallEvent> = _events

    /** UI 操作对应的屏幕坐标，只供限时白点悬浮层消费。 */
    private val _uiMarkers = MutableSharedFlow<UiMarkerEvent>(extraBufferCapacity = 64)
    val uiMarkers: SharedFlow<UiMarkerEvent> = _uiMarkers

    /** 历史记录，最新在前，最多保留 200 条。 */
    private val _history = MutableStateFlow<List<ToolCallEvent>>(emptyList())
    val history: StateFlow<List<ToolCallEvent>> = _history

    /** 当前正在执行的工具（供 Live Update / 底部胶囊展示）。 */
    private val _active = MutableStateFlow<ToolCallEvent?>(null)
    val active: StateFlow<ToolCallEvent?> = _active

    private val activeById = ConcurrentHashMap<String, ToolCallEvent>()

    fun emitStart(
        tool: String,
        displayName: String,
        keyLabel: String,
        sessionId: String?,
        parentCallId: String?,
        argsSummary: String,
        category: ToolCategory,
    ): ToolCallEvent {
        val event = ToolCallEvent(
            id = UUID.randomUUID().toString(),
            tool = tool,
            displayName = displayName,
            keyLabel = keyLabel,
            sessionId = sessionId,
            parentCallId = parentCallId,
            argsSummary = argsSummary.take(160),
            startedAt = System.currentTimeMillis(),
            status = ToolCallStatus.RUNNING,
            category = category,
        )
        activeById[event.id] = event
        _active.value = event
        _history.update { list -> (listOf(event) + list).take(200) }
        _events.tryEmit(event)
        return event
    }

    fun emitFinish(event: ToolCallEvent, error: String? = null, resultContent: String? = null) {
        val finished = event.copy(
            status = if (error == null) ToolCallStatus.SUCCESS else ToolCallStatus.ERROR,
            durationMs = System.currentTimeMillis() - event.startedAt,
            error = error,
            resultContent = resultContent?.take(MAX_RESULT_CHARS),
        )
        _history.update { list -> list.map { if (it.id == event.id) finished else it } }
        activeById.remove(event.id)
        _active.value = activeById.values.maxByOrNull { it.startedAt }
        _events.tryEmit(finished)
    }

    fun emitUiMarker(event: ToolCallEvent, point: UiPoint) {
        _uiMarkers.tryEmit(UiMarkerEvent(event.id, point))
    }

    fun clearHistory() {
        _history.value = emptyList()
    }

    private companion object {
        const val MAX_RESULT_CHARS = 20_000
    }
}
