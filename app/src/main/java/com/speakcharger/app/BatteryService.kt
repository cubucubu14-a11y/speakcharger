package com.speakcharger.app

import android.app.AlarmManager
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
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BatteryService : Service() {

    private lateinit var tts: TtsManager
    private lateinit var toneGen: ToneGenerator
    private lateinit var alarmManager: AlarmManager
    private lateinit var alarmIntent: PendingIntent

    private var lastSpokenLevel = -1
    private var firstFire = true
    private var everCharged = false
    private var currentPct = -1
    private var isChargingNow = false

    private val handler = Handler(Looper.getMainLooper())
    private var alertGeneration = 0

    private val LEVEL_REPEAT_INTERVAL = 4500L
    private val FULL_REPEAT_INTERVAL = 3500L
    private val ALARM_INTERVAL_MS = 60000L

    // ==== Receiver untuk perubahan baterai (realtime) ====
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_BATTERY_CHANGED) return
            handleBattery(intent)
        }
    }

    // ==== Heartbeat: update notifikasi tiap 30 detik ====
    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            refreshNotification()
            handler.postDelayed(this, 30000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        tts = TtsManager(this)
        toneGen = ToneGenerator(AudioManager.STREAM_ALARM, 100)
        alarmManager = getSystemService(ALARM_SERVICE) as AlarmManager

        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        startForeground(1, buildNotification("Memulai..."))

        // Cek baterai awal langsung
        checkBatteryNow()

        // Mulai heartbeat
        handler.postDelayed(heartbeatRunnable, 30000)
    }

    // ==== Baca baterai saat ini juga (tanpa tunggu broadcast) ====
    private fun checkBatteryNow() {
        try {
            val sticky = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (sticky != null) handleBattery(sticky)
        } catch (_: Exception) {}
    }

    // ==== Handle perubahan baterai ====
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
        refreshNotification()

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
            if (everCharged) {
                cancelAll()
                stopSelf()
            }
        }
    }

    private fun findCrossedLevel(from: Int, to: Int, step: Int): Int {
        if (to <= from) return -1
        var n = to - (to % step)
        if (n <= from) n -= step
        if (n <= from || n <= 0) return -1
        return n
    }

    // ==== Alert logic ====
    private fun startAlert(pct: Int) {
        cancelAll()
        alertGeneration++
        val gen = alertGeneration
        if (pct == 100) playFullSequence(gen) else playLevelSequence(gen, pct, 0)
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
        val backupHeartbeat = heartbeatRunnable
        handler.removeCallbacksAndMessages(null)
        try { tts.stop() } catch (_: Exception) {}
        handler.postDelayed(backupHeartbeat, 30000)
    }

    // ==== Notifikasi + timestamp heartbeat ====
    private fun refreshNotification() {
        val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val pctStr = if (currentPct >= 0) "$currentPct%" else "--"
        val statusStr = if (isChargingNow) "charging" else "tidak charging"
        updateNotification("Baterai $pctStr - $statusStr • $timeStr")
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

    // ==== Setup AlarmManager sebagai backup ====
    private fun scheduleAlarm() {
        try {
            val intent = Intent(this, BatteryService::class.java).apply {
                action = "SPEAKCHARGER_ALARM_CHECK"
            }
            alarmIntent = PendingIntent.getService(
                this, 100, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val triggerAt = SystemClock.elapsedRealtime() + ALARM_INTERVAL_MS

            if (Build.VERSION.SDK_INT >= 23) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, alarmIntent
                )
            } else {
                alarmManager.setExact(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, alarmIntent
                )
            }
        } catch (_: Exception) {}
    }

    private fun cancelAlarm() {
        try {
            if (::alarmIntent.isInitialized) {
                alarmManager.cancel(alarmIntent)
            }
        } catch (_: Exception) {}
    }

    // ==== onStartCommand dipanggil tiap alarm fire ====
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Cek baterai langsung
        checkBatteryNow()
        // Jadwal alarm berikutnya
        scheduleAlarm()
        return START_STICKY
    }

    // ==== Swipe dari recent → restart ====
    override fun onTaskRemoved(rootIntent: Intent?) {
        try {
            val restartService = Intent(applicationContext, this.javaClass)
            restartService.setPackage(packageName)
            val pi = PendingIntent.getService(
                this, 1, restartService,
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.set(
                AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime() + 1000,
                pi
            )
        } catch (_: Exception) {}
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(batteryReceiver) } catch (_: Exception) {}
        cancelAlarm()
        cancelAll()
        tts.shutdown()
        toneGen.release()
    }
}
