package io.github.ztfang.eye.ui.records

import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File

/**
 * 语音分段播放器：按句子的 [startMs, endMs] 只播这一段。
 *
 * 只用一个 MediaPlayer 实例复用（避免每句新建播放器导致的延迟/资源抖动），
 * 到 endMs 由 Handler 主动 pause，保证不会把下一句也放出来。
 */
class AudioSegmentPlayer {
    private var player: MediaPlayer? = null
    private var currentPath: String? = null
    private val handler = Handler(Looper.getMainLooper())
    private var stopRunnable: Runnable? = null

    /** 是否正在播放（UI 状态用，非线程安全） */
    var isPlaying: Boolean = false
        private set

    /** 播放 [path] 中 [startMs] 到 [endMs] 的一段；endMs<=startMs 时播到结尾 */
    fun play(
        path: String,
        startMs: Long,
        endMs: Long,
        onEnd: () -> Unit = {},
    ) {
        stopInternal()
        if (!File(path).exists()) return
        runCatching {
            if (player == null || currentPath != path) {
                player?.release()
                player =
                    MediaPlayer().apply {
                        setDataSource(path)
                        prepare()
                    }
                currentPath = path
            }
            val mp = player ?: return
            mp.setOnCompletionListener { isPlaying = false; onEnd() }
            mp.seekTo(startMs.toInt().coerceAtLeast(0))
            mp.start()
            isPlaying = true
            if (endMs > startMs) {
                val duration = (endMs - startMs).coerceAtLeast(50)
                stopRunnable =
                    Runnable {
                        runCatching { player?.pause() }
                        isPlaying = false
                        onEnd()
                    }.also { handler.postDelayed(it, duration) }
            }
        }.onFailure { Log.w(TAG, "分段播放失败", it) }
    }

    fun playAll(path: String) {
        play(path, 0, 0)
    }

    fun stop() {
        stopInternal()
        isPlaying = false
    }

    private fun stopInternal() {
        stopRunnable?.let { handler.removeCallbacks(it) }
        stopRunnable = null
        runCatching {
            player?.let {
                if (it.isPlaying) it.pause()
            }
        }
    }

    fun release() {
        stopInternal()
        runCatching { player?.release() }
        player = null
        currentPath = null
        isPlaying = false
    }

    companion object {
        private const val TAG = "AudioSegmentPlayer"
    }
}
