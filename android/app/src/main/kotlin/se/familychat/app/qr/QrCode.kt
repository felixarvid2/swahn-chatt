package se.familychat.app.qr

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * QR codes with zxing's pure Java core (no Google Play services). Only text is carried; callers wrap what they need in a
 * versioned text format ([SafetyQr], [InviteQr]) because binary QR payloads are decoded with a guessed charset.
 */
object QrCode {
    /** Quiet zone in modules, as the QR spec asks (cameras need the margin). */
    private const val QUIET_ZONE = 4

    /**
     * The modules of the QR code for [text] (true = dark), including the quiet zone.
     *
     * zxing picks the mask by its penalty score, and for some payloads that mask leaves data that its own detector
     * takes for finder patterns, so the code cannot be read (about one random safety-number code in six). Every code
     * is therefore decoded once here, and if that fails the other masks are tried in turn.
     */
    fun matrix(text: String): BitMatrix {
        val chosen = encode(text, mask = null)
        if (readable(chosen, text)) return chosen
        for (mask in 0 until MASK_PATTERNS) {
            val m = encode(text, mask)
            if (readable(m, text)) return m
        }
        return chosen
    }

    private const val MASK_PATTERNS = 8

    private fun encode(text: String, mask: Int?): BitMatrix {
        val hints = mutableMapOf<EncodeHintType, Any>(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.MARGIN to QUIET_ZONE,
        )
        if (mask != null) hints[EncodeHintType.QR_MASK_PATTERN] = mask
        return QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
    }

    /** Whether zxing reads [m] back as [text], rendered at 4 pixels per module. */
    private fun readable(m: BitMatrix, text: String): Boolean {
        val scale = 4
        val w = m.width * scale
        val h = m.height * scale
        val pixels = IntArray(w * h) { i -> if (m.get(i % w / scale, i / w / scale)) BLACK else WHITE }
        return decode(RGBLuminanceSource(w, h, pixels)) == text
    }

    private const val BLACK = 0xFF000000.toInt()
    private const val WHITE = 0xFFFFFFFF.toInt()

    /**
     * A black and white bitmap of the QR code for [text], [moduleSize] pixels per module. Black on white whatever the app
     * theme, because scanners expect dark modules on a light background.
     */
    fun bitmap(text: String, moduleSize: Int = 8): Bitmap {
        val m = matrix(text)
        val pixels = IntArray(m.width * m.height) { i -> if (m.get(i % m.width, i / m.width)) Color.BLACK else Color.WHITE }
        val small = Bitmap.createBitmap(pixels, m.width, m.height, Bitmap.Config.ARGB_8888)
        return if (moduleSize <= 1) small else Bitmap.createScaledBitmap(small, m.width * moduleSize, m.height * moduleSize, false)
    }

    /** Reads the QR code in [bitmap]; null if there is none or it cannot be read. */
    fun decode(bitmap: Bitmap): String? {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return decode(RGBLuminanceSource(bitmap.width, bitmap.height, pixels))
    }

    /** Reads the QR code in [source]; null if there is none or it cannot be read. */
    fun decode(source: com.google.zxing.LuminanceSource): String? = try {
        QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source)), mapOf(DecodeHintType.TRY_HARDER to true)).text
    } catch (e: com.google.zxing.ReaderException) {
        null
    }
}
