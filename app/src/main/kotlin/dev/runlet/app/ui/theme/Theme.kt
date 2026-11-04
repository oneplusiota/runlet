package dev.runlet.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Colours Material's scheme has no slot for: one accent per kind of step, status colours and code colours. */
@Immutable
class RunletExtras(
    val stage: Color,
    val wait: Color,
    val sound: Color,
    val haptic: Color,
    val message: Color,
    val volume: Color,
    val note: Color,
    val repeat: Color,
    val success: Color,
    val warning: Color,
    val codeKeyword: Color,
    val codeString: Color,
    val codeNumber: Color,
    val codeComment: Color,
    val codeBrace: Color,
    val ringTrack: Color,
)

private val DarkExtras = RunletExtras(
    stage = Color(0xFFA9ED91), wait = Color(0xFF8FB4C9), sound = Color(0xFFF2C46D), haptic = Color(0xFFC3A6F0),
    message = Color(0xFF7FD6C8), volume = Color(0xFFF0A3B8), note = Color(0xFF8C9A8A), repeat = Color(0xFFE6B07A),
    success = Color(0xFFA9ED91), warning = Color(0xFFF4BA88),
    codeKeyword = Color(0xFFA9ED91), codeString = Color(0xFFF2C46D), codeNumber = Color(0xFF8FB4C9),
    codeComment = Color(0xFF7C8A7A), codeBrace = Color(0xFFE6B07A), ringTrack = Color(0xFF263028),
)

private val LightExtras = RunletExtras(
    stage = Color(0xFF2F6B22), wait = Color(0xFF2F6F8F), sound = Color(0xFF9A6A00), haptic = Color(0xFF6B47B8),
    message = Color(0xFF0E7C70), volume = Color(0xFFB03A62), note = Color(0xFF66745F), repeat = Color(0xFFA85A12),
    success = Color(0xFF2F6B22), warning = Color(0xFF8A4B14),
    codeKeyword = Color(0xFF2F6B22), codeString = Color(0xFF9A6A00), codeNumber = Color(0xFF2F6F8F),
    codeComment = Color(0xFF7C887A), codeBrace = Color(0xFFA85A12), ringTrack = Color(0xFFDAE2D4),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFA9ED91), onPrimary = Color(0xFF0B2A04),
    primaryContainer = Color(0xFF23401A), onPrimaryContainer = Color(0xFFC9F7B6),
    secondary = Color(0xFFB9CCAF), onSecondary = Color(0xFF243320),
    secondaryContainer = Color(0xFF2C3A28), onSecondaryContainer = Color(0xFFD5E8CA),
    tertiary = Color(0xFFF4BA88), onTertiary = Color(0xFF462200),
    background = Color(0xFF0E120F), onBackground = Color(0xFFEEF2E9),
    surface = Color(0xFF0E120F), onSurface = Color(0xFFEEF2E9),
    surfaceVariant = Color(0xFF1E2720), onSurfaceVariant = Color(0xFFA3B0A0),
    surfaceContainerLowest = Color(0xFF0A0D0B), surfaceContainerLow = Color(0xFF131913),
    surfaceContainer = Color(0xFF171E18), surfaceContainerHigh = Color(0xFF1E2720),
    surfaceContainerHighest = Color(0xFF263028),
    outline = Color(0xFF5A6A5C), outlineVariant = Color(0xFF2C382E),
    error = Color(0xFFFFB4AB), onError = Color(0xFF561E18),
    errorContainer = Color(0xFF5C1A12), onErrorContainer = Color(0xFFFFDAD5),
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF2F6B22), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFC9F0B8), onPrimaryContainer = Color(0xFF0C2A05),
    secondary = Color(0xFF55624F), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD8E6CF), onSecondaryContainer = Color(0xFF131F10),
    tertiary = Color(0xFF8A4B14), onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFF5F8F1), onBackground = Color(0xFF171D16),
    surface = Color(0xFFF5F8F1), onSurface = Color(0xFF171D16),
    surfaceVariant = Color(0xFFE2E8DC), onSurfaceVariant = Color(0xFF4A5748),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFEFF3EA),
    surfaceContainer = Color(0xFFE9EEE3), surfaceContainerHigh = Color(0xFFE2E8DC),
    surfaceContainerHighest = Color(0xFFDAE2D4),
    outline = Color(0xFF768473), outlineVariant = Color(0xFFC4CEBF),
    error = Color(0xFFBA1A1A), onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002),
)

private val Base = Typography()

private val RunletTypography = Typography(
    headlineLarge = Base.headlineLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    headlineMedium = Base.headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.25).sp),
    headlineSmall = Base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = Base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = Base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    titleSmall = Base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    labelLarge = Base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    labelMedium = Base.labelMedium.copy(fontWeight = FontWeight.SemiBold),
    labelSmall = Base.labelSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp),
)

private val RunletShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** Text styles that are specific to Runlet (tabular digits keep a ticking timer from jittering). */
object RunletText {
    val timerHuge = TextStyle(fontSize = 60.sp, fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum", letterSpacing = (-1).sp)
    val timerMedium = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum")
    val timerSmall = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum")
    val code = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 21.sp)
}

/** Layout rhythm. Everything snaps to a 4 dp grid. */
object Spacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 20.dp
    val xxl = 24.dp
}

private val LocalExtras = staticCompositionLocalOf { DarkExtras }

object Runlet {
    val extras: RunletExtras
        @Composable @ReadOnlyComposable get() = LocalExtras.current
}

@Composable
fun RunletTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val scheme: ColorScheme = if (dark) DarkScheme else LightScheme
    CompositionLocalProvider(LocalExtras provides if (dark) DarkExtras else LightExtras) {
        MaterialTheme(colorScheme = scheme, typography = RunletTypography, shapes = RunletShapes, content = content)
    }
}
