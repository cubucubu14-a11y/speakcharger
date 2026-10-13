package com.speakcharger.app

import android.app.Service
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.util.Log
import java.io.File
import java.util.Locale

class AudioPlayerService : Service() {

    private var mediaPlayer: MediaPlayer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var savedAlarmVolume = -1
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()

        // WakeLock PALING AWAL (seperti Clock app)
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "SpeakCharger::Alarm"
            )
            wakeLock?.setReferenceCounted(false)
            wakeLock?.acquire(5 * 60 * 1000L)
            Log.d("SpeakCharger", "WakeLock acquired")
        } catch (e: Exception) {
            Log.e("SpeakCharger", "WakeLock error", e)
        }

        // TTS init
        try {
            tts = TextToSpeech(this) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    try { tts?.language = Locale("id", "ID") } catch (_: Exception) {}
                    ttsReady = true
                }
            }
        } catch (_: Exception) {}
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val pct = intent?.getIntExtra("pct", -1) ?: -1
        if (pct <= 0) {
            stopSelf()
            return START_NOT_STICKY
        }
        handler.postDelayed({ play(pct) }, 200)
        return START_NOT_STICKY
    }

    // Save volume alarm → set max → play → restore
    private fun saveAndBoostAlarmVolume() {
        try {
            val am = getSystemService(AUDIO_SERVICE) as AudioManager
            savedAlarmVolume = am.getStreamVolume(AudioManager.STREAM_ALARM)
            val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            am.setStreamVolume(AudioManager.STREAM_ALARM, maxVol, 0)
            Log.d("SpeakCharger", "Alarm vol: $savedAlarmVolume -> $maxVol")
        } catch (e: Exception) {
            Log.e("SpeakCharger", "Volume error", e)
        }
    }

    private fun restoreAlarmVolume() {
        try {
            if (savedAlarmVolume >= 0) {
                val am = getSystemService(AUDIO_SERVICE) as AudioManager
                am.setStreamVolume(AudioManager.STREAM_ALARM, savedAlarmVolume, 0)
                savedAlarmVolume = -1
            }
        } catch (_: Exception) {}
    }

    private fun play(pct: Int) {
        saveAndBoostAlarmVolume()

        val mp3 = findMp3(pct)
        if (mp3 != null) {
            try {
                mediaPlayer?.release()
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()

                mediaPlayer = MediaPlayer().apply {
                    setAudioAttributes(attrs)
                    setDataSource(mp3.absolutePath)
                    setWakeMode(this@AudioPlayerService, PowerManager.PARTIAL_WAKE_LOCK)
                    setOnPreparedListener {
                        Log.d("SpeakCharger", "MP3 prepared, starting")
                        it.start()
                    }
                    setOnCompletionListener {
                        Log.d("SpeakCharger", "MP3 done")
                        cleanupAndStop()
                    }
                    setOnErrorListener { _, what, extra ->
                        Log.e("SpeakCharger", "MP3 error: $what, $extra")
                        cleanupAndStop()
                        true
                    }
                    prepareAsync()
                }
                Log.d("SpeakCharger", "MP3 playing: $pct")
                return
            } catch (e: Exception) {
                Log.e("SpeakCharger", "MP3 err", e)
            }
        }

        Log.d("SpeakCharger", "No MP3, using TTS")
        if (ttsReady) {
            try {
                val params = android.os.Bundle()
                params.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_ALARM)
                tts?.speak(textForPct(pct), TextToSpeech.QUEUE_FLUSH, params, "a_$pct")
            } catch (e: Exception) {
                Log.e("SpeakCharger", "TTS err", e)
            }
        }

        handler.postDelayed({ cleanupAndStop() }, 3500)
    }

    private fun cleanupAndStop() {
        try { mediaPlayer?.release() } catch (_: Exception) {}
        mediaPlayer = null
        restoreAlarmVolume()
        handler.postDelayed({ stopSelf() }, 300)
    }

    private fun findMp3(pct: Int): File? {
        val dir = File(filesDir, "custom_sounds")
        val exts = listOf("mp3", "wav", "m4a", "ogg", "aac", "3gp")
        for (ext in exts) {
            val f = File(dir, "$pct.$ext")
            if (f.exists() && f.length() > 0) return f
        }
        return null
    }

    private fun textForPct(pct: Int): String = when (pct) {
        5 -> "Baterai lima persen"
        10 -> "Baterai sepuluh persen"
        15 -> "Baterai lima belas persen"
        20 -> "Baterai dua puluh persen"
        25 -> "Baterai dua puluh lima persen"
        30 -> "Baterai tiga puluh persen"
        35 -> "Baterai tiga puluh lima persen"
        40 -> "Baterai empat puluh persen"
        45 -> "Baterai empat puluh lima persen"
        50 -> "Baterai lima puluh persen"
        55 -> "Baterai lima puluh lima persen"
        60 -> "Baterai enam puluh persen"
        65 -> "Baterai enam puluh lima persen"
        70 -> "Baterai tujuh puluh persen"
        75 -> "Baterai tujuh puluh lima persen"
        80 -> "Baterai delapan puluh persen"
        85 -> "Baterai delapan puluh lima persen"
        90 -> "Baterai sembilan puluh persen"
        95 -> "Baterai sembilan puluh lima persen"
        100 -> "Baterai full, cabut kabel"
        else -> "Baterai $pct persen"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        try { mediaPlayer?.release() } catch (_: Exception) {}
        try { tts?.shutdown() } catch (_: Exception) {}
        restoreAlarmVolume()
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {}
        wakeLock = null
    }
}
