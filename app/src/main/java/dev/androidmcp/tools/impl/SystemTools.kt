package dev.androidmcp.tools.impl

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.Location
import android.location.LocationManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.VibratorManager
import android.provider.AlarmClock
import android.provider.Settings
import android.speech.tts.TextToSpeech
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.androidmcp.tools.McpTool
import dev.androidmcp.tools.ToolCategory
import dev.androidmcp.tools.boolOr
import dev.androidmcp.tools.errorResult
import dev.androidmcp.tools.inputSchema
import dev.androidmcp.tools.intOr
import dev.androidmcp.tools.jsonResult
import dev.androidmcp.tools.reqInt
import dev.androidmcp.tools.reqStr
import dev.androidmcp.tools.str
import dev.androidmcp.tools.textResult
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import kotlin.coroutines.resume

// ---------- 音量 / 亮度 / 铃声 ----------

class GetVolumeTool @Inject constructor(
    @ApplicationContext private val context: Context,
) : McpTool {
    override val name = "get_volume"
    override val description = "获取各音频流的当前音量与最大音量（music/ring/alarm/notification/voice_call）"
    override val category = ToolCategory.READ
    override val inputSchema: ToolSchema = inputSchema {}

    override suspend fun execute(args: JsonObject): CallToolResult {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val streams = mapOf(
            "music" to AudioManager.STREAM_MUSIC,
            "ring" to AudioManager.STREAM_RING,
            "alarm" to AudioManager.STREAM_ALARM,
            "notification" to AudioManager.STREAM_NOTIFICATION,
            "voice_call" to AudioManager.STREAM_VOICE_CALL,
        )
        return jsonResult(buildJsonObject {
            streams.forEach { (streamName, stream) ->
                putJsonObject(streamName) {
                    put("volume", am.getStreamVolume(stream))
                    put("max", am.getStreamMaxVolume(stream))
                }
            }
        })
    }
}

class SetVolumeTool @Inject constructor(
    @ApplicationContext private val context: Context,
) : McpTool {
    override val name = "set_volume"
    override val description = "设置指定音频流音量。stream 必填，level 为 0-100 百分比（自动换算为系统音量档位）"
    override val category = ToolCategory.SYSTEM
    override val inputSchema: ToolSchema = inputSchema {
        string("stream", "音频流", required = true, enum = listOf("music", "ring", "alarm", "notification"))
        integer("level", "音量百分比 0-100", required = true)
    }

    override suspend fun execute(args: JsonObject): CallToolResult {
        val streamName = args.reqStr("stream")
        val stream = when (streamName) {
            "music" -> AudioManager.STREAM_MUSIC
            "ring" -> AudioManager.STREAM_RING
            "alarm" -> AudioManager.STREAM_ALARM
            "notification" -> AudioManager.STREAM_NOTIFICATION
            else -> return errorResult("不支持的 stream，可选: music/ring/alarm/notification")
        }
        val level = args.reqInt("level").coerceIn(0, 100)
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = am.getStreamMaxVolume(stream)
        val index = max * level / 100
        return runCatching {
            am.setStreamVolume(stream, index, 0)
            textResult("已设置 $streamName 音量为 $index/$max（$level%）")
        }.getOrElse { errorResult("设置音量失败（开启勿扰时可能被系统拒绝）: ${it.message}") }
    }
}

class GetBrightnessTool @Inject constructor(
    @ApplicationContext private val context: Context,
) : McpTool {
    override val name = "get_brightness"
    override val description = "获取屏幕亮度（brightness 为 0-255 原始值，percent 为百分比）与亮度模式（manual/auto）"
    override val category = ToolCategory.READ
    override val inputSchema: ToolSchema = inputSchema {}

    override suspend fun execute(args: JsonObject): CallToolResult {
        val cr = context.contentResolver
        val brightness = runCatching {
            Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS)
        }.getOrDefault(-1)
        val mode = runCatching {
            Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE)
        }.getOrDefault(-1)
        return jsonResult(buildJsonObject {
            put("brightness", brightness)
            put("percent", if (brightness >= 0) brightness * 100 / 255 else -1)
            put("mode", when (mode) {
                Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC -> "auto"
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL -> "manual"
                else -> "unknown"
            })
        })
    }
}

