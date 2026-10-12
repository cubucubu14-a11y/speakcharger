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
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.ToneGenerator
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.accessibility.AccessibilityEvent
import androidx.core.app.NotificationCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BatteryAccessibilityService : AccessibilityService() {

    private lateinit var toneGen: ToneGenerator
    private val handler = Handler(Looper.getMainLooper())
    private var mediaPlayer: MediaPlayer? = null

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
        try { createChannel() } catch (_: Exception) {}
        try { updateNotification() } catch (_: Exception) {}
        try { toneGen = ToneGenerator(AudioManager.STREAM_ALARM, 100) } catch (_: Exception) {}

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
        try { toneGen.release() } catch (_: Exception) {}
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

    // ==== ALERT ====
    private fun fireAlert(pct: Int) {
        // 1. GETAR — bukti trigger fired
        try {
            val v = getSystemService(VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= 26) {
                v.vibrate(VibrationEffect.createOneShot(1000, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(1000)
            }
        } catch (_: Exception) {}

        // 2. NOTIF KHUSUS ALERT — bukti trigger fired
        try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            val pi = PendingIntent.getActivity(
                this, 999,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            val notif = NotificationCompat.Builder(this, "speakcharger_monitor")
                .setContentTitle("ALERT: Baterai $pct%")
                .setContentText("Trigger fired • $timeStr")
                .setSmallIcon(android.R.drawable.ic_lock_idle_charging)
                .setContentIntent(pi)
                .build()
            nm.notify(500 + pct, notif)
        } catch (_: Exception) {}

        // 3. PLAY SOUND — langsung pakai MediaPlayer (bukan TtsManager)
        generation++
        val gen = generation
        if (pct == 100) {
            playLoop(gen)
        } else {
            play3x(gen, pct, 0)
        }
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
        // Beep dulu
        try { toneGen.startTone(ToneGenerator.TONE_PROP_BEEP, 300) } catch (_: Exception) {}

        // Cari MP3 custom dari folder app
        val file = findMp3(pct)
        if (file == null) {
            handler.postDelayed({ onDone() }, 1500)
            return
        }

        handler.postDelayed({
            try {
                mediaPlayer?.release()
                mediaPlayer = MediaPlayer().apply {
                    setAudioAttributes(
                        android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_ALARM)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    setDataSource(file.absolutePath)
                    setOnCompletionListener { onDone() }
                    setOnErrorListener { _, _, _ -> onDone(); true }
                    prepare()
                    start()
                }
            } catch (_: Exception) { onDone() }
        }, 400)
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

    // ==== NOTIFIKASI MONITOR ====
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
