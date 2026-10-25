package se.swahnchat.core.model

/** Thrown by [Padding.unpad] when the bytes are not a valid padded block. */
class InvalidPaddingException(message: String) : Exception(message)

/**
 * Length-hiding padding for encrypted content.
 *
 * Scheme (ISO/IEC 7816-4 style, unambiguous): append one `0x80` byte, then `0x00` bytes until the total length is
 * a multiple of [BLOCK_SIZE]. There is always at least one padding byte, so an input that already is a multiple of
 * the block size grows by a full block. Unpadding strips trailing zeros and requires a `0x80` marker before them.
 */
object Padding {
    const val BLOCK_SIZE = 160
    private const val MARKER: Byte = 0x80.toByte()

    fun pad(data: ByteArray, blockSize: Int = BLOCK_SIZE): ByteArray {
        require(blockSize > 0) { "blockSize must be positive" }
        val padded = (data.size / blockSize + 1) * blockSize // always >= data.size + 1
        val out = data.copyOf(padded)
        out[data.size] = MARKER
        return out
    }

    /** @throws InvalidPaddingException if the length is not a block multiple or the marker is missing. */
    fun unpad(padded: ByteArray, blockSize: Int = BLOCK_SIZE): ByteArray {
        if (padded.isEmpty() || padded.size % blockSize != 0) {
            throw InvalidPaddingException("length ${padded.size} is not a positive multiple of $blockSize")
        }
        var i = padded.size - 1
        while (i >= 0 && padded[i] == 0.toByte()) i--
        if (i < 0 || padded[i] != MARKER) throw InvalidPaddingException("padding marker not found")
        // More than a full block of padding is never produced by pad().
        if (padded.size - i > blockSize) throw InvalidPaddingException("excess padding")
        return padded.copyOf(i)
    }
}
