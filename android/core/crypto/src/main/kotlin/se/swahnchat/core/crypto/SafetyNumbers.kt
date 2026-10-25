package se.swahnchat.core.crypto

import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.fingerprint.NumericFingerprintGenerator
import org.signal.libsignal.protocol.fingerprint.ScannableFingerprint

/** A safety number between two devices: [digits] (60 numeric characters) to compare by eye, [scannable] for QR codes. */
class SafetyNumber internal constructor(val digits: String, private val fingerprint: ScannableFingerprint) {
    /** Bytes to encode in a QR code for the other phone to scan. */
    val scannable: ByteArray get() = fingerprint.serialized

    /** The 60 digits in 12 groups of 5. */
    val groups: List<String> get() = digits.chunked(5)

    /** `"12345 67890 ..."` */
    val displayText: String get() = groups.joinToString(" ")

    /**
     * Compares the QR payload [scanned] from the other phone with this number. False on mismatch, on a payload of
     * another version, or on garbage.
     */
    fun matchesScanned(scanned: ByteArray): Boolean = try {
        fingerprint.compareTo(scanned)
    } catch (e: Exception) {
        false
    }
}

/**
 * Safety numbers for verifying a contact's device out of band. Identities are per device (each device has its own
 * identity key), so a number covers one local device and one remote device; both sides compute the same value. Devices are
 * identified by their immutable UUID, never by the server-assigned number.
 */
object SafetyNumbers {
    private const val ITERATIONS = 5200
    private const val VERSION = 2

    /** @param localIdentityKey / [remoteIdentityKey] serialized `IdentityKey`s (33 bytes, as in the API) */
    fun compute(
        localUserId: String,
        localDeviceId: String,
        localIdentityKey: ByteArray,
        remoteUserId: String,
        remoteDeviceId: String,
        remoteIdentityKey: ByteArray,
    ): SafetyNumber {
        val fp = NumericFingerprintGenerator(ITERATIONS).createFor(
            VERSION,
            stableId(localUserId, localDeviceId), IdentityKey(localIdentityKey),
            stableId(remoteUserId, remoteDeviceId), IdentityKey(remoteIdentityKey),
        )
        return SafetyNumber(fp.displayableFingerprint.displayText, fp.scannableFingerprint)
    }

    private fun stableId(userId: String, deviceId: String): ByteArray =
        "$userId:$deviceId".toByteArray(Charsets.UTF_8)
}