class SetBrightnessTool @Inject constructor(
    @ApplicationContext private val context: Context,
) : McpTool {
    override val name = "set_brightness"
    override val description =
        "设置屏幕亮度，level 为 0-100 百分比（同时把亮度模式切换为手动，否则自动亮度会覆盖设置）。需要「修改系统设置」特殊权限"
    override val category = ToolCategory.SYSTEM
    // WRITE_SETTINGS 是特殊权限，checkSelfPermission 不可靠：requiredPermissions 留空，
    // 在 execute 内用 Settings.System.canWrite 自检；授权跳转由 App 工具页的 specialPermissionIntent 处理。
    override val inputSchema: ToolSchema = inputSchema {
        integer("level", "亮度百分比 0-100", required = true)
    }

    override suspend fun execute(args: JsonObject): CallToolResult {
        if (!Settings.System.canWrite(context)) {
            return errorResult("未授予「修改系统设置」权限，请在 App「工具」页找到本工具并点击授权跳转系统设置开启")
        }
        val level = args.reqInt("level").coerceIn(0, 100)
        return runCatching {
            val cr = context.contentResolver
            Settings.System.putInt(
                cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
            )
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, level * 255 / 100)
            textResult("已设置亮度 $level%")
        }.getOrElse { errorResult("设置亮度失败: ${it.message}") }
    }
}

class RingerModeTool @Inject constructor(
    @ApplicationContext private val context: Context,
) : McpTool {
    override val name = "ringer_mode"
    override val description = "设置响铃模式：normal（响铃）/vibrate（振动）/silent（静音）"
    override val category = ToolCategory.SYSTEM
    override val inputSchema: ToolSchema = inputSchema {
        string("mode", "响铃模式", required = true, enum = listOf("normal", "vibrate", "silent"))
    }

    override suspend fun execute(args: JsonObject): CallToolResult {
        val modeName = args.reqStr("mode")
        val mode = when (modeName) {
            "normal" -> AudioManager.RINGER_MODE_NORMAL
            "vibrate" -> AudioManager.RINGER_MODE_VIBRATE
            "silent" -> AudioManager.RINGER_MODE_SILENT
            else -> return errorResult("不支持的 mode，可选: normal/vibrate/silent")
        }
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return runCatching {
            am.ringerMode = mode
            textResult("已设置响铃模式 $modeName")
        }.getOrElse { errorResult("设置响铃模式失败（勿扰开启时可能被系统拒绝）: ${it.message}") }
    }
}

class DndTool @Inject constructor(
    @ApplicationContext private val context: Context,
) : McpTool, RequiresDndAccess {
    override val name = "dnd"
    override val description = "开关勿扰模式（DND）。enabled=true 开启（拦截所有通知），false 关闭。需要勿扰策略访问权限"
    override val category = ToolCategory.SYSTEM
    override val inputSchema: ToolSchema = inputSchema {
        boolean("enabled", "true 开启勿扰，false 关闭", required = true)
    }

    override suspend fun execute(args: JsonObject): CallToolResult {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!nm.isNotificationPolicyAccessGranted) {
            return errorResult("未授予勿扰（DND）策略权限，请在 App「工具」页找到本工具并点击授权跳转系统设置开启")
        }
        val enabled = args["enabled"]?.jsonPrimitive?.booleanOrNull
            ?: return errorResult("缺少必填参数: enabled")
        return runCatching {
            nm.setInterruptionFilter(
                if (enabled) NotificationManager.INTERRUPTION_FILTER_NONE
                else NotificationManager.INTERRUPTION_FILTER_ALL,
            )
            textResult(if (enabled) "已开启勿扰模式" else "已关闭勿扰模式")
        }.getOrElse { errorResult("设置勿扰失败: ${it.message}") }
    }
}

// ---------- 手电筒 / 振动 / 亮屏 ----------

class FlashlightTool @Inject constructor(
    @ApplicationContext private val context: Context,
) : McpTool {
    override val name = "flashlight"
    override val description = "开关手电筒。enabled=true 打开，false 关闭（使用第一个带闪光灯的摄像头）"
    override val category = ToolCategory.SYSTEM
    override val inputSchema: ToolSchema = inputSchema {
        boolean("enabled", "true 打开，false 关闭", required = true)
    }

    override suspend fun execute(args: JsonObject): CallToolResult {
        val enabled = args["enabled"]?.jsonPrimitive?.booleanOrNull
            ?: return errorResult("缺少必填参数: enabled")
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = cm.cameraIdList.firstOrNull { id ->
            runCatching {
                cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }.getOrDefault(false)
        } ?: return errorResult("设备没有可用的闪光灯")
        return runCatching {
            cm.setTorchMode(cameraId, enabled)
            textResult(if (enabled) "手电筒已打开" else "手电筒已关闭")
        }.getOrElse { errorResult("手电筒操作失败: ${it.message}") }
    }
}

