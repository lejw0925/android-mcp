package dev.androidmcp.tools.impl

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.provider.CallLog
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.telephony.SmsManager
import android.view.KeyEvent
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.androidmcp.notification.NlService
import dev.androidmcp.tools.McpTool
import dev.androidmcp.tools.ToolCategory
import dev.androidmcp.tools.boolOr
import dev.androidmcp.tools.errorResult
import dev.androidmcp.tools.inputSchema
import dev.androidmcp.tools.intOr
import dev.androidmcp.tools.jsonResult
import dev.androidmcp.tools.reqStr
import dev.androidmcp.tools.str
import dev.androidmcp.tools.textResult
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import javax.inject.Inject

/** 通知使用权是否已授予（不依赖 NlService 是否已被系统绑定）。 */
private fun notificationAccessGranted(context: Context): Boolean =
    NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

private fun noNlError(): CallToolResult =
    errorResult("通知监听服务未运行，请在系统设置授予（或重新开关）本应用的通知使用权，可在 App「工具」页跳转")

/** 当前活跃媒体会话；无权限或异常时返回空列表。 */
private fun activeMediaControllers(context: Context): List<MediaController> = runCatching {
    val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    msm.getActiveSessions(ComponentName(context, NlService::class.java))
}.getOrDefault(emptyList())

private fun playbackStateName(state: Int?): String = when (state) {
    PlaybackState.STATE_PLAYING -> "playing"
    PlaybackState.STATE_PAUSED -> "paused"
    PlaybackState.STATE_STOPPED -> "stopped"
    PlaybackState.STATE_BUFFERING -> "buffering"
    PlaybackState.STATE_FAST_FORWARDING -> "fast_forwarding"
    PlaybackState.STATE_REWINDING -> "rewinding"
    PlaybackState.STATE_SKIPPING_TO_NEXT -> "skipping_next"
    PlaybackState.STATE_SKIPPING_TO_PREVIOUS -> "skipping_previous"
    null -> "unknown"
    else -> "state_$state"
}

// ---------- 通知 ----------

class ReadNotificationsTool @Inject constructor() : McpTool, RequiresNotificationAccess {
    override val name = "read_notifications"
    override val description = "读取当前活跃通知（包名/标题/正文/时间，按时间倒序最多 50 条）。需要通知使用权"
    override val category = ToolCategory.COMMUNICATION
    override val defaultEnabled = false // 敏感：通知含隐私内容
    override val inputSchema: ToolSchema = inputSchema {}

    override suspend fun execute(args: JsonObject): CallToolResult {
        if (!NlService.isRunning()) return noNlError()
        val notifications = NlService.activeNotifications()
            .sortedByDescending { it.postTime }
            .take(50)
        return jsonResult(buildJsonObject {
            put("count", notifications.size)
            putJsonArray("notifications") {
                notifications.forEach { sbn ->
                    val extras = sbn.notification.extras
                    add(buildJsonObject {
                        put("key", sbn.key)
                        put("package", sbn.packageName)
                        put("title", extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
                        put("text", extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())
                        put("time", sbn.postTime)
                        put("ongoing", sbn.isOngoing)
                        put("clearable", sbn.isClearable)
                    })
                }
            }
        })
    }
}

class DismissNotificationTool @Inject constructor() : McpTool, RequiresNotificationAccess {
    override val name = "dismiss_notification"
    override val description =
        "清除通知。传 key（read_notifications 返回的 key）清除单条；或 all=true 清除全部可清除通知。需要通知使用权"
    override val category = ToolCategory.COMMUNICATION
    override val defaultEnabled = false
    override val inputSchema: ToolSchema = inputSchema {
        string("key", "通知 key（read_notifications 返回）")
        boolean("all", "为 true 时清除全部可清除通知")
    }

