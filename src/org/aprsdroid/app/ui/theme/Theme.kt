package org.aprsdroid.app.ui.theme

import android.app.Activity
import android.preference.PreferenceManager
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val NightScheme = darkColorScheme(
    primary = NightAmber,
    onPrimary = BrandNavyDark,
    primaryContainer = Color(0xFF4A4210),
    onPrimaryContainer = Color(0xFFF6E88A),
    secondary = Color(0xFF9DB4D6),
    onSecondary = BrandNavyDark,
    secondaryContainer = NightRaised,
    onSecondaryContainer = NightText,
    tertiary = Color(0xFF7FD68A),
    onTertiary = Color(0xFF06270F),
    background = NightBackground,
    onBackground = NightText,
    surface = NightBackground,
    onSurface = NightText,
    surfaceVariant = NightRaised,
    onSurfaceVariant = NightMuted,
    surfaceContainerLowest = NightBackground,
    surfaceContainerLow = NightSurface,
    surfaceContainer = NightSurface,
    surfaceContainerHigh = NightRaised,
    surfaceContainerHighest = NightRaised,
    outline = Color(0xFF55708F),
    outlineVariant = NightLine,
    error = Color(0xFFFF9A8F),
    onError = Color(0xFF3B0A06),
)

private val DayScheme = lightColorScheme(
    primary = BrandNavy,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E0F0),
    onPrimaryContainer = BrandNavyDark,
    secondary = BrandSteel,
    onSecondary = Color.White,
    secondaryContainer = DayRaised,
    onSecondaryContainer = DayText,
    tertiary = Color(0xFF1F7A33),
    onTertiary = Color.White,
    background = DayBackground,
    onBackground = DayText,
    surface = DayBackground,
    onSurface = DayText,
    surfaceVariant = DayRaised,
    onSurfaceVariant = DayMuted,
    surfaceContainerLowest = DaySurface,
    surfaceContainerLow = DaySurface,
    surfaceContainer = DaySurface,
    surfaceContainerHigh = DayRaised,
    surfaceContainerHighest = DayRaised,
    outline = Color(0xFF7C8DA3),
    outlineVariant = DayLine,
    error = Color(0xFFB3261E),
    onError = Color.White,
)

/**
 * APRSdroid theme. Follows the "theme" preference: "system" (default),
 * "dark" (night) or "light" (day, for sunlight). Wallpaper-based dynamic
 * colour is deliberately not used: packet and station colours carry
 * meaning and must look the same on every device.
 */
@Composable
fun AprsTheme(
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val pref = PreferenceManager.getDefaultSharedPreferences(context).getString("theme", "system")
    val darkTheme = when (pref) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            // The system bars sit on the window background, which is navy in
            // both themes, so their icons are always the light variant.
            (view.context as? Activity)?.window?.let { window ->
                val c = WindowCompat.getInsetsController(window, view)
                c.isAppearanceLightStatusBars = false
                c.isAppearanceLightNavigationBars = false
            }
        }
    }
    CompositionLocalProvider(LocalAprsColors provides if (darkTheme) NightAprsColors else DayAprsColors) {
        MaterialTheme(
            colorScheme = if (darkTheme) NightScheme else DayScheme,
            typography = AprsTypography,
            content = content,
        )
    }
}

/** Shorthand: `AprsTheme.colors.tx`. */
object AprsTheme {
    val colors: AprsColors
        @Composable @ReadOnlyComposable get() = LocalAprsColors.current
}
