package dev.androidmcp.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Gemini 视觉体系：深邃底色 + 蓝→紫→粉标志渐变 + 圆润形状。 */
object GeminiPalette {
    val Blue = Color(0xFF4796E3)
    val Purple = Color(0xFF9177C7)
    val Pink = Color(0xFFD96570)

    val Background = Color(0xFF070B14)
    val Surface = Color(0xFF0D1120)
    val SurfaceContainer = Color(0xFF131829)
    val SurfaceBright = Color(0xFF1A2136)
    val OnSurface = Color(0xFFE4E8F5)
    val OnSurfaceVariant = Color(0xFFA7AFCB)
    val Outline = Color(0xFF3A4360)

    val Primary = Color(0xFF9EC1FA)
    val OnPrimary = Color(0xFF0A1939)
    val Secondary = Color(0xFFCBB8F0)
    val OnSecondary = Color(0xFF241A3E)
    val Tertiary = Color(0xFFF2B8C6)
    val OnTertiary = Color(0xFF3D1220)
}

private val GeminiDarkScheme = darkColorScheme(
    primary = GeminiPalette.Primary,
    onPrimary = GeminiPalette.OnPrimary,
    primaryContainer = Color(0xFF274376),
    onPrimaryContainer = Color(0xFFD7E4FF),
    secondary = GeminiPalette.Secondary,
    onSecondary = GeminiPalette.OnSecondary,
    secondaryContainer = Color(0xFF3B2F5C),
    onSecondaryContainer = Color(0xFFEADFFF),
    tertiary = GeminiPalette.Tertiary,
    onTertiary = GeminiPalette.OnTertiary,
    tertiaryContainer = Color(0xFF5C2A3B),
    onTertiaryContainer = Color(0xFFFFD9E2),
    background = GeminiPalette.Background,
    onBackground = GeminiPalette.OnSurface,
    surface = GeminiPalette.Surface,
    onSurface = GeminiPalette.OnSurface,
    surfaceVariant = GeminiPalette.SurfaceContainer,
    onSurfaceVariant = GeminiPalette.OnSurfaceVariant,
    surfaceContainer = GeminiPalette.SurfaceContainer,
    surfaceContainerHigh = GeminiPalette.SurfaceBright,
    outline = GeminiPalette.Outline,
    outlineVariant = Color(0xFF2A3150),
)

private val GeminiShapes = Shapes(
    small = RoundedCornerShape(16.dp),
    medium = RoundedCornerShape(24.dp),
    large = RoundedCornerShape(32.dp),
    extraLarge = RoundedCornerShape(40.dp),
)

/** Gemini 风格深色主题（固定深色，符合"深夜极客控制台"气质）。 */
@Composable
fun AndroidMcpTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = GeminiDarkScheme,
        shapes = GeminiShapes,
        content = content,
    )
}
