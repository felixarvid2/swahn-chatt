package se.swahnchat.app.qr

import se.swahnchat.core.crypto.SafetyNumber
import java.util.Base64

/**
 * The QR code of a safety number: libsignal's scannable fingerprint (`SafetyNumber.scannable`, binary) as
 * `fcsn1:<base64url>`. The prefix names the format so that a scan of anything else is recognised as "not a safety number".
 * The other phone compares the scan with its own number through libsignal (`SafetyNumber.matchesScanned`), which checks
 * both fingerprints, so a code of another pair of devices never matches.
 */
object SafetyQr {
    const val PREFIX = "fcsn1:"

    /** Larger than any real fingerprint; a longer scan is not one. */
    private const val MAX_TEXT = 512

    /** The text to put in the QR code for [number]. */
    fun payload(number: SafetyNumber): String = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(number.scannable)

    /** The fingerprint bytes in a scanned [text], or null if it is not a safety number code. */
    fun parse(text: String): ByteArray? {
        if (text.length > MAX_TEXT || !text.startsWith(PREFIX)) return null
        return try {
            Base64.getUrlDecoder().decode(text.removePrefix(PREFIX)).takeIf { it.isNotEmpty() }
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    /** What comparing a scan with [number] says. */
    fun compare(number: SafetyNumber, scannedText: String): ScanResult {
        val bytes = parse(scannedText) ?: return ScanResult.NOT_A_SAFETY_CODE
        return if (number.matchesScanned(bytes)) ScanResult.MATCH else ScanResult.MISMATCH
    }

    enum class ScanResult { MATCH, MISMATCH, NOT_A_SAFETY_CODE }
}
