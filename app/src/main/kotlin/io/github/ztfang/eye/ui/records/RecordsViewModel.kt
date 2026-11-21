package io.github.ztfang.eye.ui.records

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.ztfang.eye.domain.model.DictEntry
import io.github.ztfang.eye.domain.model.RecordSession
import io.github.ztfang.eye.domain.model.TranscriptLine
import io.github.ztfang.eye.domain.model.VocabEntry
import io.github.ztfang.eye.domain.repository.DictionaryRepository
import io.github.ztfang.eye.domain.repository.SettingsRepository
import io.github.ztfang.eye.domain.repository.TranscriptRepository
import io.github.ztfang.eye.domain.repository.VocabularyRepository
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 记录窗口 / 难词 / 复习的唯一 ViewModel：UI 只跟它打交道 */
@HiltViewModel
class RecordsViewModel
    @Inject
    constructor(
        private val transcriptRepository: TranscriptRepository,
        private val vocabularyRepository: VocabularyRepository,
        private val dictionaryRepository: DictionaryRepository,
        private val settingsRepository: SettingsRepository,
    ) : ViewModel() {
        val sessions: StateFlow<List<RecordSession>> =
            transcriptRepository.sessions.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

        val words: StateFlow<List<VocabEntry>> =
            vocabularyRepository.words.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

        val reminderEnabled: StateFlow<Boolean> =
            settingsRepository.reviewReminderEnabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

        /** 语音总占用（字节）：录音默认保留，所以这里给用户一个「一键清理语音」的入口 */
        val audioBytes: StateFlow<Long> =
            transcriptRepository.sessions
                .map { list ->
                    withContext(Dispatchers.IO) {
                        list.sumOf { session ->
                            session.audioPath?.let { path ->
                                java.io.File(path).takeIf { it.exists() }?.length() ?: 0L
                            } ?: 0L
                        }
                    }
                }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

        /** 待复习数量（入口按钮的小红点用） */
        val dueCount: StateFlow<Int> =
            vocabularyRepository.words
                .map { list ->
                    val now = System.currentTimeMillis()
                    list.count { it.isDue(now) }
                }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

        fun currentSessionId(): Long = transcriptRepository.currentSessionId()

        fun linesOf(sessionId: Long): Flow<List<TranscriptLine>> = transcriptRepository.linesOf(sessionId)

        fun dueWords(now: Long): Flow<List<VocabEntry>> = vocabularyRepository.dueWords(now)

        fun setReminderEnabled(enabled: Boolean) {
            viewModelScope.launch { settingsRepository.setReviewReminderEnabled(enabled) }
        }

        /** 一次清掉所有会话的语音，文字全部保留 */
        fun deleteAllAudio() {
            viewModelScope.launch {
                sessions.value.forEach { session ->
                    if (session.audioPath != null) transcriptRepository.deleteAudioOnly(session.id)
                }
            }
        }

        fun deleteSession(sessionId: Long) {
            viewModelScope.launch { transcriptRepository.deleteSession(sessionId, deleteText = true) }
        }

        fun deleteAudio(sessionId: Long) {
            viewModelScope.launch { transcriptRepository.deleteAudioOnly(sessionId) }
        }

        fun audioPathOf(session: RecordSession): String? = transcriptRepository.audioFileOf(session)

        suspend fun lookup(word: String): DictEntry? = dictionaryRepository.lookup(word)

        /**
         * 标记难词：自动抓取「所在句 + 前两句 + 后两句」语境。
         * 找不到行（例如老记录被删）时只存单词本身，不阻断标记。
         */
        fun markWord(
            word: String,
            displayWord: String,
            sessionId: Long,
            lineId: Long,
            phonetic: String,
            meaning: String,
            onResult: (Boolean) -> Unit,
        ) {
            viewModelScope.launch {
                val lines = runCatching { transcriptRepository.linesSnapshot(sessionId) }.getOrDefault(emptyList())
                val index = lines.indexOfFirst { it.id == lineId }
                val sentence = if (index >= 0) lines[index].sourceText else ""
                val before =
                    if (index > 0) {
                        lines.subList((index - 2).coerceAtLeast(0), index).joinToString("\n") { it.sourceText }
                    } else {
                        ""
                    }
                val after =
                    if (index >= 0 && index < lines.size - 1) {
                        lines.subList(index + 1, (index + 3).coerceAtMost(lines.size)).joinToString("\n") { it.sourceText }
                    } else {
                        ""
                    }
                val entry =
                    VocabEntry(
                        word = displayWord,
                        normalized = word.lowercase(),
                        phonetic = phonetic,
                        meaning = meaning,
                        sentence = sentence,
                        contextBefore = before,
                        contextAfter = after,
                        sessionId = sessionId,
                        lineId = lineId,
                        createdAt = System.currentTimeMillis(),
                        dueAt = io.github.ztfang.eye.domain.model.ReviewScheduler.initialDueAt(System.currentTimeMillis()),
                    )
                val id = vocabularyRepository.mark(entry)
                onResult(id > 0)
            }
        }

        fun grade(
            entry: VocabEntry,
            quality: Int,
            onDone: (VocabEntry) -> Unit = {},
        ) {
            viewModelScope.launch {
                val updated = vocabularyRepository.grade(entry, quality)
                onDone(updated)
            }
        }

        fun deleteWord(entry: VocabEntry) {
            viewModelScope.launch { vocabularyRepository.delete(entry) }
        }
    }
