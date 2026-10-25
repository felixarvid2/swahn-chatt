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
    /** Quiet zone in modules; the spec asks for 4, 2 still scans well and keeps the code large on a small screen. */
    private const val QUIET_ZONE = 2

    /** The modules of the QR code for [text] (true = dark), including the quiet zone. */
    fun matrix(text: String): BitMatrix = QRCodeWriter().encode(
        text, BarcodeFormat.QR_CODE, 0, 0,
        mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.MARGIN to QUIET_ZONE,
        ),
    )

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
