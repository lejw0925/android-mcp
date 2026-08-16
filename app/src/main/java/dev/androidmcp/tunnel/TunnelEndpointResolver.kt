package dev.androidmcp.tunnel

/** 将运行中的隧道状态转换为可直接交给 MCP HTTP 客户端的完整地址。 */
fun publicMcpAddresses(
    cloudflared: TunnelState,
    frpc: TunnelState,
): List<String> = buildList {
    (cloudflared as? TunnelState.Running)?.publicUrl
        ?.takeIf { it.isNotBlank() }
        ?.let(::asHttpMcpUrl)
        ?.let(::add)
    (frpc as? TunnelState.Running)?.publicUrl
        ?.takeIf { it.isNotBlank() }
        ?.let { endpoint ->
            val withScheme = if (endpoint.contains("://")) endpoint else "http://$endpoint"
            asHttpMcpUrl(withScheme)
        }
        ?.let(::add)
}.distinct()

private fun asHttpMcpUrl(value: String): String {
    val trimmed = value.trim().trimEnd('/')
    return if (trimmed.endsWith("/mcp")) trimmed else "$trimmed/mcp"
}
