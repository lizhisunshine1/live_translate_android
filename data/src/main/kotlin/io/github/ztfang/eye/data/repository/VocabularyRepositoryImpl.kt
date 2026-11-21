package io.github.ztfang.eye.data.repository

import io.github.ztfang.eye.data.local.dao.RecordDao
import io.github.ztfang.eye.data.local.entity.VocabEntity
import io.github.ztfang.eye.domain.model.ReviewScheduler
import io.github.ztfang.eye.domain.model.VocabEntry
import io.github.ztfang.eye.domain.repository.VocabularyRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** 难词本实现：写入时预置 SM-2 状态，复习时按质量分重排下一次到期时间 */
@Singleton
class VocabularyRepositoryImpl
    @Inject
    constructor(
        private val dao: RecordDao,
    ) : VocabularyRepository {
        override val words: Flow<List<VocabEntry>> = dao.vocabWords().map { list -> list.map { it.toDomain() } }

        override fun dueWords(now: Long): Flow<List<VocabEntry>> = dao.dueWords(now).map { list -> list.map { it.toDomain() } }

        override suspend fun mark(entry: VocabEntry): Long {
            if (hasWord(entry.normalized)) return DUPLICATE
            val entity =
                entry.toEntity().copy(
                    dueAt = if (entry.dueAt > 0) entry.dueAt else ReviewScheduler.initialDueAt(entry.createdAt),
                    ease = if (entry.ease > 0) entry.ease else 2.5,
                )
            return dao.insertWord(entity)
        }

        override suspend fun grade(
            entry: VocabEntry,
            quality: Int,
        ): VocabEntry {
            val now = System.currentTimeMillis()
            val next = ReviewScheduler.schedule(entry, quality, now)
            dao.updateWord(next.toEntity())
            return next
        }

        override suspend fun delete(entry: VocabEntry) {
            dao.deleteWord(entry.id)
        }

        override suspend fun deleteAll() {
            dao.deleteAllWords()
        }

        override suspend fun hasWord(normalized: String): Boolean = dao.findWord(normalized) != null

        override suspend fun findWord(normalized: String): VocabEntry? = dao.findWord(normalized)?.toDomain()

        private fun VocabEntity.toDomain(): VocabEntry =
            VocabEntry(
                id = id,
                word = word,
                normalized = normalized,
                phonetic = phonetic,
                meaning = meaning,
                sentence = sentence,
                contextBefore = contextBefore,
                contextAfter = contextAfter,
                sessionId = sessionId,
                lineId = lineId,
                createdAt = createdAt,
                dueAt = dueAt,
                intervalDays = intervalDays,
                ease = ease,
                repetitions = repetitions,
                lapses = lapses,
                mastered = mastered,
            )

        private fun VocabEntry.toEntity(): VocabEntity =
            VocabEntity(
                id = id,
                word = word,
                normalized = normalized,
                phonetic = phonetic,
                meaning = meaning,
                sentence = sentence,
                contextBefore = contextBefore,
                contextAfter = contextAfter,
                sessionId = sessionId,
                lineId = lineId,
                createdAt = createdAt,
                dueAt = dueAt,
                intervalDays = intervalDays,
                ease = ease,
                repetitions = repetitions,
                lapses = lapses,
                mastered = mastered,
            )

        companion object {
            /** 已存在同词：返回负数，UI 直接提示「已在难词本」 */
            const val DUPLICATE = -1L
        }
    }
