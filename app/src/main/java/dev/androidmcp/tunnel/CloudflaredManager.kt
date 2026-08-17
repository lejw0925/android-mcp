package dev.androidmcp.tunnel

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.androidmcp.data.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Cloudflare 账户授权与固定域名绑定流程状态。 */
sealed interface LoginState {
    data object Idle : LoginState

    /** 等待用户在浏览器完成授权，[url] 为 cloudflared 输出的授权链接。 */
    data class WaitingAuth(val url: String) : LoginState
    data object Working : LoginState
    data object Success : LoginState
    data class Error(val message: String) : LoginState
}

/**
 * cloudflared 隧道：quick 模式一条命令拿到 trycloudflare 随机域名；
 * custom 模式登录 Cloudflare 账户（tunnel login → cert.pem），
 * 在本机执行 create + route dns，建立固定子域名到本机端口的出站隧道。
 *
 * 证书与隧道凭证（cert.pem / <id>.json）只存 [homeDir]（应用私有目录），
 * 通过 HOME 环境变量引导 cloudflared 写入该目录。账户证书与单隧道凭据分开保存：
 * 移除账户授权时只删除 cert.pem，固定子域名和单隧道凭据会保留以便继续运行。
 */
@Singleton
class CloudflaredManager @Inject constructor(
    @ApplicationContext context: Context,
    private val settings: SettingsRepository,
    private val repo: TunnelRepository,
    private val installer: BinaryInstaller,
) : TunnelManagerBase("cloudflared") {

    /** cloudflared 的 HOME；默认目录仍是 HOME/.cloudflared。 */
    private val homeDir = File(context.filesDir, "cloudflared")
    private val cloudflaredDir get() = File(homeDir, ".cloudflared")
    private val certFile get() = File(cloudflaredDir, "cert.pem")
    private val configFile get() = File(cloudflaredDir, "config.yml")

    private val _loggedIn = MutableStateFlow(certFile.exists())
    /** 是否已登录（以 cert.pem 是否存在为准）。 */
    val loggedIn: StateFlow<Boolean> = _loggedIn.asStateFlow()

    private val _loginState = MutableStateFlow<LoginState>(LoginState.Idle)
    val loginState: StateFlow<LoginState> = _loginState.asStateFlow()

    /** custom 模式运行时使用的公网域名（start 时从配置读取）。 */
    @Volatile private var currentHostname: String? = null

    private class SetupOperation {
        @Volatile var process: Process? = null
        @Volatile var job: Job? = null
    }

    private val setupLock = Any()
    @Volatile private var activeSetup: SetupOperation? = null

    /** quick 隧道公网 URL 出现在 stderr 日志中。 */
    private val urlRegex = Regex("https://[a-zA-Z0-9-]+\\.trycloudflare\\.com")

    fun start() {
        if (busy) return
        scope.launch {
            try {
                val cfg = repo.cloudflaredConfig.first()
                val customDomain = cfg.mode == "custom"
                if (customDomain) {
                    if (hasActiveSetup()) {
                        _state.value = TunnelState.Error("固定域名正在配置中，请完成后再开启")
                        return@launch
                    }
                    if (
                        !CloudflaredSupport.isValidHostname(cfg.hostname) ||
                        cfg.tunnelId.isBlank() ||
                        cfg.routedHostname != cfg.hostname ||
                        !credentialsFile(cfg.tunnelId).isFile
                    ) {
                        _state.value = TunnelState.Error("请先创建并绑定固定子域名")
                        return@launch
                    }
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
                val cmd = if (customDomain) {
                    // 按当前端口重写 ingress，避免改端口后配置过期
                    writeConfig(cfg.tunnelId, cfg.hostname, port)
                    currentHostname = cfg.hostname
                    common + listOf("--config", configFile.absolutePath, "run", cfg.tunnelId)
                } else {
                    currentHostname = null
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

    /**
     * 登录 Cloudflare 账户：运行 `tunnel login`，把输出的授权 URL 交给 UI，
     * 用户可在本机浏览器或扫描同一 URL 的二维码完成授权；cloudflared 自动下载 cert.pem。
     */
    fun login() {
        launchSetup("登录") { operation ->
            // cloudflared creates HOME/.cloudflared itself, but its Android build does not
            // create a missing HOME parent. Make the app-private parent first.
            if (!homeDir.isDirectory && !homeDir.mkdirs()) {
                _loginState.value = LoginState.Error("无法创建 Cloudflare 凭据目录")
                return@launchSetup
            }
            val bin = installer.ensureInstalled(Binaries.CLOUDFLARED)
            val (code, lines) = execOnce(
                operation,
                listOf(bin.absolutePath, "tunnel", "login"),
            ) { line ->
                CloudflaredSupport.extractLoginUrl(line)?.let { url ->
                    _loginState.value = LoginState.WaitingAuth(url)
                }
            }
            if (code == 0 && certFile.exists()) {
                _loggedIn.value = true
                _loginState.value = LoginState.Success
            } else {
                _loginState.value = LoginState.Error(
                    commandFailure(lines, "登录未完成（退出码 $code）"),
                )
            }
        }
    }

    /**
     * 建站（幂等）：本机已有 credentials 的同名隧道会复用；
     * 若同名隧道属于另一台设备，明确报错而不是删除远端资源。
     */
    fun provision() {
        launchSetup("建站") setup@ { operation ->
            val cfg = repo.cloudflaredConfig.first()
            if (!certFile.exists()) {
                _loginState.value = LoginState.Error("请先登录 Cloudflare 账户")
                return@setup
            }
            if (!CloudflaredSupport.isValidHostname(cfg.hostname)) {
                _loginState.value = LoginState.Error("域名格式不正确：${cfg.hostname}")
                return@setup
            }
            if (!CloudflaredSupport.isValidTunnelName(cfg.tunnelName)) {
                _loginState.value = LoginState.Error("隧道名称仅可包含字母、数字、- 或 _（最长 100 位）")
                return@setup
            }

            val bin = installer.ensureInstalled(Binaries.CLOUDFLARED)
            val name = cfg.tunnelName.trim()

            // 无法列出时不盲目创建，以免产生冲突资源。
            val (listCode, listLines) = execOnce(
                operation,
                listOf(bin.absolutePath, "tunnel", "list", "--output", "json"),
            )
            if (listCode != 0) {
                _loginState.value = LoginState.Error("读取隧道列表失败：${commandFailure(listLines, "退出码 $listCode")}")
                return@setup
            }
            val existingId = CloudflaredSupport.findTunnelId(listLines.joinToString("\n"), name)
            val id = when {
                existingId == null -> {
                    val (createCode, createLines) = execOnce(
                        operation,
                        listOf(bin.absolutePath, "tunnel", "create", name),
                    )
                    CloudflaredSupport.findTunnelId(createLines).also { createdId ->
                        if (createCode != 0 || createdId == null) {
                            _loginState.value = LoginState.Error(
                                "创建隧道失败：${commandFailure(createLines, "退出码 $createCode")}",
                            )
                        }
                    }
                }
                credentialsFile(existingId).isFile -> existingId
                else -> {
                    _loginState.value = LoginState.Error(
                        "同名隧道已存在，但本机没有它的凭据。请换一个隧道名称，或到 Cloudflare 控制台处理。",
                    )
                    null
                }
            } ?: return@setup

            // 仅在首次绑定或域名变更后创建 DNS 记录。已有记录一律报错，不能假定它指向本隧道。
            if (cfg.tunnelId != id || cfg.routedHostname != cfg.hostname) {
                val (routeCode, routeLines) = execOnce(
                    operation,
                    listOf(bin.absolutePath, "tunnel", "route", "dns", id, cfg.hostname),
                )
                if (routeCode != 0) {
                    _loginState.value = LoginState.Error(
                        "绑定域名失败：${commandFailure(routeLines, "退出码 $routeCode")}",
                    )
                    return@setup
                }
            }

            writeConfig(id, cfg.hostname, settings.port.first())
            repo.setCloudflaredTunnelBinding(id, cfg.hostname)
            _loginState.value = LoginState.Success
        }
    }

    /**
     * 移除账户级证书，但保留固定子域名与该隧道的凭据。
     * cloudflared 运行既有隧道只需要 <uuid>.json；之后只有创建或改绑时才需重新登录。
     */
    fun disconnectAccount() {
        val pendingSetup = cancelActiveSetup()
        _loginState.value = LoginState.Idle
        scope.launch {
            pendingSetup?.join()
            if (certFile.exists() && !certFile.delete()) {
                _loggedIn.value = certFile.exists()
                _loginState.value = LoginState.Error("无法移除本机 Cloudflare 账户授权")
                return@launch
            }
            _loggedIn.value = false
            appendLog("已移除本机 Cloudflare 账户授权；固定子域名和隧道凭据已保留")
        }
    }

    /** Cancel a waiting browser authorization or a currently running provisioning command. */
    fun cancelSetup() {
        if (cancelActiveSetup() != null) {
            appendLog("正在取消 Cloudflare 配置流程…")
            _loginState.value = LoginState.Working
        }
    }

    /** 登录流程完成后由 UI 调用，把 Success 复位为 Idle。 */
    fun acknowledgeLoginState() {
        if (_loginState.value is LoginState.Success) _loginState.value = LoginState.Idle
    }

    private fun credentialsFile(id: String) = File(cloudflaredDir, "$id.json")

    private fun writeConfig(tunnelId: String, hostname: String, port: Int) {
        cloudflaredDir.mkdirs()
        configFile.writeText(
            buildString {
                appendLine("tunnel: $tunnelId")
                appendLine("credentials-file: ${credentialsFile(tunnelId).absolutePath}")
                appendLine("ingress:")
                appendLine("  - hostname: $hostname")
                appendLine("    service: http://127.0.0.1:$port")
                appendLine("  - service: http_status:404")
            },
        )
    }

    /**
     * 一次性命令执行器（区别于长跑隧道的 TunnelProcess：不自动重启）。
     * 挂起等待进程退出，返回退出码与全部输出行；[onLine] 可流式观察输出。
     */
    private suspend fun execOnce(
        operation: SetupOperation,
        command: List<String>,
        onLine: ((String) -> Unit)? = null,
    ): Pair<Int, List<String>> = withContext(Dispatchers.IO) {
        appendLog("$ " + command.joinToString(" ") { if (it.length > 64) it.take(20) + "…(已隐藏)" else it })
        val pb = ProcessBuilder(command).redirectErrorStream(true)
        pb.environment()["HOME"] = homeDir.absolutePath
        val p = pb.start()
        operation.process = p
        try {
            val lines = mutableListOf<String>()
            p.inputStream.bufferedReader().forEachLine { line ->
                lines += line
                appendLog(line)
                onLine?.invoke(line)
            }
            val code = p.waitFor()
            code to lines
        } catch (e: Exception) {
            // destroy() closes the stream on a different thread. Treat that IOException as
            // a normal cancellation when it was initiated by cancelSetup()/disconnectAccount().
            if (operation.job?.isCancelled == true) {
                throw CancellationException("Cloudflare 配置已取消")
            }
            throw e
        } finally {
            if (operation.process === p) operation.process = null
        }
    }

    private fun launchSetup(label: String, action: suspend (SetupOperation) -> Unit) {
        val operation = synchronized(setupLock) {
            if (activeSetup != null) null else SetupOperation().also { activeSetup = it }
        } ?: return

        _loginState.value = LoginState.Working
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                action(operation)
            } catch (e: CancellationException) {
                _loginState.value = LoginState.Idle
                throw e
            } catch (e: Exception) {
                appendLog("$label 失败：${e.message}")
                _loginState.value = LoginState.Error(e.message ?: "$label 失败")
            } finally {
                synchronized(setupLock) {
                    if (activeSetup === operation) activeSetup = null
                }
                operation.process = null
            }
        }
        operation.job = job
        job.start()
    }

    private fun hasActiveSetup(): Boolean = synchronized(setupLock) { activeSetup != null }

    private fun cancelActiveSetup(): Job? {
        val operation = synchronized(setupLock) { activeSetup } ?: return null
        operation.process?.destroy()
        operation.job?.cancel()
        return operation.job
    }

    private fun commandFailure(lines: List<String>, fallback: String): String =
        lines.lastOrNull { it.isNotBlank() }?.take(200) ?: fallback

    private fun handleLine(line: String) {
        appendLog(line)
        // quick 模式：解析 trycloudflare URL
        val match = urlRegex.find(line)
        if (match != null) {
            _state.value = TunnelState.Running(match.value)
            return
        }
        // 固定域名模式：以连接注册成功作为运行标志，公网地址即绑定的域名。
        if (line.contains("Registered tunnel connection") && _state.value !is TunnelState.Running) {
            _state.value = TunnelState.Running(currentHostname?.let { "https://$it" })
        }
    }

    private companion object {
        val ANDROID_EDGE_ADDRESSES = listOf(
            "region1.v2.argotunnel.com:7844",
            "region2.v2.argotunnel.com:7844",
        )
    }
}
