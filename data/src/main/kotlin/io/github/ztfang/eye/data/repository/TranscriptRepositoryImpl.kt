package io.github.ztfang.eye.data.repository

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.ztfang.eye.data.local.dao.RecordDao
import io.github.ztfang.eye.data.local.entity.LineEntity
import io.github.ztfang.eye.data.local.entity.SessionEntity
import io.github.ztfang.eye.data.record.PcmWavWriter
import io.github.ztfang.eye.domain.model.RecordSession
import io.github.ztfang.eye.domain.model.TranscriptLine
import io.github.ztfang.eye.domain.repository.TranscriptRepository
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

/**
 * 记录仓库实现。
 *
 * 双写策略：DB 负责查询与索引，records 目录负责「看得见的落盘」——
 *   records/<yyyyMMdd-hhmmss>/audio.wav   语音（默认会话结束即删）
 *   records/<yyyyMMdd-hhmmss>/session.json 文本快照（原文 + 译文 + 时间轴）
 * 这样即使 App 被卸载/数据库损坏，文本记录仍能用文本编辑器打开。
 *
 * 并发：录音循环直接调 [appendAudio]（非挂起、只碰文件句柄）；
 * 数据库写全部排进单消费者队列，保证「句子顺序」与「译文回填」不乱序。
 */
