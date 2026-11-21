package io.github.ztfang.eye.data.record

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 流式 WAV 写入器（16kHz / 16bit / 单声道）。
 *
 * 录音循环边读边写：先占位 44 字节标准头，录完再回填长度。
 * 崩溃/被杀时文件头长度会是 0，但数据区仍在——记录窗口读不出时长时按数据区长度兜底。
 *
 * 线程安全：录音线程写、UI 线程关，全部走锁。
 */
class PcmWavWriter(
    private val file: File,
    private val sampleRate: Int = SAMPLE_RATE,
) {
    private val lock = Any()
    private var raf: RandomAccessFile? = null
    private var dataBytes: Long = 0
    private var closed = false

    /** 已写入的采样点数（16bit 单声道，1 采样 = 2 字节） */
    val samplesWritten: Long
        get() = synchronized(lock) { dataBytes / 2 }

    /** 已写入时长（毫秒） */
    val durationMs: Long
        get() = samplesWritten * 1000L / sampleRate

    fun open(): Boolean =
        synchronized(lock) {
            if (raf != null) return true
            runCatching {
                file.parentFile?.mkdirs()
                val out = RandomAccessFile(file, "rw")
                out.setLength(0)
                out.write(WAV_HEADER_PLACEHOLDER)
                raf = out
                dataBytes = 0
                closed = false
            }.isSuccess
        }

    fun write(
        samples: ShortArray,
        length: Int,
    ) {
        if (length <= 0) return
        synchronized(lock) {
            val out = raf ?: return
            if (closed) return
            runCatching {
                val buf = ByteBuffer.allocate(length * 2).order(ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until length) buf.putShort(samples[i])
                out.write(buf.array(), 0, length * 2)
                dataBytes += length * 2L
            }
        }
    }

    fun close() {
        synchronized(lock) {
            val out = raf ?: return
            raf = null
            closed = true
            runCatching {
                // 回填 RIFF / data 长度
                out.seek(0)
                out.write(buildHeader(dataBytes))
                out.close()
            }
        }
    }

    private fun buildHeader(payloadBytes: Long): ByteArray {
        val byteRate = sampleRate * CHANNELS * BITS_PER_SAMPLE / 8
        val blockAlign = CHANNELS * BITS_PER_SAMPLE / 8
        val buf = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray(Charsets.US_ASCII))
        buf.putInt((36 + payloadBytes).toInt())
        buf.put("WAVE".toByteArray(Charsets.US_ASCII))
        buf.put("fmt ".toByteArray(Charsets.US_ASCII))
        buf.putInt(16) // PCM fmt chunk size
        buf.putShort(1) // PCM
        buf.putShort(CHANNELS.toShort())
        buf.putInt(sampleRate)
        buf.putInt(byteRate)
        buf.putShort(blockAlign.toShort())
        buf.putShort(BITS_PER_SAMPLE.toShort())
        buf.put("data".toByteArray(Charsets.US_ASCII))
        buf.putInt(payloadBytes.toInt())
        return buf.array()
    }

    companion object {
        const val SAMPLE_RATE = 16000
        private const val CHANNELS = 1
        private const val BITS_PER_SAMPLE = 16
        private const val HEADER_SIZE = 44

        private val WAV_HEADER_PLACEHOLDER: ByteArray =
            ByteBuffer
                .allocate(HEADER_SIZE)
                .order(ByteOrder.LITTLE_ENDIAN)
                .apply {
                    put("RIFF".toByteArray(Charsets.US_ASCII))
                    putInt(36)
                    put("WAVE".toByteArray(Charsets.US_ASCII))
                    put("fmt ".toByteArray(Charsets.US_ASCII))
                    putInt(16)
                    putShort(1)
                    putShort(CHANNELS.toShort())
                    putInt(SAMPLE_RATE)
                    putInt(SAMPLE_RATE * CHANNELS * BITS_PER_SAMPLE / 8)
                    putShort((CHANNELS * BITS_PER_SAMPLE / 8).toShort())
                    putShort(BITS_PER_SAMPLE.toShort())
                    put("data".toByteArray(Charsets.US_ASCII))
                    putInt(0)
                }.array()

        /** 读取 WAV 的 data 长度（毫秒）；文件缺头/被截断时按文件长度兜底 */
        fun readDurationMs(file: File): Long {
            if (!file.exists() || file.length() <= HEADER_SIZE) return 0
            return runCatching {
                RandomAccessFile(file, "r").use { raf ->
                    raf.seek(40)
                    val dataLen = Integer.reverseBytes(raf.readInt())
                    val bytes = if (dataLen > 0) dataLen.toLong() else raf.length() - HEADER_SIZE
                    bytes * 1000L / (SAMPLE_RATE * 2L)
                }
            }.getOrDefault((file.length() - HEADER_SIZE) * 1000L / (SAMPLE_RATE * 2L))
        }
    }
}
