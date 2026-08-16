package dev.androidmcp.effects

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.Choreographer
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * 屏幕边缘粒子光效：工具调用 START 事件触发爆发，从随机边的随机位置向屏内
 * 发射 40~80 个带柔和辉光的粒子（radial gradient 雪碧图，硬件加速绘制）。
 *
 * Gemini 风格：粒子主色取事件类别的 effectColor，点缀 Gemini 蓝/紫/粉；
 * 运动有方向感，ease-out 减速 + alpha 渐隐 + 尺寸弥散，生命周期约 1.2s。
 * Choreographer 驱动 60fps，无活跃粒子时停止帧回调省电；平时 alpha=0，爆发时淡入。
 */
@SuppressLint("ViewConstructor")
class EdgeParticleView(context: Context) : View(context) {

    private data class Particle(
        var x: Float,
        var y: Float,
        var vx: Float,
        var vy: Float,
        val color: Int,
        val baseRadius: Float,
        val lifeMs: Long,
        var ageMs: Long = 0L,
    )

    private data class Marker(
        val x: Float,
        val y: Float,
        var ageMs: Long = 0L,
    )

    private val particles = ArrayList<Particle>()
    private val sprites = HashMap<Int, Bitmap>()
    private val drawPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
    }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val markerRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * resources.displayMetrics.density
        color = Color.WHITE
    }
    private val dstRect = RectF()

    private var running = false
    private var lastFrameMs = -1L
    private var haloAgeMs = HALO_LIFE_MS
    private var haloCategoryColor = GEMINI_COLORS[0]
    private var marker: Marker? = null
    private val densityScale = resources.displayMetrics.density / 3f

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            val nowMs = frameTimeNanos / 1_000_000L
            step(nowMs)
            if (hasActiveEffect()) {
                Choreographer.getInstance().postFrameCallback(this)
            } else {
                // 无活跃粒子：停帧省电并整体淡出
                running = false
                lastFrameMs = -1L
                animate().alpha(0f).setDuration(FADE_OUT_MS).start()
            }
        }
    }

    init {
        alpha = 0f
    }

    /** 触发一次爆发。[categoryColor] 为事件类别的 effectColor（ARGB）。 */
    fun burst(categoryColor: Int) {
        val w = if (width > 0) width.toFloat() else resources.displayMetrics.widthPixels.toFloat()
        val h = if (height > 0) height.toFloat() else resources.displayMetrics.heightPixels.toFloat()
        val count = Random.nextInt(BURST_MIN, BURST_MAX + 1)
        haloAgeMs = 0L
        haloCategoryColor = categoryColor
        // 随机边、随机位置；粒子初速度指向屏内（带扩散角）
        val (sx, sy, baseAngle) = when (Random.nextInt(4)) {
            0 -> Triple(0f, h * Random.nextFloat(), 0f)                       // 左边 → 向右
            1 -> Triple(w, h * Random.nextFloat(), PI.toFloat())              // 右边 → 向左
            2 -> Triple(w * Random.nextFloat(), 0f, (PI / 2).toFloat())       // 上边 → 向下
            else -> Triple(w * Random.nextFloat(), h, -(PI / 2).toFloat())    // 下边 → 向上
        }
        repeat(count) {
            // 65% 类别色，35% Gemini 蓝紫粉点缀
            val color = if (Random.nextFloat() < 0.65f) categoryColor else GEMINI_COLORS.random()
            val angle = baseAngle + (Random.nextFloat() - 0.5f) * SPREAD_RAD
            val speed = (350f + Random.nextFloat() * 1150f) * densityScale
            particles += Particle(
                x = sx,
                y = sy,
                vx = cos(angle) * speed,
                vy = sin(angle) * speed,
                color = color,
                baseRadius = (8f + Random.nextFloat() * 14f) * densityScale,
                lifeMs = 900L + Random.nextLong(500L),
            )
        }
        // 并发爆发叠加时限制总量，避免绘制过载
        while (particles.size > MAX_PARTICLES) particles.removeAt(0)
        startAnimating()
    }

    /** 在 UI 工具实际操作的屏幕位置显示短暂白色圆点。 */
    fun showMarker(x: Float, y: Float) {
        val w = if (width > 0) width.toFloat() else resources.displayMetrics.widthPixels.toFloat()
        val h = if (height > 0) height.toFloat() else resources.displayMetrics.heightPixels.toFloat()
        marker = Marker(x.coerceIn(0f, w), y.coerceIn(0f, h))
        startAnimating()
    }

    private fun startAnimating() {
        animate().cancel()
        if (alpha < 1f) animate().alpha(1f).setDuration(FADE_IN_MS).start()
        if (!running) {
            running = true
            lastFrameMs = -1L
            Choreographer.getInstance().postFrameCallback(frameCallback)
        }
    }

    /** 推进粒子并在需要时重绘。 */
    private fun step(nowMs: Long) {
        val dtSec = if (lastFrameMs < 0) 0.016f else ((nowMs - lastFrameMs).coerceIn(1L, 50L)) / 1000f
        lastFrameMs = nowMs
        val damping = exp(-3.2f * dtSec) // ease-out 减速
        val dtMs = (dtSec * 1000f).toLong()
        haloAgeMs = (haloAgeMs + dtMs).coerceAtMost(HALO_LIFE_MS)
        marker?.let {
            it.ageMs += dtMs
            if (it.ageMs >= MARKER_LIFE_MS) marker = null
        }
        val it = particles.iterator()
        while (it.hasNext()) {
            val p = it.next()
            p.ageMs += dtMs
            if (p.ageMs >= p.lifeMs) {
                it.remove()
                continue
            }
            p.vx *= damping
            p.vy *= damping
            p.x += p.vx * dtSec
            p.y += p.vy * dtSec
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        drawHalo(canvas)
        for (p in particles) {
            val t = (p.ageMs.toFloat() / p.lifeMs).coerceIn(0f, 1f)
            val fade = 1f - t
            drawPaint.alpha = (fade * fade * 255).toInt() // alpha 渐隐
            val r = p.baseRadius * (0.7f + 0.6f * t)      // 尾部弥散：尺寸随时间膨胀
            dstRect.set(p.x - r, p.y - r, p.x + r, p.y + r)
            canvas.drawBitmap(sprite(p.color), null, dstRect, drawPaint)
        }
        drawMarker(canvas)
    }

    private fun drawHalo(canvas: Canvas) {
        if (haloAgeMs >= HALO_LIFE_MS) return
        val life = haloAgeMs.toFloat() / HALO_LIFE_MS
        val fade = ((1f - life) * 0.72f).coerceIn(0f, 1f)
        val horizontal = LinearGradient(
            0f,
            0f,
            width.toFloat(),
            0f,
            intArrayOf(GEMINI_COLORS[0], haloCategoryColor, GEMINI_COLORS[1], GEMINI_COLORS[2]),
            null,
            Shader.TileMode.CLAMP,
        )
        val vertical = LinearGradient(
            0f,
            0f,
            0f,
            height.toFloat(),
            intArrayOf(GEMINI_COLORS[2], GEMINI_COLORS[1], haloCategoryColor, GEMINI_COLORS[0]),
            null,
            Shader.TileMode.CLAMP,
        )
        val step = 2f * resources.displayMetrics.density
        repeat(HALO_STEPS) { index ->
            val inset = index * step
            val depth = 1f - index.toFloat() / HALO_STEPS
            haloPaint.alpha = (255f * fade * depth * depth).toInt()
            haloPaint.shader = horizontal
            canvas.drawLine(0f, inset, width.toFloat(), inset, haloPaint)
            canvas.drawLine(0f, height - inset, width.toFloat(), height - inset, haloPaint)
            haloPaint.shader = vertical
            canvas.drawLine(inset, 0f, inset, height.toFloat(), haloPaint)
            canvas.drawLine(width - inset, 0f, width - inset, height.toFloat(), haloPaint)
        }
        haloPaint.shader = null
    }

    private fun drawMarker(canvas: Canvas) {
        val current = marker ?: return
        val t = (current.ageMs.toFloat() / MARKER_LIFE_MS).coerceIn(0f, 1f)
        val fade = 1f - t
        val density = resources.displayMetrics.density
        val glowRadius = (18f + 5f * sin(t * PI).toFloat()) * density
        markerPaint.alpha = (fade * 220).toInt()
        markerPaint.shader = RadialGradient(
            current.x,
            current.y,
            glowRadius,
            intArrayOf(Color.WHITE, 0x99FFFFFF.toInt(), Color.TRANSPARENT),
            floatArrayOf(0f, 0.28f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(current.x, current.y, glowRadius, markerPaint)
        markerPaint.shader = null
        markerPaint.color = Color.WHITE
        markerPaint.alpha = (fade * 255).toInt()
        canvas.drawCircle(current.x, current.y, 4.5f * density, markerPaint)
        markerRingPaint.alpha = (fade * 210).toInt()
        canvas.drawCircle(current.x, current.y, (8f + 7f * t) * density, markerRingPaint)
    }

    /** 每色一张辉光雪碧图：白热核心 → 彩色光晕 → 透明。 */
    private fun sprite(color: Int): Bitmap = sprites.getOrPut(color) {
        val size = SPRITE_SIZE
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val r = size / 2f
        val core = Color.argb(230, 255, 255, 255)
        val mid = Color.argb(150, Color.red(color), Color.green(color), Color.blue(color))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                r, r, r,
                intArrayOf(core, mid, Color.TRANSPARENT),
                floatArrayOf(0f, 0.35f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        c.drawCircle(r, r, r, paint)
        bmp
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        running = false
        lastFrameMs = -1L
        particles.clear()
        marker = null
        haloAgeMs = HALO_LIFE_MS
        animate().cancel()
    }

    private fun hasActiveEffect(): Boolean =
        particles.isNotEmpty() || haloAgeMs < HALO_LIFE_MS || marker != null

    companion object {
        /** Gemini 蓝 / 紫 / 粉（与 ui/theme/Theme.kt 的 GeminiPalette 一致）。 */
        private val GEMINI_COLORS = intArrayOf(0xFF4796E3.toInt(), 0xFF9177C7.toInt(), 0xFFD96570.toInt())
        private const val BURST_MIN = 40
        private const val BURST_MAX = 80
        private const val MAX_PARTICLES = 400
        private const val SPRITE_SIZE = 96
        private const val FADE_IN_MS = 180L
        private const val FADE_OUT_MS = 450L
        private const val SPREAD_RAD = 1.2f
        private const val HALO_LIFE_MS = 1_500L
        private const val HALO_STEPS = 10
        private const val MARKER_LIFE_MS = 900L
    }
}
