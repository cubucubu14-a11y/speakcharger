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
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
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

    private var lastLevel = -1
    private var firstFire = true
    private var currentPct = -1
    private var isChargingNow = false
    private var generation = 0

    private val NOTIF_ID = 1
    private val TEST_ID = 100
    private val ALERT_ID_BASE = 200

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_BATTERY_CHANGED) return
            handleBattery(intent)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()

        // Init TTS
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale("id", "ID")
                ttsReady = true
            }
        }

        try { createChannels() } catch (_: Exception) {}
        try { updateMonitorNotification() } catch (_: Exception) {}

        try {
            registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        } catch (_: Exception) {}

        // ★★★ TEST: Bunyi langsung saat connect ★★★
        handler.postDelayed({
            playTestSound()
        }, 1500)

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

    // ★ TEST SOUND — cek audio bisa keluar atau enggak ★
    private fun playTestSound() {
        // 1. TTS dengan STREAM_ACCESSIBILITY
        try {
            if (ttsReady) {
                val params = android.os.Bundle()
                params.putInt(
                    TextToSpeech.Engine.KEY_PARAM_STREAM,
                    AudioManager.STREAM_ACCESSIBILITY
                )
                params.putInt(
                    TextToSpeech.Engine.KEY_PARAM_VOLUME, 100
                )
                tts?.speak("Tes suara SpeakCharger", TextToSpeech.QUEUE_FLUSH, params, "test")
            }
        } catch (_: Exception) {}

        // 2. Notif dengan suara — sebagai backup
        try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            val pi = PendingIntent.getActivity(
                this, 0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val notif = NotificationCompat.Builder(this, "speakcharger_test")
                .setContentTitle("SpeakCharger Test")
                .setContentText("Jika bunyi = audio OK")
                .setSmallIcon(android.R.drawable.ic_lock_idle_charging)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            nm.notify(TEST_ID, notif)
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
        updateMonitorNotification()

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
        // Kirim notif ALERT dengan suara (backup kalau TTS gak jalan)
        try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            val pi = PendingIntent.getActivity(
                this, pct,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val text = if (pct == 100) "Baterai FULL! Cabut kabel!" else "Baterai $pct%"
            val notif = NotificationCompat.Builder(this, "speakcharger_alert")
                .setContentTitle("SpeakCharger - Baterai $pct%")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_lock_idle_charging)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            nm.notify(ALERT_ID_BASE + pct, notif)
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
        // Coba MP3 dulu
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
                return
            } catch (_: Exception) {}
        }
        // Fallback TTS via STREAM_ACCESSIBILITY
        if (ttsReady) {
            try {
                val params = android.os.Bundle()
                params.putInt(
                    TextToSpeech.Engine.KEY_PARAM_STREAM,
                    AudioManager.STREAM_ACCESSIBILITY
                )
                tts?.speak(textForPct(pct), TextToSpeech.QUEUE_FLUSH, params, "a_$pct")
            } catch (_: Exception) {}
        }
        handler.postDelayed({ onDone() }, 2000)
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

    private fun createChannels() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

            // Monitor channel (silent)
            val monCh = NotificationChannel(
                "speakcharger_monitor", "Monitor", NotificationManager.IMPORTANCE_LOW
            )
            monCh.setSound(null, null)
            monCh.enableVibration(false)
            nm.createNotificationChannel(monCh)

            // Test channel (dengan suara)
            val testCh = NotificationChannel(
                "speakcharger_test", "Test", NotificationManager.IMPORTANCE_HIGH
            )
            val defaultSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            testCh.setSound(defaultSound, AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build())
            nm.createNotificationChannel(testCh)

            // Alert channel (dengan suara)
            val alertCh = NotificationChannel(
                "speakcharger_alert", "Alert", NotificationManager.IMPORTANCE_HIGH
            )
            val alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            alertCh.setSound(alarmSound, AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build())
            alertCh.enableVibration(false)
            nm.createNotificationChannel(alertCh)
        }
    }

    private fun buildMonitorNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val pctStr = if (currentPct >= 0) "$currentPct%" else "--"
        val statusStr = if (isChargingNow) "charging" else "tidak charging"
        return NotificationCompat.Builder(this, "speakcharger_monitor")
            .setContentTitle("SpeakCharger Aktif")
            .setContentText("Baterai $pctStr - $statusStr • $timeStr")
            .setSmallIcon(android.R.drawable.ic_lock_idle_charging)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }

    private fun updateMonitorNotification() {
        try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID, buildMonitorNotification())
        } catch (_: Exception) {}
    }
}
