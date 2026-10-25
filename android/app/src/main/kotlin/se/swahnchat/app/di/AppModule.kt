package se.swahnchat.app.di

import android.content.Context
import android.os.Build
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import se.swahnchat.app.BuildConfig
import se.swahnchat.app.data.StorageDirs
import se.swahnchat.app.location.LocationSource
import se.swahnchat.app.location.PlatformLocationSource
import se.swahnchat.app.media.AttachmentPreparer
import se.swahnchat.app.media.Media3VideoTranscoder
import se.swahnchat.app.media.VideoCompressor
import se.swahnchat.app.media.VideoTranscoder
import se.swahnchat.app.data.db.AppDatabase
import se.swahnchat.app.data.db.RoomLocalStore
import se.swahnchat.app.data.db.RoomProtocolStorage
import se.swahnchat.app.data.db.RoomTokenProvider
import se.swahnchat.app.data.security.DatabaseProvider
import se.swahnchat.core.client.EngineConfig
import se.swahnchat.core.client.SwahnChatEngine
import se.swahnchat.core.client.LocalStore
import se.swahnchat.core.crypto.ProtocolStorage
import se.swahnchat.core.network.TokenProvider
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    /** The one position source: the platform LocationManager (no Google Play services). */
    @Provides @Singleton
    fun locationSource(impl: PlatformLocationSource): LocationSource = impl

    /** Video compression before upload: Media3 Transformer behind the [VideoTranscoder] interface; temp files in the staging folder. */
    @Provides @Singleton
    fun videoTranscoder(impl: Media3VideoTranscoder): VideoTranscoder = impl

    @Provides @Singleton
    fun videoCompressor(preparer: AttachmentPreparer, transcoder: VideoTranscoder): VideoCompressor =
        VideoCompressor(transcoder, tempDir = { preparer.compressDir }, outputDir = { preparer.outgoingDir })

    @Provides @Singleton @ApplicationScope
    fun applicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Room on SQLCipher. Opened lazily by Room on the first query (always off the main thread). */
    @Provides @Singleton
    fun database(@ApplicationContext context: Context): AppDatabase = DatabaseProvider.create(context)

    /** Where the app keeps files; the engine writes there and the storage screen measures it. */
    @Provides @Singleton
    fun storageDirs(@ApplicationContext context: Context): StorageDirs = StorageDirs(
        attachments = File(context.filesDir, "attachments"),
        transfer = File(context.filesDir, "transfer"),
        cache = context.cacheDir,
        database = context.getDatabasePath(AppDatabase.FILE_NAME),
        outgoing = File(context.filesDir, "outgoing"),
    )

    @Provides @Singleton
    fun localStore(db: AppDatabase): LocalStore = RoomLocalStore(db)

    @Provides @Singleton
    fun protocolStorage(db: AppDatabase): ProtocolStorage = RoomProtocolStorage(db)

    @Provides @Singleton
    fun tokenProvider(db: AppDatabase): TokenProvider = RoomTokenProvider(db)

    /**
     * The one engine. It needs no server address up front: `login`/`redeemInvite` take the address the user typed on the
     * login screen (it can be changed there until the account exists) and the account record keeps it afterwards.
     * Decrypted attachments live in the app's private files directory (not backed up: allowBackup is off and the data extraction rules exclude everything).
     */
    @Provides @Singleton
    fun engine(
        dirs: StorageDirs,
        store: LocalStore,
        protocol: ProtocolStorage,
        tokens: TokenProvider,
        @ApplicationScope scope: CoroutineScope,
    ): SwahnChatEngine = SwahnChatEngine(
        store = store,
        protocolStorage = protocol,
        tokens = tokens,
        config = EngineConfig(
            deviceName = defaultDeviceName(),
            attachmentDir = dirs.attachments,
            tempDir = dirs.transfer,
            forwardDir = dirs.outgoing ?: File(dirs.transfer, "forward"),
            allowInsecureLan = BuildConfig.ALLOW_INSECURE_LAN,
        ),
        scope = scope,
    )

    fun defaultDeviceName(): String {
        val manufacturer = Build.MANUFACTURER.orEmpty().trim()
        val model = Build.MODEL.orEmpty().trim()
        val name = if (model.startsWith(manufacturer, ignoreCase = true)) model else "$manufacturer $model"
        return name.trim().ifEmpty { "Android" }.take(64)
    }
}
