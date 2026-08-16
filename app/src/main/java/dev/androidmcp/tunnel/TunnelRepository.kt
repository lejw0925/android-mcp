package dev.androidmcp.tunnel

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.tunnelStore by preferencesDataStore(name = "tunnel")

/**
 * cloudflared 配置：quick = 临时隧道（trycloudflare 随机域名）；
 * named = 登录 Cloudflare 账户后创建的命名隧道（hostname 绑定自有域名）。
 */
data class CloudflaredConfig(
    val enabled: Boolean = false,
    val mode: String = "quick",
    /** named 模式绑定的公网域名，如 mcp.example.com。 */
    val hostname: String = "",
    /** 命名隧道名（cloudflared tunnel create 的名字）。 */
    val tunnelName: String = "androidmcp",
    /** create 成功后回填的隧道 UUID。 */
    val tunnelId: String = "",
    /** 已成功创建 DNS 路由的域名；与 [hostname] 不同则需要重新建站。 */
    val routedHostname: String = "",
)

/** frpc 配置：连接自建 frps 服务器，把本机 MCP 端口映射为远程 TCP 端口。 */
data class FrpcConfig(
    val enabled: Boolean = false,
    val serverAddr: String = "",
    val serverPort: Int = 7000,
    val token: String = "",
    val remotePort: Int = 8080,
)

/** 隧道配置持久化（独立于全局设置，存 "tunnel" DataStore）。 */
@Singleton
class TunnelRepository @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val store = context.tunnelStore

    val cloudflaredConfig: Flow<CloudflaredConfig> = store.data.map { prefs ->
        CloudflaredConfig(
            enabled = prefs[KEY_CF_ENABLED] ?: false,
            mode = prefs[KEY_CF_MODE] ?: "quick",
            hostname = prefs[KEY_CF_HOSTNAME] ?: "",
            tunnelName = prefs[KEY_CF_TUNNEL_NAME] ?: "androidmcp",
            tunnelId = prefs[KEY_CF_TUNNEL_ID] ?: "",
            routedHostname = prefs[KEY_CF_ROUTED_HOSTNAME] ?: "",
        )
    }

    val frpcConfig: Flow<FrpcConfig> = store.data.map { prefs ->
        FrpcConfig(
            enabled = prefs[KEY_FRPC_ENABLED] ?: false,
            serverAddr = prefs[KEY_FRPC_ADDR] ?: "",
            serverPort = prefs[KEY_FRPC_PORT] ?: 7000,
            token = prefs[KEY_FRPC_TOKEN] ?: "",
            remotePort = prefs[KEY_FRPC_REMOTE] ?: 8080,
        )
    }

    suspend fun setCloudflaredEnabled(value: Boolean) = store.edit { it[KEY_CF_ENABLED] = value }
    suspend fun setCloudflaredMode(value: String) = store.edit { prefs ->
        prefs[KEY_CF_MODE] = if (value == "named") "named" else "quick"
        // The former named-tunnel implementation stored a reusable tunnel token.
        // It is no longer consumed by this login-based flow, so remove it promptly.
        prefs.remove(KEY_CF_LEGACY_TOKEN)
    }

    suspend fun setCloudflaredHostname(value: String) = store.edit { prefs ->
        val hostname = CloudflaredSupport.normalizeHostname(value)
        if (prefs[KEY_CF_HOSTNAME] != hostname) {
            prefs[KEY_CF_HOSTNAME] = hostname
            prefs.remove(KEY_CF_ROUTED_HOSTNAME)
        }
    }

    suspend fun setCloudflaredTunnelName(value: String) = store.edit { prefs ->
        val name = value.trim()
        if (prefs[KEY_CF_TUNNEL_NAME] != name) {
            prefs[KEY_CF_TUNNEL_NAME] = name
            // A different name represents a different locally-managed tunnel.
            prefs.remove(KEY_CF_TUNNEL_ID)
            prefs.remove(KEY_CF_ROUTED_HOSTNAME)
        }
    }

    suspend fun setCloudflaredTunnelBinding(tunnelId: String, hostname: String) = store.edit { prefs ->
        prefs[KEY_CF_TUNNEL_ID] = tunnelId
        prefs[KEY_CF_ROUTED_HOSTNAME] = CloudflaredSupport.normalizeHostname(hostname)
    }

    /** Remove the deprecated token after upgrading from the old named-tunnel flow. */
    suspend fun clearLegacyCloudflaredToken() = store.edit { it.remove(KEY_CF_LEGACY_TOKEN) }

    /** 退出登录时清空 named 隧道配置（保留 enabled/mode）。 */
    suspend fun clearCloudflaredNamed() = store.edit { prefs ->
        prefs.remove(KEY_CF_HOSTNAME)
        prefs.remove(KEY_CF_TUNNEL_NAME)
        prefs.remove(KEY_CF_TUNNEL_ID)
        prefs.remove(KEY_CF_ROUTED_HOSTNAME)
        prefs.remove(KEY_CF_LEGACY_TOKEN)
    }

    suspend fun setFrpcEnabled(value: Boolean) = store.edit { it[KEY_FRPC_ENABLED] = value }
    suspend fun setFrpcServerAddr(value: String) = store.edit { it[KEY_FRPC_ADDR] = value }
    suspend fun setFrpcServerPort(value: Int) = store.edit { it[KEY_FRPC_PORT] = value.coerceIn(1, 65535) }
    suspend fun setFrpcToken(value: String) = store.edit { it[KEY_FRPC_TOKEN] = value }
    suspend fun setFrpcRemotePort(value: Int) = store.edit { it[KEY_FRPC_REMOTE] = value.coerceIn(1, 65535) }

    companion object {
        private val KEY_CF_ENABLED = booleanPreferencesKey("cloudflared_enabled")
        private val KEY_CF_MODE = stringPreferencesKey("cloudflared_mode")
        private val KEY_CF_HOSTNAME = stringPreferencesKey("cloudflared_hostname")
        private val KEY_CF_TUNNEL_NAME = stringPreferencesKey("cloudflared_tunnel_name")
        private val KEY_CF_TUNNEL_ID = stringPreferencesKey("cloudflared_tunnel_id")
        private val KEY_CF_ROUTED_HOSTNAME = stringPreferencesKey("cloudflared_routed_hostname")
        private val KEY_CF_LEGACY_TOKEN = stringPreferencesKey("cloudflared_token")

        private val KEY_FRPC_ENABLED = booleanPreferencesKey("frpc_enabled")
        private val KEY_FRPC_ADDR = stringPreferencesKey("frpc_server_addr")
        private val KEY_FRPC_PORT = intPreferencesKey("frpc_server_port")
        private val KEY_FRPC_TOKEN = stringPreferencesKey("frpc_token")
        private val KEY_FRPC_REMOTE = intPreferencesKey("frpc_remote_port")
    }
}