class VibrateTool @Inject constructor(
    @ApplicationContext private val context: Context,
) : McpTool {
    override val name = "vibrate"
    override val description = "让设备振动。duration_ms 为振动毫秒数，默认 300，上限 10000"
    override val category = ToolCategory.SYSTEM
    override val inputSchema: ToolSchema = inputSchema {
        integer("duration_ms", "振动时长（毫秒），默认 300，上限 10000")
    }

    override suspend fun execute(args: JsonObject): CallToolResult {
        val duration = args.intOr("duration_ms", 300).coerceIn(1, 10_000).toLong()
        val vibrator = context.getSystemService(VibratorManager::class.java)?.defaultVibrator
            ?: return errorResult("设备不支持振动")
        return runCatching {
            vibrator.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE))
            textResult("已振动 ${duration}ms")
        }.getOrElse { errorResult("振动失败: ${it.message}") }
    }
}

class WakeScreenTool @Inject constructor(
    @ApplicationContext private val context: Context,
) : McpTool {
    override val name = "wake_screen"
    override val description = "点亮屏幕约 5 秒（只亮屏，不解除锁屏）"
    override val category = ToolCategory.SYSTEM
    override val inputSchema: ToolSchema = inputSchema {}

    @Suppress("DEPRECATION") // SCREEN_BRIGHT_WAKE_LOCK 已废弃但仍是亮屏最直接的可用方式
    override suspend fun execute(args: JsonObject): CallToolResult = runCatching {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = pm.newWakeLock(
            PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.SCREEN_BRIGHT_WAKE_LOCK,
            "androidmcp:wake_screen",
        )
        wakeLock.acquire(5_000)
        textResult("屏幕已点亮（约 5 秒）")
    }.getOrElse { errorResult("亮屏失败: ${it.message}") }
}

// ---------- 应用 / 闹钟 / 计时器 ----------

class ListAppsTool @Inject constructor(
    @ApplicationContext private val context: Context,
) : McpTool {
    override val name = "list_apps"
    override val description =
        "列出已安装应用（包名/应用名/版本）。include_system=true 时包含系统应用，默认仅用户应用。结果超过 500 条会截断"
    override val category = ToolCategory.READ
    override val inputSchema: ToolSchema = inputSchema {
        boolean("include_system", "是否包含系统应用，默认 false")
    }

    override suspend fun execute(args: JsonObject): CallToolResult {
        val includeSystem = args.boolOr("include_system", false)
        val pm = context.packageManager
        val apps = installedPackages(pm).mapNotNull { pi ->
            val ai = pi.applicationInfo ?: return@mapNotNull null
            if (!includeSystem && (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0) return@mapNotNull null
            Triple(
                pi.packageName,
                runCatching { pm.getApplicationLabel(ai).toString() }.getOrDefault(pi.packageName),
                pi.versionName ?: "",
            )
        }.sortedBy { it.second.lowercase() }
        val truncated = apps.size > 500
        return jsonResult(buildJsonObject {
            put("count", if (truncated) 500 else apps.size)
            put("truncated", truncated)
            putJsonArray("apps") {
                apps.take(500).forEach { (pkg, label, version) ->
                    add(buildJsonObject {
                        put("package", pkg)
                        put("label", label)
                        put("version", version)
                    })
                }
            }
        })
    }

    private fun installedPackages(pm: PackageManager): List<PackageInfo> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledPackages(0)
        }
}

class OpenAppSettingsTool @Inject constructor(
    @ApplicationContext private val context: Context,
) : McpTool {
    override val name = "open_app_settings"
    override val description = "打开指定应用的系统设置详情页"
    override val category = ToolCategory.SYSTEM
    override val inputSchema: ToolSchema = inputSchema {
        string("package", "目标应用包名", required = true)
    }

    override suspend fun execute(args: JsonObject): CallToolResult {
        val pkg = args.reqStr("package")
        val exists = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(pkg, 0)
            }
        }.isSuccess
        if (!exists) return errorResult("应用未安装: $pkg")
        return runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            textResult("已打开 $pkg 的应用设置页")
        }.getOrElse { errorResult("打开失败: ${it.message}") }
    }
}

