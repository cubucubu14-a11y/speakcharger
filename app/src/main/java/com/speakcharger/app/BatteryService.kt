package com.speakcharger.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat

class BatteryService : Service() {

    private lateinit var tts: TtsManager
    private lateinit var toneGen: ToneGenerator
    private var lastSpokenLevel = -1
    private var firstFire = true
    private var everCharged = false
    private val handler = Handler(Looper.getMainLooper())

    // Generation ID: tiap alert baru, ID naik. Yang lama otomatis batal.
    private var alertGeneration = 0

    // Timing (ms)
    private val LEVEL_REPEAT_INTERVAL = 4500L
    private val FULL_REPEAT_INTERVAL = 3500L

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_BATTERY_CHANGED) return
            handleBattery(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        tts = TtsManager(this)
        toneGen = ToneGenerator(AudioManager.STREAM_ALARM, 100)
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        startForeground(1, buildNotification("Menunggu data baterai..."))
    }

    private fun handleBattery(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (level < 0 || scale <= 0) return
        val pct = level * 100 / scale

        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val isCharging = plugged == BatteryManager.BATTERY_PLUGGED_AC ||
                         plugged == BatteryManager.BATTERY_PLUGGED_USB ||
                         plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS ||
                         status == BatteryManager.BATTERY_STATUS_CHARGING ||
                         status == BatteryManager.BATTERY_STATUS_FULL

        updateNotification(
            "Baterai $pct% - " + if (isCharging) "charging" else "tidak charging"
        )

        if (firstFire) {
            firstFire = false
            lastSpokenLevel = pct
            if (isCharging) everCharged = true
            return
        }

        if (isCharging) {
            everCharged = true

            // ★ FIX BUG: deteksi kalau MELEWATI kelipatan 5
            val crossed = findCrossedLevel(lastSpokenLevel, pct, 5)
            if (crossed > 0) {
                lastSpokenLevel = pct
                startAlert(crossed)
            } else if (pct > lastSpokenLevel) {
                // update posisi terakhir walau gak lewat kelipatan 5
                lastSpokenLevel = pct
            }
        } else {
            if (everCharged) {
                cancelAll()
                stopSelf()
            }
        }
    }

    // Cari kelipatan `step` tertinggi yang LEWAT di antara from (exclusive) dan to (inclusive)
    // Contoh: from=62, to=66, step=5 → return 65
    private fun findCrossedLevel(from: Int, to: Int, step: Int): Int {
        if (to <= from) return -1
        var n = to - (to % step)   // kelipatan terbesar <= to
        if (n <= from) {
            n -= step              // mundur satu step
        }
        if (n <= from || n <= 0) return -1
        return n
    }

    private fun startAlert(pct: Int) {
        cancelAll()
        alertGeneration++
        val gen = alertGeneration

        if (pct == 100) {
            playFullSequence(gen)
        } else {
            playLevelSequence(gen, pct, 0)
        }
    }

    private fun playLevelSequence(gen: Int, pct: Int, iteration: Int) {
        if (gen != alertGeneration) return
        if (iteration >= 3) return

        toneGen.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 250)
        handler.postDelayed({
            if (gen == alertGeneration) toneGen.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 250)
        }, 300)
        handler.postDelayed({
            if (gen == alertGeneration) toneGen.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 250)
        }, 600)

        val kata = when (pct) {
            5 -> "lima"
            10 -> "sepuluh"
            15 -> "lima belas"
            20 -> "dua puluh"
            25 -> "dua puluh lima"
            30 -> "tiga puluh"
            35 -> "tiga puluh lima"
            40 -> "empat puluh"
            45 -> "empat puluh lima"
            50 -> "lima puluh"
            55 -> "lima puluh lima"
            60 -> "enam puluh"
            65 -> "enam puluh lima"
            70 -> "tujuh puluh"
            75 -> "tujuh puluh lima"
            80 -> "delapan puluh"
            85 -> "delapan puluh lima"
            90 -> "sembilan puluh"
            95 -> "sembilan puluh lima"
            else -> "$pct"
        }
        handler.postDelayed({
            if (gen == alertGeneration) tts.speak("Baterai $kata persen")
        }, 1000)

        handler.postDelayed({
            if (gen == alertGeneration) playLevelSequence(gen, pct, iteration + 1)
        }, LEVEL_REPEAT_INTERVAL)
    }

    private fun playFullSequence(gen: Int) {
        if (gen != alertGeneration) return

        toneGen.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 250)
        handler.postDelayed({
            if (gen == alertGeneration) toneGen.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 250)
        }, 300)
        handler.postDelayed({
            if (gen == alertGeneration) toneGen.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 250)
        }, 600)

        handler.postDelayed({
            if (gen == alertGeneration) tts.speak("Baterai full, cabut kabel")
        }, 1000)

        handler.postDelayed({
            if (gen == alertGeneration) playFullSequence(gen)
        }, FULL_REPEAT_INTERVAL)
    }

    private fun cancelAll() {
        alertGeneration++
        handler.removeCallbacksAndMessages(null)
        try { tts.stop() } catch (_: Exception) {}
    }

    private fun buildNotification(text: String): Notification {
        val channelId = "speakcharger_ch"
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(
                channelId,
                "SpeakCharger Monitor",
                NotificationManager.IMPORTANCE_LOW
            )
            ch.description = "Notifikasi monitoring baterai"
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(ch)
        }

        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("SpeakCharger Aktif")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_idle_charging)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }

    private fun updateNotification(text: String) {
        try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(1, buildNotification(text))
        } catch (_: Exception) {}
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(batteryReceiver) } catch (_: Exception) {}
        cancelAll()
        tts.shutdown()
        toneGen.release()
    }
}
