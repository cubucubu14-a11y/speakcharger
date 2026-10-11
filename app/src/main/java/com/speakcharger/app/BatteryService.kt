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
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BatteryService : Service() {

    private lateinit var tts: TtsManager
    private lateinit var toneGen: ToneGenerator
    private lateinit var alarmManager: AlarmManager

    private var lastSpokenLevel = -1
    private var firstFire = true
    private var everCharged = false
    private var currentPct = -1
    private var isChargingNow = false

    private val handler = Handler(Looper.getMainLooper())

    private val LEVEL_REPEAT_INTERVAL = 4500L
    private val FULL_REPEAT_INTERVAL = 3500L
    private val ALARM_INTERVAL_MS = 60000L

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_BATTERY_CHANGED) return
            handleBattery(intent)
        }
    }

    private val pollRunnable = object : Runnable {
        override fun run() {
            checkBatteryNow()
            handler.postDelayed(this, 15000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        tts = TtsManager(this)
        toneGen = ToneGenerator(AudioManager.STREAM_ALARM, 100)
        alarmManager = getSystemService(ALARM_SERVICE) as AlarmManager

        createAlertChannel()

        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

        // ★ KUNCI: pakai tipe MEDIA_PLAYBACK biar audio boleh keluar saat background
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                ServiceCompat.startForeground(
                    this,
                    1,
                    buildNotification("Memulai..."),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } catch (_: Exception) {
                startForeground(1, buildNotification("Memulai..."))
            }
        } else {
            startForeground(1, buildNotification("Memulai..."))
        }

        checkBatteryNow()
        scheduleAlarm()
        handler.postDelayed(pollRunnable, 15000)
    }

    private fun checkBatteryNow() {
        try {
            val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
            val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            if (pct < 0) return

            val sticky = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (sticky == null) return

            val plugged = sticky.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
            val status = sticky.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
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
                if (everCharged) stopSelf()
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
            if (everCharged) stopSelf()
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
        val teksNotif = if (pct == 100) {
            "Baterai FULL! Cabut kabel sekarang!"
        } else {
            "Baterai $pct%"
        }
        showAlertNotification(pct, teksNotif)

        if (pct == 100) {
            playFullRepeat()
        } else {
            playLevelSequence(pct)
        }
    }

    private var repeatGeneration = 0

    private fun playLevelSequence(pct: Int) {
        repeatGeneration++
        val gen = repeatGeneration
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
        for (i in 0 until 3) {
            val delay = i * LEVEL_REPEAT_INTERVAL
            handler.postDelayed({
                if (gen != repeatGeneration) return@postDelayed
                try { toneGen.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 250) } catch (_: Exception) {}
                handler.postDelayed({
                    if (gen != repeatGeneration) return@postDelayed
                    tts.speak("Baterai $kata persen")
                }, 300)
            }, delay)
        }
    }

    private fun playFullRepeat() {
        repeatGeneration++
        val gen = repeatGeneration
        val loop = object : Runnable {
            override fun run() {
                if (gen != repeatGeneration) return
                try {
                    toneGen.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 250)
                    handler.postDelayed({
                        try { toneGen.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 250) } catch (_: Exception) {}
                    }, 300)
                    handler.postDelayed({
                        try { toneGen.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 250) } catch (_: Exception) {}
                    }, 600)
                    handler.postDelayed({
                        if (gen == repeatGeneration) tts.speak("Baterai full, cabut kabel")
                    }, 1000)
                } catch (_: Exception) {}
                handler.postDelayed(this, FULL_REPEAT_INTERVAL)
            }
        }
        handler.post(loop)
    }

    private fun createAlertChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

            val monitorCh = NotificationChannel(
                "speakcharger_monitor",
                "Monitor Baterai",
                NotificationManager.IMPORTANCE_LOW
            )
            monitorCh.description = "Notifikasi monitor biasa"
            nm.createNotificationChannel(monitorCh)

            val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

            val alertCh = NotificationChannel(
                "speakcharger_alert",
                "Alert Baterai",
                NotificationManager.IMPORTANCE_HIGH
            )
            alertCh.description = "Alert kelipatan 5%"
            alertCh.enableVibration(false)
            alertCh.enableLights(true)

            if (alarmUri != null) {
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                alertCh.setSound(alarmUri, attrs)
            }
            try { alertCh.setBypassDnd(true) } catch (_: Exception) {}
            nm.createNotificationChannel(alertCh)
        }
    }

    private fun showAlertNotification(pct: Int, text: String) {
        try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

            val pi = PendingIntent.getActivity(
                this, pct,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            val builder = NotificationCompat.Builder(this, "speakcharger_alert")
                .setContentTitle("SpeakCharger - Baterai $pct%")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_lock_idle_charging)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setAutoCancel(true)
                .setContentIntent(pi)

            if (Build.VERSION.SDK_INT < 26) {
                val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                builder.setSound(alarmUri)
            }

            nm.notify(1000 + pct, builder.build())
        } catch (_: Exception) {}
    }

    private fun refreshNotification() {
        val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val pctStr = if (currentPct >= 0) "$currentPct%" else "--"
        val statusStr = if (isChargingNow) "charging" else "tidak charging"
        updateNotification("Baterai $pctStr - $statusStr • $timeStr")
    }

    private fun buildNotification(text: String): Notification {
        val channelId = if (Build.VERSION.SDK_INT >= 26) "speakcharger_monitor" else "speakcharger_ch"

        if (Build.VERSION.SDK_INT < 26) {
            val ch = NotificationChannel(
                "speakcharger_ch", "SpeakCharger", NotificationManager.IMPORTANCE_LOW
            )
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

    private fun scheduleAlarm() {
        try {
            val alarmIntent = Intent(this, BatteryService::class.java).apply {
                action = "SPEAKCHARGER_ALARM_CHECK"
            }
            val operation = PendingIntent.getService(
                this, 100, alarmIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val showIntent = PendingIntent.getActivity(
                this, 101,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val triggerAt = System.currentTimeMillis() + ALARM_INTERVAL_MS
            alarmManager.setAlarmClock(
                AlarmManager.AlarmClockInfo(triggerAt, showIntent),
                operation
            )
        } catch (_: Exception) {}
    }

    private fun cancelAlarm() {
        try {
            val alarmIntent = Intent(this, BatteryService::class.java).apply {
                action = "SPEAKCHARGER_ALARM_CHECK"
            }
            val pi = PendingIntent.getService(
                this, 100, alarmIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.cancel(pi)
        } catch (_: Exception) {}
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        checkBatteryNow()
        scheduleAlarm()
        return START_STICKY
    }

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
        handler.removeCallbacksAndMessages(null)
        try { tts.stop() } catch (_: Exception) {}
        tts.shutdown()
        toneGen.release()
    }
}