class SetAlarmTool @Inject constructor(
    @ApplicationContext private val context: Context,
) : McpTool {
    override val name = "set_alarm"
    override val description = "创建闹钟。hour（0-23）与 minute（0-59）必填，message 可选（闹钟标签）。依赖系统时钟应用"
    override val category = ToolCategory.SYSTEM
    override val inputSchema: ToolSchema = inputSchema {
        integer("hour", "小时 0-23", required = true)
        integer("minute", "分钟 0-59", required = true)
        string("message", "闹钟标签/备注")
    }

    override suspend fun execute(args: JsonObject): CallToolResult {
        val hour = args.reqInt("hour")
        val minute = args.reqInt("minute")
        if (hour !in 0..23 || minute !in 0..59) return errorResult("hour 需在 0-23、minute 需在 0-59")
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        args.str("message")?.let { intent.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        return runCatching {
            context.startActivity(intent)
            textResult("已创建闹钟 %02d:%02d".format(hour, minute))
        }.getOrElse { errorResult("创建闹钟失败（设备可能无闹钟应用）: ${it.message}") }
    }
}

class SetTimerTool @Inject constructor(
    @ApplicationContext private val context: Context,
) : McpTool {
    override val name = "set_timer"
    override val description = "启动倒计时。seconds 必填（1-86400），message 可选。依赖系统时钟应用"
    override val category = ToolCategory.SYSTEM
    override val inputSchema: ToolSchema = inputSchema {
        integer("seconds", "倒计时秒数", required = true)
        string("message", "计时器标签/备注")
    }

    override suspend fun execute(args: JsonObject): CallToolResult {
        val seconds = args.reqInt("seconds")
        if (seconds !in 1..86_400) return errorResult("seconds 需在 1-86400")
        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        args.str("message")?.let { intent.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        return runCatching {
            context.startActivity(intent)
            textResult("已启动 ${seconds} 秒倒计时")
        }.getOrElse { errorResult("启动倒计时失败（设备可能无时钟应用）: ${it.message}") }
    }
}

// ---------- 语音 / 位置 / 传感器 ----------

class SpeakTool @Inject constructor(
    @ApplicationContext private val context: Context,
) : McpTool {
    override val name = "speak"
    override val description = "用系统 TTS 朗读文本（异步朗读，立即返回）"
    override val category = ToolCategory.SYSTEM
    override val inputSchema: ToolSchema = inputSchema {
        string("text", "要朗读的文本", required = true)
    }

    private val mutex = Mutex()

    @Volatile
    private var ttsInstance: TextToSpeech? = null

    override suspend fun execute(args: JsonObject): CallToolResult {
        val text = args.reqStr("text")
        if (text.isBlank()) return errorResult("text 不能为空")
        val tts = ensureTts() ?: return errorResult("TTS 初始化失败或超时（5 秒）")
        val result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "androidmcp_speak")
        return if (result == TextToSpeech.SUCCESS) {
            textResult("已开始朗读（${text.length} 字符）")
        } else {
            errorResult("TTS 朗读调用失败: $result")
        }
    }

    /** 惰性创建并等待 TTS 初始化完成（最多 5 秒）；实例复用，Mutex 防并发重复创建。 */
    private suspend fun ensureTts(): TextToSpeech? {
        ttsInstance?.let { return it }
        return mutex.withLock {
            ttsInstance ?: withContext(Dispatchers.Main) {
                withTimeoutOrNull(5_000) {
                    suspendCancellableCoroutine<TextToSpeech?> { cont ->
                        var created: TextToSpeech? = null
                        created = TextToSpeech(context) { status ->
                            if (cont.isActive) {
                                cont.resume(if (status == TextToSpeech.SUCCESS) created else null)
                            }
                        }
                        cont.invokeOnCancellation { created?.shutdown() }
                    }
                }
            }?.also { ttsInstance = it }
        }
    }
}

class GetLocationTool @Inject constructor(
    @ApplicationContext private val context: Context,
) : McpTool {
    override val name = "get_location"
    override val description =
        "获取当前位置（经纬度/精度/海拔/时间）。依次尝试 fused/GPS/网络定位，整体超时 15 秒。需要位置权限与系统定位开关"
    override val category = ToolCategory.READ
    override val requiredPermissions = listOf(
        android.Manifest.permission.ACCESS_FINE_LOCATION,
        android.Manifest.permission.ACCESS_COARSE_LOCATION,
    )
    override val inputSchema: ToolSchema = inputSchema {}

    override suspend fun execute(args: JsonObject): CallToolResult {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = listOf(
            LocationManager.FUSED_PROVIDER,
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
        ).filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        if (providers.isEmpty()) return errorResult("定位服务未开启或无可用定位提供器")
        val location = withTimeoutOrNull(15_000) {
            for (provider in providers) {
                val loc = awaitLocation(lm, provider)
                if (loc != null) return@withTimeoutOrNull loc
            }
            null
        } ?: return errorResult("获取位置超时或失败（15 秒）")
        return jsonResult(buildJsonObject {
            put("latitude", location.latitude)
            put("longitude", location.longitude)
            if (location.hasAccuracy()) put("accuracy_m", location.accuracy)
            put("altitude_m", location.altitude)
            put("provider", location.provider)
            put("time", location.time)
        })
    }

    /** 挂起封装 getCurrentLocation；协程取消时联动 CancellationSignal。 */
    private suspend fun awaitLocation(lm: LocationManager, provider: String): Location? =
        suspendCancellableCoroutine { cont ->
            val signal = CancellationSignal()
            cont.invokeOnCancellation { signal.cancel() }
            runCatching {
                lm.getCurrentLocation(provider, signal, ContextCompat.getMainExecutor(context)) { loc ->
                    if (cont.isActive) cont.resume(loc)
                }
            }.onFailure { if (cont.isActive) cont.resume(null) }
        }
}

class ListSensorsTool @Inject constructor(
    @ApplicationContext private val context: Context,
) : McpTool {
    override val name = "list_sensors"
    override val description = "列出设备全部传感器（名称/类型 type 值/厂商），type 值可传给 read_sensor"
    override val category = ToolCategory.READ
    override val inputSchema: ToolSchema = inputSchema {}

    override suspend fun execute(args: JsonObject): CallToolResult {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensors = sm.getSensorList(Sensor.TYPE_ALL)
        return jsonResult(buildJsonObject {
            put("count", sensors.size)
            putJsonArray("sensors") {
                sensors.forEach { s ->
                    add(buildJsonObject {
                        put("name", s.name)
                        put("type", s.type)
                        put("vendor", s.vendor)
                    })
                }
            }
        })
    }
}

class ReadSensorTool @Inject constructor(
    @ApplicationContext private val context: Context,
) : McpTool {
    override val name = "read_sensor"
    override val description =
        "读取指定传感器在采样窗口内的最近一次数据。type 为 Android 传感器类型值（见 list_sensors）；duration_ms 默认 1000、上限 5000"
    override val category = ToolCategory.READ
    override val inputSchema: ToolSchema = inputSchema {
        integer("type", "传感器类型值（android sensor type）", required = true)
        integer("duration_ms", "采样窗口（毫秒），默认 1000，上限 5000")
    }

    override suspend fun execute(args: JsonObject): CallToolResult {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val type = args.reqInt("type")
        val sensor = sm.getDefaultSensor(type) ?: return errorResult("没有 type=$type 的传感器")
        val duration = args.intOr("duration_ms", 1000).coerceIn(50, 5000).toLong()
        val latest = AtomicReference<FloatArray?>(null)
        val samples = AtomicInteger(0)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                latest.set(event.values.copyOf())
                samples.incrementAndGet()
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        if (!sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_FASTEST)) {
            return errorResult("无法注册传感器监听（可能被系统限制）")
        }
        try {
            delay(duration)
        } finally {
            sm.unregisterListener(listener)
        }
        val values = latest.get() ?: return errorResult("采样窗口（${duration}ms）内未收到传感器数据")
        return jsonResult(buildJsonObject {
            put("name", sensor.name)
            put("type", sensor.type)
            put("samples", samples.get())
            putJsonArray("values") { values.forEach { add(it) } }
        })
    }
}
