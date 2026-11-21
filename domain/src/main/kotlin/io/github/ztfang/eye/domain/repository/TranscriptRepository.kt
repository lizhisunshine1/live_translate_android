package io.github.ztfang.eye.domain.repository

import io.github.ztfang.eye.domain.model.RecordSession
import io.github.ztfang.eye.domain.model.TranscriptLine
import io.github.ztfang.eye.domain.model.VocabEntry
import kotlinx.coroutines.flow.Flow

/**
 * 录音记录仓库：会话、逐句文本、语音文件。
 *
 * 约定：
 *  - 文本（原文 / 译文）只在用户明确删除记录时才会消失；
 *  - 语音默认在会话结束后删除（除非用户打开「保留语音」），以节省空间；
 *  - 所有内容同时落盘到 records 目录（session.json + audio.wav），DB 只是索引。
 */
interface TranscriptRepository {
    /** 全部会话，新的在前 */
    val sessions: Flow<List<RecordSession>>

    /** 某一个会话的所有句子 */
    fun linesOf(sessionId: Long): Flow<List<TranscriptLine>>

    /** 当前正在录音的会话 id；0 表示没有进行中的会话 */
    fun currentSessionId(): Long

    /** 开始新会话：创建行、准备语音文件（失败不影响字幕功能） */
    suspend fun startSession(
        sourceLanguage: String,
        targetLanguage: String,
    ): Long

    /** 结束当前会话：补头、写 session.json、按设置决定是否删除语音 */
    suspend fun finishSession()

    /** 写入一帧 16k / 16bit / 单声道 PCM（同步、非挂起，供录音循环调用） */
    fun appendAudio(
        frame: ShortArray,
        length: Int,
    )

    /** 当前录音已写入的语音时长（毫秒） */
    fun currentAudioPositionMs(): Long

    /** 一句话收口时登记（译文稍后回填）；返回该行在会话内的顺序号 */
    fun addLine(
        sourceText: String,
        audioEndMs: Long,
    )

    /** 译文回填：按原文匹配最近一条未翻译的行 */
    fun updateLineTranslation(
        sourceText: String,
        translatedText: String,
    )

    /** 取某会话全部句子（挂起作用，供难词语境抓取） */
    suspend fun linesSnapshot(sessionId: Long): List<TranscriptLine>

    /** 删除整个记录（文本 + 语音文件）。deleteText=true 时不可恢复 */
    suspend fun deleteSession(
        sessionId: Long,
        deleteText: Boolean,
    )

    /** 只删语音，保留文本 */
    suspend fun deleteAudioOnly(sessionId: Long)

    /** 语音文件是否存在 */
    fun audioFileOf(session: RecordSession): String?
}

/** 难词本 + 复习排程 */
interface VocabularyRepository {
    val words: Flow<List<VocabEntry>>

    /** 到期待复习（含新词） */
    fun dueWords(now: Long): Flow<List<VocabEntry>>

    /**
     * 标记难词。已存在同词（且未掌握）时返回已有条目 id 的负数表示重复。
     */
    suspend fun mark(entry: VocabEntry): Long

    suspend fun grade(
        entry: VocabEntry,
        quality: Int,
    ): VocabEntry

    suspend fun delete(entry: VocabEntry)

    suspend fun deleteAll()

    suspend fun hasWord(normalized: String): Boolean

    /** 按单词查一条（词典弹窗显示「已在难词本」用） */
    suspend fun findWord(normalized: String): VocabEntry?
}

/** 离线英汉词典 */
interface DictionaryRepository {
    suspend fun lookup(word: String): io.github.ztfang.eye.domain.model.DictEntry?
}
