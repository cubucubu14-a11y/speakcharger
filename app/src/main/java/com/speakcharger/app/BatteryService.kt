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
    private var fullRepeatRunnable: Runnable? = null

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
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                         status == BatteryManager.BATTERY_STATUS_FULL

        updateNotification(
            "Baterai $pct% — " + if (isCharging) "charging" else "tidak charging"
        )

        if (firstFire) {
            firstFire = false
            lastSpokenLevel = pct
            if (isCharging) everCharged = true
            return
        }

        if (isCharging) {
            everCharged = true
            if (pct == lastSpokenLevel) return
            if (pct % 10 != 0 || pct <= 0) return

            lastSpokenLevel = pct
            triggerAlert(pct)
        } else {
            if (everCharged) {
                stopFullRepeat()
                stopSelf()
            }
        }
    }

    private fun triggerAlert(pct: Int) {
        if (pct == 100) {
            handler.postDelayed({ toneGen.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 300) }, 0)
            handler.postDelayed({ toneGen.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 300) }, 350)
            handler.postDelayed({ toneGen.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 300) }, 700)
            handler.postDelayed({ tts.speak("Baterai full, cabut kabel") }, 1100)
            startFullRepeat()
        } else {
            toneGen.startTone(ToneGenerator.TONE_PROP_BEEP, 400)
            val kata = when (pct) {
                10 -> "sepuluh"
                20 -> "dua puluh"
                30 -> "tiga puluh"
                40 -> "empat puluh"
                50 -> "lima puluh"
                60 -> "enam puluh"
                70 -> "tujuh puluh"
                80 -> "delapan puluh"
                90 -> "sembilan puluh"
                else -> "$pct"
            }
            handler.postDelayed({ tts.speak("Baterai $kata persen") }, 500)
        }
    }

    private fun startFullRepeat() {
        stopFullRepeat()
        fullRepeatRunnable = object : Runnable {
            override fun run() {
                toneGen.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 300)
                handler.postDelayed({ toneGen.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 300) }, 350)
                handler.postDelayed({ toneGen.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 300) }, 700)
                handler.postDelayed({ tts.speak("Baterai full, cabut kabel") }, 1100)
                fullRepeatRunnable?.let { handler.postDelayed(it, 60000) }
            }
        }
        fullRepeatRunnable?.let { handler.postDelayed(it, 60000) }
    }

    private fun stopFullRepeat() {
        fullRepeatRunnable?.let { handler.removeCallbacks(it) }
        fullRepeatRunnable = null
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
        stopFullRepeat()
        handler.removeCallbacksAndMessages(null)
        tts.shutdown()
        toneGen.release()
    }
}
