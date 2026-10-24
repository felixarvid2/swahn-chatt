package se.familychat.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper

/**
 * The one database of the app. Version 2 (1: first release; 2: `drafts`).
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
        MessageEntity::class, OutboxEntity::class, DraftEntity::class,
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
        const val VERSION = 2
        const val FILE_NAME = "familychat.db"

        /** 1 -> 2: the unsent composer text per conversation. Nothing else changes, so no existing row is touched. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `drafts` (`conversationId` TEXT NOT NULL, `text` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`conversationId`))")
            }
        }

        /** Migrations from older versions; every shipped version needs one (see `MigrationTest`). */
        val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2)

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
