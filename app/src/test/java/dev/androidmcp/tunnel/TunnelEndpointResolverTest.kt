package dev.androidmcp.tunnel

import org.junit.Assert.assertEquals
import org.junit.Test

class TunnelEndpointResolverTest {

    @Test
    fun `quick tunnel URL becomes complete MCP endpoint`() {
        assertEquals(
            listOf("https://demo.trycloudflare.com/mcp"),
            publicMcpAddresses(
                TunnelState.Running("https://demo.trycloudflare.com"),
                TunnelState.Stopped,
            ),
        )
    }

    @Test
    fun `frpc host and port become HTTP MCP endpoint`() {
        assertEquals(
            listOf("http://example.com:18080/mcp"),
            publicMcpAddresses(
                TunnelState.Stopped,
                TunnelState.Running("example.com:18080"),
            ),
        )
    }

    @Test
    fun `existing MCP suffix is not duplicated`() {
        assertEquals(
            listOf("https://mcp.example.com/mcp"),
            publicMcpAddresses(
                TunnelState.Running("https://mcp.example.com/mcp/"),
                TunnelState.Stopped,
            ),
        )
    }
}
