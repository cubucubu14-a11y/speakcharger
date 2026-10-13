package com.speakcharger.app

import android.accessibilityservice.AccessibilityService
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.core.app.NotificationCompat
import java.util.Locale

class BatteryAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())

    private var lastLevel = -1
    private var firstFire = true
    private var currentPct = -1
    private var isChargingNow = false
    private var isReceiverRegistered = false

    // Timer state
    private var chargingStartTime = 0L
    private var chargingStartBattery = -1
    private var isTimerActive = false

    private val CH_MONITOR = "speakcharger_monitor_v8"
    private val NOTIF_MONITOR = 1
    private val ALARM_REQUEST_CODE = 7777

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_BATTERY_CHANGED) return
            handleBattery(intent)
        }
    }

    // ★ Timer update tiap 1 detik + baca baterai langsung
    private val timerRunnable = object : Runnable {
        override fun run() {
            if (isTimerActive && isChargingNow) {
                refreshBatteryDirectly()
                updateMonitorNotification()
                handler.postDelayed(this, 1000)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        try { createChannel() } catch (e: Exception) { Log.e("SpeakCharger", "Ch err", e) }

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
            Log.e("SpeakCharger", "Reg err", e)
        }

        try {
            val sticky = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (sticky != null) handleBattery(sticky)
        } catch (_: Exception) {}

        updateMonitorNotification()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
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

    // ★ Baca baterai langsung dari sistem — gak nunggu broadcast
    private fun refreshBatteryDirectly() {
        try {
            val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
            val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            if (pct >= 0 && pct != currentPct) {
                val oldPct = currentPct
                currentPct = pct
                Log.d("SpeakCharger", "Direct read: $oldPct -> $pct")

                // Cek kelipatan 5 dari polling
                if (isChargingNow && !firstFire) {
                    if (pct > lastLevel) {
                        val crossed = findCrossedLevel(lastLevel, pct, 5)
                        if (crossed > 0) {
                            lastLevel = pct
                            triggerViaAlarm(crossed)
                        } else {
                            lastLevel = pct
                        }
                    }
                }
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

        val wasCharging = isChargingNow

        currentPct = pct
        isChargingNow = isCharging

        // Timer START saat charging baru mulai
        if (isCharging && !wasCharging) {
            chargingStartTime = System.currentTimeMillis()
            chargingStartBattery = pct
            isTimerActive = true
            handler.removeCallbacks(timerRunnable)
            handler.post(timerRunnable)
            Log.d("SpeakCharger", "Timer START at $pct%")
        }

        // Timer STOP saat charging berhenti
        if (!isCharging && wasCharging) {
            isTimerActive = false
            handler.removeCallbacks(timerRunnable)
            Log.d("SpeakCharger", "Timer STOP")
        }

        updateMonitorNotification()

        if (!isCharging) {
            lastLevel = pct
            firstFire = true
            return
        }

        if (firstFire) {
            firstFire = false
            lastLevel = pct
            if (pct == 100) triggerViaAlarm(100)
            return
        }

        if (pct == lastLevel) return
        if (pct < lastLevel) { lastLevel = pct; return }

        val crossed = findCrossedLevel(lastLevel, pct, 5)
        lastLevel = pct

        if (crossed > 0) triggerViaAlarm(crossed)
    }

    private fun findCrossedLevel(from: Int, to: Int, step: Int): Int {
        if (to <= from) return -1
        val target = to - (to % step)
        if (target <= from || target <= 0) return -1
        return target
    }

    private fun triggerViaAlarm(pct: Int) {
        try {
            val alarmManager = getSystemService(ALARM_SERVICE) as AlarmManager
            val intent = Intent(this, AlarmTriggerReceiver::class.java).apply {
                action = "SPEAKCHARGER_ALARM_TRIGGER"
                putExtra("pct", pct)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val pi = PendingIntent.getBroadcast(this, ALARM_REQUEST_CODE, intent, flags)
            val triggerAt = SystemClock.elapsedRealtime() + 100

            if (Build.VERSION.SDK_INT >= 23) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi
                )
            } else {
                alarmManager.setExact(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi
                )
            }
            Log.d("SpeakCharger", "AlarmManager set for $pct%")
        } catch (e: Exception) {
            Log.e("SpeakCharger", "AlarmManager err", e)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(
                CH_MONITOR, "Monitor", NotificationManager.IMPORTANCE_LOW
            )
            ch.setSound(null, null)
            ch.enableVibration(false)
            nm.createNotificationChannel(ch)
        }
    }

    private fun buildMonitorNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val pctStr = if (currentPct >= 0) "$currentPct%" else "--"

        val title: String
        val text: String

        if (isChargingNow && isTimerActive && chargingStartTime > 0) {
            val elapsed = System.currentTimeMillis() - chargingStartTime
            val h = elapsed / 3600000
            val m = (elapsed % 3600000) / 60000
            val s = (elapsed % 60000) / 1000
            val timeStr = String.format(Locale.getDefault(), "%02d:%02d:%02d", h, m, s)
            val startB = if (chargingStartBattery >= 0) "$chargingStartBattery%" else "--"
            title = "🔋 Charging"
            text = "⏱ $timeStr • $startB → $pctStr"
        } else if (isChargingNow) {
            title = "🔋 Charging"
            text = "Baterai $pctStr"
        } else {
            title = "SpeakCharger Aktif"
            text = "Baterai $pctStr - tidak charging"
        }

        return NotificationCompat.Builder(this, CH_MONITOR)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_idle_charging)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pi)
            .build()
    }

    private fun updateMonitorNotification() {
        try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_MONITOR, buildMonitorNotification())
        } catch (_: Exception) {}
    }
}
