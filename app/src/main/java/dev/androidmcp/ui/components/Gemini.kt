package dev.androidmcp.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.androidmcp.ui.theme.GeminiPalette

/** Gemini 标志渐变：蓝 → 紫 → 粉。 */
val GeminiBrush: Brush
    get() = Brush.linearGradient(
        colors = listOf(GeminiPalette.Blue, GeminiPalette.Purple, GeminiPalette.Pink),
    )

/** 呼吸感的动态 Gemini 渐变（用于"服务运行中"等活跃状态）。 */
@Composable
fun animatedGeminiBrush(durationMs: Int = 3600): Brush {
    val transition = rememberInfiniteTransition(label = "gemini")
    val shift by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMs, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "shift",
    )
    return Brush.linearGradient(
        colors = listOf(GeminiPalette.Blue, GeminiPalette.Purple, GeminiPalette.Pink),
        start = Offset(0f, 0f),
        end = Offset(600f + 800f * shift, 400f * shift + 200f),
    )
}

/** 弥散光晕圆斑：作为卡片/页面的氛围背景（柔和、虚化、 ethereal）。 */
@Composable
fun GlowOrb(
    modifier: Modifier = Modifier,
    color: Color = GeminiPalette.Purple,
    alpha: Float = 0.55f,
    blurRadius: Dp = 48.dp,
) {
    Box(
        modifier = modifier
            .blur(blurRadius)
            .drawBehind {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(color.copy(alpha = alpha), Color.Transparent),
                        center = center,
                        radius = size.minDimension / 2f,
                    ),
                )
            },
    )
}

/** 渐变描边：柔和的能量边缘。 */
fun Modifier.geminiGlowBorder(
    shape: Shape = RoundedCornerShape(28.dp),
    width: Dp = 1.5.dp,
    alpha: Float = 0.8f,
): Modifier = this.drawBehind {
    val brush = Brush.linearGradient(
        colors = listOf(
            GeminiPalette.Blue.copy(alpha = alpha),
            GeminiPalette.Purple.copy(alpha = alpha * 0.7f),
            GeminiPalette.Pink.copy(alpha = alpha * 0.4f),
        ),
    )
    drawOutline(
        outline = shape.createOutline(size, layoutDirection, this),
        brush = brush,
        style = Stroke(width = width.toPx()),
    )
}

/**
 * 静态 RGB 色散描边，按压时才短暂外扩并增强透明度。
 *
 * 内部 interactionSource 不会消费指针事件，因此可叠加在 Button、Card 等既有点击控件上。
 */
fun Modifier.geminiDispersion(
    shape: Shape = RoundedCornerShape(28.dp),
    enabled: Boolean = true,
    strength: Float = 1f,
): Modifier = composed {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressAmount by animateFloatAsState(
        targetValue = if (pressed && enabled) 1f else 0f,
        animationSpec = spring(),
        label = "geminiDispersion",
    )
    val normalizedStrength = strength.coerceIn(0f, 2f)

    this
        .pointerInput(enabled) {
            if (!enabled) return@pointerInput
            awaitPointerEventScope {
                while (true) {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val press = PressInteraction.Press(down.position)
                    interactionSource.tryEmit(press)
                    val up = waitForUpOrCancellation()
                    interactionSource.tryEmit(
                        if (up == null) PressInteraction.Cancel(press) else PressInteraction.Release(press),
                    )
                }
            }
        }
        .drawWithCache {
            val outline = shape.createOutline(size, layoutDirection, this)
            val baseOffset = 1.25.dp.toPx() * normalizedStrength
            val pressOffset = 3.5.dp.toPx() * normalizedStrength * pressAmount
            val stroke = Stroke(width = 1.dp.toPx())
            onDrawBehind {
                listOf(
                    Offset(-baseOffset - pressOffset, -baseOffset) to GeminiPalette.Blue,
                    Offset(0f, baseOffset + pressOffset) to GeminiPalette.Purple,
                    Offset(baseOffset + pressOffset, -baseOffset) to GeminiPalette.Pink,
                ).forEach { (offset, color) ->
                    withTransform({ translate(offset.x, offset.y) }) {
                        drawOutline(
                            outline = outline,
                            color = color.copy(alpha = (0.13f + 0.22f * pressAmount) * normalizedStrength),
                            style = stroke,
                        )
                    }
                }
            }
        }
}

/** Gemini 渐变按钮（主行动点）。 */
@Composable
fun GeminiButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val shape = RoundedCornerShape(24.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(if (enabled) animatedGeminiBrush() else Brush.linearGradient(listOf(Color.DarkGray, Color.DarkGray))),
    ) {
        Button(
            onClick = onClick,
            enabled = enabled,
            shape = shape,
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.Transparent,
                contentColor = Color.White,
                disabledContainerColor = Color.Transparent,
                disabledContentColor = Color(0x99FFFFFF),
            ),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
        ) {
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}
