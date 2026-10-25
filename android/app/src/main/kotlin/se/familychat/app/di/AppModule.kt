package se.familychat.app.di

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
import se.familychat.app.BuildConfig
import se.familychat.app.data.StorageDirs
import se.familychat.app.data.db.AppDatabase
import se.familychat.app.data.db.RoomLocalStore
import se.familychat.app.data.db.RoomProtocolStorage
import se.familychat.app.data.db.RoomTokenProvider
import se.familychat.app.data.security.DatabaseProvider
import se.familychat.core.client.EngineConfig
import se.familychat.core.client.FamilyChatEngine
import se.familychat.core.client.LocalStore
import se.familychat.core.crypto.ProtocolStorage
import se.familychat.core.network.TokenProvider
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
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
    ): FamilyChatEngine = FamilyChatEngine(
        store = store,
        protocolStorage = protocol,
        tokens = tokens,
        config = EngineConfig(
            deviceName = defaultDeviceName(),
            attachmentDir = dirs.attachments,
            tempDir = dirs.transfer,
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
