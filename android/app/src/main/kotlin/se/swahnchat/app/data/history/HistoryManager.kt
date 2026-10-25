package se.swahnchat.app.data.history

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import se.swahnchat.app.BuildConfig
import se.swahnchat.app.data.StorageDirs
import se.swahnchat.app.di.ApplicationScope
import se.swahnchat.core.client.SwahnChatEngine
import se.swahnchat.core.client.LocalStore
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Why a history job ended without a result, for the screen to word. */
enum class HistoryError { NOT_A_FILE, VERSION, PARAMETERS, WRONG_PASSPHRASE, DAMAGED, TOO_LARGE, WRONG_ACCOUNT, CONTENT, IO, WEAK, UNKNOWN }

sealed interface HistoryJob {
    data object Idle : HistoryJob
    data class Exporting(val messages: Int) : HistoryJob
    data class ImportReading(val messages: Int) : HistoryJob
    data class ImportMerging(val messages: Int) : HistoryJob
    data class Exported(val result: ExportResult) : HistoryJob
    data class Imported(val result: ImportResult) : HistoryJob
    data class Failed(val error: HistoryError) : HistoryJob
}

fun Throwable.toHistoryError(): HistoryError = when (this) {
    is HistoryException.NotAHistoryFile -> HistoryError.NOT_A_FILE
    is HistoryException.UnsupportedVersion -> HistoryError.VERSION
    is HistoryException.UnsupportedParameters -> HistoryError.PARAMETERS
    is HistoryException.WrongPassphraseOrDamaged -> HistoryError.WRONG_PASSPHRASE
    is HistoryException.Damaged -> HistoryError.DAMAGED
    is HistoryException.TooLarge -> HistoryError.TOO_LARGE
    is HistoryException.WrongAccount -> HistoryError.WRONG_ACCOUNT
    is HistoryException.BadContent -> HistoryError.CONTENT
    is HistoryException.WeakPassphrase -> HistoryError.WEAK
    is IOException -> HistoryError.IO
    else -> HistoryError.UNKNOWN
}

/**
 * Runs history exports and imports for the screen, in the app's scope so that leaving the screen does not cut a long job
 * short. The passphrase is only ever a parameter of the running job: it is not stored, not saved with the screen's state and
 * not logged.
 */
@Singleton
class HistoryManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: LocalStore,
    private val engine: SwahnChatEngine,
    private val dirs: StorageDirs,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<HistoryJob>(HistoryJob.Idle)
    val state: StateFlow<HistoryJob> = _state.asStateFlow()

    private val running: Boolean get() = _state.value.let { it is HistoryJob.Exporting || it is HistoryJob.ImportReading || it is HistoryJob.ImportMerging }

    fun clear() {
        if (!running) _state.value = HistoryJob.Idle
    }

    /** Writes the encrypted file to [target] (a document the user created with the system file chooser). */
    fun export(target: Uri, passphrase: String, includeMedia: Boolean) {
        if (running) return
        val me = engine.account.value?.userId ?: return
        _state.value = HistoryJob.Exporting(0)
        scope.launch {
            try {
                val out = context.contentResolver.openOutputStream(target, "wt") ?: throw IOException("Cannot open the target")
                val result = HistoryExporter(store).export(
                    out, passphrase, me, includeMedia, BuildConfig.VERSION_NAME,
                    onProgress = { done, _ -> _state.value = HistoryJob.Exporting(done) },
                )
                _state.value = HistoryJob.Exported(result)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // a half-written file must not look like a backup
                runCatching { DocumentsContract.deleteDocument(context.contentResolver, target) }
                _state.value = HistoryJob.Failed(e.toHistoryError())
            }
        }
    }

    /** Reads the file at [source] and merges it into this phone's history. */
    fun import(source: Uri, passphrase: String) {
        if (running) return
        val me = engine.account.value?.userId ?: return
        _state.value = HistoryJob.ImportReading(0)
        scope.launch {
            try {
                val length = context.contentResolver.query(source, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
                    if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null
                }
                val input = context.contentResolver.openInputStream(source) ?: throw IOException("Cannot open the file")
                val result = input.use {
                    HistoryImporter(store, dirs.attachments, spoolDir = context.cacheDir).import(it, length, passphrase, me) { phase, done ->
                        _state.value = when (phase) {
                            HistoryImporter.Phase.READING -> HistoryJob.ImportReading(done)
                            HistoryImporter.Phase.MERGING -> HistoryJob.ImportMerging(done)
                        }
                    }
                }
                _state.value = HistoryJob.Imported(result)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = HistoryJob.Failed(e.toHistoryError())
            }
        }
    }
}
