package com.speakcharger.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.io.File
import java.util.Locale

class AudioPlayerService : Service() {

    private var mediaPlayer: MediaPlayer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var focusRequest: AudioFocusRequest? = null
    private val handler = Handler(Looper.getMainLooper())

    private val CH_ID = "speakcharger_media_v1"
    private val NOTIF_ID = 999

    override fun onCreate() {
        super.onCreate()
        createChannel()

        try {
            startForegroundCompat()
        } catch (e: Exception) {
            Log.e("SpeakCharger", "Foreground error", e)
        }

        try {
            tts = TextToSpeech(this) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    try { tts?.language = Locale("id", "ID") } catch (_: Exception) {
                        try { tts?.language = Locale.US } catch (_: Exception) {}
                    }
                    ttsReady = true
                    Log.d("SpeakCharger", "TTS ready")
                }
            }
        } catch (_: Exception) {}
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val pct = intent?.getIntExtra("pct", -1) ?: -1
        if (pct > 0) {
            handler.postDelayed({ playSound(pct) }, 300)
        } else {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startForegroundCompat() {
        val notif = buildNotification("SpeakCharger Aktif")
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                ServiceCompat.startForeground(
                    this, NOTIF_ID, notif,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
                Log.d("SpeakCharger", "Foreground OK")
            } catch (e: Exception) {
                Log.e("SpeakCharger", "ServiceCompat error", e)
                startForeground(NOTIF_ID, notif)
            }
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun requestFocus() {
        try {
            val am = getSystemService(AUDIO_SERVICE) as AudioManager
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()

            if (Build.VERSION.SDK_INT >= 26) {
                focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(attrs)
                    .setAcceptsDelayedFocusGain(true)
                    .setOnAudioFocusChangeListener { }
                    .build()
                am.requestAudioFocus(focusRequest!!)
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus(
                    null,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
                )
            }
            Log.d("SpeakCharger", "Audio focus OK")
        } catch (e: Exception) {
            Log.e("SpeakCharger", "Focus error", e)
        }
    }

    private fun abandonFocus() {
        try {
            val am = getSystemService(AUDIO_SERVICE) as AudioManager
            if (Build.VERSION.SDK_INT >= 26) {
                focusRequest?.let { am.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus(null)
            }
        } catch (_: Exception) {}
    }

    private fun playSound(pct: Int) {
        requestFocus()

        val mp3 = findMp3(pct)
        if (mp3 != null) {
            try {
                mediaPlayer?.release()
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                mediaPlayer = MediaPlayer().apply {
                    setAudioAttributes(attrs)
                    setDataSource(mp3.absolutePath)
                    setOnCompletionListener {
                        abandonFocus()
                        Log.d("SpeakCharger", "MP3 done")
                        handler.postDelayed({ stopSelf() }, 500)
                    }
                    setOnErrorListener { _, _, _ ->
                        abandonFocus()
                        handler.postDelayed({ stopSelf() }, 500)
                        true
                    }
                    prepare()
                    start()
                }
                Log.d("SpeakCharger", "MP3 play: $pct")
                return
            } catch (e: Exception) {
                Log.e("SpeakCharger", "MP3 err", e)
            }
        }

        if (ttsReady) {
            try {
                val params = android.os.Bundle()
                params.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
                tts?.speak(textForPct(pct), TextToSpeech.QUEUE_FLUSH, params, "a_$pct")
                Log.d("SpeakCharger", "TTS play: $pct")
            } catch (e: Exception) {
                Log.e("SpeakCharger", "TTS err", e)
            }
        }

        handler.postDelayed({
            abandonFocus()
            stopSelf()
        }, 3500)
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

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(
                CH_ID, "Audio Playback", NotificationManager.IMPORTANCE_LOW
            )
            ch.setSound(null, null)
            ch.enableVibration(false)
            nm.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CH_ID)
            .setContentTitle("SpeakCharger")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_idle_charging)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        try { mediaPlayer?.release() } catch (_: Exception) {}
        try { tts?.shutdown() } catch (_: Exception) {}
        abandonFocus()
    }
}
