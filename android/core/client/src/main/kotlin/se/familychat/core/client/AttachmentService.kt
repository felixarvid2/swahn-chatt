package se.familychat.core.client

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import se.familychat.core.crypto.AttachmentCipher
import se.familychat.core.crypto.AttachmentCryptoException
import java.io.File
import java.io.IOException
import java.util.Base64

/** The downloaded ciphertext did not match the checksum/size announced inside the encrypted message. */
class AttachmentIntegrityException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Downloads, verifies and decrypts attachments of received messages. */
internal class AttachmentService(private val ctx: EngineContext) {
    private val locks = HashMap<String, Mutex>()

    private fun lockFor(id: String): Mutex = synchronized(locks) { locks.getOrPut(id) { Mutex() } }

    /**
     * Downloads attachment [attachmentId] of [messageId], resuming a partial download if one exists in the temp dir,
     * verifies the ciphertext SHA-256 and the plaintext size against the values from the message, decrypts into
     * `attachmentDir` and updates the message. Returns the decrypted file.
     *
     * @throws AttachmentIntegrityException checksum/size mismatch (the partial data is discarded)
     * @throws AttachmentCryptoException wrong key or tampered ciphertext
     */
    suspend fun download(messageId: String, attachmentId: String, onProgress: ((Long) -> Unit)? = null): File =
        lockFor("$messageId/$attachmentId").withLock {
            val message = ctx.store.getMessage(messageId) ?: throw IllegalArgumentException("Unknown message")
            val att = message.attachments.firstOrNull { it.id == attachmentId }
                ?: throw IllegalArgumentException("Unknown attachment")
            val key = att.key ?: throw IllegalStateException("Attachment has no key (not uploaded yet)")
            val sha = att.sha256 ?: throw IllegalStateException("Attachment has no checksum")
            if (att.localPath != null && File(att.localPath).isFile && att.downloadState == DownloadState.DONE) {
                return@withLock File(att.localPath)
            }
            require(att.size in 0..ctx.config.maxAttachmentBytes) { "Attachment size ${att.size} is not acceptable" }
            setState(message, attachmentId, DownloadState.DOWNLOADING, null)
            ctx.config.tempDir.mkdirs()
            ctx.config.attachmentDir.mkdirs()
            val safeId = attachmentId.filter { it.isLetterOrDigit() || it == '-' }
            val part = File(ctx.config.tempDir, "dl-$safeId.part")
            val plain = File(ctx.config.attachmentDir, safeId)
            val tmpPlain = File(ctx.config.attachmentDir, "$safeId.tmp")
            try {
                withContext(ctx.config.ioDispatcher) {
                    var attempt = 0
                    while (true) {
                        ctx.api.downloadAttachment(attachmentId, part, onProgress)
                        val actual = AttachmentCipher.sha256Hex(part)
                        if (actual.equals(sha, ignoreCase = true)) break
                        // A resumed file may be stale or corrupt: start over once, then give up.
                        part.delete()
                        if (++attempt >= 2) throw AttachmentIntegrityException("Checksum of attachment $attachmentId does not match")
                    }
                    val written = AttachmentCipher.decryptFile(part, tmpPlain, Base64.getDecoder().decode(key))
                    if (written != att.size) {
                        tmpPlain.delete()
                        throw AttachmentIntegrityException("Decrypted size $written differs from announced ${att.size}")
                    }
                    if (plain.exists()) plain.delete()
                    if (!tmpPlain.renameTo(plain)) throw IOException("Could not move decrypted attachment into place")
                    part.delete()
                }
            } catch (e: CancellationException) {
                // keep the partial file for the next resume
                setStateQuietly(messageId, attachmentId, DownloadState.NONE, null)
                throw e
            } catch (e: IOException) {
                // Network errors keep the partial file (resume); integrity errors already deleted it.
                setStateQuietly(messageId, attachmentId, DownloadState.FAILED, null)
                if (e is AttachmentIntegrityException) part.delete()
                throw e
            } catch (e: AttachmentCryptoException) {
                part.delete(); tmpPlain.delete()
                setStateQuietly(messageId, attachmentId, DownloadState.FAILED, null)
                throw e
            } catch (e: Exception) {
                setStateQuietly(messageId, attachmentId, DownloadState.FAILED, null)
                throw e
            }
            ctx.store.getMessage(messageId)?.let { setState(it, attachmentId, DownloadState.DONE, plain.absolutePath) }
            plain
        }

    private suspend fun setState(message: StoredMessage, attachmentId: String, state: DownloadState, path: String?) {
        ctx.store.updateMessage(message.id) { m ->
            m.copy(attachments = m.attachments.map { if (it.id == attachmentId) it.copy(downloadState = state, localPath = path ?: it.localPath) else it })
        }
        ctx.emit(EngineEvent.MessageChanged(message.conversationId, message.id))
    }

    private suspend fun setStateQuietly(messageId: String, attachmentId: String, state: DownloadState, path: String?) {
        try {
            ctx.store.getMessage(messageId)?.let { setState(it, attachmentId, state, path) }
        } catch (e: Exception) {
            // best effort
        }
    }
}
