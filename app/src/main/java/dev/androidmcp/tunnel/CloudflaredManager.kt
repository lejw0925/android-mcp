package dev.androidmcp.tunnel

import dev.androidmcp.data.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * cloudflared 隧道：quick 模式一条命令拿到 trycloudflare 随机域名；
 * named 模式用 token 运行 Cloudflare 控制台配置的命名隧道。
 */
@Singleton
class CloudflaredManager @Inject constructor(
    private val settings: SettingsRepository,
    private val repo: TunnelRepository,
    private val installer: BinaryInstaller,
) : TunnelManagerBase("cloudflared") {

    /** quick 隧道公网 URL 出现在 stderr 日志中。 */
    private val urlRegex = Regex("https://[a-zA-Z0-9-]+\\.trycloudflare\\.com")

    fun start() {
        if (busy) return
        scope.launch {
            try {
                val cfg = repo.cloudflaredConfig.first()
                if (cfg.mode == "named" && cfg.token.isBlank()) {
                    _state.value = TunnelState.Error("请先填写 Tunnel Token")
                    return@launch
                }
                val port = settings.port.first()
                _state.value = TunnelState.Extracting
                val bin = installer.ensureInstalled(Binaries.CLOUDFLARED)
                _state.value = TunnelState.Starting
                // Android 没有 /etc/resolv.conf。Android 原生构建可用 Bionic 解析普通主机名，
                // 但 Go 的 SRV 查询仍会回落到 [::1]:53；固定区域入口可绕过该 SRV 查询。
                val common = buildList {
                    add(bin.absolutePath)
                    add("tunnel")
                    add("--no-autoupdate")
                    ANDROID_EDGE_ADDRESSES.forEach { address ->
                        add("--edge")
                        add(address)
                    }
                }
                val cmd = if (cfg.mode == "named") {
                    common + listOf("run", "--token", cfg.token.trim())
                } else {
                    common + listOf("--url", "http://127.0.0.1:$port")
                }
                runProcess(cmd, ::handleLine)
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
        // quick 模式：解析 trycloudflare URL
        val match = urlRegex.find(line)
        if (match != null) {
            _state.value = TunnelState.Running(match.value)
            return
        }
        // named 模式没有 URL 可解析，以连接注册成功作为运行标志
        if (line.contains("Registered tunnel connection") && _state.value !is TunnelState.Running) {
            _state.value = TunnelState.Running(null)
        }
    }

    private companion object {
        val ANDROID_EDGE_ADDRESSES = listOf(
            "region1.v2.argotunnel.com:7844",
            "region2.v2.argotunnel.com:7844",
        )
    }
}
