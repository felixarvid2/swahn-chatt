package se.swahnchat.app.qr

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import org.signal.libsignal.protocol.IdentityKeyPair
import se.swahnchat.core.crypto.SafetyNumbers
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * QR generation and reading of a generated bitmap on a real Android runtime (real `Bitmap`, libsignal fingerprints, and the R8
 * build in the qa run). The camera cannot run on CI; everything behind it (payload formats, comparison) can.
 */
@RunWith(AndroidJUnit4::class)
class QrInstrumentedTest {
    @Test fun invitationQrSurvivesBitmapAndParsing() {
        val text = InviteQr.payload("https://hemma.example.org/api/v1/", "K7QXr93MD_-abcd")
        val parsed = InviteQr.parse(QrCode.decode(QrCode.bitmap(text))!!, allowInsecureLan = false)
        assertTrue(parsed is InviteParse.Ok, parsed.toString())
        assertEquals("https://hemma.example.org", (parsed as InviteParse.Ok).invite.server)
        assertEquals("K7QXr93MD_-abcd", parsed.invite.code)
        val http = InviteQr.parse(InviteQr.payload("http://hemma.example.org", "K7QXr93MD_-abcd"), allowInsecureLan = false)
        assertEquals(InviteRejection.INSECURE_SERVER, (http as InviteParse.Rejected).reason)
    }

    @Test fun safetyNumberQrIsMatchedByTheOtherPhoneOnly() {
        val aKey = IdentityKeyPair.generate().publicKey.serialize()
        val bKey = IdentityKeyPair.generate().publicKey.serialize()
        val eKey = IdentityKeyPair.generate().publicKey.serialize()
        val aDev = UUID.randomUUID().toString()
        val bDev = UUID.randomUUID().toString()
        val eDev = UUID.randomUUID().toString()
        val onA = SafetyNumbers.compute("anna", aDev, aKey, "bertil", bDev, bKey)
        val onB = SafetyNumbers.compute("bertil", bDev, bKey, "anna", aDev, aKey)
        val shown = QrCode.decode(QrCode.bitmap(SafetyQr.payload(onA)))!!
        assertEquals(SafetyQr.ScanResult.MATCH, SafetyQr.compare(onB, shown))
        val eve = SafetyNumbers.compute("eve", eDev, eKey, "anna", aDev, aKey)
        assertEquals(SafetyQr.ScanResult.MISMATCH, SafetyQr.compare(onB, QrCode.decode(QrCode.bitmap(SafetyQr.payload(eve)))!!))
        assertEquals(SafetyQr.ScanResult.NOT_A_SAFETY_CODE, SafetyQr.compare(onB, "https://example.org"))
    }
}
