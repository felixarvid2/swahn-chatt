package se.swahnchat.core.network

import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source
import java.io.File
import java.io.InputStream

/**
 * A [RequestBody] that streams from an [InputStream] without loading it into memory. [opener] is called every time
 * the body is written (OkHttp may write twice on a retry), so it must return a fresh stream each time.
 * With a known [contentLength] the request is sent with a Content-Length header (the server streams uploads with
 * quota checks), otherwise chunked.
 */
class StreamingRequestBody(
    private val contentType: MediaType? = "application/octet-stream".toMediaType(),
    private val contentLength: Long = -1,
    private val onProgress: ((bytesWritten: Long) -> Unit)? = null,
    private val opener: () -> InputStream,
) : RequestBody() {
    override fun contentType(): MediaType? = contentType
    override fun contentLength(): Long = contentLength
    override fun isOneShot(): Boolean = false

    override fun writeTo(sink: BufferedSink) {
        opener().source().use { source ->
            var total = 0L
            while (true) {
                val n = source.read(sink.buffer, SEGMENT)
                if (n < 0) break
                total += n
                sink.emitCompleteSegments()
                onProgress?.invoke(total)
            }
        }
    }

    companion object {
        private const val SEGMENT = 64L * 1024

        fun of(file: File, onProgress: ((Long) -> Unit)? = null): StreamingRequestBody =
            StreamingRequestBody(contentLength = file.length(), onProgress = onProgress) { file.inputStream() }
    }
}
