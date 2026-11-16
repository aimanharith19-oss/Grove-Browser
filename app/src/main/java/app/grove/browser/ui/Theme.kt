package app.grove.browser.ui

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.lerp
import app.grove.browser.Prefs

/** Windows Phone accent colours. */
val ACCENTS = listOf(
    0xFFA4C400, 0xFF60A917, 0xFF008A00, 0xFF00ABA9, 0xFF1BA1E2, 0xFF0050EF, 0xFF6A00FF, 0xFFAA00FF, 0xFFF472D0, 0xFFD80073,
    0xFFA20025, 0xFFE51400, 0xFFFA6800, 0xFFF0A30A, 0xFFE3C800, 0xFF825A2C, 0xFF6D8764, 0xFF647687, 0xFF76608A, 0xFF87794E,
)

/** Windows Phone motion curve: fast start, long soft landing. Used by every Grove animation. */
val WpEase = CubicBezierEasing(0.1f, 0.9f, 0.2f, 1f)
fun <T> wp(ms: Int = 320) = tween<T>(ms, easing = WpEase)

/** Grove tint design: the accent colour glowing from the top of the screen into the background. */
fun Modifier.tintBackground(): Modifier = composed {
    val cs = MaterialTheme.colorScheme
    background(cs.background).background(Brush.verticalGradient(
        0f to lerp(cs.background, cs.primary, 0.42f), 0.28f to lerp(cs.background, cs.primary, 0.16f), 0.62f to cs.background))
}

private val square = RoundedCornerShape(0)
private fun light(size: Int) = TextStyle(fontWeight = FontWeight.Light, fontSize = size.sp)
private val wpType = Typography(
    displayLarge = light(56), headlineLarge = light(44), titleLarge = light(26),
    bodyLarge = light(18), bodyMedium = TextStyle(fontSize = 15.sp), labelSmall = TextStyle(fontSize = 11.sp),
)

@Composable
fun GroveTheme(prefs: Prefs, content: @Composable () -> Unit) {
    val dark = when (prefs.theme) { 1 -> true; 2 -> false; else -> isSystemInDarkTheme() }
    val accent = Color(ACCENTS.getOrElse(prefs.accent) { ACCENTS[2] })
    val scheme = if (dark) darkColorScheme(
        primary = accent, onPrimary = Color.White, secondary = accent,
        background = Color.Black, onBackground = Color.White, surface = Color.Black, onSurface = Color.White,
        surfaceVariant = lerp(Color(0xFF1A1A1A), accent, 0.20f), onSurfaceVariant = Color(0xFFB4B4B4),
        surfaceContainerHigh = lerp(Color(0xFF141414), accent, 0.16f), surfaceContainer = lerp(Color(0xFF141414), accent, 0.12f),
        surfaceContainerHighest = lerp(Color(0xFF1C1C1C), accent, 0.20f), surfaceContainerLow = lerp(Color(0xFF101010), accent, 0.10f),
        outline = lerp(Color(0xFF808080), accent, 0.4f),
    ) else lightColorScheme(
        primary = accent, onPrimary = Color.White, secondary = accent,
        background = Color.White, onBackground = Color.Black, surface = Color.White, onSurface = Color.Black,
        surfaceVariant = lerp(Color(0xFFE4E4E4), accent, 0.16f), onSurfaceVariant = Color(0xFF5A5A5A),
        surfaceContainerHigh = lerp(Color(0xFFF4F4F4), accent, 0.10f), surfaceContainer = lerp(Color(0xFFF4F4F4), accent, 0.08f),
        surfaceContainerHighest = lerp(Color(0xFFEDEDED), accent, 0.14f), surfaceContainerLow = lerp(Color(0xFFF8F8F8), accent, 0.06f),
        outline = lerp(Color(0xFF808080), accent, 0.4f),
    )
    val view = LocalView.current
    SideEffect {
        val w = (view.context as? Activity)?.window ?: return@SideEffect
        WindowCompat.getInsetsController(w, view).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }
    MaterialTheme(
        colorScheme = scheme,
        shapes = Shapes(extraSmall = square, small = square, medium = square, large = square, extraLarge = square),
        typography = wpType,
        content = content,
    )
}
