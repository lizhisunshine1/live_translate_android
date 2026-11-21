package io.github.ztfang.eye.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** 录音会话表：一次「打开浮窗 → 关闭浮窗」对应一条 */
@Entity(
    tableName = "tb_session",
    indices = [Index("startTime")],
)
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionKey: String,
    val startTime: Long,
    val endTime: Long = 0,
    val sourceLanguage: String,
    val targetLanguage: String,
    val lineCount: Int = 0,
    val audioPath: String? = null,
    val audioDeleted: Boolean = false,
    val durationMs: Long = 0,
)

/** 会话内逐句文本表 */
@Entity(
    tableName = "tb_line",
    indices = [Index("sessionId"), Index("timestamp")],
)
data class LineEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val seq: Int,
    val timestamp: Long,
    val startMs: Long = 0,
    val endMs: Long = 0,
    val sourceText: String,
    val translatedText: String = "",
    val isFavorite: Boolean = false,
)

/** 难词表（含 SM-2 复习状态） */
@Entity(
    tableName = "tb_vocab",
    indices = [Index("normalized"), Index("dueAt")],
)
data class VocabEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val word: String,
    val normalized: String,
    val phonetic: String = "",
    val meaning: String = "",
    val sentence: String = "",
    val contextBefore: String = "",
    val contextAfter: String = "",
    val sessionId: Long = 0,
    val lineId: Long = 0,
    val createdAt: Long,
    val dueAt: Long = 0,
    val intervalDays: Int = 0,
    val ease: Double = 2.5,
    val repetitions: Int = 0,
    val lapses: Int = 0,
    val mastered: Boolean = false,
)
