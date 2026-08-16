package dev.androidmcp.tunnel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudflaredSupportTest {

    @Test
    fun `tunnel list parsing is independent of JSON field order`() {
        val wantedId = "6ff42ae2-765d-4adf-8112-31c55c1551ef"
        val output = """
            INF checking account
            [
              {"name":"other","id":"11111111-1111-1111-1111-111111111111"},
              {"id":"$wantedId","createdAt":"2026-08-17T00:00:00Z","name":"androidmcp"}
            ]
            INF done
        """.trimIndent()

        assertEquals(wantedId, CloudflaredSupport.findTunnelId(output, "androidmcp"))
    }

    @Test
    fun `malformed list output does not invent a tunnel id`() {
        assertNull(CloudflaredSupport.findTunnelId("not json", "androidmcp"))
        assertNull(CloudflaredSupport.findTunnelId("[]", "androidmcp"))
    }

    @Test
    fun `hostname validation accepts a normal public hostname only`() {
        assertTrue(CloudflaredSupport.isValidHostname(" MCP.Example.COM "))
        assertFalse(CloudflaredSupport.isValidHostname("https://mcp.example.com"))
        assertFalse(CloudflaredSupport.isValidHostname("mcp.example.com:8080"))
        assertFalse(CloudflaredSupport.isValidHostname("-mcp.example.com"))
        assertFalse(CloudflaredSupport.isValidHostname("mcp.example.1"))
    }

    @Test
    fun `authorization URL excludes surrounding terminal punctuation`() {
        assertEquals(
            "https://dash.cloudflare.com/argotunnel?callback=abc",
            CloudflaredSupport.extractLoginUrl(
                "Please visit (https://dash.cloudflare.com/argotunnel?callback=abc) to log in.",
            ),
        )
    }

    @Test
    fun `tunnel name is constrained before provisioning`() {
        assertTrue(CloudflaredSupport.isValidTunnelName("androidmcp_01"))
        assertFalse(CloudflaredSupport.isValidTunnelName(""))
        assertFalse(CloudflaredSupport.isValidTunnelName("android mcp"))
    }
}
