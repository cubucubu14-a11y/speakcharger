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
import android.media.RingtoneManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.core.app.NotificationCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BatteryAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())

    private var lastLevel = -1
    private var firstFire = true
    private var currentPct = -1
    private var isChargingNow = false
    private var isReceiverRegistered = false

    private var fullAlarmActive = false
    private var fullAlarmRunnable: Runnable? = null

    private val CH_MONITOR = "speakcharger_monitor_v5"
    private val CH_ALERT = "speakcharger_alert_v5"
    private val NOTIF_MONITOR = 1
    private val NOTIF_ALERT_BASE = 100

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_BATTERY_CHANGED) return
            handleBattery(intent)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()

        try { createChannels() } catch (e: Exception) {
            Log.e("SpeakCharger", "Channel error", e)
        }

        try {
            val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(batteryReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(batteryReceiver, filter)
            }
            isReceiverRegistered = true
        } catch (e: Exception) {
            Log.e("SpeakCharger", "Receiver error", e)
        }

        try {
            val sticky = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (sticky != null) handleBattery(sticky)
        } catch (e: Exception) {
            Log.e("SpeakCharger", "Battery init error", e)
        }

        updateMonitorNotification()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        stopFullAlarm()
        if (isReceiverRegistered) {
            try { unregisterReceiver(batteryReceiver) } catch (_: Exception) {}
            isReceiverRegistered = false
        }
        try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(NOTIF_MONITOR)
        } catch (_: Exception) {}
        super.onDestroy()
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
        Log.d("SpeakCharger", "Battery=$pct Charging=$isCharging")
        updateMonitorNotification()

        if (!isCharging) {
            stopFullAlarm()
            lastLevel = pct
            firstFire = true
            return
        }

        if (firstFire) {
            firstFire = false
            lastLevel = pct
            if (pct == 100) {
                fireAlert(100)
                startFullAlarm()
            }
            return
        }

        if (pct == lastLevel) return
        if (pct < lastLevel) { lastLevel = pct; return }

        val crossed = findCrossedLevel(lastLevel, pct, 5)
        lastLevel = pct

        if (crossed > 0) {
            fireAlert(crossed)
            if (crossed == 100) startFullAlarm()
        }
    }

    private fun findCrossedLevel(from: Int, to: Int, step: Int): Int {
        if (to <= from) return -1
        val target = to - (to % step)
        if (target <= from || target <= 0) return -1
        return target
    }

    private fun fireAlert(pct: Int) {
        // 1. Getar
        try {
            val vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
            if (vibrator.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= 26) {
                    vibrator.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(500)
                }
            }
        } catch (e: Exception) { Log.e("SpeakCharger", "Vibrate error", e) }

        // 2. Notif alert
        try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            val pi = PendingIntent.getActivity(
                this, pct,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val text = if (pct == 100) "Baterai FULL! Cabut kabel!" else "Baterai mencapai $pct%"

            val notification = NotificationCompat.Builder(this, CH_ALERT)
                .setContentTitle("SpeakCharger - $pct%")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_lock_idle_charging)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .build()

            nm.notify(NOTIF_ALERT_BASE + pct, notification)
            Log.d("SpeakCharger", "Alert fired: $pct%")
        } catch (e: Exception) { Log.e("SpeakCharger", "Alert error", e) }

        // 3. ★ Kirim ke AudioPlayerService (foreground mediaPlayback) ★
        try {
            val intent = Intent(this, AudioPlayerService::class.java).apply {
                putExtra("pct", pct)
            }
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            Log.d("SpeakCharger", "AudioPlayerService started: $pct")
        } catch (e: Exception) {
            Log.e("SpeakCharger", "Start audio service error", e)
        }
    }

    private fun startFullAlarm() {
        if (fullAlarmActive) return
        fullAlarmActive = true
        val runnable = object : Runnable {
            override fun run() {
                if (!fullAlarmActive || !isChargingNow) { stopFullAlarm(); return }
                if (currentPct < 100) { stopFullAlarm(); return }
                fireAlert(100)
                handler.postDelayed(this, 6000)
            }
        }
        fullAlarmRunnable = runnable
        handler.postDelayed(runnable, 6000)
    }

    private fun stopFullAlarm() {
        fullAlarmActive = false
        fullAlarmRunnable?.let { handler.removeCallbacks(it) }
        fullAlarmRunnable = null
        try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(NOTIF_ALERT_BASE + 100)
        } catch (_: Exception) {}
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

            val monitorChannel = NotificationChannel(
                CH_MONITOR, "Monitor", NotificationManager.IMPORTANCE_LOW
            )
            monitorChannel.setSound(null, null)
            monitorChannel.enableVibration(false)
            nm.createNotificationChannel(monitorChannel)

            val alertChannel = NotificationChannel(
                CH_ALERT, "Alert Baterai", NotificationManager.IMPORTANCE_HIGH
            )
            val sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            if (sound != null) {
                val attributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                alertChannel.setSound(sound, attributes)
            }
            alertChannel.enableVibration(true)
            alertChannel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC)
            nm.createNotificationChannel(alertChannel)
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
        return NotificationCompat.Builder(this, CH_MONITOR)
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
            nm.notify(NOTIF_MONITOR, buildMonitorNotification())
        } catch (e: Exception) { Log.e("SpeakCharger", "Monitor error", e) }
    }
}
