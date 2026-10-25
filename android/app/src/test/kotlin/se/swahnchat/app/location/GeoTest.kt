package se.swahnchat.app.location

import org.junit.Test
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Location validation and the `geo:` URI, with no Android in sight. */
class GeoTest {
    @Test fun `a fix becomes content with the accuracy rounded up and the label trimmed`() {
        val spec = buildLocation(LocationFix(59.3293, 18.0686, 12.2), "  Hemma  ")
        assertNotNull(spec)
        assertEquals(13.0, spec.accuracyM)
        assertEquals("Hemma", spec.label)
        assertNull(buildLocation(LocationFix(59.3, 18.0, 5.0), "   ")!!.label, "an empty label is dropped")
        assertNull(buildLocation(LocationFix(59.3, 18.0, 5.0))!!.label)
    }

    @Test fun `ranges are checked at the edges`() {
        assertNotNull(buildLocation(LocationFix(90.0, 180.0, 0.0)))
        assertNotNull(buildLocation(LocationFix(-90.0, -180.0, 100_000.0)))
        assertNull(buildLocation(LocationFix(90.0001, 0.0, 1.0)))
        assertNull(buildLocation(LocationFix(-90.0001, 0.0, 1.0)))
        assertNull(buildLocation(LocationFix(0.0, 180.0001, 1.0)))
        assertNull(buildLocation(LocationFix(0.0, -180.0001, 1.0)))
        assertNull(buildLocation(LocationFix(0.0, 0.0, -0.5)))
        assertNull(buildLocation(LocationFix(0.0, 0.0, 100_000.5)))
    }

    @Test fun `not-a-number and infinite values are refused`() {
        assertNull(buildLocation(LocationFix(Double.NaN, 0.0, 1.0)))
        assertNull(buildLocation(LocationFix(0.0, Double.POSITIVE_INFINITY, 1.0)))
        assertNull(buildLocation(LocationFix(0.0, 0.0, Double.NaN)))
        assertNull(buildLocation(LocationFix(0.0, 0.0, Double.POSITIVE_INFINITY)))
    }

    @Test fun `a label of 100 characters is kept, one of 101 is refused rather than cut`() {
        assertNotNull(buildLocation(LocationFix(1.0, 2.0, 3.0), "x".repeat(100)))
        assertNull(buildLocation(LocationFix(1.0, 2.0, 3.0), "x".repeat(101)))
        assertTrue(buildLocation(LocationFix(1.0, 2.0, 3.0), "  " + "x".repeat(100) + "  ") != null, "the limit applies after trimming")
    }

    @Test fun `the geo uri has the coordinates twice and a dot as decimal separator in every language`() {
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale("sv", "SE"))
            assertEquals("geo:59.329300,18.068600?q=59.329300,18.068600", geoUri(59.3293, 18.0686))
            assertEquals("59.329300, 18.068600", formatCoordinates(59.3293, 18.0686))
            assertEquals("geo:-33.868800,-151.209300?q=-33.868800,-151.209300", geoUri(-33.8688, -151.2093))
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test fun `the label is percent-encoded so it cannot end the parentheses or add parameters`() {
        assertEquals("geo:1.000000,2.000000?q=1.000000,2.000000(Hemma%20hos%20mormor)", geoUri(1.0, 2.0, "Hemma hos mormor"))
        // parentheses, ampersand, equals, question mark, hash, slash, plus, percent and the quote all stay inside the label
        val evil = "a) &q=9,9 (b?c#d/e+f%g\"h"
        val uri = geoUri(1.0, 2.0, evil)
        val label = uri.substringAfter("(").removeSuffix(")")
        assertFalse(label.any { it in "()& =?#/+\"" }, label)
        assertEquals(1, uri.count { it == '(' }); assertEquals(1, uri.count { it == ')' })
        assertEquals(1, uri.count { it == '?' })
        assertEquals("a%29%20%26q%3D9%2C9%20%28b%3Fc%23d%2Fe%2Bf%25g%22h", label)
        // non-ASCII is UTF-8, `*` is encoded too
        assertEquals("geo:1.000000,2.000000?q=1.000000,2.000000(Caf%C3%A9%202%2A)", geoUri(1.0, 2.0, "Café 2*"))
    }

    @Test fun `no label or a blank one gives a pin without a name`() {
        assertEquals("geo:1.000000,2.000000?q=1.000000,2.000000", geoUri(1.0, 2.0, null))
        assertEquals("geo:1.000000,2.000000?q=1.000000,2.000000", geoUri(1.0, 2.0, "  "))
    }

    @Test fun `accuracy is shown in metres below a kilometre and in kilometres above`() {
        assertEquals("12 m", formatAccuracy(12.4, Locale.ROOT))
        assertEquals("999 m", formatAccuracy(999.0, Locale.ROOT))
        assertEquals("1,5 km", formatAccuracy(1500.0, Locale("sv", "SE")))
        assertEquals("1.5 km", formatAccuracy(1500.0, Locale.ENGLISH))
    }
}
