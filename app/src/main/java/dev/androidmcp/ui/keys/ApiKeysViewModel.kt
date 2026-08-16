package dev.androidmcp.ui.keys

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.androidmcp.auth.ApiKeyMeta
import dev.androidmcp.auth.ApiKeyStore
import dev.androidmcp.auth.NewApiKey
import dev.androidmcp.server.McpServerManager
import dev.androidmcp.tunnel.CloudflaredManager
import dev.androidmcp.tunnel.FrpcManager
import dev.androidmcp.tunnel.publicMcpAddresses
import dev.androidmcp.util.NetUtils
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ApiKeysViewModel @Inject constructor(
    private val apiKeyStore: ApiKeyStore,
    private val serverManager: McpServerManager,
    cloudflared: CloudflaredManager,
    frpc: FrpcManager,
) : ViewModel() {

    val keys: StateFlow<List<ApiKeyMeta>> = apiKeyStore.keys
    val pendingNewKey: StateFlow<NewApiKey?> = apiKeyStore.pendingNewKey
    private val connectionAddresses = combine(
        serverManager.state,
        cloudflared.state,
        frpc.state,
    ) { server, cloudflaredState, frpcState ->
        publicMcpAddresses(cloudflaredState, frpcState).ifEmpty { server.addresses }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        viewModelScope.launch { apiKeyStore.ensureDefaultKey() }
    }

    fun create(label: String) = viewModelScope.launch { apiKeyStore.create(label) }
    fun revoke(id: String) = viewModelScope.launch { apiKeyStore.revoke(id) }
    fun setEnabled(id: String, enabled: Boolean) = viewModelScope.launch { apiKeyStore.setEnabled(id, enabled) }
    fun dismissNewKey() = apiKeyStore.consumePendingNewKey()

    /** 当前可用的连接地址（服务运行中取实际地址，否则用 LAN IP + 配置端口推算）。 */
    fun currentAddress(): String {
        val state = serverManager.state.value
        connectionAddresses.value.firstOrNull()?.let { return it }
        val ip = NetUtils.lanAddresses().firstOrNull() ?: "192.168.x.x"
        return "http://$ip:${state.port}/mcp"
    }

    /** 含密钥明文的客户端配置（仅新建 key 时可生成，用于二维码/复制）。 */
    fun clientConfigJson(address: String, apiKey: String): String = """
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
