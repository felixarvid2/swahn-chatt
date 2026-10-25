package se.swahnchat.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

// Fallback palette (Android 11 and older, or dynamic colour switched off): a calm teal with a warm accent. It is our own;
// it does not follow any other messenger's branding.
private val LightColors = lightColorScheme(
    primary = Color(0xFF1F6F6B), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFBDEFE9), onPrimaryContainer = Color(0xFF00201E),
    secondary = Color(0xFF4A6361), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCCE8E4), onSecondaryContainer = Color(0xFF051F1E),
    tertiary = Color(0xFF8A5A2B), onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDCBE), onTertiaryContainer = Color(0xFF2E1500),
    background = Color(0xFFF5FAF9), onBackground = Color(0xFF161D1C),
    surface = Color(0xFFF5FAF9), onSurface = Color(0xFF161D1C),
    surfaceVariant = Color(0xFFDAE5E3), onSurfaceVariant = Color(0xFF3F4948),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFEFF5F3),
    surfaceContainer = Color(0xFFE9EFEE), surfaceContainerHigh = Color(0xFFE3E9E8), surfaceContainerHighest = Color(0xFFDEE4E2),
    outline = Color(0xFF6F7977), outlineVariant = Color(0xFFBEC9C7),
    error = Color(0xFFBA1A1A), onError = Color(0xFFFFFFFF), errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF6FD7CF), onPrimary = Color(0xFF003733),
    primaryContainer = Color(0xFF00504C), onPrimaryContainer = Color(0xFFBDEFE9),
    secondary = Color(0xFFB1CCC8), onSecondary = Color(0xFF1C3533),
    secondaryContainer = Color(0xFF324B49), onSecondaryContainer = Color(0xFFCCE8E4),
    tertiary = Color(0xFFFFB877), onTertiary = Color(0xFF4A2800),
    tertiaryContainer = Color(0xFF6B4010), onTertiaryContainer = Color(0xFFFFDCBE),
    background = Color(0xFF0E1514), onBackground = Color(0xFFDDE4E2),
    surface = Color(0xFF0E1514), onSurface = Color(0xFFDDE4E2),
    surfaceVariant = Color(0xFF3F4948), onSurfaceVariant = Color(0xFFBEC9C7),
    surfaceContainerLowest = Color(0xFF090F0F), surfaceContainerLow = Color(0xFF161D1C),
    surfaceContainer = Color(0xFF1A2120), surfaceContainerHigh = Color(0xFF242B2A), surfaceContainerHighest = Color(0xFF2F3635),
    outline = Color(0xFF899392), outlineVariant = Color(0xFF3F4948),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005), errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
)

private val AppShapes = Shapes(
    small = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
)

/** Colours that Material 3 has no slot for. */
data class StatusColors(
    /** The colour of the "insecure connection" banner (always red, in both themes). */
    val warningBanner: Color,
    val onWarningBanner: Color,
    /** Filled double check = read. */
    val read: Color,
    val failed: Color,
)

private val LightStatus = StatusColors(Color(0xFFB3261E), Color.White, Color(0xFF1F6F6B), Color(0xFFBA1A1A))
private val DarkStatus = StatusColors(Color(0xFFB3261E), Color.White, Color(0xFF6FD7CF), Color(0xFFFFB4AB))

val LocalStatusColors = androidx.compose.runtime.staticCompositionLocalOf { LightStatus }

@Composable
fun SwahnChatTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    /** Material You colours on Android 12+; the fixed palette otherwise (and in screenshot tests). */
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val scheme: ColorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalStatusColors provides if (darkTheme) DarkStatus else LightStatus) {
        MaterialTheme(colorScheme = scheme, shapes = AppShapes, typography = MaterialTheme.typography, content = content)
    }
}
