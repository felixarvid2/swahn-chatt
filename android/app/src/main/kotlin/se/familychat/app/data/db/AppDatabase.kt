package se.familychat.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper

/**
 * The one database of the app. Version 1.
 *
 * Schema changes: bump [version], add a `Migration` to [MIGRATIONS], and extend `MigrationTest` (the exported schema JSON
 * files in `app/schemas/` are the test fixtures: commit the new one).
 */
@Database(
    version = AppDatabase.VERSION,
    exportSchema = true,
    entities = [
        AccountEntity::class, MetaEntity::class, TokenEntity::class, SyncStateEntity::class,
        UserEntity::class, DeviceEntity::class, ConversationEntity::class, MemberEntity::class,
        MessageEntity::class, OutboxEntity::class,
        SignalIdentityEntity::class, SignalPreKeyEntity::class, SignalSignedPreKeyEntity::class,
        SignalKyberPreKeyEntity::class, SignalLastResortUsedEntity::class, SignalSessionEntity::class,
        SignalRemoteIdentityEntity::class, SignalCounterEntity::class,
    ],
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun storeDao(): StoreDao
    abstract fun protocolDao(): ProtocolDao
    abstract fun tokenDao(): TokenDao
    abstract fun uiDao(): UiDao

    companion object {
        const val VERSION = 1
        const val FILE_NAME = "familychat.db"

        /** Migrations from older versions; empty while the schema is at version 1. */
        val MIGRATIONS: Array<Migration> = emptyArray()

        /** Durable ratchet writes: a lost libsignal step cannot be recovered, so never trade `synchronous` for speed. */
        private val durability = object : Callback() {
            override fun onOpen(db: SupportSQLiteDatabase) {
                db.query("PRAGMA synchronous=FULL").close()
            }
        }

        /** Builds the database on [factory] (SQLCipher in the app). */
        fun build(
            context: Context,
            factory: SupportSQLiteOpenHelper.Factory?,
            name: String? = FILE_NAME,
            configure: (RoomDatabase.Builder<AppDatabase>) -> Unit = {},
        ): AppDatabase {
            val builder = if (name == null) {
                Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            } else {
                Room.databaseBuilder(context, AppDatabase::class.java, name)
            }
            if (factory != null) builder.openHelperFactory(factory)
            configure(builder)
            return builder.addMigrations(*MIGRATIONS).addCallback(durability).build()
        }
    }
}
