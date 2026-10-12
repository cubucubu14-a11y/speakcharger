package com.speakcharger.app

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.tts.TextToSpeech
import android.view.accessibility.AccessibilityEvent
import androidx.core.app.NotificationCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BatteryAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var mediaPlayer: MediaPlayer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null

    private var lastLevel = -1
    private var firstFire = true
    private var currentPct = -1
    private var isChargingNow = false
    private var generation = 0

    private val NOTIF_ID = 1

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_BATTERY_CHANGED) return
            handleBattery(intent)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()

        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager

        // Init TTS untuk fallback
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale("id", "ID")
                ttsReady = true
            }
        }

        try { createChannel() } catch (_: Exception) {}
        try { updateNotification() } catch (_: Exception) {}

        try {
            val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            registerReceiver(batteryReceiver, filter)
        } catch (_: Exception) {}

        try {
            val sticky = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (sticky != null) handleBattery(sticky)
        } catch (_: Exception) {}
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(batteryReceiver) } catch (_: Exception) {}
        try { mediaPlayer?.release() } catch (_: Exception) {}
        try { tts?.shutdown() } catch (_: Exception) {}
    }

    // ★★★ KUNCI: Request audio focus sebelum play ★★★
    private fun requestAudioFocus() {
        try {
            val am = audioManager ?: return
            // Pakai STREAM_ACCESSIBILITY — stream khusus accessibility
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()

            if (Build.VERSION.SDK_INT >= 26) {
                focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(attrs)
                    .setAcceptsDelayedFocusGain(true)
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
        } catch (_: Exception) {}
    }

    private fun handleBattery(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (level < 0 || scale <= 0) return
        val pct = level * 100 / scale

        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val isCharging = plugged != 0 ||
                         status == BatteryManager.BATTERY_STATUS_CHARGING ||
                         status == BatteryManager.BATTERY_STATUS_FULL

        currentPct = pct
        isChargingNow = isCharging
        updateNotification()

        if (!isCharging) {
            lastLevel = pct
            return
        }

        if (firstFire) {
            firstFire = false
            lastLevel = pct
            return
        }

        if (pct == lastLevel) return

        val crossed = findCrossedLevel(lastLevel, pct, 5)
        if (crossed > 0) {
            lastLevel = pct
            fireAlert(crossed)
        } else if (pct > lastLevel) {
            lastLevel = pct
        }
    }

    private fun findCrossedLevel(from: Int, to: Int, step: Int): Int {
        if (to <= from) return -1
        var n = to - (to % step)
        if (n <= from) n -= step
        if (n <= from || n <= 0) return -1
        return n
    }

    private fun fireAlert(pct: Int) {
        // Vibrate sebagai bukti trigger fired
        try {
            val v = getSystemService(VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= 26) {
                v.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(500)
            }
        } catch (_: Exception) {}

        generation++
        val gen = generation
        if (pct == 100) playLoop(gen) else play3x(gen, pct, 0)
    }

    private fun play3x(gen: Int, pct: Int, iter: Int) {
        if (gen != generation) return
        if (iter >= 3) return
        playOnce(pct) {
            if (gen != generation) return@playOnce
            handler.postDelayed({
                if (gen == generation) play3x(gen, pct, iter + 1)
            }, 1000)
        }
    }

    private fun playLoop(gen: Int) {
        if (gen != generation) return
        playOnce(100) {
            if (gen != generation) return@playOnce
            handler.postDelayed({
                if (gen == generation) playLoop(gen)
            }, 1500)
        }
    }

    private fun playOnce(pct: Int, onDone: () -> Unit) {
        // ★ Request audio focus DULU
        requestAudioFocus()

        val file = findMp3(pct)
        if (file != null) {
            try {
                mediaPlayer?.release()
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                mediaPlayer = MediaPlayer().apply {
                    setAudioAttributes(attrs)
                    setDataSource(file.absolutePath)
                    setOnCompletionListener { onDone() }
                    setOnErrorListener { _, _, _ -> onDone(); true }
                    prepare()
                    start()
                }
            } catch (_: Exception) { onDone() }
        } else {
            // Fallback: TTS dengan USAGE_ASSISTANCE_ACCESSIBILITY
            if (ttsReady) {
                try {
                    val text = textForPct(pct)
                    val params = android.os.Bundle()
                    params.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
                    tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "u_$pct")
                } catch (_: Exception) {}
            }
            handler.postDelayed({ onDone() }, 2000)
        }
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

    private fun findMp3(pct: Int): File? {
        val dir = File(filesDir, "custom_sounds")
        val exts = listOf("mp3", "wav", "m4a", "ogg", "aac", "3gp")
        for (ext in exts) {
            val f = File(dir, "$pct.$ext")
            if (f.exists() && f.length() > 0) return f
        }
        return null
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(
                "speakcharger_monitor",
                "Monitor Baterai",
                NotificationManager.IMPORTANCE_LOW
            )
            ch.setSound(null, null)
            ch.enableVibration(false)
            nm.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification {
        val channelId = "speakcharger_monitor"
        if (Build.VERSION.SDK_INT < 26) {
            val ch = NotificationChannel(
                channelId, "SpeakCharger", NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(ch)
        }

        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val pctStr = if (currentPct >= 0) "$currentPct%" else "--"
        val statusStr = if (isChargingNow) "charging" else "tidak charging"

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("SpeakCharger Aktif")
            .setContentText("Baterai $pctStr - $statusStr • $timeStr")
            .setSmallIcon(android.R.drawable.ic_lock_idle_charging)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }

    private fun updateNotification() {
        try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID, buildNotification())
        } catch (_: Exception) {}
    }
}
