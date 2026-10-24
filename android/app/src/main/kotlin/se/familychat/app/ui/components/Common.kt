package se.familychat.app.ui.components

import android.text.format.DateFormat
import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import se.familychat.app.R
import se.familychat.app.ui.model.AvatarUi
import se.familychat.app.ui.theme.LocalStatusColors
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** "Now" for relative dates ("today", weekday); fixed in screenshot tests. */
val LocalNowMillis = compositionLocalOf { System.currentTimeMillis() }

/** Resolves an avatar attachment id to a local file (downloaded once). The app provides the real one. */
val LocalAvatarLoader = compositionLocalOf<suspend (String) -> File?> { { null } }

private val AvatarPalette = listOf(
    0xFF1F6F6B, 0xFF8A5A2B, 0xFF5B5F97, 0xFF8C4A60, 0xFF4A7B3A, 0xFF9B4A3C, 0xFF3E6A8F, 0xFF7A6A1F,
)

private fun initialsOf(name: String): String {
    val parts = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    return when {
        parts.isEmpty() -> "?"
        parts.size == 1 -> parts[0].take(1).uppercase()
        else -> (parts.first().take(1) + parts.last().take(1)).uppercase()
    }
}

/** Round avatar: the picture if there is one (and it loads), else initials on a colour derived from the name. */
@Composable
fun Avatar(avatar: AvatarUi, modifier: Modifier = Modifier, size: Dp = 48.dp) {
    val loader = LocalAvatarLoader.current
    val file by produceState<File?>(null, avatar.attachmentId) {
        value = avatar.attachmentId?.let { id -> runCatching { loader(id) }.getOrNull() }
    }
    val color = Color(AvatarPalette[(avatar.name.hashCode() and 0x7FFFFFFF) % AvatarPalette.size])
    Box(
        modifier = modifier.size(size).clip(CircleShape).background(color),
        contentAlignment = Alignment.Center,
    ) {
        val picture = file
        if (picture != null) {
            AsyncImage(model = picture, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(size))
        } else {
            Text(initialsOf(avatar.name), color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = (size.value * 0.38f).sp)
        }
    }
}

/** Persistent red strip while the server address is plain http (development builds only). */
@Composable
fun InsecureBanner(modifier: Modifier = Modifier) {
    val colors = LocalStatusColors.current
    Row(
        modifier = modifier.fillMaxWidth().background(colors.warningBanner).padding(horizontal = 16.dp, vertical = 6.dp)
            ,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Warning, contentDescription = null, tint = colors.onWarningBanner, modifier = Modifier.size(18.dp))
        Text(
            stringResource(R.string.insecure_connection_banner),
            color = colors.onWarningBanner,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

fun isInsecureUrl(url: String?): Boolean = url != null && url.trim().startsWith("http://", ignoreCase = true)

// --- formatting -------------------------------------------------------------------------------------------------------

@Composable
fun currentLocale(): Locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()

/** "14:05" / "2:05 PM", following the device's 12/24 hour setting. */
@Composable
fun rememberClockFormatter(zone: ZoneId = ZoneId.systemDefault()): (Long) -> String {
    val context = LocalContext.current
    val locale = currentLocale()
    return remember(context, locale, zone) {
        val pattern = if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a"
        val f = DateTimeFormatter.ofPattern(pattern, locale);
        { ms: Long -> f.format(Instant.ofEpochMilli(ms).atZone(zone)) }
    }
}

/** For the conversation list: time today, weekday this week, else the date. */
@Composable
fun rememberListTimeFormatter(now: Long = LocalNowMillis.current, zone: ZoneId = ZoneId.systemDefault()): (Long) -> String {
    val clock = rememberClockFormatter(zone)
    val locale = currentLocale()
    return remember(clock, locale, now, zone) {
        val weekday = DateTimeFormatter.ofPattern("EEE", locale)
        val dayMonth = DateTimeFormatter.ofPattern("d MMM", locale)
        val full = DateTimeFormatter.ofPattern("d MMM yyyy", locale);
        { ms: Long ->
            val d = Instant.ofEpochMilli(ms).atZone(zone)
            val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
            val days = java.time.temporal.ChronoUnit.DAYS.between(d.toLocalDate(), today)
            when {
                days == 0L -> clock(ms)
                days in 1..6 -> weekday.format(d)
                d.year == today.year -> dayMonth.format(d)
                else -> full.format(d)
            }
        }
    }
}

/** "Idag", "Igår" or a date, for the separators in a conversation. */
@Composable
fun dayLabel(dayStart: Long, now: Long = LocalNowMillis.current, zone: ZoneId = ZoneId.systemDefault()): String {
    val locale = currentLocale()
    val day = Instant.ofEpochMilli(dayStart).atZone(zone).toLocalDate()
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    return when (day) {
        today -> stringResource(R.string.today)
        today.minusDays(1) -> stringResource(R.string.yesterday)
        else -> DateTimeFormatter.ofPattern(if (day.year == today.year) "EEEE d MMMM" else "d MMMM yyyy", locale)
            .format(day).replaceFirstChar { it.titlecase(locale) }
    }
}

@Composable
fun formatSize(bytes: Long): String = Formatter.formatShortFileSize(LocalContext.current, bytes)

fun formatDuration(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}
