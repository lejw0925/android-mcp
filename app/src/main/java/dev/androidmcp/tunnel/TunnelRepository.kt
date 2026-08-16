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

/** cloudflared 配置：quick = 临时隧道（trycloudflare 随机域名）；named = token 命名的隧道。 */
data class CloudflaredConfig(
    val enabled: Boolean = false,
    val mode: String = "quick",
    val token: String = "",
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
            token = prefs[KEY_CF_TOKEN] ?: "",
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
    suspend fun setCloudflaredMode(value: String) = store.edit { it[KEY_CF_MODE] = value }
    suspend fun setCloudflaredToken(value: String) = store.edit { it[KEY_CF_TOKEN] = value }

    suspend fun setFrpcEnabled(value: Boolean) = store.edit { it[KEY_FRPC_ENABLED] = value }
    suspend fun setFrpcServerAddr(value: String) = store.edit { it[KEY_FRPC_ADDR] = value }
    suspend fun setFrpcServerPort(value: Int) = store.edit { it[KEY_FRPC_PORT] = value.coerceIn(1, 65535) }
    suspend fun setFrpcToken(value: String) = store.edit { it[KEY_FRPC_TOKEN] = value }
    suspend fun setFrpcRemotePort(value: Int) = store.edit { it[KEY_FRPC_REMOTE] = value.coerceIn(1, 65535) }

    companion object {
        private val KEY_CF_ENABLED = booleanPreferencesKey("cloudflared_enabled")
        private val KEY_CF_MODE = stringPreferencesKey("cloudflared_mode")
        private val KEY_CF_TOKEN = stringPreferencesKey("cloudflared_token")

        private val KEY_FRPC_ENABLED = booleanPreferencesKey("frpc_enabled")
        private val KEY_FRPC_ADDR = stringPreferencesKey("frpc_server_addr")
        private val KEY_FRPC_PORT = intPreferencesKey("frpc_server_port")
        private val KEY_FRPC_TOKEN = stringPreferencesKey("frpc_token")
        private val KEY_FRPC_REMOTE = intPreferencesKey("frpc_remote_port")
    }
}
