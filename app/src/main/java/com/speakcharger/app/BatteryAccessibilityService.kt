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
import android.media.ToneGenerator
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import androidx.core.app.NotificationCompat

class BatteryAccessibilityService : AccessibilityService() {

    private lateinit var tts: TtsManager
    private lateinit var toneGen: ToneGenerator

    private var lastSpokenLevel = -1
    private var firstFire = true
    private var everCharged = false
    private var currentPct = -1
    private var isChargingNow = false

    private val handler = Handler(Looper.getMainLooper())
    private var alertGeneration = 0

    private val POLL_INTERVAL = 15000L
    private val HEARTBEAT_INTERVAL = 30000L

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_BATTERY_CHANGED) return
            handleBattery(intent)
        }
    }

    private val pollRunnable = object : Runnable {
        override fun run() {
            checkBatteryNow()
            handler.postDelayed(this, POLL_INTERVAL)
        }
    }

    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            try { showNotification("Monitor aktif") } catch (_: Exception) {}
            handler.postDelayed(this, HEARTBEAT_INTERVAL)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()

        // 1. Bikin notif channel dulu
        try { createChannel() } catch (_: Exception) {}

        // 2. Tampilkan notif langsung — biar ketahuan service hidup
        try { showNotification("Service terhubung") } catch (_: Exception) {}

        // 3. Init semua komponen
        try {
            tts = TtsManager(this)
            toneGen = ToneGenerator(AudioManager.STREAM_ALARM, 100)
        } catch (_: Exception) {}

        // 4. Daftar broadcast baterai
        try {
            registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        } catch (_: Exception) {}

        // 5. Cek baterai awal
        try { checkBatteryNow() } catch (_: Exception) {}

        // 6. Jadwal polling & heartbeat
        handler.postDelayed(pollRunnable, POLL_INTERVAL)
        handler.postDelayed(heartbeatRunnable, HEARTBEAT_INTERVAL)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(batteryReceiver) } catch (_: Exception) {}
        handler.removeCallbacksAndMessages(null)
        try { stopAllPlayback() } catch (_: Exception) {}
        try { tts.shutdown() } catch (_: Exception) {}
        try { toneGen.release() } catch (_: Exception) {}
    }

    private fun checkBatteryNow() {
        try {
            val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
            val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            if (pct < 0) return

            val sticky = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (sticky == null) return

            sticky.putExtra(BatteryManager.EXTRA_LEVEL, pct)
            sticky.putExtra(BatteryManager.EXTRA_SCALE, 100)
            handleBattery(sticky)
        } catch (_: Exception) {}
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

        currentPct = pct
        isChargingNow = isCharging

        if (firstFire) {
            firstFire = false
            lastSpokenLevel = pct
            if (isCharging) everCharged = true
            return
        }

        if (isCharging) {
            everCharged = true
            val crossed = findCrossedLevel(lastSpokenLevel, pct, 5)
            if (crossed > 0) {
                lastSpokenLevel = pct
                startAlert(crossed)
            } else if (pct > lastSpokenLevel) {
                lastSpokenLevel = pct
            }
        } else {
            if (everCharged) stopAllPlayback()
        }
    }

    private fun findCrossedLevel(from: Int, to: Int, step: Int): Int {
        if (to <= from) return -1
        var n = to - (to % step)
        if (n <= from) n -= step
        if (n <= from || n <= 0) return -1
        return n
    }

    private fun startAlert(pct: Int) {
        stopAllPlayback()
        alertGeneration++
        val gen = alertGeneration
        if (pct == 100) playFullLoop(gen) else playLevelSequence(gen, pct, 0)
    }

    private fun playBeepThenFile(pct: Int, onFinish: () -> Unit) {
        try { toneGen.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 350) } catch (_: Exception) {}
        handler.postDelayed({
            try { tts.playFor(pct) { onFinish() } } catch (_: Exception) { onFinish() }
        }, 500)
    }

    private fun playLevelSequence(gen: Int, pct: Int, iteration: Int) {
        if (gen != alertGeneration) return
        if (iteration >= 3) return
        playBeepThenFile(pct) {
            if (gen != alertGeneration) return@playBeepThenFile
            handler.postDelayed({
                if (gen == alertGeneration) playLevelSequence(gen, pct, iteration + 1)
            }, 1200)
        }
    }

    private fun playFullLoop(gen: Int) {
        if (gen != alertGeneration) return
        playBeepThenFile(100) {
            if (gen != alertGeneration) return@playBeepThenFile
            handler.postDelayed({
                if (gen == alertGeneration) playFullLoop(gen)
            }, 1500)
        }
    }

    private fun stopAllPlayback() {
        alertGeneration++
        try { tts.stop() } catch (_: Exception) {}
        try { toneGen.stopTone() } catch (_: Exception) {}
    }

    // ==== NOTIFIKASI (tanpa startForeground) ====

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(
                "speakcharger_acc", "Monitor Baterai",
                NotificationManager.IMPORTANCE_LOW
            )
            ch.setSound(null, null)
            ch.enableVibration(false)
            nm.createNotificationChannel(ch)
        }
    }

    private fun showNotification(status: String) {
        try {
            val channelId = "speakcharger_acc"

            val pi = PendingIntent.getActivity(
                this, 0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            val timeStr = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                .format(java.util.Date())
            val pctStr = if (currentPct >= 0) "$currentPct%" else "--"
            val statusStr = if (isChargingNow) "charging" else "tidak charging"

            val notif: Notification = NotificationCompat.Builder(this, channelId)
                .setContentTitle("SpeakCharger Aktif")
                .setContentText("$status • $pctStr $statusStr • $timeStr")
                .setSmallIcon(android.R.drawable.ic_lock_idle_charging)
                .setOngoing(true)
                .setContentIntent(pi)
                .build()

            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(1, notif)
        } catch (_: Exception) {}
    }
}
