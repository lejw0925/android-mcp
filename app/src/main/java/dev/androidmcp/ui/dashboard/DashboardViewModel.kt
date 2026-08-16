package dev.androidmcp.ui.dashboard

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.androidmcp.events.ToolCallEvent
import dev.androidmcp.events.ToolCallEventBus
import dev.androidmcp.server.McpServerManager
import dev.androidmcp.server.McpServerService
import dev.androidmcp.server.ServerState
import dev.androidmcp.tunnel.CloudflaredManager
import dev.androidmcp.tunnel.FrpcManager
import dev.androidmcp.tunnel.publicMcpAddresses
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class DashboardViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val serverManager: McpServerManager,
    cloudflared: CloudflaredManager,
    frpc: FrpcManager,
    events: ToolCallEventBus,
) : ViewModel() {

    val serverState: StateFlow<ServerState> = serverManager.state
    val activeTool: StateFlow<ToolCallEvent?> = events.active
    val connectionAddresses: StateFlow<List<String>> = combine(
        serverManager.state,
        cloudflared.state,
        frpc.state,
    ) { server, cloudflaredState, frpcState ->
        if (!server.running) {
            emptyList()
        } else {
            publicMcpAddresses(cloudflaredState, frpcState).ifEmpty { server.addresses }
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun toggleServer() {
        if (serverState.value.running) {
            McpServerService.stop(context)
        } else {
            McpServerService.start(context)
        }
    }

    /** 供 MCP 客户端（Claude Code / Kimi Code 等）使用的配置模板。 */
    fun clientConfigJson(address: String, apiKey: String = "<你的 API Key>"): String = """
        {
          "mcpServers": {
            "android-phone": {
              "type": "http",
              "url": "$address",
              "headers": {
                "Authorization": "Bearer $apiKey"
              }
            }
          }
        }
        """.trimIndent()
}
