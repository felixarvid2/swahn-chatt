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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.lerp
import se.swahnchat.app.data.Accent
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

/** True while the "Hög kontrast" variant is on: components that normally rely on a tint add a real outline. */
val LocalHighContrast = androidx.compose.runtime.staticCompositionLocalOf { false }

/** The four colours an accent changes; the rest of the palette follows from them. */
private class AccentColors(
    val primary: Color, val container: Color, val onContainer: Color,
    val darkPrimary: Color, val darkOnPrimary: Color, val darkContainer: Color, val darkOnContainer: Color,
)

private val Accents = mapOf(
    Accent.BLUE to AccentColors(
        Color(0xFF1B5FAA), Color(0xFFD3E4FF), Color(0xFF001C3A), Color(0xFFA5C8FF), Color(0xFF00315F), Color(0xFF004786), Color(0xFFD3E4FF),
    ),
    Accent.PURPLE to AccentColors(
        Color(0xFF6B4EA0), Color(0xFFE9DDFF), Color(0xFF230F46), Color(0xFFD0BCFF), Color(0xFF381E72), Color(0xFF4F378B), Color(0xFFEADDFF),
    ),
    Accent.ROSE to AccentColors(
        Color(0xFFA8365F), Color(0xFFFFD9E2), Color(0xFF3E001D), Color(0xFFFFB1C8), Color(0xFF5E1133), Color(0xFF7B2949), Color(0xFFFFD9E2),
    ),
    Accent.ORANGE to AccentColors(
        Color(0xFF9A4A00), Color(0xFFFFDCC2), Color(0xFF311300), Color(0xFFFFB782), Color(0xFF502400), Color(0xFF753600), Color(0xFFFFDCC2),
    ),
    Accent.GREEN to AccentColors(
        Color(0xFF2E6B2F), Color(0xFFB2F1AD), Color(0xFF002105), Color(0xFF97D590), Color(0xFF00390A), Color(0xFF14521A), Color(0xFFB2F1AD),
    ),
)

/** The swatch of [accent] for the picker (the primary colour of the light scheme; null for [Accent.SYSTEM], which has no fixed colour). */
fun accentSwatch(accent: Accent): Color? = when (accent) {
    Accent.SYSTEM -> null
    Accent.TEAL -> LightColors.primary
    else -> Accents.getValue(accent).primary
}

/** [base] with the accent's primary colours; surfaces get a faint tint of the accent so nothing teal is left over. */
internal fun withAccent(base: ColorScheme, accent: Accent, dark: Boolean): ColorScheme {
    val a = Accents[accent] ?: return base
    val primary = if (dark) a.darkPrimary else a.primary
    val container = if (dark) a.darkContainer else a.container
    val neutral = if (dark) Color(0xFF121212) else Color.White
    fun tint(amount: Float, from: Color = neutral) = lerp(from, container, amount)
    return if (dark) {
        base.copy(
            primary = primary, onPrimary = a.darkOnPrimary, primaryContainer = container, onPrimaryContainer = a.darkOnContainer,
            secondary = lerp(primary, Color(0xFFBBBBBB), 0.6f), secondaryContainer = lerp(container, neutral, 0.5f), onSecondaryContainer = a.darkOnContainer,
            background = tint(0.06f, Color(0xFF0E0E0E)), surface = tint(0.06f, Color(0xFF0E0E0E)),
            surfaceVariant = tint(0.35f, Color(0xFF3A3A3A)), surfaceContainerLowest = tint(0.03f, Color(0xFF090909)),
            surfaceContainerLow = tint(0.1f, Color(0xFF151515)), surfaceContainer = tint(0.14f, Color(0xFF191919)),
            surfaceContainerHigh = tint(0.18f, Color(0xFF222222)), surfaceContainerHighest = tint(0.22f, Color(0xFF2D2D2D)),
            outline = lerp(Color(0xFF8E8E8E), primary, 0.15f), outlineVariant = tint(0.3f, Color(0xFF3A3A3A)),
        )
    } else {
        base.copy(
            primary = primary, onPrimary = Color.White, primaryContainer = container, onPrimaryContainer = a.onContainer,
            secondary = lerp(primary, Color(0xFF555555), 0.6f), secondaryContainer = lerp(container, neutral, 0.5f), onSecondaryContainer = a.onContainer,
            background = tint(0.12f), surface = tint(0.12f), surfaceVariant = tint(0.45f, Color(0xFFE2E2E2)),
            surfaceContainerLowest = Color.White, surfaceContainerLow = tint(0.15f), surfaceContainer = tint(0.2f),
            surfaceContainerHigh = tint(0.25f), surfaceContainerHighest = tint(0.3f),
            outline = lerp(Color(0xFF777777), primary, 0.15f), outlineVariant = tint(0.55f, Color(0xFFCCCCCC)),
        )
    }
}

