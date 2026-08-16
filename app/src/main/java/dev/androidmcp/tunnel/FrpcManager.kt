package dev.androidmcp.tunnel

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.androidmcp.data.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * frpc 隧道：连接用户自建的 frps 服务器，把 127.0.0.1:<MCP 端口>
 * 映射为服务器的 <remotePort>。每次启动根据当前配置重新生成 frpc.toml。
 */
@Singleton
class FrpcManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val repo: TunnelRepository,
    private val installer: BinaryInstaller,
) : TunnelManagerBase("frpc") {

    /** 当前运行的对外地址（serverAddr:remotePort），用于 Running 状态展示。 */
    @Volatile private var currentEndpoint: String? = null

    fun start() {
        if (busy) return
        scope.launch {
            try {
                val cfg = repo.frpcConfig.first()
                if (cfg.serverAddr.isBlank()) {
                    _state.value = TunnelState.Error("请先填写 frps 服务器地址")
                    return@launch
                }
                val port = settings.port.first()
                _state.value = TunnelState.Extracting
                val bin = installer.ensureInstalled(Binaries.FRPC)
                val conf = writeConfig(cfg, port)
                currentEndpoint = "${cfg.serverAddr.trim()}:${cfg.remotePort}"
                _state.value = TunnelState.Starting
                runProcess(listOf(bin.absolutePath, "-c", conf.absolutePath), ::handleLine)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                appendLog("启动失败：${e.message}")
                _state.value = TunnelState.Error(e.message ?: "启动失败")
            }
        }
    }

    private fun handleLine(line: String) {
        appendLog(line)
        if (line.contains("start proxy success")) {
            _state.value = TunnelState.Running(currentEndpoint)
        }
    }

    override fun stop() {
        currentEndpoint = null
        super.stop()
    }

    /** 生成 frpc.toml（TOML 字符串做最小转义）。 */
    private fun writeConfig(cfg: FrpcConfig, localPort: Int): File {
        fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
        val sb = StringBuilder()
        sb.appendLine("serverAddr = \"${esc(cfg.serverAddr.trim())}\"")
        sb.appendLine("serverPort = ${cfg.serverPort}")
        if (cfg.token.isNotBlank()) {
            sb.appendLine("auth.method = \"token\"")
            sb.appendLine("auth.token = \"${esc(cfg.token.trim())}\"")
        }
        sb.appendLine()
        sb.appendLine("[[proxies]]")
        sb.appendLine("name = \"mcp\"")
        sb.appendLine("type = \"tcp\"")
        sb.appendLine("localIP = \"127.0.0.1\"")
        sb.appendLine("localPort = $localPort")
        sb.appendLine("remotePort = ${cfg.remotePort}")
        return File(context.filesDir, "frpc.toml").apply { writeText(sb.toString()) }
    }
}
