package com.speakcharger.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
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
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.io.File
import java.util.Locale

class AudioPlayerService : Service() {

    companion object {
        const val ACTION_PREPARE = "com.speakcharger.PREPARE"
        const val ACTION_PLAY = "com.speakcharger.PLAY"
        const val ACTION_STOP = "com.speakcharger.STOP"
        const val EXTRA_PCT = "pct"
    }

    private var mediaPlayer: MediaPlayer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var savedAlarmVolume = -1
    private val handler = Handler(Looper.getMainLooper())

    private val CH_ID = "speakcharger_media_v5"
    private val NOTIF_ID = 999

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForegroundCompat()

        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "SpeakCharger::AudioService"
            )
            wakeLock?.setReferenceCounted(false)
            wakeLock?.acquire(3 * 60 * 60 * 1000L)
            Log.d("SpeakCharger", "AudioService WakeLock acquired")
        } catch (e: Exception) {
            Log.e("SpeakCharger", "WakeLock error", e)
        }

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
        val action = intent?.action
        Log.d("SpeakCharger", "AudioService action=$action")

        try {
            when (action) {
                ACTION_PLAY -> {
                    val pct = intent.getIntExtra(EXTRA_PCT, -1)
                    if (pct > 0) handler.postDelayed({ play(pct) }, 100)
                }
                ACTION_STOP -> stopSelf()
                ACTION_PREPARE, null -> updateNotification("Siap memutar suara")
            }
        } catch (e: Exception) {
            Log.e("SpeakCharger", "onStartCommand error", e)
        }
        return START_STICKY
    }

    private fun startForegroundCompat() {
        val notif = buildNotification("Siap memutar suara")
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                ServiceCompat.startForeground(
                    this, NOTIF_ID, notif,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } catch (e: Exception) {
                startForeground(NOTIF_ID, notif)
            }
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun saveAndBoostAlarmVolume() {
        try {
            val am = getSystemService(AUDIO_SERVICE) as AudioManager
            savedAlarmVolume = am.getStreamVolume(AudioManager.STREAM_ALARM)
            val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            am.setStreamVolume(AudioManager.STREAM_ALARM, maxVol, 0)
        } catch (_: Exception) {}
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
        try {
            saveAndBoostAlarmVolume()
            updateNotification("Memutar: Baterai $pct%")

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
                        setOnPreparedListener { it.start() }
                        setOnCompletionListener { onPlayDone() }
                        setOnErrorListener { _, what, extra ->
                            Log.e("SpeakCharger", "MP3 error: $what, $extra")
                            onPlayDone()
                            true
                        }
                        prepareAsync()
                    }
                    return
                } catch (e: Exception) {
                    Log.e("SpeakCharger", "MP3 err", e)
                }
            }

            if (ttsReady) {
                try {
                    val params = android.os.Bundle()
                    params.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_ALARM)
                    tts?.speak(textForPct(pct), TextToSpeech.QUEUE_FLUSH, params, "a_$pct")
                } catch (_: Exception) {}
            }
            handler.postDelayed({ onPlayDone() }, 3500)
        } catch (e: Exception) {
            Log.e("SpeakCharger", "Play error", e)
        }
    }

    private fun onPlayDone() {
        try { mediaPlayer?.release() } catch (_: Exception) {}
        mediaPlayer = null
        restoreAlarmVolume()
        updateNotification("Siap memutar suara")
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
            .setContentTitle("SpeakCharger - Aktif")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_idle_charging)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pi)
            .build()
    }

    private fun updateNotification(text: String) {
        try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID, buildNotification(text))
        } catch (_: Exception) {}
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
        Log.d("SpeakCharger", "AudioService destroyed")
    }
}
