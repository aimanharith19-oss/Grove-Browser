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
import androidx.compose.ui.unit.dp
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

/** Accent colours: Edge blue first, then the Windows Phone ones. */
val ACCENTS = listOf(
    0xFF0078D4,
    0xFFA4C400, 0xFF60A917, 0xFF008A00, 0xFF00ABA9, 0xFF1BA1E2, 0xFF0050EF, 0xFF6A00FF, 0xFFAA00FF, 0xFFF472D0, 0xFFD80073,
    0xFFA20025, 0xFFE51400, 0xFFFA6800, 0xFFF0A30A, 0xFFE3C800, 0xFF825A2C, 0xFF6D8764, 0xFF647687, 0xFF76608A, 0xFF87794E,
)

/** Windows Phone motion curve: fast start, long soft landing. Used by every Grove animation. */
val WpEase = CubicBezierEasing(0.1f, 0.9f, 0.2f, 1f)
fun <T> wp(ms: Int = 320) = tween<T>(ms, easing = WpEase)

/** Edge look: plain background for Grove's screens. */
fun Modifier.tintBackground(): Modifier = composed { background(MaterialTheme.colorScheme.background) }

/** Edge (Fluent) shapes and type: rounded corners, regular weight. */
private fun r(dp: Int) = RoundedCornerShape(dp.dp)
private val edgeType = Typography(
    displayLarge = TextStyle(fontSize = 40.sp), headlineLarge = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.SemiBold), titleLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp), bodyMedium = TextStyle(fontSize = 14.sp), labelSmall = TextStyle(fontSize = 11.sp),
)

@Composable
fun GroveTheme(prefs: Prefs, content: @Composable () -> Unit) {
    val dark = when (prefs.theme) { 1 -> true; 2 -> false; else -> isSystemInDarkTheme() }
    val accent = Color(ACCENTS.getOrElse(prefs.accent) { ACCENTS[2] })
    // Edge's neutral greys: the bars are a light grey (dark grey at night), the address bar a slightly darker pill.
    val scheme = if (dark) darkColorScheme(
        primary = accent, onPrimary = Color.White, secondary = accent, primaryContainer = lerp(Color(0xFF292929), accent, 0.35f),
        background = Color(0xFF1F1F1F), onBackground = Color.White, surface = Color(0xFF2B2B2B), onSurface = Color.White,
        surfaceVariant = Color(0xFF3B3B3B), onSurfaceVariant = Color(0xFFC8C8C8),
        surfaceContainerHigh = Color(0xFF333333), surfaceContainer = Color(0xFF2B2B2B),
        surfaceContainerHighest = Color(0xFF3B3B3B), surfaceContainerLow = Color(0xFF242424),
        outline = Color(0xFF6B6B6B),
    ) else lightColorScheme(
        primary = accent, onPrimary = Color.White, secondary = accent, primaryContainer = lerp(Color.White, accent, 0.14f),
        background = Color.White, onBackground = Color(0xFF1B1B1B), surface = Color(0xFFF7F7F7), onSurface = Color(0xFF1B1B1B),
        surfaceVariant = Color(0xFFEBEBEB), onSurfaceVariant = Color(0xFF616161),
        surfaceContainerHigh = Color(0xFFF0F0F0), surfaceContainer = Color(0xFFF7F7F7),
        surfaceContainerHighest = Color(0xFFE6E6E6), surfaceContainerLow = Color(0xFFFAFAFA),
        outline = Color(0xFFB3B3B3),
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
        shapes = Shapes(extraSmall = r(4), small = r(8), medium = r(12), large = r(16), extraLarge = r(24)),
        typography = edgeType,
        content = content,
    )
}
