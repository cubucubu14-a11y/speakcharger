package com.speakcharger.app

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

class TtsManager(context: Context) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var isReady = false
    private val queue = mutableListOf<String>()

    init {
        tts = TextToSpeech(context.applicationContext, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale("id", "ID"))
            if (result == TextToSpeech.LANG_MISSING_DATA ||
                result == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts?.setLanguage(Locale.US)
            }
            tts?.setSpeechRate(0.9f)
            tts?.setPitch(1.0f)
            isReady = true

            val toPlay = queue.toList()
            queue.clear()
            toPlay.forEach { speakInternal(it) }
        }
    }

    fun speak(text: String) {
        if (isReady) speakInternal(text) else queue.add(text)
    }

    private fun speakInternal(text: String) {
        try {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "u_${System.currentTimeMillis()}")
        } catch (_: Exception) {}
    }

    fun shutdown() {
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Exception) {}
        tts = null
        isReady = false
        queue.clear()
    }
}
