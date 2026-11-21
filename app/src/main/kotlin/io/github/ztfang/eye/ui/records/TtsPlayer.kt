package io.github.ztfang.eye.ui.records

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

/**
 * 轻量 TTS 发音器：优先英文语音，设备没装英文语音包时退化为任意可用语音。
 *
 * 为什么不用在线发音：需求是「离线词典」，读音也必须离线可用；
 * Android 系统自带 TTS 引擎（Google / 厂商引擎）通常已内置英文音库。
 */
class TtsPlayer(
    private val context: Context,
) {
    private var tts: TextToSpeech? = null
    private var ready = false
    private var pendingWord: String? = null

    fun speak(word: String) {
        val engine = tts
        if (engine == null || !ready) {
            pendingWord = word
            return
        }
        val actual =
            if (engine.isLanguageAvailable(Locale.US) >= TextToSpeech.LANG_AVAILABLE) {
                engine.language = Locale.US
                word
            } else {
                word
            }
        engine.speak(actual, TextToSpeech.QUEUE_FLUSH, null, "word")
    }

    fun init() {
        if (tts != null) return
        tts =
            TextToSpeech(context) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    val engine = tts ?: return@TextToSpeech
                    runCatching {
                        if (engine.isLanguageAvailable(Locale.US) >= TextToSpeech.LANG_AVAILABLE) {
                            engine.language = Locale.US
                        } else if (engine.isLanguageAvailable(Locale.UK) >= TextToSpeech.LANG_AVAILABLE) {
                            engine.language = Locale.UK
                        }
                        engine.setSpeechRate(0.92f)
                        ready = true
                        pendingWord?.let {
                            pendingWord = null
                            speak(it)
                        }
                    }.onFailure { Log.w(TAG, "TTS 初始化失败", it) }
                } else {
                    Log.w(TAG, "TTS 引擎不可用 status=$status")
                }
            }
    }

    fun release() {
        runCatching { tts?.stop() }
        runCatching { tts?.shutdown() }
        tts = null
        ready = false
    }

    companion object {
        private const val TAG = "TtsPlayer"
    }
}
