package dev.androidmcp.tunnel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** 隧道运行状态。Running 的 publicUrl：cloudflared 为 trycloudflare 域名，frpc 为 服务器:远程端口。 */
sealed interface TunnelState {
    data object Stopped : TunnelState
    data object Extracting : TunnelState
    data object Starting : TunnelState
    data class Running(val publicUrl: String?) : TunnelState
    data class Error(val message: String) : TunnelState
}

/**
 * 通用子进程托管：启动/停止、输出按行回调（stdout+stderr 合并）、
 * 退出码监听、异常退出自动重启（指数退避 1s/2s/4s，最多 [maxRestarts] 次）。
 */
class TunnelProcess(
    private val command: List<String>,
    private val scope: CoroutineScope,
    private val maxRestarts: Int = 3,
    private val onLine: (String) -> Unit,
    private val onExit: (exitCode: Int, willRestart: Boolean) -> Unit,
) {
    private val stopping = AtomicBoolean(false)
    @Volatile private var process: Process? = null
    @Volatile private var restarts = 0

    fun start() {
        stopping.set(false)
        restarts = 0
        launch()
    }

    private fun launch() {
        val p = ProcessBuilder(command).redirectErrorStream(true).start()
        process = p
        // 输出行读取（进程销毁时流关闭，循环自然结束）
        scope.launch(Dispatchers.IO) {
            try {
                p.inputStream.bufferedReader().forEachLine { onLine(it) }
            } catch (_: Exception) {
                // 进程被强杀时读流会抛异常，忽略
            }
        }
        // 退出监听与自动重启
        scope.launch(Dispatchers.IO) {
            val code = try {
                p.waitFor()
            } catch (_: Exception) {
                -1
            }
            if (stopping.get()) return@launch // 主动停止，不回调不重启
            if (restarts < maxRestarts) {
                restarts++
                onExit(code, true)
                delay(1000L * (1L shl (restarts - 1))) // 指数退避
                if (!stopping.get()) launch()
            } else {
                onExit(code, false)
            }
        }
    }

    fun stop() {
        stopping.set(true)
        val p = process ?: return
        p.destroy()
        scope.launch(Dispatchers.IO) {
            try {
                if (!p.waitFor(2, TimeUnit.SECONDS)) p.destroyForcibly()
            } catch (_: Exception) {
                p.destroyForcibly()
            }
        }
    }
}

/**
 * 隧道 Manager 基类：状态流 + 日志环形缓冲（最近 200 行）+ 进程托管封装。
 */
abstract class TunnelManagerBase(protected val tag: String) {
    protected val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    protected val _state = MutableStateFlow<TunnelState>(TunnelState.Stopped)
    val state: StateFlow<TunnelState> = _state.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    @Volatile private var process: TunnelProcess? = null

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    /** 是否处于"已占用"状态（释放组件中/启动中/运行中），用于防止重复 start。 */
    protected val busy: Boolean
        get() = when (_state.value) {
            TunnelState.Extracting, TunnelState.Starting, is TunnelState.Running -> true
            else -> false
        }

    fun appendLog(line: String) {
        val stamped = "${timeFormat.format(Date())}  $line"
        _logs.update { (it + stamped).takeLast(200) }
    }

    protected fun runProcess(command: List<String>, onLine: (String) -> Unit) {
        appendLog("$ ${command.joinToString(" ") { if (it.length > 64) it.take(20) + "…(已隐藏)" else it }}")
        val p = TunnelProcess(
            command = command,
            scope = scope,
            onLine = onLine,
            onExit = ::handleExit,
        )
        process = p
        p.start()
    }

    private fun handleExit(code: Int, willRestart: Boolean) {
        if (willRestart) {
            appendLog("进程异常退出（code=$code），将自动重启…")
            _state.value = TunnelState.Starting
        } else {
            appendLog("进程退出（code=$code），已达最大重启次数")
            _state.value = TunnelState.Error("进程异常退出，退出码 $code（已重试 3 次）")
        }
    }

    open fun stop() {
        process?.stop()
        process = null
        _state.value = TunnelState.Stopped
    }
}
