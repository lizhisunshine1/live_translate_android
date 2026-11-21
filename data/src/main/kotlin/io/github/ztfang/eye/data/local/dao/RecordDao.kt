package io.github.ztfang.eye.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import io.github.ztfang.eye.data.local.entity.LineEntity
import io.github.ztfang.eye.data.local.entity.SessionEntity
import io.github.ztfang.eye.data.local.entity.VocabEntity
import kotlinx.coroutines.flow.Flow

/** 记录（会话 / 句子）与难词的数据库访问 */
@Dao
interface RecordDao {
    // ==================== 会话 ====================
    @Query("SELECT * FROM tb_session ORDER BY startTime DESC")
    fun sessions(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM tb_session WHERE id = :id")
    suspend fun session(id: Long): SessionEntity?

    @Insert
    suspend fun insertSession(entity: SessionEntity): Long

    @Update
    suspend fun updateSession(entity: SessionEntity)

    @Query("UPDATE tb_session SET endTime = :endTime, durationMs = :durationMs, lineCount = :lineCount WHERE id = :id")
    suspend fun finishSession(
        id: Long,
        endTime: Long,
        durationMs: Long,
        lineCount: Int,
    )

    @Query("UPDATE tb_session SET audioPath = NULL, audioDeleted = 1 WHERE id = :id")
    suspend fun markAudioDeleted(id: Long)

    @Query("DELETE FROM tb_session WHERE id = :id")
    suspend fun deleteSession(id: Long)

    // ==================== 句子 ====================
    @Query("SELECT * FROM tb_line WHERE sessionId = :sessionId ORDER BY seq ASC")
    fun linesOf(sessionId: Long): Flow<List<LineEntity>>

    @Query("SELECT * FROM tb_line WHERE sessionId = :sessionId ORDER BY seq ASC")
    suspend fun linesSnapshot(sessionId: Long): List<LineEntity>

    @Insert
    suspend fun insertLine(entity: LineEntity): Long

    @Query("SELECT * FROM tb_line WHERE sessionId = :sessionId ORDER BY seq DESC LIMIT 1")
    suspend fun lastLine(sessionId: Long): LineEntity?

    @Query("SELECT COUNT(*) FROM tb_line WHERE sessionId = :sessionId")
    suspend fun lineCount(sessionId: Long): Int

    @Query(
        "UPDATE tb_line SET translatedText = :translatedText WHERE id = " +
            "(SELECT id FROM tb_line WHERE sessionId = :sessionId AND sourceText = :sourceText ORDER BY seq DESC LIMIT 1)",
    )
    suspend fun updateTranslation(
        sessionId: Long,
        sourceText: String,
        translatedText: String,
    )

    @Query("UPDATE tb_line SET isFavorite = :favorite WHERE id = :id")
    suspend fun setFavorite(
        id: Long,
        favorite: Boolean,
    )

    @Query("DELETE FROM tb_line WHERE sessionId = :sessionId")
    suspend fun deleteLinesOf(sessionId: Long)

    // ==================== 难词 ====================
    @Query("SELECT * FROM tb_vocab ORDER BY createdAt DESC")
    fun vocabWords(): Flow<List<VocabEntity>>

    @Query("SELECT * FROM tb_vocab WHERE mastered = 0 AND dueAt <= :now ORDER BY dueAt ASC")
    fun dueWords(now: Long): Flow<List<VocabEntity>>

    @Query("SELECT * FROM tb_vocab WHERE normalized = :normalized ORDER BY createdAt DESC LIMIT 1")
    suspend fun findWord(normalized: String): VocabEntity?

    @Insert
    suspend fun insertWord(entity: VocabEntity): Long

    @Update
    suspend fun updateWord(entity: VocabEntity)

    @Query("DELETE FROM tb_vocab WHERE id = :id")
    suspend fun deleteWord(id: Long)

    @Query("DELETE FROM tb_vocab")
    suspend fun deleteAllWords()
}
