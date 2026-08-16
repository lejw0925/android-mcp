package dev.androidmcp.server

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.androidmcp.auth.ApiKeyStore
import dev.androidmcp.tools.ToolRegistry
import dev.androidmcp.util.NetUtils
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.request.header
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

data class ServerState(
    val running: Boolean = false,
    val port: Int = 8080,
    val addresses: List<String> = emptyList(),
    val activeSessions: Int = 0,
    val lastError: String? = null,
)

/**
 * 内嵌 Ktor(CIO) + 官方 MCP Kotlin SDK 的 Streamable HTTP 服务。
 * 鉴权：ApplicationCallPipeline 拦截 /mcp 路径，校验 Authorization: Bearer <apikey>。
 * 注意关闭 SDK 默认的 DNS rebinding 保护（默认仅允许 localhost Host，LAN 访问会被拒）。
 */
@Singleton
class McpServerManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val apiKeyStore: ApiKeyStore,
    private val toolRegistry: ToolRegistry,
) {
    private var engine: EmbeddedServer<*, *>? = null
    private val sessionCount = AtomicInteger(0)

    private val _state = MutableStateFlow(ServerState())
    val state: StateFlow<ServerState> = _state

    @Synchronized
    fun start(port: Int) {
        if (engine != null) return
        try {
            engine = embeddedServer(CIO, host = "0.0.0.0", port = port) {
                install(CORS) {
                    anyHost()
                    allowMethod(HttpMethod.Options)
                    allowMethod(HttpMethod.Get)
                    allowMethod(HttpMethod.Post)
                    allowMethod(HttpMethod.Delete)
                    allowNonSimpleContentTypes = true
                    allowHeader(HttpHeaders.Authorization)
                    allowHeader("Mcp-Session-Id")
                    allowHeader("Mcp-Protocol-Version")
                    exposeHeader("Mcp-Session-Id")
                    exposeHeader("Mcp-Protocol-Version")
                }

                // 鉴权拦截：所有 /mcp 请求必须携带有效 Bearer key
                // Ktor 3.5：ApplicationCallPipeline 拦截器内通过 context（PipelineCall，继承 ApplicationCall）访问
                intercept(ApplicationCallPipeline.Plugins) {
                    if (context.request.path().startsWith("/mcp")) {
                        val token = context.request.header(HttpHeaders.Authorization)
                            ?.removePrefix("Bearer ")?.trim()
                        val key = token?.let { apiKeyStore.validate(it) }
                        if (key == null) {
                            context.respond(
                                HttpStatusCode.Unauthorized,
                                """{"error":"invalid or missing API key","hint":"Authorization: Bearer <apikey>"}""",
                            )
                            finish()
                        } else {
                            context.attributes.put(KEY_LABEL_ATTR, key.label)
                        }
                    }
                }

                environment.monitor.subscribe(ApplicationStarted) {
                    _state.update {
                        it.copy(
                            running = true,
                            port = port,
                            addresses = NetUtils.lanAddresses().map { ip -> "http://$ip:$port/mcp" },
                            lastError = null,
                        )
                    }
                }
                environment.monitor.subscribe(ApplicationStopped) {
                    _state.update { it.copy(running = false, activeSessions = 0) }
                }

                mcpStreamableHttp(
                    path = "/mcp",
                    enableDnsRebindingProtection = false,
                ) {
                    val label = runCatching {
                        call.attributes.getOrNull(KEY_LABEL_ATTR)
                    }.getOrNull() ?: "unknown"
                    toolRegistry.createServer(label).also { server ->
                        val counted = AtomicBoolean(false)
                        server.onConnect {
                            // 传输建立不等于 MCP 会话成立；收到 initialized 后才计数。
                            server.sessions.values.singleOrNull()?.onInitialized {
                                if (counted.compareAndSet(false, true)) {
                                    sessionCount.incrementAndGet()
                                    _state.update { it.copy(activeSessions = sessionCount.get()) }
                                }
                            }
                        }
                        server.onClose {
                            if (counted.compareAndSet(true, false)) {
                                val count = sessionCount.updateAndGet { current ->
                                    (current - 1).coerceAtLeast(0)
                                }
                                _state.update { s -> s.copy(activeSessions = count) }
                            }
                        }
                    }
                }
            }.start(wait = false)
        } catch (t: Throwable) {
            Log.e(TAG, "start failed", t)
            engine = null
            _state.update { it.copy(running = false, lastError = t.message) }
        }
    }

    @Synchronized
    fun stop() {
        runCatching { engine?.stop(500, 1500) }
        engine = null
        sessionCount.set(0)
        _state.update { it.copy(running = false, activeSessions = 0) }
    }

    fun isRunning(): Boolean = engine != null

    companion object {
        private const val TAG = "McpServerManager"
        private val KEY_LABEL_ATTR = io.ktor.util.AttributeKey<String>("mcp-key-label")
    }
}
