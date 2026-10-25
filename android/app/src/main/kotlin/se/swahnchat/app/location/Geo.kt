package se.swahnchat.app.location

import se.swahnchat.core.model.Content
import se.swahnchat.core.model.LocationSpec
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.roundToInt

/**
 * A position as the phone reported it. [accuracyM] is the radius in metres within which the fix is expected to lie (the
 * platform's "horizontal accuracy"); a fix that reports none is treated as unknown and refused by [buildLocation].
 */
data class LocationFix(val lat: Double, val lon: Double, val accuracyM: Double)

/**
 * The content to send for [fix] with an optional [label], or null when it is not valid (see [LocationSpec.isValid]). The label is
 * trimmed and an empty one dropped; a label over [Content.MAX_LOCATION_LABEL] characters is refused, not cut, so what the sender
 * confirmed in the preview is what goes out. The accuracy is rounded up to a whole metre, never reported better than it was.
 */
fun buildLocation(fix: LocationFix, label: String? = null): LocationSpec? {
    val trimmed = label?.trim()?.takeIf { it.isNotEmpty() }
    if (!fix.accuracyM.isFinite() || fix.accuracyM < 0.0) return null
    val accuracy = Math.ceil(fix.accuracyM)
    return LocationSpec(fix.lat, fix.lon, accuracy, trimmed).takeIf { it.isValid }
}

/** `59.329300, 18.068600`: six decimals (about 0.1 m), a dot whatever the language, so it can be pasted into any map app. */
fun formatCoordinates(lat: Double, lon: Double): String = String.format(Locale.ROOT, "%.6f, %.6f", lat, lon)

/** "12 m" below a kilometre, "1,2 km" above (the decimal separator follows [locale]). */
fun formatAccuracy(metres: Double, locale: Locale = Locale.getDefault()): String =
    if (metres < 1000.0) "${metres.roundToInt()} m" else String.format(locale, "%.1f km", metres / 1000.0)

/**
 * The `geo:` URI that hands [lat], [lon] to a map app: `geo:lat,lon?q=lat,lon(label)`. The label is percent-encoded (UTF-8, a space
 * as `%20`, and parentheses as `%28` `%29`), so it cannot end the `(...)` part early or add query parameters. Without a label the
 * pin carries no name: `geo:lat,lon?q=lat,lon`.
 */
fun geoUri(lat: Double, lon: Double, label: String? = null): String {
    val at = String.format(Locale.ROOT, "%.6f,%.6f", lat, lon)
    val name = label?.trim()?.takeIf { it.isNotEmpty() }
        ?.let { URLEncoder.encode(it, "UTF-8").replace("+", "%20").replace("*", "%2A") }
    return if (name == null) "geo:$at?q=$at" else "geo:$at?q=$at($name)"
}
