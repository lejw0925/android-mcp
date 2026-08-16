package dev.androidmcp.events

import dev.androidmcp.tools.ToolCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ToolCallEventBusTest {

    @Test
    fun `finishing nested call restores parent as active`() {
        val bus = ToolCallEventBus()
        val parent = bus.emitStart(
            tool = "batch_execute",
            displayName = "顺序执行工具组",
            keyLabel = "test",
            sessionId = "session-1",
            parentCallId = null,
            argsSummary = "",
            category = ToolCategory.SYSTEM,
        )
        val child = bus.emitStart(
            tool = "get_battery",
            displayName = "获取电池状态",
            keyLabel = "test",
            sessionId = "session-1",
            parentCallId = parent.id,
            argsSummary = "",
            category = ToolCategory.READ,
        )

        bus.emitFinish(child, resultContent = "ok")
        assertEquals(parent.id, bus.active.value?.id)

        bus.emitFinish(parent, resultContent = "done")
        assertNull(bus.active.value)
        assertEquals("done", bus.history.value.first { it.id == parent.id }.resultContent)
    }
}
