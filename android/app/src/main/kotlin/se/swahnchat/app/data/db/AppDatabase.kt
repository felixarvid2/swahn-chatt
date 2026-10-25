package se.swahnchat.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper

/**
 * The one database of the app. Version 7 (1: first release; 2: `drafts`; 3: `message_fts` search index, `conversation_settings`;
 * 4: `message_receipts`, `local_deletions`; 5: `reactions`, `conversation_timers`, columns for forwarded / imported /
 * disappearing messages and timer notices; 6: poll votes, starred messages, mentions, polls, scheduled messages; 7: `location` columns).
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
        MessageEntity::class, OutboxEntity::class, DraftEntity::class, MessageFtsEntity::class, ConversationSettingsEntity::class,
        MessageReceiptEntity::class, LocalDeletionEntity::class, ReactionEntity::class, ConversationTimerEntity::class,
        PollVoteEntity::class, StarredMessageEntity::class,
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
        const val VERSION = 7
        const val FILE_NAME = "swahnchat.db"

        /** 1 -> 2: the unsent composer text per conversation. Nothing else changes, so no existing row is touched. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `drafts` (`conversationId` TEXT NOT NULL, `text` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`conversationId`))")
            }
        }

        /**
         * 2 -> 3: the search index and the local conversation settings. No existing row is changed. The index is filled from
         * the messages that are already there (`rebuild`), the sync triggers (the very ones Room creates on a new database,
         * copied from the exported `3.json`) keep it current from then on.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `conversation_settings` (`conversationId` TEXT NOT NULL, `pinnedAt` INTEGER, `mutedUntil` INTEGER NOT NULL, `archived` INTEGER NOT NULL, PRIMARY KEY(`conversationId`))")
                db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS `message_fts` USING FTS4(`text` TEXT, tokenize=unicode61 `remove_diacritics=0`, content=`messages`)")
                db.execSQL("CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_message_fts_BEFORE_UPDATE BEFORE UPDATE ON `messages` BEGIN DELETE FROM `message_fts` WHERE `docid`=OLD.`rowid`; END")
                db.execSQL("CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_message_fts_BEFORE_DELETE BEFORE DELETE ON `messages` BEGIN DELETE FROM `message_fts` WHERE `docid`=OLD.`rowid`; END")
                db.execSQL("CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_message_fts_AFTER_UPDATE AFTER UPDATE ON `messages` BEGIN INSERT INTO `message_fts`(`docid`, `text`) VALUES (NEW.`rowid`, NEW.`text`); END")
                db.execSQL("CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_message_fts_AFTER_INSERT AFTER INSERT ON `messages` BEGIN INSERT INTO `message_fts`(`docid`, `text`) VALUES (NEW.`rowid`, NEW.`text`); END")
                db.execSQL("INSERT INTO `message_fts`(`message_fts`) VALUES ('rebuild')")
            }
        }

        /**
         * 3 -> 4: receipt times (item "message info") and the tombstones of "delete for me". Two new tables, no existing row is
         * changed: messages from before keep their delivered/read status (in `messages`) and just have no times.
         */
        @Suppress("MaxLineLength") // SQL, copied from the exported 4.json
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `message_receipts` (`messageId` TEXT NOT NULL, `userId` TEXT NOT NULL, `conversationId` TEXT NOT NULL, `deliveredAt` INTEGER, `readAt` INTEGER, PRIMARY KEY(`messageId`, `userId`))")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_message_receipts_conversationId` ON `message_receipts` (`conversationId`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `local_deletions` (`messageId` TEXT NOT NULL, `conversationId` TEXT NOT NULL, `deletedAt` INTEGER NOT NULL, PRIMARY KEY(`messageId`))")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_local_deletions_conversationId` ON `local_deletions` (`conversationId`)")
            }
        }

        /**
         * 4 -> 5: reactions, the local disappearing-messages timer, and the columns the new features need. Additive only: two
         * new tables, and `ALTER TABLE ... ADD COLUMN` on `messages` and `outbox` with defaults that mean "as before"
         * (not forwarded, not imported, no timer, no removal time). No existing row is rewritten; the search index and its
         * triggers do not involve the new columns. Messages from before version 5 never expire (they have no `expires_in`).
         */
        @Suppress("MaxLineLength") // SQL, copied from the exported 5.json
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `reactions` (`messageId` TEXT NOT NULL, `userId` TEXT NOT NULL, `conversationId` TEXT NOT NULL, `emoji` TEXT, `seq` INTEGER, `at` INTEGER NOT NULL, PRIMARY KEY(`messageId`, `userId`))")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_reactions_conversationId` ON `reactions` (`conversationId`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `conversation_timers` (`conversationId` TEXT NOT NULL, `seconds` INTEGER NOT NULL, `setBy` TEXT NOT NULL, `seq` INTEGER, `at` INTEGER NOT NULL, PRIMARY KEY(`conversationId`))")
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `forwarded` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `imported` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `expiresIn` INTEGER")
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `expireAt` INTEGER")
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `systemValue` INTEGER")
                db.execSQL("ALTER TABLE `outbox` ADD COLUMN `forwarded` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `outbox` ADD COLUMN `expiresIn` INTEGER")
                db.execSQL("ALTER TABLE `outbox` ADD COLUMN `remove` INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * 5 -> 6: poll votes, starred messages (local only), and the columns for @-mentions, polls and scheduled messages.
         * Additive only: two new tables and `ALTER TABLE ... ADD COLUMN` on `messages` and `outbox` with defaults that mean "as
         * before" (no mentions, not a poll, not scheduled). No existing row is rewritten; the search index and its triggers do
         * not involve the new columns.
         */
        @Suppress("MaxLineLength") // SQL, copied from the exported 6.json
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `poll_votes` (`messageId` TEXT NOT NULL, `userId` TEXT NOT NULL, `conversationId` TEXT NOT NULL, `optionIds` TEXT NOT NULL, `seq` INTEGER, `at` INTEGER NOT NULL, PRIMARY KEY(`messageId`, `userId`))")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_poll_votes_conversationId` ON `poll_votes` (`conversationId`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `starred_messages` (`messageId` TEXT NOT NULL, `conversationId` TEXT NOT NULL, `starredAt` INTEGER NOT NULL, PRIMARY KEY(`messageId`))")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_starred_messages_conversationId` ON `starred_messages` (`conversationId`)")
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `mentions` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `poll` TEXT")
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `scheduledAt` INTEGER")
                db.execSQL("ALTER TABLE `outbox` ADD COLUMN `mentions` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `outbox` ADD COLUMN `poll` TEXT")
                db.execSQL("ALTER TABLE `outbox` ADD COLUMN `optionIds` TEXT")
                db.execSQL("ALTER TABLE `outbox` ADD COLUMN `close` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `outbox` ADD COLUMN `sendAt` INTEGER")
            }
        }

        /**
         * 6 -> 7: shared positions. Two nullable `location` columns (JSON) on `messages` and `outbox`; no existing row is rewritten
         * and the search index and its triggers do not involve the new column.
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `location` TEXT")
                db.execSQL("ALTER TABLE `outbox` ADD COLUMN `location` TEXT")
            }
        }

        /** Migrations from older versions; every shipped version needs one (see `MigrationTest`). */
        val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)

        /** Durable ratchet writes: a lost libsignal step cannot be recovered, so never trade `synchronous` for speed. Also `secure_delete`. */
        private val durability = object : Callback() {
            override fun onOpen(db: SupportSQLiteDatabase) {
                db.query("PRAGMA synchronous=FULL").close()
                // freed pages are overwritten with zeros, so a removed message does not stay readable in the (encrypted) file's free space
                db.query("PRAGMA secure_delete=ON").close()
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
