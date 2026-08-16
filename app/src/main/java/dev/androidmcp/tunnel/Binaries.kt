package dev.androidmcp.tunnel

import android.content.Context
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** 支持的 CPU ABI（只打包 64 位）。 */
enum class Abi { ARM64, AMD64 }

/** 一个内置隧道二进制的元数据：版本、各 ABI 的 native library 文件名与 SHA256。 */
class BinarySpec(
    val key: String,
    val version: String,
    val nativeLibraryNames: Map<Abi, String>,
    val sha256: Map<Abi, String>,
)

/** 二进制校验状态。 */
sealed interface BinaryState {
    data object NotInstalled : BinaryState
    data object Verifying : BinaryState
    data object Ready : BinaryState
    data class Error(val reason: String) : BinaryState
}

/**
 * 锁定的二进制版本与校验值。二进制作为未压缩 JNI library 内置在 APK 中，
 * 由系统解包至 applicationInfo.nativeLibraryDir 后直接执行。
 * 来源（2026-08 下载并核对软件包与二进制 SHA256）：
 * cloudflared: Termux 官方仓库基于 cloudflare/cloudflared 2026.8.2 构建的 Android 原生二进制
 * frpc:        repos/fatedier/frp v0.71.0（从 tar.gz 解出 frpc 后打包，hash 为解包后文件的 SHA256）
 */
object Binaries {
    val CLOUDFLARED = BinarySpec(
        key = "cloudflared",
        version = "2026.8.2",
        nativeLibraryNames = mapOf(
            Abi.ARM64 to "libcloudflared.so",
            Abi.AMD64 to "libcloudflared.so",
        ),
        sha256 = mapOf(
            Abi.ARM64 to "adcbc5cb319af844a4ce932f4ed656ee8656b1c478faf5001aff4b6166a950ef",
            Abi.AMD64 to "bef4e82cb4fd26e6fd99ffbd9d1b8735e9eaff96987298d0998013b195fe6786",
        ),
    )

    val FRPC = BinarySpec(
        key = "frpc",
        version = "0.71.0",
        nativeLibraryNames = mapOf(
            Abi.ARM64 to "libfrpc.so",
            Abi.AMD64 to "libfrpc.so",
        ),
        sha256 = mapOf(
            Abi.ARM64 to "6e8e45fd0c7514b636fd8d049212f8a5715e8b33c412cf968988252e7a8a00f2",
            Abi.AMD64 to "f79fff8de3089ec711ff8bdd4b73e00dfe491a1c3d754983c8b0f8d58c21b068",
        ),
    )

    /** 当前设备 ABI；不支持（如纯 32 位）返回 null。 */
    fun currentAbi(): Abi? {
        val abis = Build.SUPPORTED_ABIS
        return when {
            abis.any { it.equals("arm64-v8a", ignoreCase = true) } -> Abi.ARM64
            abis.any { it.equals("x86_64", ignoreCase = true) } -> Abi.AMD64
            else -> null
        }
    }
}

/** 校验 APK 内置的 native library 是否可直接作为隧道组件执行。 */
@Singleton
class BinaryInstaller @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val states = HashMap<String, kotlinx.coroutines.flow.MutableStateFlow<BinaryState>>()
    private val mutexes = HashMap<String, Mutex>()

    fun stateOf(spec: BinarySpec): kotlinx.coroutines.flow.StateFlow<BinaryState> =
        synchronized(states) {
            states.getOrPut(spec.key) {
                kotlinx.coroutines.flow.MutableStateFlow(initialState(spec))
            }
        }

    fun binaryFile(spec: BinarySpec): File {
        val abi = Binaries.currentAbi()
            ?: throw IllegalStateException("不支持的 CPU 架构：${Build.SUPPORTED_ABIS.joinToString()}")
        return File(context.applicationInfo.nativeLibraryDir, spec.nativeLibraryNames.getValue(abi))
    }

    private fun initialState(spec: BinarySpec): BinaryState =
        if (Binaries.currentAbi() == null) BinaryState.Error("不支持的 CPU 架构") else BinaryState.NotInstalled

    private fun setState(spec: BinarySpec, state: BinaryState) {
        synchronized(states) {
            states.getOrPut(spec.key) { kotlinx.coroutines.flow.MutableStateFlow(state) }
        }.value = state
    }

    /** 确保 APK 内置的 native library 存在、可执行且与锁定 SHA256 一致。 */
    suspend fun ensureInstalled(spec: BinarySpec): File =
        withContext(Dispatchers.IO) {
            val mutex = synchronized(mutexes) { mutexes.getOrPut(spec.key) { Mutex() } }
            mutex.withLock {
                try {
                    setState(spec, BinaryState.Verifying)
                    val abi = Binaries.currentAbi()
                        ?: throw IOException("不支持的 CPU 架构：${Build.SUPPORTED_ABIS.joinToString()}")
                    val binary = binaryFile(spec)
                    if (!binary.exists() || !binary.canExecute()) {
                        throw IOException(
                            "内置组件不可执行：${binary.absolutePath} " +
                                "(exists=${binary.exists()}, canExecute=${binary.canExecute()})",
                        )
                    }
                    val expected = spec.sha256.getValue(abi)
                    val actual = sha256(binary)
                    if (!actual.equals(expected, ignoreCase = true)) {
                        throw IOException(
                            "SHA256 校验失败：${binary.absolutePath} " +
                                "(canExecute=${binary.canExecute()}, 期望 ${expected.take(12)}…，实际 ${actual.take(12)}…)",
                        )
                    }
                    setState(spec, BinaryState.Ready)
                    binary
                } catch (e: Exception) {
                    val reason = e.message ?: "校验内置组件失败"
                    setState(spec, BinaryState.Error(reason))
                    throw e
                }
            }
        }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buf)
                if (read == -1) break
                md.update(buf, 0, read)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