    override suspend fun execute(args: JsonObject): CallToolResult {
        val service = NlService.get() ?: return noNlError()
        val key = args.str("key")
        val all = args.boolOr("all", false)
        return when {
            all -> runCatching { service.cancelAllNotifications() }
                .map { textResult("已清除全部可清除通知") }
                .getOrElse { errorResult("清除失败: ${it.message}") }
            key != null -> runCatching { service.cancelNotification(key) }
                .map { textResult("已清除通知 $key") }
                .getOrElse { errorResult("清除失败: ${it.message}") }
            else -> errorResult("请提供 key 或 all=true")
        }
    }
}

// ---------- 短信 / 通话 / 联系人 ----------

class SendSmsTool @Inject constructor(
    @ApplicationContext private val context: Context,
) : McpTool {
    override val name = "send_sms"
    override val description = "发送短信。phone 为手机号，message 为内容（超长自动分片多条发送）"
    override val category = ToolCategory.COMMUNICATION
    override val defaultEnabled = false
    override val requiredPermissions = listOf(android.Manifest.permission.SEND_SMS)
    override val inputSchema: ToolSchema = inputSchema {
        string("phone", "接收手机号", required = true)
        string("message", "短信内容", required = true)
    }

    override suspend fun execute(args: JsonObject): CallToolResult {
        val phone = args.reqStr("phone")
        val message = args.reqStr("message")
        val sms = context.getSystemService(SmsManager::class.java)
            ?: return errorResult("设备不支持短信")
        return runCatching {
            val parts = sms.divideMessage(message)
            if (parts.size > 1) {
                sms.sendMultipartTextMessage(phone, null, parts, null, null)
                textResult("已发送短信到 $phone（${parts.size} 条分片）")
            } else {
                sms.sendTextMessage(phone, null, message, null, null)
                textResult("已发送短信到 $phone")
            }
        }.getOrElse { errorResult("发送失败: ${it.message}") }
    }
}

class ListSmsTool @Inject constructor(
    @ApplicationContext private val context: Context,
) : McpTool {
    override val name = "list_sms"
    override val description = "读取收件箱短信（号码/内容/时间，按时间倒序）。limit 默认 20、上限 100"
    override val category = ToolCategory.COMMUNICATION
    override val defaultEnabled = false
    override val requiredPermissions = listOf(android.Manifest.permission.READ_SMS)
    override val inputSchema: ToolSchema = inputSchema {
        integer("limit", "返回条数，默认 20，上限 100")
    }

    override suspend fun execute(args: JsonObject): CallToolResult {
        val limit = args.intOr("limit", 20).coerceIn(1, 100)
        val cursor = context.contentResolver.query(
            Uri.parse("content://sms/inbox"),
            arrayOf("address", "body", "date"),
            null, null, "date DESC",
        ) ?: return errorResult("无法读取短信收件箱")
        val items = buildList {
            cursor.use { c ->
                while (c.moveToNext() && size < limit) {
                    add(buildJsonObject {
                        put("address", c.getString(0))
                        put("body", c.getString(1))
                        put("date", c.getLong(2))
                    })
                }
            }
        }
        return jsonResult(buildJsonObject {
            put("count", items.size)
            putJsonArray("messages") { items.forEach { add(it) } }
        })
    }
}

class ReadCallLogTool @Inject constructor(
    @ApplicationContext private val context: Context,
) : McpTool {
    override val name = "read_call_log"
    override val description =
        "读取通话记录（号码/姓名/类型/时间/时长秒，按时间倒序）。limit 默认 20、上限 100；type 取值 incoming/outgoing/missed/rejected/voicemail/blocked"
    override val category = ToolCategory.COMMUNICATION
    override val defaultEnabled = false
    override val requiredPermissions = listOf(android.Manifest.permission.READ_CALL_LOG)
    override val inputSchema: ToolSchema = inputSchema {
        integer("limit", "返回条数，默认 20，上限 100")
    }

    override suspend fun execute(args: JsonObject): CallToolResult {
        val limit = args.intOr("limit", 20).coerceIn(1, 100)
        val cursor = context.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(
                CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME,
                CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION,
            ),
            null, null, "${CallLog.Calls.DATE} DESC",
        ) ?: return errorResult("无法读取通话记录")
        val items = buildList {
            cursor.use { c ->
                while (c.moveToNext() && size < limit) {
                    add(buildJsonObject {
                        put("number", c.getString(0))
                        put("name", c.getString(1))
                        put("type", when (c.getInt(2)) {
                            CallLog.Calls.INCOMING_TYPE -> "incoming"
                            CallLog.Calls.OUTGOING_TYPE -> "outgoing"
                            CallLog.Calls.MISSED_TYPE -> "missed"
                            CallLog.Calls.REJECTED_TYPE -> "rejected"
                            CallLog.Calls.VOICEMAIL_TYPE -> "voicemail"
                            CallLog.Calls.BLOCKED_TYPE -> "blocked"
                            else -> "other"
                        })
                        put("date", c.getLong(3))
                        put("duration_s", c.getLong(4))
                    })
                }
            }
        }
        return jsonResult(buildJsonObject {
            put("count", items.size)
            putJsonArray("calls") { items.forEach { add(it) } }
        })
    }
}

