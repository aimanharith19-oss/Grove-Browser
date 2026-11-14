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
import app.grove.browser.Prefs

/** Windows Phone accent colours. */
val ACCENTS = listOf(
    0xFFA4C400, 0xFF60A917, 0xFF008A00, 0xFF00ABA9, 0xFF1BA1E2, 0xFF0050EF, 0xFF6A00FF, 0xFFAA00FF, 0xFFF472D0, 0xFFD80073,
    0xFFA20025, 0xFFE51400, 0xFFFA6800, 0xFFF0A30A, 0xFFE3C800, 0xFF825A2C, 0xFF6D8764, 0xFF647687, 0xFF76608A, 0xFF87794E,
)

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
        surfaceVariant = Color(0xFF1F1F1F), onSurfaceVariant = Color(0xFFA0A0A0), surfaceContainerHigh = Color(0xFF1F1F1F),
    ) else lightColorScheme(
        primary = accent, onPrimary = Color.White, secondary = accent,
        background = Color.White, onBackground = Color.Black, surface = Color.White, onSurface = Color.Black,
        surfaceVariant = Color(0xFFDDDDDD), onSurfaceVariant = Color(0xFF666666), surfaceContainerHigh = Color(0xFFF2F2F2),
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
