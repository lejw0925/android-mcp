package dev.androidmcp.shizuku

import android.content.Context
import android.content.pm.PackageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.thread

/** Shizuku shell 执行结果。输出超过 64KB 会被截断并在文本尾部标注。 */
data class ShellResult(
    val stdout: String,
    val stderr: String,
    val exitCode: Int,
    val timedOut: Boolean,
)

/**
 * Shizuku 集成（P4 将在此填充 run_shell 等能力）。
 * 仅在 Shizuku 已安装、binder 存活且已授权时就绪。
 */
@Singleton
class ShizukuManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val _binderReady = MutableStateFlow(false)
    val binderReady: StateFlow<Boolean> = _binderReady

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        _binderReady.value = true
    }
    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        _binderReady.value = false
    }
    private val permissionListener = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
        _permissionGranted.value = grantResult == PackageManager.PERMISSION_GRANTED
    }

    private val _permissionGranted = MutableStateFlow(false)
    val permissionGranted: StateFlow<Boolean> = _permissionGranted

    fun init() {
        runCatching {
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            Shizuku.addRequestPermissionResultListener(permissionListener)
            _permissionGranted.value = hasPermission()
            _binderReady.value = Shizuku.pingBinder()
        }
    }

    fun isInstalled(): Boolean = runCatching {
        context.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0)
        true
    }.getOrDefault(false)

    fun pingBinder(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun hasPermission(): Boolean =
        pingBinder() && runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)

    fun requestPermission() {
        runCatching {
            if (!Shizuku.isPreV11()) Shizuku.requestPermission(REQUEST_CODE)
        }
    }

    fun isReady(): Boolean = pingBinder() && hasPermission()

    /**
     * 以 Shizuku（shell/ADB）权限执行命令，等同 `adb shell <cmd>`。
     * 通过反射调用隐藏 API `Shizuku.newProcess` 拿进程（binder 调用与 Looper 无关，IO 线程即可）；
     * stdout/stderr 分线程读取防管道写满死锁，超时先 destroy 再 destroyForcibly。
     */
    suspend fun execShell(cmd: String, timeoutMs: Long = 10_000): ShellResult = withContext(Dispatchers.IO) {
        if (!isReady()) return@withContext ShellResult("", "Shizuku 未就绪", -1, false)
        val process = try {
            newShizukuProcess(arrayOf("sh", "-c", cmd))
        } catch (t: Throwable) {
            return@withContext ShellResult("", "无法创建 Shizuku 进程: ${t.message}", -1, false)
        }
        val stdout = StreamBuffer()
        val stderr = StreamBuffer()
        val outThread = thread(isDaemon = true) { stdout.readAll(process.inputStream) }
        val errThread = thread(isDaemon = true) { stderr.readAll(process.errorStream) }
        val finished = runCatching { process.waitFor(timeoutMs, TimeUnit.MILLISECONDS) }.getOrDefault(false)
        if (!finished) {
            runCatching { process.destroy() }
            if (!runCatching { process.waitFor(500, TimeUnit.MILLISECONDS) }.getOrDefault(false)) {
                runCatching { process.destroyForcibly() }
            }
        }
        // 进程结束/销毁后流会 EOF，读取线程随之退出；join 兜底防极端卡住
        outThread.join(2_000)
        errThread.join(2_000)
        ShellResult(
            stdout = stdout.text(),
            stderr = stderr.text(),
            exitCode = if (finished) runCatching { process.exitValue() }.getOrDefault(-1) else -1,
            timedOut = !finished,
        )
    }

    /** 反射调用隐藏 API：Shizuku.newProcess(String[] cmd, String[] env, String dir)。 */
    private fun newShizukuProcess(cmd: Array<String>): Process {
        val method = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java,
        )
        method.isAccessible = true
        return method.invoke(null, cmd, null, null) as Process
    }

    /** 读流到字符串并截断到 64KB；超限后继续读丢弃，避免管道写满阻塞子进程。 */
    private class StreamBuffer {
        private val sb = StringBuilder()

        @Volatile
        var truncated = false
            private set

        fun readAll(input: java.io.InputStream) {
            val buf = ByteArray(8192)
            while (true) {
                val n = runCatching { input.read(buf) }.getOrDefault(-1)
                if (n <= 0) break
                if (sb.length >= LIMIT) {
                    truncated = true
                    continue
                }
                val chunk = String(buf, 0, n, Charsets.UTF_8)
                if (sb.length + chunk.length > LIMIT) {
                    sb.append(chunk, 0, LIMIT - sb.length)
                    truncated = true
                } else {
                    sb.append(chunk)
                }
            }
            runCatching { input.close() }
        }

        fun text(): String = if (truncated) "$sb\n…[输出超过 64KB，已截断]" else sb.toString()
    }

    companion object {
        private const val REQUEST_CODE = 20240
        private const val LIMIT = 64 * 1024
    }
}
