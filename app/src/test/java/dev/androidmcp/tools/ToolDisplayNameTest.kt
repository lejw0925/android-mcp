package dev.androidmcp.tools

import org.junit.Assert.assertEquals
import org.junit.Test

class ToolDisplayNameTest {

    @Test
    fun `known protocol identifiers have Chinese display names`() {
        assertEquals("点击", toolDisplayName("click"))
        assertEquals("读取通知", toolDisplayName("read_notifications"))
        assertEquals("顺序执行工具组", toolDisplayName("batch_execute"))
    }

    @Test
    fun `unknown identifier remains readable`() {
        assertEquals("future_tool", toolDisplayName("future_tool"))
    }
}
