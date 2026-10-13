package com.speakcharger.app

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var tvAccessStatus: TextView
    private lateinit var tvBattery: TextView
    private lateinit var tvTimer: TextView
    private lateinit var tvTimerLabel: TextView
    private lateinit var tvLastSession: TextView
    private lateinit var btnAccessibility: Button
    private lateinit var ttsManager: TtsManager

    private lateinit var prefs: SharedPreferences

    private val handler = Handler(Looper.getMainLooper())
    private var chargingStartTime = 0L
    private var chargingStartBat = -1
    private var isCharging = false
    private var currentPct = -1

    private val KEY_LAST_DURATION = "last_charge_duration"
    private val KEY_LAST_START_BAT = "last_charge_start_bat"
    private val KEY_LAST_END_BAT = "last_charge_end_bat"
    private val KEY_LAST_DATE = "last_charge_date"

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_BATTERY_CHANGED) return
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            val pct = level * 100 / scale
            val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val nowCharging = plugged != 0 ||
                    status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL

            val wasCharging = isCharging
            currentPct = pct
            isCharging = nowCharging

            if (nowCharging && !wasCharging) {
                chargingStartTime = System.currentTimeMillis()
                chargingStartBat = pct
                tvTimerLabel.text = "Sedang Charging"
                tvTimer.setTextColor(0xFF4CAF50.toInt())
                tvLastSession.text = ""
                startTimerUI()
            }
            if (!nowCharging && wasCharging) {
                saveLastSession()
                stopTimerUI()
                tvTimerLabel.text = "Selesai Charging"
                tvTimer.setTextColor(0xFF9E9E9E.toInt())
            }

            updateBatteryUI()
        }
    }

    private val timerUIRunnable = object : Runnable {
        override fun run() {
            if (isCharging && chargingStartTime > 0) {
                val elapsed = System.currentTimeMillis() - chargingStartTime
                tvTimer.text = "⏱ " + formatDuration(elapsed)
                handler.postDelayed(this, 1000)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("speakcharger_prefs", MODE_PRIVATE)

        tvAccessStatus = findViewById(R.id.tvAccessStatus)
        tvBattery = findViewById(R.id.tvBattery)
        tvTimer = findViewById(R.id.tvTimer)
        tvTimerLabel = findViewById(R.id.tvTimerLabel)
        tvLastSession = findViewById(R.id.tvLastSession)
        btnAccessibility = findViewById(R.id.btnAccessibility)

        ttsManager = TtsManager(this)
        ttsManager.renderAllToCache {}

        btnAccessibility.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                Toast.makeText(this, "Cari SpeakCharger, aktifkan", Toast.LENGTH_LONG).show()
            } catch (_: Exception) {}
        }

        findViewById<Button>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        findViewById<Button>(R.id.btnTest).setOnClickListener {
            ttsManager.playFor(100) {}
        }

        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        updateStatus()
        loadLastSession()
    }

    private fun formatDuration(ms: Long): String {
        val h = ms / 3600000
        val m = (ms % 3600000) / 60000
        val s = (ms % 60000) / 1000
        return String.format(Locale.getDefault(), "%02d:%02d:%02d", h, m, s)
    }

    private fun saveLastSession() {
        if (chargingStartTime <= 0) return
        val duration = System.currentTimeMillis() - chargingStartTime
        val dateStr = SimpleDateFormat("dd MMM HH:mm", Locale.getDefault()).format(Date())

        prefs.edit()
            .putLong(KEY_LAST_DURATION, duration)
            .putInt(KEY_LAST_START_BAT, chargingStartBat)
            .putInt(KEY_LAST_END_BAT, currentPct)
            .putString(KEY_LAST_DATE, dateStr)
            .apply()

        loadLastSession()
    }

    private fun loadLastSession() {
        val duration = prefs.getLong(KEY_LAST_DURATION, -1L)
        if (duration <= 0) {
            tvLastSession.text = ""
            return
        }
        val startBat = prefs.getInt(KEY_LAST_START_BAT, -1)
        val endBat = prefs.getInt(KEY_LAST_END_BAT, -1)
        val dateStr = prefs.getString(KEY_LAST_DATE, "") ?: ""
        val batStr = if (startBat >= 0 && endBat >= 0) " • $startBat% → $endBat%" else ""
        tvLastSession.text = "Terakhir: $dateStr • ${formatDuration(duration)}$batStr"
    }

    private fun updateStatus() {
        if (isAccessibilityEnabled()) {
            tvAccessStatus.text = "AKTIF"
            tvAccessStatus.setTextColor(0xFF4CAF50.toInt())
            btnAccessibility.text = "1. Aksesibilitas AKTIF ✓"
            btnAccessibility.backgroundTintList = ColorStateList.valueOf(0xFF4CAF50.toInt())
        } else {
            tvAccessStatus.text = "BELUM AKTIF"
            tvAccessStatus.setTextColor(0xFFF44336.toInt())
            btnAccessibility.text = "1. Aktifkan Aksesibilitas"
            btnAccessibility.backgroundTintList = ColorStateList.valueOf(0xFFE91E63.toInt())
        }
    }

    private fun updateBatteryUI() {
        val status = if (isCharging) "charging" else "tidak charging"
        tvBattery.text = "$currentPct% · $status"
    }

    private fun startTimerUI() {
        handler.removeCallbacks(timerUIRunnable)
        handler.post(timerUIRunnable)
    }

    private fun stopTimerUI() {
        handler.removeCallbacks(timerUIRunnable)
    }

    private fun isAccessibilityEnabled(): Boolean {
        return try {
            val am = getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
            val list = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            list.any {
                it.resolveInfo.serviceInfo.packageName == packageName &&
                it.resolveInfo.serviceInfo.name == BatteryAccessibilityService::class.java.name
            }
        } catch (_: Exception) { false }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        try { unregisterReceiver(batteryReceiver) } catch (_: Exception) {}
        ttsManager.shutdown()
    }
}