class QueryContactsTool @Inject constructor(
    @ApplicationContext private val context: Context,
) : McpTool {
    override val name = "query_contacts"
    override val description = "按姓名或号码模糊搜索联系人（query 必填），返回姓名/号码，最多 50 条"
    override val category = ToolCategory.COMMUNICATION
    override val defaultEnabled = false
    override val requiredPermissions = listOf(android.Manifest.permission.READ_CONTACTS)
    override val inputSchema: ToolSchema = inputSchema {
        string("query", "姓名或号码的子串", required = true)
    }

    override suspend fun execute(args: JsonObject): CallToolResult {
        val like = "%${args.reqStr("query")}%"
        val cursor = context.contentResolver.query(
            Phone.CONTENT_URI,
            arrayOf(Phone.DISPLAY_NAME, Phone.NUMBER),
            "${Phone.DISPLAY_NAME} LIKE ? OR ${Phone.NUMBER} LIKE ?",
            arrayOf(like, like),
            "${Phone.DISPLAY_NAME} ASC",
        ) ?: return errorResult("无法查询联系人")
        val items = buildList {
            cursor.use { c ->
                while (c.moveToNext() && size < 50) {
                    add(buildJsonObject {
                        put("name", c.getString(0))
                        put("number", c.getString(1))
                    })
                }
            }
        }
        return jsonResult(buildJsonObject {
            put("count", items.size)
            putJsonArray("contacts") { items.forEach { add(it) } }
        })
    }
}

class MakeCallTool @Inject constructor(
    @ApplicationContext private val context: Context,
) : McpTool {
    override val name = "make_call"
    override val description = "直接拨打电话（不经拨号盘确认）。phone 必填"
    override val category = ToolCategory.COMMUNICATION
    override val defaultEnabled = false
    override val requiredPermissions = listOf(android.Manifest.permission.CALL_PHONE)
    override val inputSchema: ToolSchema = inputSchema {
        string("phone", "要拨打的号码", required = true)
    }

    override suspend fun execute(args: JsonObject): CallToolResult {
        val phone = args.reqStr("phone")
        if (phone.isBlank()) return errorResult("phone 不能为空")
        return runCatching {
            context.startActivity(
                Intent(Intent.ACTION_CALL, Uri.parse("tel:$phone"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            textResult("已拨打 $phone")
        }.getOrElse { errorResult("拨打失败: ${it.message}") }
    }
}

// ---------- 媒体 ----------

class MediaControlTool @Inject constructor(
    @ApplicationContext private val context: Context,
) : McpTool, RequiresNotificationAccess {
    override val name = "media_control"
    override val description =
        "控制媒体播放：play/pause/next/previous。优先作用于正在播放的媒体会话；无活跃会话时回退为系统媒体按键广播。需要通知使用权"
    override val category = ToolCategory.COMMUNICATION
    override val defaultEnabled = false
    override val inputSchema: ToolSchema = inputSchema {
        string("command", "控制命令", required = true, enum = listOf("play", "pause", "next", "previous"))
    }

    override suspend fun execute(args: JsonObject): CallToolResult {
        if (!notificationAccessGranted(context)) {
            return errorResult("未授予通知使用权，无法访问媒体会话，请在系统设置开启（可在 App「工具」页跳转）")
        }
        val command = args.reqStr("command")
        val controllers = activeMediaControllers(context)
        val target = controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: controllers.firstOrNull()
        if (target != null) {
            return runCatching {
                val controls = target.transportControls
                when (command) {
                    "play" -> controls.play()
                    "pause" -> controls.pause()
                    "next" -> controls.skipToNext()
                    "previous" -> controls.skipToPrevious()
                    else -> return errorResult("command 必须是 play/pause/next/previous")
                }
                textResult("已向 ${target.packageName} 发送 $command")
            }.getOrElse { errorResult("媒体控制失败: ${it.message}") }
        }
        // 回退：系统媒体按键（分 down/up 两次派发）
        val keyCode = when (command) {
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            else -> return errorResult("command 必须是 play/pause/next/previous")
        }
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        return textResult("无活跃媒体会话，已发送系统媒体按键 $command")
    }
}

class NowPlayingTool @Inject constructor(
    @ApplicationContext private val context: Context,
) : McpTool, RequiresNotificationAccess {
    override val name = "now_playing"
    override val description = "获取当前活跃媒体会话信息（应用/标题/艺术家/专辑/播放状态）。需要通知使用权"
    override val category = ToolCategory.READ
    override val defaultEnabled = false
    override val inputSchema: ToolSchema = inputSchema {}

    override suspend fun execute(args: JsonObject): CallToolResult {
        if (!notificationAccessGranted(context)) {
            return errorResult("未授予通知使用权，无法访问媒体会话，请在系统设置开启（可在 App「工具」页跳转）")
        }
        val controllers = activeMediaControllers(context)
        if (controllers.isEmpty()) return textResult("当前没有活跃的媒体会话")
        return jsonResult(buildJsonObject {
            putJsonArray("sessions") {
                controllers.forEach { c ->
                    val md = c.metadata
                    add(buildJsonObject {
                        put("package", c.packageName)
                        put("state", playbackStateName(c.playbackState?.state))
                        put("title", md?.getString(MediaMetadata.METADATA_KEY_TITLE))
                        put("artist", md?.getString(MediaMetadata.METADATA_KEY_ARTIST))
                        put("album", md?.getString(MediaMetadata.METADATA_KEY_ALBUM))
                    })
                }
            }
        })
    }
}
