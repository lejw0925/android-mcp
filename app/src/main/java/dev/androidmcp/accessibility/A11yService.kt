package dev.androidmcp.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import dev.androidmcp.events.UiPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import java.util.concurrent.atomic.AtomicReference

/**
 * 无障碍引擎：UI 树序列化、节点查找、手势注入、文本输入、滚动、全局动作、截图、
 * 文本等待与前台应用探测。
 *
 * 约定：
 * - 所有异步系统回调（手势/截图）统一用 suspendCancellableCoroutine 包装成挂起 API，并带超时保护；
 * - 节点读取操作线程安全，可直接在 IO 线程调用（调用方 ToolRegistry 已切到 Dispatchers.IO）；
 * - AccessibilityNodeInfo 一律不手动 recycle（API 34+ 已废弃 recycle，交给系统管理，避免崩溃）。
 */
class A11yService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    // ---------- UI 树序列化 ----------

    /**
     * 序列化当前活跃窗口的 UI 树为紧凑 JSON。
     * 节点字段：t=文本 d=内容描述 id=资源ID短名 c=类名短名 b=边界"l,t,r,b"
     *          f=标志字母串(k可点击 l可长按 s可滚动 e可编辑 f可聚焦 c已勾选 n可用) children=子节点数组。
     * 节点总数超过 [MAX_NODES] 时截断并在根节点加 truncated=true。
     * 无活跃窗口时抛出 IllegalStateException。
     */
    fun dumpUiTree(maxDepth: Int): JsonObject {
        val root = rootInActiveWindow ?: throw IllegalStateException("当前无活跃窗口")
        val counter = intArrayOf(0)
        var truncated = false

        fun serialize(node: AccessibilityNodeInfo, depth: Int): JsonObject? {
            if (counter[0] >= MAX_NODES) {
                truncated = true
                return null
            }
            counter[0]++
            return buildJsonObject {
                node.text?.toString()?.takeIf { it.isNotEmpty() }?.let { put("t", it) }
                node.contentDescription?.toString()?.takeIf { it.isNotEmpty() }?.let { put("d", it) }
                shortId(node.viewIdResourceName)?.let { put("id", it) }
                node.className?.toString()?.substringAfterLast('.')?.let { put("c", it) }
                val r = Rect()
                node.getBoundsInScreen(r)
                put("b", "${r.left},${r.top},${r.right},${r.bottom}")
                flags(node).takeIf { it.isNotEmpty() }?.let { put("f", it) }
                if (depth < maxDepth && node.childCount > 0) {
                    putJsonArray("children") {
                        for (i in 0 until node.childCount) {
                            if (truncated) break
                            val child = node.getChild(i) ?: continue
                            serialize(child, depth + 1)?.let { add(it) }
                        }
                    }
                }
            }
        }

        val tree = serialize(root, 0) ?: buildJsonObject {}
        return if (truncated) JsonObject(tree + ("truncated" to JsonPrimitive(true))) else tree
    }

    /** 单个节点的摘要 JSON（供 find_element 使用，不含 children）。 */
    fun nodeSummary(node: AccessibilityNodeInfo): JsonObject = buildJsonObject {
        put("text", node.text?.toString())
        put("desc", node.contentDescription?.toString())
        put("id", shortId(node.viewIdResourceName))
        put("class", node.className?.toString()?.substringAfterLast('.'))
        val r = Rect()
        node.getBoundsInScreen(r)
        put("b", "${r.left},${r.top},${r.right},${r.bottom}")
        put("f", flags(node))
    }

    /** 资源 ID 短名：去掉 "包名:id/" 前缀，如 com.x:id/login_btn -> login_btn。 */
    private fun shortId(fullId: CharSequence?): String? =
        fullId?.toString()?.substringAfterLast('/')?.takeIf { it.isNotEmpty() }

    /** 节点标志字母串：k=clickable l=longClickable s=scrollable e=editable f=focusable c=checked n=enabled。 */
    private fun flags(node: AccessibilityNodeInfo): String = buildString {
        if (node.isClickable) append('k')
        if (node.isLongClickable) append('l')
        if (node.isScrollable) append('s')
        if (node.isEditable) append('e')
        if (node.isFocusable) append('f')
        if (node.isChecked) append('c')
        if (node.isEnabled) append('n')
    }

    // ---------- 节点查找 ----------

    /**
     * BFS 遍历活跃窗口，返回同时满足所有非空条件的节点（条件为与关系）。
     * text 精确匹配；textContains/desc 为包含匹配；id 匹配资源 ID 短名或全名。
     */
    fun findNodes(
        text: String? = null,
        textContains: String? = null,
        id: String? = null,
        desc: String? = null,
    ): List<AccessibilityNodeInfo> {
        val root = rootInActiveWindow ?: return emptyList()
        val result = ArrayList<AccessibilityNodeInfo>()
        val queue = java.util.ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var scanned = 0
        while (queue.isNotEmpty() && result.size < MAX_FIND_RESULTS && scanned < MAX_SCAN_NODES) {
            val node = queue.removeFirst()
            scanned++
            if (matches(node, text, textContains, id, desc)) result.add(node)
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::addLast)
        }
        return result
    }

    private fun matches(
        node: AccessibilityNodeInfo,
        text: String?,
        textContains: String?,
        id: String?,
        desc: String?,
    ): Boolean {
        if (text != null && node.text?.toString() != text) return false
        if (textContains != null && node.text?.contains(textContains) != true) return false
        if (id != null && shortId(node.viewIdResourceName) != id && node.viewIdResourceName?.toString() != id) {
            return false
        }
        if (desc != null && node.contentDescription?.contains(desc) != true) return false
        return true
    }

    // ---------- 手势 ----------

    /** 短按点击 (x, y)。失败返回 false，不抛异常。 */
    suspend fun tap(x: Float, y: Float): Boolean {
        markInteraction(x, y)
        return performStroke(Path().apply { moveTo(x, y); lineTo(x, y) }, TAP_DURATION_MS)
    }

    /** 长按 (x, y)。失败返回 false，不抛异常。 */
    suspend fun longPress(x: Float, y: Float): Boolean {
        markInteraction(x, y)
        return performStroke(Path().apply { moveTo(x, y); lineTo(x, y) }, LONG_PRESS_DURATION_MS)
    }

    /** 从 (x1,y1) 滑动到 (x2,y2)。失败返回 false，不抛异常。 */
    suspend fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean {
        markInteraction(x1, y1)
        return performStroke(
            Path().apply { moveTo(x1, y1); lineTo(x2, y2) },
            durationMs.coerceIn(50, MAX_STROKE_DURATION_MS),
        )
    }

    /**
     * 多笔画同时手势（每个笔画为途经点列表，至少 2 个点）。失败返回 false，不抛异常。
     */
    suspend fun gesture(strokes: List<List<Pair<Float, Float>>>, durationMs: Long): Boolean {
        val valid = strokes.filter { it.size >= 2 }
        if (valid.isEmpty()) return false
        valid.first().firstOrNull()?.let { (x, y) -> markInteraction(x, y) }
        val duration = durationMs.coerceIn(50, MAX_STROKE_DURATION_MS)
        val builder = GestureDescription.Builder()
        valid.forEach { points ->
            val path = Path()
            points.forEachIndexed { i, (px, py) -> if (i == 0) path.moveTo(px, py) else path.lineTo(px, py) }
            builder.addStroke(GestureDescription.StrokeDescription(path, 0, duration))
        }
        return performGesture(builder.build(), duration + GESTURE_CALLBACK_SLACK_MS)
    }

    private suspend fun performStroke(path: Path, durationMs: Long): Boolean =
        performGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
                .build(),
            durationMs + GESTURE_CALLBACK_SLACK_MS,
        )

    /** dispatchGesture 回调转挂起结果；超时或取消均返回 false。 */
    private suspend fun performGesture(desc: GestureDescription, timeoutMs: Long): Boolean =
        withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<Boolean> { cont ->
                val accepted = dispatchGesture(desc, object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        cont.resume(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        cont.resume(false)
                    }
                }, Handler(Looper.getMainLooper()))
                if (!accepted) cont.resume(false)
            }
        } ?: false

    // ---------- 点击 / 输入 / 滚动 ----------

    /**
     * 按选择器点击节点：可点击则 performAction(ACTION_CLICK)，否则点击其边界中心。
     * 返回 null 表示未找到节点；true/false 为点击结果。
     */
    suspend fun clickNode(
        text: String? = null,
        textContains: String? = null,
        id: String? = null,
        desc: String? = null,
    ): Boolean? {
        val node = findNodes(text, textContains, id, desc).firstOrNull() ?: return null
        nodeCenter(node)?.let { (x, y) -> markInteraction(x, y) }
        if (node.isClickable) return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        val r = Rect()
        node.getBoundsInScreen(r)
        if (r.isEmpty) return false
        return tap(r.exactCenterX(), r.exactCenterY())
    }

    /**
     * 向输入框写入文本。目标：优先选择器命中的 editable 节点，其次当前输入焦点的 editable 节点。
     * clear=true 时先置空再写入。找不到目标或写入失败返回 false。
     */
    suspend fun inputText(
        text: String,
        clear: Boolean,
        textContains: String? = null,
        id: String? = null,
    ): Boolean {
        var target: AccessibilityNodeInfo? = null
        if (textContains != null || id != null) {
            val candidates = findNodes(textContains = textContains, id = id)
            target = candidates.firstOrNull { it.isEditable } ?: candidates.firstOrNull()
        }
        if (target == null) {
            val focused = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (focused != null && focused.isEditable) target = focused
        }
        if (target == null) return false
        nodeCenter(target)?.let { (x, y) -> markInteraction(x, y) }
        target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        if (clear && !setNodeText(target, "")) return false
        return setNodeText(target, text)
    }

    private fun setNodeText(node: AccessibilityNodeInfo, value: String): Boolean {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    /**
     * 滚动。优先对选择器命中节点（或其可滚动祖先）/首个可滚动节点执行
     * ACTION_SCROLL_FORWARD/BACKWARD；失败时用全屏滑动手势兜底。
     */
    suspend fun scrollNode(forward: Boolean, textContains: String? = null, id: String? = null): Boolean {
        var target: AccessibilityNodeInfo? = null
        if (textContains != null || id != null) {
            // 命中节点不可滚动时，向上找最近的可滚动祖先（最多 6 层）
            var cur = findNodes(textContains = textContains, id = id).firstOrNull()
            var steps = 0
            while (cur != null && !cur.isScrollable && steps < 6) {
                cur = cur.parent
                steps++
            }
            target = cur?.takeIf { it.isScrollable }
        }
        if (target == null) target = firstScrollable()
        val action =
            if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        if (target != null) {
            nodeCenter(target)?.let { (x, y) -> markInteraction(x, y) }
            if (target.performAction(action)) return true
        }
        // 手势兜底：全屏竖向滑动
        val (w, h) = screenSize()
        val cx = w / 2f
        return if (forward) {
            swipe(cx, h * 0.75f, cx, h * 0.25f, SCROLL_FALLBACK_MS)
        } else {
            swipe(cx, h * 0.25f, cx, h * 0.75f, SCROLL_FALLBACK_MS)
        }
    }

    private fun firstScrollable(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val queue = java.util.ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var scanned = 0
        while (queue.isNotEmpty() && scanned < MAX_SCAN_NODES) {
            val node = queue.removeFirst()
            scanned++
            if (node.isScrollable) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::addLast)
        }
        return null
    }

    private fun nodeCenter(node: AccessibilityNodeInfo): Pair<Float, Float>? {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        return if (bounds.isEmpty) null else bounds.exactCenterX() to bounds.exactCenterY()
    }

    fun rememberNodeInteraction(node: AccessibilityNodeInfo) {
        nodeCenter(node)?.let { (x, y) -> markInteraction(x, y) }
    }

    private fun markInteraction(x: Float, y: Float) {
        lastInteractionPoint.set(UiPoint(x, y))
    }

    // ---------- 全局动作 ----------

    /** 执行全局动作，返回是否成功；不支持的动作返回 false。 */
    fun global(action: String): Boolean {
        val code = when (action) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> GLOBAL_ACTION_QUICK_SETTINGS
            "power_dialog" -> GLOBAL_ACTION_POWER_DIALOG
            "lock_screen" -> GLOBAL_ACTION_LOCK_SCREEN
            "split_screen" -> GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN
            "screenshot" -> GLOBAL_ACTION_TAKE_SCREENSHOT
            else -> return false
        }
        return performGlobalAction(code)
    }

    // ---------- 截图 ----------

    /**
     * 截取屏幕并按 scale 缩放后返回 Bitmap（10 秒超时保护）。
     * quality 不参与位图生成，仅供调用方做 JPEG 压缩，签名保留以保持调用一致。
     */
    suspend fun screenshot(scale: Float, quality: Int): Bitmap =
        withTimeoutOrNull(SCREENSHOT_TIMEOUT_MS) {
            suspendCancellableCoroutine<Bitmap> { cont ->
                takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        runCatching {
                            val hardware = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                            result.hardwareBuffer.close()
                            val software = hardware?.copy(Bitmap.Config.ARGB_8888, false)
                                ?: throw IllegalStateException("截图结果为空")
                            val s = scale.coerceIn(0.05f, 1f)
                            if (s < 1f) {
                                Bitmap.createScaledBitmap(
                                    software,
                                    (software.width * s).toInt().coerceAtLeast(1),
                                    (software.height * s).toInt().coerceAtLeast(1),
                                    true,
                                )
                            } else {
                                software
                            }
                        }.fold({ cont.resume(it) }, { cont.resumeWithException(it) })
                    }

                    override fun onFailure(errorCode: Int) {
                        cont.resumeWithException(IllegalStateException("截图失败，错误码: $errorCode"))
                    }
                })
            }
        } ?: throw IllegalStateException("截图超时（10s）")

    // ---------- 等待与探测 ----------

    /** 每 300ms 轮询一次：appear=true 等待文本出现，false 等待文本消失；超时返回 false。 */
    suspend fun waitFor(text: String, appear: Boolean, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val found = findNodes(textContains = text).isNotEmpty()
            if (found == appear) return true
            if (System.currentTimeMillis() >= deadline) return false
            delay(300)
        }
    }

    /** 当前屏幕尺寸（宽, 高）像素。 */
    fun screenSize(): Pair<Int, Int> =
        resources.displayMetrics.let { it.widthPixels to it.heightPixels }

    /** 前台应用包名 + 窗口标题；无活跃窗口时均为 null。 */
    fun currentApp(): Pair<String?, String?> {
        val root = rootInActiveWindow ?: return (null to null)
        return root.packageName?.toString() to root.window?.title?.toString()
    }

    companion object {
        /** UI 树序列化的节点总数上限。 */
        private const val MAX_NODES = 800

        /** findNodes 单次最多返回的匹配数。 */
        private const val MAX_FIND_RESULTS = 50

        /** BFS 单次最多扫描的节点数（防失控）。 */
        private const val MAX_SCAN_NODES = 2000

        private const val TAP_DURATION_MS = 80L
        private const val LONG_PRESS_DURATION_MS = 1000L
        private const val SCROLL_FALLBACK_MS = 400L
        private const val GESTURE_CALLBACK_SLACK_MS = 2500L

        /** GestureDescription 单笔最大时长（系统上限 60s，留余量）。 */
        private const val MAX_STROKE_DURATION_MS = 59_000L
        private const val SCREENSHOT_TIMEOUT_MS = 10_000L

        @Volatile
        private var instance: A11yService? = null

        fun isRunning(): Boolean = instance != null

        /** 系统是否已启用本服务；不依赖进程内服务实例是否已完成连接。 */
        fun isEnabled(context: Context): Boolean {
            val component = ComponentName(context, A11yService::class.java).flattenToString()
            val manager = context.getSystemService(AccessibilityManager::class.java)
            return manager
                .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .any { it.id == component }
        }

        /** Android 13+ 的侧载 APK 需要先解除系统的受限设置门槛。 */
        @Suppress("DEPRECATION")
        fun shouldShowRestrictedSettingsGuide(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false

            val packageSource = runCatching {
                context.packageManager
                    .getInstallSourceInfo(context.packageName)
                    .packageSource
            }.getOrDefault(PackageInstaller.PACKAGE_SOURCE_UNSPECIFIED)

            val restrictedSettingsAllowed = runCatching {
                context.getSystemService(AppOpsManager::class.java).unsafeCheckOpNoThrow(
                    ACCESS_RESTRICTED_SETTINGS_OP,
                    context.applicationInfo.uid,
                    context.packageName,
                ) == AppOpsManager.MODE_ALLOWED
            }.getOrDefault(false)

            return shouldShowRestrictedSettingsGuide(
                isAndroid13OrNewer = true,
                installedFromStore = packageSource == PackageInstaller.PACKAGE_SOURCE_STORE,
                restrictedSettingsAllowed = restrictedSettingsAllowed,
            )
        }

        fun accessibilitySettingsIntent(): Intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)

        fun appManagementIntent(context: Context): Intent =
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))

        /** 获取当前服务实例，未连接返回 null。 */
        fun get(): A11yService? = instance

        private val lastInteractionPoint = AtomicReference<UiPoint?>(null)

        fun clearInteractionPoint() {
            lastInteractionPoint.set(null)
        }

        fun consumeInteractionPoint(): UiPoint? = lastInteractionPoint.getAndSet(null)

        private const val ACCESS_RESTRICTED_SETTINGS_OP =
            "android:access_restricted_settings"

    }
}

internal fun shouldShowRestrictedSettingsGuide(
    isAndroid13OrNewer: Boolean,
    installedFromStore: Boolean,
    restrictedSettingsAllowed: Boolean = false,
): Boolean = isAndroid13OrNewer && !installedFromStore && !restrictedSettingsAllowed
