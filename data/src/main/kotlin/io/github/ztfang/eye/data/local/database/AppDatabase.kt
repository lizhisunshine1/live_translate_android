package io.github.ztfang.eye.data.local.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import io.github.ztfang.eye.data.local.dao.HistoryDao
import io.github.ztfang.eye.data.local.dao.RecordDao
import io.github.ztfang.eye.data.local.entity.HistoryRecord
import io.github.ztfang.eye.data.local.entity.LineEntity
import io.github.ztfang.eye.data.local.entity.SessionEntity
import io.github.ztfang.eye.data.local.entity.VocabEntity

@Database(
    entities = [HistoryRecord::class, SessionEntity::class, LineEntity::class, VocabEntity::class],
    version = 3,
    // 导出 schema 到 data/schemas/：将来写 Migration 时才有历史基线可比对。
    // 曾为 false 且配合 fallbackToDestructiveMigration，一旦 version++ 会静默清空用户全部历史。
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun historyDao(): HistoryDao

    abstract fun recordDao(): RecordDao

    companion object {
        /**
         * v1 → v2：为 `tb_history.timestamp` 增加索引。
         *
         * 历史列表固定 `ORDER BY timestamp DESC`，记录量大后无索引会退化为全表扫描 + 排序。
         * 索引名必须与 Room 生成的保持一致（`index_<表名>_<列名>`），否则迁移后
         * Room 校验实际 schema 时会判定不匹配并抛异常。
         */
        val MIGRATION_1_2: Migration =
            object : Migration(1, 2) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_tb_history_timestamp ON tb_history(timestamp)")
                }
            }

        /**
         * v2 → v3：新增录音会话 / 逐句文本 / 难词三张表。
         *
         * 老用户升级只加表，不动 tb_history，历史记录零丢失。
         * 表结构必须与 Room 生成的 schema 完全一致（列顺序、默认值、索引名），
         * 否则启动时 Room 校验失败会抛 IllegalStateException。
         */
        val MIGRATION_2_3: Migration =
            object : Migration(2, 3) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `tb_session` (" +
                            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`sessionKey` TEXT NOT NULL, " +
                            "`startTime` INTEGER NOT NULL, " +
                            "`endTime` INTEGER NOT NULL, " +
                            "`sourceLanguage` TEXT NOT NULL, " +
                            "`targetLanguage` TEXT NOT NULL, " +
                            "`lineCount` INTEGER NOT NULL, " +
                            "`audioPath` TEXT, " +
                            "`audioDeleted` INTEGER NOT NULL, " +
                            "`durationMs` INTEGER NOT NULL)",
                    )
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_tb_session_startTime` ON `tb_session` (`startTime`)")

                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `tb_line` (" +
                            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`sessionId` INTEGER NOT NULL, " +
                            "`seq` INTEGER NOT NULL, " +
                            "`timestamp` INTEGER NOT NULL, " +
                            "`startMs` INTEGER NOT NULL, " +
                            "`endMs` INTEGER NOT NULL, " +
                            "`sourceText` TEXT NOT NULL, " +
                            "`translatedText` TEXT NOT NULL, " +
                            "`isFavorite` INTEGER NOT NULL)",
                    )
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_tb_line_sessionId` ON `tb_line` (`sessionId`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_tb_line_timestamp` ON `tb_line` (`timestamp`)")

                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `tb_vocab` (" +
                            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`word` TEXT NOT NULL, " +
                            "`normalized` TEXT NOT NULL, " +
                            "`phonetic` TEXT NOT NULL, " +
                            "`meaning` TEXT NOT NULL, " +
                            "`sentence` TEXT NOT NULL, " +
                            "`contextBefore` TEXT NOT NULL, " +
                            "`contextAfter` TEXT NOT NULL, " +
                            "`sessionId` INTEGER NOT NULL, " +
                            "`lineId` INTEGER NOT NULL, " +
                            "`createdAt` INTEGER NOT NULL, " +
                            "`dueAt` INTEGER NOT NULL, " +
                            "`intervalDays` INTEGER NOT NULL, " +
                            "`ease` REAL NOT NULL, " +
                            "`repetitions` INTEGER NOT NULL, " +
                            "`lapses` INTEGER NOT NULL, " +
                            "`mastered` INTEGER NOT NULL)",
                    )
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_tb_vocab_normalized` ON `tb_vocab` (`normalized`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_tb_vocab_dueAt` ON `tb_vocab` (`dueAt`)")
                }
            }
    }
}
