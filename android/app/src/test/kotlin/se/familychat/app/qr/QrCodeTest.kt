package se.familychat.app.qr

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import org.signal.libsignal.protocol.IdentityKeyPair
import se.familychat.core.crypto.SafetyNumber
import se.familychat.core.crypto.SafetyNumbers
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** QR generation and decoding of a generated bitmap (what CI can check; the camera cannot run there). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class QrCodeTest {
    private class Phone(val user: String) {
        val device = UUID.randomUUID().toString()
        val key: ByteArray = IdentityKeyPair.generate().publicKey.serialize()
    }

    private fun number(local: Phone, remote: Phone): SafetyNumber =
        SafetyNumbers.compute(local.user, local.device, local.key, remote.user, remote.device, remote.key)

    private val anna = Phone("anna")
    private val bertil = Phone("bertil")
    private val eve = Phone("eve")

    @Test fun `text survives encoding into a bitmap and decoding it again`() {
        val text = "familychat://invite?v=1&server=https%3A%2F%2Fhemma.example.org&code=K7QX93MD"
        assertEquals(text, QrCode.decode(QrCode.bitmap(text)))
        assertEquals("åäö ✓", QrCode.decode(QrCode.bitmap("åäö ✓")))
    }

    @Test fun `a blank or noisy bitmap decodes to nothing`() {
        val blank = android.graphics.Bitmap.createBitmap(200, 200, android.graphics.Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.WHITE) }
        assertNull(QrCode.decode(blank))
    }

    @Test fun `the safety number QR of one phone is matched by the other phone and by nobody else`() {
        val annas = number(anna, bertil)
        val bertils = number(bertil, anna)
        // Anna shows her code, Bertil's phone scans it (and the other way round)
        val scannedByBertil = QrCode.decode(QrCode.bitmap(SafetyQr.payload(annas)))
        assertNotNull(scannedByBertil)
        assertEquals(SafetyQr.ScanResult.MATCH, SafetyQr.compare(bertils, scannedByBertil))
        val scannedByAnna = QrCode.decode(QrCode.bitmap(SafetyQr.payload(bertils)))
        assertEquals(SafetyQr.ScanResult.MATCH, SafetyQr.compare(annas, scannedByAnna!!))

        // Eve's code (a different pair) does not match, neither does a code whose identity key differs
        val evesWithAnna = QrCode.decode(QrCode.bitmap(SafetyQr.payload(number(eve, anna))))!!
        assertEquals(SafetyQr.ScanResult.MISMATCH, SafetyQr.compare(bertils, evesWithAnna))
        val sameDeviceOtherKey = SafetyNumbers.compute("bertil", bertil.device, Phone("bertil").key, "anna", anna.device, anna.key)
        assertEquals(
            SafetyQr.ScanResult.MISMATCH,
            SafetyQr.compare(annas, QrCode.decode(QrCode.bitmap(SafetyQr.payload(sameDeviceOtherKey)))!!),
        )
    }

    @Test fun `scans that are not safety number codes are recognised`() {
        val n = number(anna, bertil)
        assertEquals(SafetyQr.ScanResult.NOT_A_SAFETY_CODE, SafetyQr.compare(n, "familychat://invite?v=1"))
        assertEquals(SafetyQr.ScanResult.NOT_A_SAFETY_CODE, SafetyQr.compare(n, "https://example.org"))
        assertEquals(SafetyQr.ScanResult.NOT_A_SAFETY_CODE, SafetyQr.compare(n, ""))
        assertEquals(SafetyQr.ScanResult.NOT_A_SAFETY_CODE, SafetyQr.compare(n, SafetyQr.PREFIX + "%%%"))
        assertEquals(SafetyQr.ScanResult.NOT_A_SAFETY_CODE, SafetyQr.compare(n, SafetyQr.PREFIX))
        assertEquals(SafetyQr.ScanResult.NOT_A_SAFETY_CODE, SafetyQr.compare(n, SafetyQr.PREFIX + "A".repeat(600)))
        // well-formed base64 that is not a fingerprint: libsignal says no
        assertEquals(SafetyQr.ScanResult.MISMATCH, SafetyQr.compare(n, SafetyQr.PREFIX + "AQIDBA"))
    }

    @Test fun `the payload is text a person could not mistake for an invitation`() {
        val p = SafetyQr.payload(number(anna, bertil))
        assertEquals(true, p.startsWith("fcsn1:"))
        assertEquals(true, p.length < 200)
        assertEquals(true, p.all { it.isLetterOrDigit() || it == ':' || it == '-' || it == '_' })
    }
}
