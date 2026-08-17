package dev.androidmcp.server

import org.junit.Assert.assertEquals
import org.junit.Test

class ActiveApiKeyConnectionsTest {

    @Test
    fun `same key uses one active connection until every request closes`() {
        val connections = ActiveApiKeyConnections()

        assertEquals(1, connections.acquire("key-a"))
        assertEquals(1, connections.acquire("key-a"))
        assertEquals(1, connections.release("key-a"))
        assertEquals(0, connections.release("key-a"))
    }

    @Test
    fun `different keys are counted independently and clear resets state`() {
        val connections = ActiveApiKeyConnections()

        assertEquals(1, connections.acquire("key-a"))
        assertEquals(2, connections.acquire("key-b"))
        assertEquals(1, connections.release("key-a"))
        connections.clear()
        assertEquals(0, connections.release("key-b"))
    }
}
