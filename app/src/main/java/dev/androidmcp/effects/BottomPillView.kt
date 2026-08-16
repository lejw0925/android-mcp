package dev.androidmcp.effects

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import android.view.animation.DecelerateInterpolator
import dev.androidmcp.events.ToolCallEvent
import dev.androidmcp.events.ToolCallStatus

/**
 * 底部模糊胶囊：屏幕底部居中滚动展示最近一条工具调用（图标点 + 工具名 + 耗时/状态）。
 *
 * 局部磨砂质感完全绘制在胶囊自己的小窗口内，不使用会在部分 OEM 模糊整屏的
 * FLAG_BLUR_BEHIND；
 * 1dp 描边复用 Gemini 蓝→紫→粉渐变（见 ui/components/Gemini.kt 的 geminiGlowBorder）。
 *
 * 新事件到来时整体向上滑入替换旧文本（"底部向上"入场）；3 秒无新事件自动淡出，
 * 淡出结束后通过 [onHidden] 通知宿主移除窗口。
 */
@SuppressLint("ViewConstructor")
class BottomPillView(context: Context) : View(context) {

    private val density = resources.displayMetrics.density
    private val padH = 16f * density
    private val pillHeight = 40f * density
    private val dotRadius = 4f * density
    private val dotGap = 8f * density

    private var text: CharSequence = ""
    private var dotColor = GEMINI_BLUE
    private var textColor = COLOR_ON_SURFACE

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_SURFACE_72
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density // 1dp 渐变描边
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dotGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 13f * resources.displayMetrics.scaledDensity
    }
    private val bgRect = RectF()
    private var borderShaderWidth = -1f
    private var backgroundShaderWidth = -1f

    /** 淡出结束回调（宿主据此移除窗口）。 */
    var onHidden: (() -> Unit)? = null

    private val hideRunnable = Runnable { fadeOut() }

    init {
        alpha = 0f
    }

    /** 展示一条事件：换文本 + 向上滑入；3 秒后自动淡出。 */
    fun showEvent(event: ToolCallEvent) {
        dotColor = event.category.effectColor.toInt()
        textColor = if (event.status == ToolCallStatus.ERROR) COLOR_ERROR else COLOR_ON_SURFACE
        text = when (event.status) {
            ToolCallStatus.RUNNING -> "⟡ ${event.displayName} · 来自 ${event.keyLabel}"
            ToolCallStatus.SUCCESS -> "✓ ${event.displayName} · ${event.durationMs ?: 0}ms"
            ToolCallStatus.ERROR -> "✕ ${event.displayName} · ${event.error ?: "失败"}"
        }
        removeCallbacks(hideRunnable)
        animate().cancel()
        // 底部向上滑入
        translationY = 24f * density
        animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(260L)
            .setInterpolator(DecelerateInterpolator())
            .start()
        requestLayout()
        invalidate()
        postDelayed(hideRunnable, AUTO_HIDE_MS)
    }

    private fun fadeOut() {
        animate().cancel()
        animate()
            .alpha(0f)
            .translationY(12f * density)
            .setDuration(300L)
            .withEndAction { onHidden?.invoke() }
            .start()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxWidth = (resources.displayMetrics.widthPixels - 48f * density).toInt()
        val textWidth = textPaint.measureText(text.toString())
        val contentWidth = (padH * 2 + dotRadius * 2 + dotGap + textWidth).toInt()
        setMeasuredDimension(
            resolveSize(contentWidth.coerceAtMost(maxWidth), widthMeasureSpec),
            resolveSize(pillHeight.toInt(), heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = h / 2f
        bgRect.set(0f, 0f, w, h)
        if (backgroundShaderWidth != w) {
            backgroundShaderWidth = w
            bgPaint.shader = LinearGradient(
                0f,
                0f,
                w,
                h,
                intArrayOf(COLOR_SURFACE_TOP, COLOR_SURFACE_72, COLOR_SURFACE_BOTTOM),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRoundRect(bgRect, r, r, bgPaint)
        // Gemini 渐变描边：蓝 → 紫 → 粉（alpha 递减）
        if (borderShaderWidth != w) {
            borderShaderWidth = w
            borderPaint.shader = LinearGradient(
                0f, 0f, w, 0f,
                intArrayOf(
                    withAlpha(GEMINI_BLUE, 0.8f),
                    withAlpha(GEMINI_PURPLE, 0.56f),
                    withAlpha(GEMINI_PINK, 0.32f),
                ),
                null,
                Shader.TileMode.CLAMP,
            )
        }
        bgRect.inset(borderPaint.strokeWidth / 2f, borderPaint.strokeWidth / 2f)
        canvas.drawRoundRect(bgRect, r, r, borderPaint)
        // 类别色图标点（带一点光晕）
        val dotCx = padH + dotRadius
        val dotCy = h / 2f
        dotGlowPaint.color = withAlpha(dotColor, 0.35f)
        canvas.drawCircle(dotCx, dotCy, dotRadius * 2.2f, dotGlowPaint)
        dotPaint.color = dotColor
        canvas.drawCircle(dotCx, dotCy, dotRadius, dotPaint)
        // 文本（垂直居中，超长省略）
        val textX = padH + dotRadius * 2 + dotGap
        val avail = w - textX - padH
        val shown = TextUtils.ellipsize(text, textPaint, avail, TextUtils.TruncateAt.END)
        textPaint.color = textColor
        val baseline = h / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(shown, 0, shown.length, textX, baseline, textPaint)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(hideRunnable)
        animate().cancel()
    }

    private fun withAlpha(color: Int, alpha: Float): Int =
        Color.argb((alpha * 255).toInt(), Color.red(color), Color.green(color), Color.blue(color))

    companion object {
        /** Gemini 蓝 / 紫 / 粉（与 ui/theme/Theme.kt 的 GeminiPalette 一致）。 */
        private val GEMINI_BLUE = 0xFF4796E3.toInt()
        private val GEMINI_PURPLE = 0xFF9177C7.toInt()
        private val GEMINI_PINK = 0xFFD96570.toInt()

        private val COLOR_ON_SURFACE = 0xFFE4E8F5.toInt()
        private val COLOR_ERROR = 0xFFF28B82.toInt()
        private val COLOR_SURFACE_72 = 0xB8131829.toInt()
        private val COLOR_SURFACE_TOP = 0xD8273045.toInt()
        private val COLOR_SURFACE_BOTTOM = 0xC00B1020.toInt()
        private const val AUTO_HIDE_MS = 3000L
    }
}
