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
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
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
    private var isFullAlertActive = false

    private var chargingStartTime = 0L
    private var chargingStartBattery = -1
    private var isTimerActive = false

    private val CH_MONITOR = "speakcharger_monitor_v9"
    private val NOTIF_MONITOR = 1

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_BATTERY_CHANGED) return
            try {
                handleBattery(intent)
            } catch (e: Exception) {
                Log.e("SpeakCharger", "handleBattery err", e)
            }
        }
    }

    private val timerRunnable = object : Runnable {
        override fun run() {
            try {
                if (isTimerActive && isChargingNow) {
                    refreshBatteryDirectly()
                    updateMonitorNotification()
                    handler.postDelayed(this, 1000)
                }
            } catch (e: Exception) {
                Log.e("SpeakCharger", "Timer err", e)
                handler.postDelayed(this, 2000)
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

    private fun refreshBatteryDirectly() {
        try {
            val bm = applicationContext.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
                ?: return
            val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            if (pct in 0..100 && pct != currentPct) {
                val oldPct = currentPct
                currentPct = pct
                Log.d("SpeakCharger", "Direct read: $oldPct -> $pct")

                if (isChargingNow && !firstFire && pct > lastLevel) {
                    try {
                        val crossed = findCrossedLevel(lastLevel, pct, 5)
                        if (crossed > 0) {
                            lastLevel = pct
                            if (crossed == 100) {
                                if (!isFullAlertActive) {
                                    isFullAlertActive = true
                                    fireAlert(100)
                                }
                            } else {
                                fireAlert(crossed)
                            }
                        } else {
                            lastLevel = pct
                        }
                    } catch (e: Exception) {
                        Log.e("SpeakCharger", "Crossed err", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("SpeakCharger", "Refresh err", e)
        }
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

        // Charging START
        if (isCharging && !wasCharging) {
            chargingStartTime = System.currentTimeMillis()
            chargingStartBattery = pct
            isTimerActive = true
            isFullAlertActive = false
            firstFire = true
            lastLevel = pct
            handler.removeCallbacks(timerRunnable)
            handler.postDelayed(timerRunnable, 1000)
            startAudioService(AudioPlayerService.ACTION_PREPARE, -1)
            Log.d("SpeakCharger", "Charging START at $pct%")
        }

        // Charging STOP
        if (!isCharging && wasCharging) {
            isTimerActive = false
            isFullAlertActive = false
            handler.removeCallbacks(timerRunnable)
            startAudioService(AudioPlayerService.ACTION_STOP, -1)
            Log.d("SpeakCharger", "Charging STOP")
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
            return
        }

        if (pct == lastLevel) return
        if (pct < lastLevel) { lastLevel = pct; return }

        val crossed = findCrossedLevel(lastLevel, pct, 5)
        lastLevel = pct

        if (crossed > 0) {
            if (crossed == 100) {
                if (!isFullAlertActive) {
                    isFullAlertActive = true
                    fireAlert(100)
                }
            } else {
                fireAlert(crossed)
            }
        }
    }

    private fun findCrossedLevel(from: Int, to: Int, step: Int): Int {
        if (to <= from) return -1
        val target = to - (to % step)
        if (target <= from || target <= 0) return -1
        return target
    }

    private fun startAudioService(action: String, pct: Int) {
        try {
            val intent = Intent(applicationContext, AudioPlayerService::class.java).apply {
                this.action = action
                if (pct > 0) putExtra(AudioPlayerService.EXTRA_PCT, pct)
            }
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            Log.d("SpeakCharger", "AudioService cmd: $action pct=$pct")
        } catch (e: Exception) {
            Log.e("SpeakCharger", "AudioService err", e)
        }
    }

    private fun fireAlert(pct: Int) {
        startAudioService(AudioPlayerService.ACTION_PLAY, pct)
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