@Singleton
class TranscriptRepositoryImpl
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val dao: RecordDao,
    ) : TranscriptRepository {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** 串行 DB 写队列：录音线程/主线程都只往这里塞任务 */
        private val dbQueue = Channel<(suspend () -> Unit)?>(Channel.UNLIMITED)

        @Volatile private var workerStarted = false

        @Volatile private var sessionId: Long = 0L

        @Volatile private var sessionKey: String = ""

        @Volatile private var sessionStart: Long = 0L

        @Volatile private var sourceLang: String = "en"

        @Volatile private var targetLang: String = "zh"

        @Volatile private var writer: PcmWavWriter? = null

        @Volatile private var audioFile: File? = null

        private val seqCounter = AtomicInteger(0)

        @Volatile private var lastLineEndMs: Long = 0L

        /** 最近一次结束的会话，用于接收「停录后才回来的译文」 */
        @Volatile private var lastFinishedSessionId: Long = 0L

        @Volatile private var lastFinishedAt: Long = 0L

        private val recordsRoot: File
            get() {
                val base = context.getExternalFilesDir(null) ?: context.filesDir
                return File(base, "records")
            }

        private fun ensureWorker() {
            if (workerStarted) return
            synchronized(this) {
                if (workerStarted) return
                workerStarted = true
                scope.launch {
                    for (task in dbQueue) {
                        if (task == null) break
                        runCatching { task() }.onFailure { Log.w(TAG, "记录写入失败", it) }
                    }
                }
            }
        }

        private fun enqueue(task: suspend () -> Unit) {
            ensureWorker()
            dbQueue.trySend(task)
        }

        /** 等队列里已排队的 DB 写全部落地（结束会话前调用，避免 lineCount / 译文丢尾巴） */
        private suspend fun awaitQueue() {
            val done = CompletableDeferred<Unit>()
            enqueue { done.complete(Unit) }
            runCatching { withTimeout(5000) { done.await() } }
        }

        // ==================== 会话 ====================

        override val sessions: Flow<List<RecordSession>> =
            dao
                .sessions()
                .map { list -> list.map { it.toDomain() } }

        override fun linesOf(sessionId: Long): Flow<List<TranscriptLine>> = dao.linesOf(sessionId).map { list -> list.map { it.toDomain() } }

        override fun currentSessionId(): Long = sessionId

        override suspend fun startSession(
            sourceLanguage: String,
            targetLanguage: String,
        ): Long {
            // 防御：上一个会话还没收尾就开新的（例如浮窗被杀），先归档
            if (sessionId > 0) finishSession()

            val now = System.currentTimeMillis()
            val key = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(now))
            val entity =
                SessionEntity(
                    sessionKey = key,
                    startTime = now,
                    sourceLanguage = sourceLanguage,
                    targetLanguage = targetLanguage,
                )
            val id = dao.insertSession(entity)
            sessionId = id
            sessionKey = key
            sessionStart = now
            sourceLang = sourceLanguage
            targetLang = targetLanguage
            seqCounter.set(0)
            lastLineEndMs = 0

            val dir = File(recordsRoot, key)
            val wav = File(dir, "audio.wav")
            val w = PcmWavWriter(wav)
            if (w.open()) {
                writer = w
                audioFile = wav
                dao.updateSession(entity.copy(id = id, audioPath = wav.absolutePath))
                Log.i(TAG, "会话开始 id=$id, 语音落盘: ${wav.absolutePath}")
            } else {
                writer = null
                audioFile = null
                Log.w(TAG, "语音文件创建失败，本次只记录文本: ${wav.absolutePath}")
            }
            return id
        }

        override suspend fun finishSession() {
            val id = sessionId
            if (id <= 0) return
            sessionId = 0
            // 停录瞬间可能还有句子/译文排在写队列里：先等它们落库再统计条数
            awaitQueue()
            val startedAt = sessionStart
            val wav = audioFile
            val w = writer
            writer = null
            audioFile = null
            w?.close()

            val end = System.currentTimeMillis()
            lastFinishedSessionId = id
            lastFinishedAt = end
            val count = dao.lineCount(id)
            val durationMs = (end - startedAt).coerceAtLeast(0)
            val session = dao.session(id)
            val audioDurationMs = wav?.let { PcmWavWriter.readDurationMs(it) } ?: 0
            dao.finishSession(id, end, durationMs, count)
            // 结束时间要一并落到 json 快照里：先把更新后的 session 算出来再用
            val finished =
                session?.copy(
                    endTime = end,
                    durationMs = durationMs,
                    lineCount = count,
                )
            if (finished != null) dao.updateSession(finished)

            // 写文本快照（音轨已补头，时长取实际语音长度）
            val lines = dao.linesSnapshot(id)
            writeSessionJson(id, finished, lines, audioDurationMs)

            // 语音默认保留：删除动作只发生在用户主动「管理 → 删除」时，
            // 且那时默认只删语音、不动文字（文字是难词/复习的原料）。
            Log.i(TAG, "会话结束 id=$id, 语音已保留: ${wav?.absolutePath} (${audioDurationMs}ms)")
        }

        // ==================== 语音 ====================

        override fun appendAudio(
            frame: ShortArray,
            length: Int,
        ) {
            writer?.write(frame, length)
        }

        override fun currentAudioPositionMs(): Long = writer?.durationMs ?: 0L

        // ==================== 句子 ====================

        override fun addLine(
            sourceText: String,
            audioEndMs: Long,
        ) {
            val sid = sessionId
            if (sid <= 0) return
            val seq = seqCounter.getAndIncrement()
            val startMs = lastLineEndMs
            lastLineEndMs = audioEndMs.coerceAtLeast(startMs)
            val now = System.currentTimeMillis()
            enqueue {
                dao.insertLine(
                    LineEntity(
                        sessionId = sid,
                        seq = seq,
                        timestamp = now,
                        startMs = startMs,
                        endMs = lastLineEndMs,
                        sourceText = sourceText,
                    ),
                )
            }
        }

        override fun updateLineTranslation(
            sourceText: String,
            translatedText: String,
        ) {
            // 停录瞬间的最后一两句译文常常晚到：会话已收尾就回填到「刚结束的那个会话」
            // （5 分钟窗口内），否则这几句在记录里永远是「翻译中…」。
            val sid =
                sessionId.takeIf { it > 0 }
                    ?: lastFinishedSessionId.takeIf { System.currentTimeMillis() - lastFinishedAt < LATE_TRANSLATION_WINDOW_MS }
                    ?: return
            enqueue { dao.updateTranslation(sid, sourceText, translatedText) }
            if (sid != sessionId) {
                // 会话 json 已写过一次，晚到的译文要再刷新一遍
                scope.launch {
                    delay(2000)
                    val session = dao.session(sid)
                    if (session != null) writeSessionJson(sid, session, dao.linesSnapshot(sid), 0)
                }
            }
        }

        override suspend fun linesSnapshot(sessionId: Long): List<TranscriptLine> = dao.linesSnapshot(sessionId).map { it.toDomain() }

        // ==================== 删除 ====================

        override suspend fun deleteSession(
            sessionId: Long,
            deleteText: Boolean,
        ) {
            val session = dao.session(sessionId)
            if (session != null) {
                dao.deleteLinesOf(sessionId)
                dao.deleteSession(sessionId)
                runCatching { File(recordsRoot, session.sessionKey).deleteRecursively() }
                Log.i(TAG, "记录已删除 id=$sessionId (deleteText=$deleteText)")
            }
        }

        override suspend fun deleteAudioOnly(sessionId: Long) {
            val session = dao.session(sessionId) ?: return
            val file = session.audioPath?.let { File(it) } ?: File(File(recordsRoot, session.sessionKey), "audio.wav")
            runCatching { file.delete() }
            dao.markAudioDeleted(sessionId)
            // 文本快照里同步标记语音已删除，避免用户看着 json 以为还有音频
            val lines = dao.linesSnapshot(sessionId)
            writeSessionJson(sessionId, session.copy(audioPath = null), lines, 0)
            Log.i(TAG, "仅删除语音 id=$sessionId")
        }

        override fun audioFileOf(session: RecordSession): String? {
            val path = session.audioPath ?: return null
            val f = File(path)
            return if (f.exists() && f.length() > 44) f.absolutePath else null
        }

        // ==================== 落盘快照 ====================

        private suspend fun writeSessionJson(
            id: Long,
            session: SessionEntity?,
            lines: List<LineEntity>,
            audioDurationMs: Long,
        ) {
            val key = session?.sessionKey ?: sessionKey
            if (key.isBlank()) return
            runCatching {
                val dir = File(recordsRoot, key)
                dir.mkdirs()
                val json =
                    JSONObject().apply {
                        put("sessionKey", key)
                        put("startTime", session?.startTime ?: sessionStart)
                        put("endTime", session?.endTime ?: 0L)
                        put("durationMs", session?.durationMs ?: 0L)
                        put("audioDurationMs", audioDurationMs)
                        put("sourceLanguage", session?.sourceLanguage ?: sourceLang)
                        put("targetLanguage", session?.targetLanguage ?: targetLang)
                        val audioName = if (session?.audioPath != null) "audio.wav" else ""
                        put("audio", audioName)
                        put(
                            "lines",
                            JSONArray().apply {
                                lines.forEach { line ->
                                    put(
                                        JSONObject().apply {
                                            put("seq", line.seq)
                                            put("time", line.timestamp)
                                            put("startMs", line.startMs)
                                            put("endMs", line.endMs)
                                            put("source", line.sourceText)
                                            put("translation", line.translatedText)
                                        },
                                    )
                                }
                            },
                        )
                    }
                File(dir, "session.json").writeText(json.toString(2), Charsets.UTF_8)
            }.onFailure { Log.w(TAG, "session.json 写入失败", it) }
        }

        private fun SessionEntity.toDomain(): RecordSession =
            RecordSession(
                id = id,
                sessionKey = sessionKey,
                startTime = startTime,
                endTime = endTime,
                sourceLanguage = sourceLanguage,
                targetLanguage = targetLanguage,
                lineCount = lineCount,
                audioPath = audioPath,
                audioDeleted = audioDeleted || audioPath == null,
                durationMs = durationMs,
            )

        private fun LineEntity.toDomain(): TranscriptLine =
            TranscriptLine(
                id = id,
                sessionId = sessionId,
                seq = seq,
                timestamp = timestamp,
                startMs = startMs,
                endMs = endMs,
                sourceText = sourceText,
                translatedText = translatedText,
                isFavorite = isFavorite,
            )

        companion object {
            private const val TAG = "TranscriptRepository"

            /** 会话结束后多久内回来的译文还算这个会话的 */
            private const val LATE_TRANSLATION_WINDOW_MS = 5 * 60 * 1000L
        }
    }
