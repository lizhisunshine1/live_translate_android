package io.github.ztfang.eye.data.repository

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.ztfang.eye.domain.model.DictEntry
import io.github.ztfang.eye.domain.repository.DictionaryRepository
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 离线英汉词典（assets/dict/<首字母>.tsv，由 ECDICT 词库裁剪而来）。
 *
 * 为什么按首字母分片：整本词典常驻内存要十几 MB，分片后每次只加载一个文件、
 * 只保留最近用过的几个分片，滚动浏览长句时内存占用稳定。
 * 每个分片是纯文本 `单词\t音标\t释义`，无需 JSON 解析。
 */
@Singleton
class DictionaryRepositoryImpl
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : DictionaryRepository {
        private val mutex = Mutex()

        /** 已加载的分片缓存（最多 8 个首字母桶） */
        private val buckets = object : LinkedHashMap<String, Map<String, DictEntry>>(8, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Map<String, DictEntry>>?): Boolean = size > 8
        }

        override suspend fun lookup(word: String): DictEntry? =
            withContext(Dispatchers.IO) {
                val normalized = normalize(word)
                if (normalized.isEmpty()) return@withContext null

                findExact(normalized)?.let { return@withContext it }

                // 词形还原：studies/studied/running/bigger 这类变形在听力里很常见
                for (candidate in lemmaCandidates(normalized)) {
                    findExact(candidate)?.let { return@withContext it.copy(lematized = true) }
                }
                null
            }

        private suspend fun findExact(word: String): DictEntry? {
            val bucket = bucketOf(word) ?: return null
            val map = loadBucket(bucket)
            return map[word]
        }

        private suspend fun loadBucket(bucket: String): Map<String, DictEntry> =
            mutex.withLock {
                buckets[bucket]?.let { return it }
                val map = HashMap<String, DictEntry>(4096)
                runCatching {
                    context.assets.open("dict/$bucket.tsv").bufferedReader(Charsets.UTF_8).useLines { lines ->
                        lines.forEach { raw ->
                            if (raw.isEmpty()) return@forEach
                            val parts = raw.split('\t')
                            if (parts.size < 2) return@forEach
                            val w = parts[0].trim()
                            if (w.isEmpty()) return@forEach
                            val phonetic = if (parts.size > 1) parts[1].trim() else ""
                            val meaning = if (parts.size > 2) parts[2].trim() else ""
                            map[w] = DictEntry(word = w, phonetic = phonetic, translation = meaning)
                        }
                    }
                }.onFailure { Log.w(TAG, "词典分片加载失败: $bucket", it) }
                buckets[bucket] = map
                map
            }

        private fun bucketOf(word: String): String? {
            val c = word.firstOrNull()?.lowercaseChar() ?: return null
            return if (c in 'a'..'z') c.toString() else null
        }

        private fun normalize(raw: String): String {
            val sb = StringBuilder(raw.length)
            raw.lowercase(Locale.US).forEach { c ->
                when {
                    c.isLetter() -> sb.append(c)
                    c == '\'' || c == '-' -> sb.append(c)
                }
            }
            // 去掉首尾的连接符（"well-" / "'cause"）
            return sb.toString().trim('\'', '-')
        }

        /** 生成可能的原形候选，按「越像原形越靠前」排序 */
        private fun lemmaCandidates(word: String): List<String> {
            val out = ArrayList<String>(10)
            fun add(s: String) {
                if (s.length >= 2 && s != word && s !in out) out.add(s)
            }
            fun undouble(s: String): String =
                if (s.length >= 3 && s[s.length - 1] == s[s.length - 2]) s.dropLast(1) else s

            when {
                word.endsWith("ies") && word.length > 4 -> add(word.dropLast(3) + "y")
                word.endsWith("ves") && word.length > 4 -> {
                    add(word.dropLast(3) + "f")
                    add(word.dropLast(3) + "fe")
                }
                word.endsWith("es") && word.length > 3 -> {
                    add(word.dropLast(2))
                    add(word.dropLast(1))
                }
                word.endsWith("s") && !word.endsWith("ss") && word.length > 3 -> add(word.dropLast(1))
            }

            if (word.endsWith("ied") && word.length > 4) add(word.dropLast(3) + "y")
            if (word.endsWith("ed") && word.length > 3) {
                add(word.dropLast(2))
                add(word.dropLast(1))
                add(undouble(word.dropLast(2)))
            }
            if (word.endsWith("ing") && word.length > 5) {
                add(word.dropLast(3))
                add(word.dropLast(3) + "e")
                add(undouble(word.dropLast(3)))
            }
            if (word.endsWith("er") && word.length > 4) {
                add(word.dropLast(2))
                add(word.dropLast(1))
                add(undouble(word.dropLast(2)))
            }
            if (word.endsWith("est") && word.length > 5) {
                add(word.dropLast(3))
                add(word.dropLast(2))
                add(undouble(word.dropLast(3)))
            }
            if (word.endsWith("ly") && word.length > 4) add(word.dropLast(2))
            if (word.endsWith("'s")) add(word.dropLast(2))
            return out
        }

        companion object {
            private const val TAG = "DictionaryRepository"
        }
    }
