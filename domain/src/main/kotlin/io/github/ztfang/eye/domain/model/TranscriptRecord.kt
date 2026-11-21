package io.github.ztfang.eye.domain.model

/**
 * 一次录音会话（从打开悬浮字幕开始采集，到停止采集为止）。
 *
 * 会话是「记录」的第一层：一句话的原文/译文、以及它对应的那一段语音，
 * 都挂在一个会话下面，这样记录窗口才能按「本次 / 以往」回看。
 */
data class RecordSession(
    val id: Long = 0,
    /** 目录名，同时也是记录文件夹里子目录的名字，如 20261121-193015 */
    val sessionKey: String,
    val startTime: Long,
    /** 会话结束时间；仍在录音时为 0 */
    val endTime: Long = 0,
    val sourceLanguage: String,
    val targetLanguage: String,
    val lineCount: Int = 0,
    /** 语音文件绝对路径；语音已删除时为 null */
    val audioPath: String? = null,
    val audioDeleted: Boolean = false,
    val durationMs: Long = 0,
) {
    val isOngoing: Boolean get() = endTime <= 0

    /** 会话时长：录音中按「现在」算，方便列表显示 */
    fun effectiveDurationMs(now: Long): Long = if (endTime > 0) durationMs else (now - startTime).coerceAtLeast(0)
}

/** 会话里的一句话（一行字幕） */
data class TranscriptLine(
    val id: Long = 0,
    val sessionId: Long,
    /** 会话内顺序号，从 0 开始 */
    val seq: Int,
    val timestamp: Long,
    /** 相对会话开始的语音位置（毫秒），用于按句回放 */
    val startMs: Long = 0,
    val endMs: Long = 0,
    val sourceText: String,
    val translatedText: String = "",
    val isFavorite: Boolean = false,
)

/**
 * 难词条目。
 *
 * 除了单词本身，还完整保存「当时不会的那句话 + 前两句 + 后两句」，
 * 复习时按句意回忆，比孤立背单词有效。
 */
data class VocabEntry(
    val id: Long = 0,
    val word: String,
    /** 小写化的检索键，用于判重 */
    val normalized: String,
    val phonetic: String = "",
    val meaning: String = "",
    /** 标记该词时所在的句子 */
    val sentence: String = "",
    /** 语境：该句之前的句子（按顺序，最多两句，用换行拼接） */
    val contextBefore: String = "",
    /** 语境：该句之后的句子（最多两句，用换行拼接） */
    val contextAfter: String = "",
    val sessionId: Long = 0,
    val lineId: Long = 0,
    val createdAt: Long,
    // ===== 遗忘曲线（SM-2）状态 =====
    /** 下次应复习时间 */
    val dueAt: Long = 0,
    /** 当前间隔（天）；0 表示尚未开始复习 */
    val intervalDays: Int = 0,
    /** 难度系数，SM-2 默认 2.5 */
    val ease: Double = 2.5,
    /** 连续答对次数 */
    val repetitions: Int = 0,
    /** 遗忘次数（答错次数） */
    val lapses: Int = 0,
    /** 已掌握（长期不再提醒） */
    val mastered: Boolean = false,
) {
    /** 是否到期（mastered 的不再提醒） */
    fun isDue(now: Long): Boolean = !mastered && dueAt in 1..now
}
