package com.speakcharger.app

import android.content.Context
import android.media.AudioAttributes
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

            try {
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                tts?.setAudioAttributes(attrs)
            } catch (_: Exception) {}

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

    // ★ METHOD BARU: stop TTS yang sedang berbicara
    fun stop() {
        try { tts?.stop() } catch (_: Exception) {}
    }

    private fun speakInternal(text: String) {
        try {
            val params = android.os.Bundle()
            params.putInt(
                TextToSpeech.Engine.KEY_PARAM_STREAM,
                android.media.AudioManager.STREAM_ALARM
            )
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "u_${System.currentTimeMillis()}")
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
