package com.speakcharger.app

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.provider.OpenableColumns
import android.speech.tts.TextToSpeech
import java.io.File
import java.util.Locale

class TtsManager(private val context: Context) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var isReady = false
    private var mediaPlayer: MediaPlayer? = null

    private val cacheDir: File = File(context.cacheDir, "tts_files").apply {
        if (!exists()) mkdirs()
    }
    private val customDir: File = File(context.filesDir, "custom_sounds").apply {
        if (!exists()) mkdirs()
    }

    private val TEXTS = mapOf(
        5 to "Baterai lima persen",
        10 to "Baterai sepuluh persen",
        15 to "Baterai lima belas persen",
        20 to "Baterai dua puluh persen",
        25 to "Baterai dua puluh lima persen",
        30 to "Baterai tiga puluh persen",
        35 to "Baterai tiga puluh lima persen",
        40 to "Baterai empat puluh persen",
        45 to "Baterai empat puluh lima persen",
        50 to "Baterai lima puluh persen",
        55 to "Baterai lima puluh lima persen",
        60 to "Baterai enam puluh persen",
        65 to "Baterai enam puluh lima persen",
        70 to "Baterai tujuh puluh persen",
        75 to "Baterai tujuh puluh lima persen",
        80 to "Baterai delapan puluh persen",
        85 to "Baterai delapan puluh lima persen",
        90 to "Baterai sembilan puluh persen",
        95 to "Baterai sembilan puluh lima persen",
        100 to "Baterai full, cabut kabel"
    )

    init {
        tts = TextToSpeech(context.applicationContext, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val r = tts?.setLanguage(Locale("id", "ID"))
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts?.setLanguage(Locale.US)
            }
            tts?.setSpeechRate(0.9f)
            tts?.setPitch(1.0f)
            isReady = true
        }
    }

    fun hasCustomSound(pct: Int): Boolean = getCustomFile(pct) != null

    private fun getCustomFile(pct: Int): File? {
        val exts = listOf("mp3", "wav", "m4a", "ogg", "aac", "3gp")
        for (ext in exts) {
            val f = File(customDir, "$pct.$ext")
            if (f.exists() && f.length() > 0) return f
        }
        return null
    }

    fun saveCustomSound(pct: Int, uri: Uri): Boolean {
        return try {
            deleteCustomSound(pct)
            val displayName = getDisplayName(uri) ?: "sound.mp3"
            val ext = displayName.substringAfterLast('.', "mp3").lowercase()
                .let { if (it.length > 5) "mp3" else it }
            val dest = File(customDir, "$pct.$ext")
            context.contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            dest.exists() && dest.length() > 0
        } catch (_: Exception) { false }
    }

    fun deleteCustomSound(pct: Int) {
        try {
            customDir.listFiles()?.forEach { f ->
                if (f.nameWithoutExtension == pct.toString()) f.delete()
            }
        } catch (_: Exception) {}
    }

    private fun getDisplayName(uri: Uri): String? {
        var name: String? = null
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) name = c.getString(idx)
            }
        } catch (_: Exception) {}
        return name
    }

    fun fileFor(pct: Int): File = File(cacheDir, "baterai_$pct.wav")

    fun renderAllToCache(onDone: () -> Unit) {
        if (!isReady) {
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                renderAllToCache(onDone)
            }, 500)
            return
        }
        Thread {
            for ((pct, text) in TEXTS) {
                val file = fileFor(pct)
                if (file.exists()) continue
                try {
                    val params = android.os.Bundle()
                    tts?.synthesizeToFile(text, params, file, "u_$pct")
                    Thread.sleep(300)
                } catch (_: Exception) {}
            }
            android.os.Handler(android.os.Looper.getMainLooper()).post { onDone() }
        }.start()
    }

    fun playFor(pct: Int, onFinish: () -> Unit) {
        val customFile = getCustomFile(pct)
        if (customFile != null) {
            playFile(customFile, onFinish)
            return
        }
        val cacheFile = fileFor(pct)
        if (cacheFile.exists()) {
            playFile(cacheFile, onFinish)
            return
        }
        speakLive(pct)
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ onFinish() }, 2000)
    }

    private fun playFile(file: File, onFinish: () -> Unit) {
        try {
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                setAudioAttributes(attrs)
                setDataSource(file.absolutePath)
                setOnCompletionListener { onFinish() }
                setOnErrorListener { _, _, _ -> onFinish(); true }
                prepare()
                start()
            }
        } catch (_: Exception) {
            onFinish()
        }
    }

    private fun speakLive(pct: Int) {
        try {
            val text = TEXTS[pct] ?: return
            val params = android.os.Bundle()
            params.putInt(
                TextToSpeech.Engine.KEY_PARAM_STREAM,
                android.media.AudioManager.STREAM_ALARM
            )
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "u_${System.currentTimeMillis()}")
        } catch (_: Exception) {}
    }

    fun stop() {
        try { mediaPlayer?.stop(); mediaPlayer?.release() } catch (_: Exception) {}
        mediaPlayer = null
        try { tts?.stop() } catch (_: Exception) {}
    }

    fun shutdown() {
        stop()
        try { tts?.shutdown() } catch (_: Exception) {}
        tts = null
        isReady = false
    }
}