/** The WCAG contrast ratio of two opaque colours: 1 (the same) to 21 (black on white). */
fun contrastRatio(a: Color, b: Color): Float {
    val la = a.luminance()
    val lb = b.luminance()
    return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
}

/** [color] moved towards black or white (whichever [background] is farther from) until it reaches [target]:1 on [background]. */
internal fun ensureContrast(color: Color, background: Color, target: Float = 7f): Color {
    if (contrastRatio(color, background) >= target) return color
    val end = if (background.luminance() > 0.5f) Color.Black else Color.White
    var step = 0.05f
    while (step < 1f) {
        val c = lerp(color, end, step)
        if (contrastRatio(c, background) >= target) return c
        step += 0.05f
    }
    return end
}

/**
 * "Hög kontrast": [base] (with the accent or the system colours) pushed to pure white or black surfaces, text at 7:1 or more
 * on every surface it is drawn on, and strong outlines. The accent survives as the (darkened or lightened) primary colour.
 */
internal fun highContrast(base: ColorScheme, dark: Boolean): ColorScheme {
    val bg = if (dark) Color.Black else Color.White
    val text = if (dark) Color.White else Color.Black
    val containers = if (dark) listOf(Color(0xFF0D0D0D), Color(0xFF141414), Color(0xFF1C1C1C), Color(0xFF242424), Color(0xFF2E2E2E))
    else listOf(Color(0xFFFAFAFA), Color(0xFFF4F4F4), Color(0xFFEDEDED), Color(0xFFE6E6E6), Color(0xFFDEDEDE))
    val variant = if (dark) Color(0xFF1F1F1F) else Color(0xFFEBEBEB)
    // coloured text must also hold on the darkest (light theme) or brightest (dark theme) container and on the variant surface
    val worst = containers[3] // the surfaceContainerHighest used below
    fun strong(c: Color) = ensureContrast(ensureContrast(ensureContrast(c, bg), variant), worst)
    val primary = strong(base.primary)
    val secondary = strong(base.secondary)
    val tertiary = strong(base.tertiary)
    val error = strong(base.error)
    fun container(of: Color) = lerp(of, bg, if (dark) 0.75f else 0.85f)
    return base.copy(
        primary = primary, onPrimary = bg, primaryContainer = container(primary), onPrimaryContainer = text,
        secondary = secondary, onSecondary = bg, secondaryContainer = container(secondary), onSecondaryContainer = text,
        tertiary = tertiary, onTertiary = bg, tertiaryContainer = container(tertiary), onTertiaryContainer = text,
        background = bg, onBackground = text, surface = bg, onSurface = text,
        surfaceVariant = variant, onSurfaceVariant = if (dark) Color(0xFFEDEDED) else Color(0xFF1F1F1F),
        surfaceContainerLowest = bg, surfaceContainerLow = containers[0], surfaceContainer = containers[1],
        surfaceContainerHigh = containers[2], surfaceContainerHighest = containers[3],
        outline = if (dark) Color(0xFFD0D0D0) else Color(0xFF404040), outlineVariant = if (dark) Color(0xFF999999) else Color(0xFF666666),
        error = error, onError = bg, errorContainer = container(error), onErrorContainer = text,
    )
}

/** The scheme [SwahnChatTheme] builds, without Compose (the screenshot tests and the contrast test use it with a fixed palette). */
fun appColorScheme(dark: Boolean, accent: Accent = Accent.SYSTEM, highContrast: Boolean = false): ColorScheme {
    val base = withAccent(if (dark) DarkColors else LightColors, accent, dark)
    return if (highContrast) highContrast(base, dark) else base
}

@Composable
fun SwahnChatTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    /** Material You colours on Android 12+; the fixed palette otherwise (and in screenshot tests). Only used by [Accent.SYSTEM]. */
    dynamicColor: Boolean = true,
    accent: Accent = Accent.SYSTEM,
    highContrast: Boolean = false,
    content: @Composable () -> Unit,
) {
    val scheme: ColorScheme = when {
        accent == Accent.SYSTEM && dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        else -> appColorScheme(darkTheme, accent)
    }.let { if (highContrast) highContrast(it, darkTheme) else it }
    val status = when {
        highContrast -> StatusColors(
            Color(0xFF8C1A13), Color.White, scheme.primary, scheme.error,
        )
        darkTheme -> DarkStatus
        else -> LightStatus
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalStatusColors provides status, LocalHighContrast provides highContrast) {
        MaterialTheme(colorScheme = scheme, shapes = AppShapes, typography = MaterialTheme.typography, content = content)
    }
}
