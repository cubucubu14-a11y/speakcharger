package com.speakcharger.app

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.os.BatteryManager
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var tvAccessStatus: TextView
    private lateinit var tvBattery: TextView
    private lateinit var btnAccessibility: Button
    private lateinit var ttsManager: TtsManager

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_BATTERY_CHANGED) {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                val pct = level * 100 / scale
                val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
                val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                val isCharging = plugged != 0 ||
                                 status == BatteryManager.BATTERY_STATUS_CHARGING ||
                                 status == BatteryManager.BATTERY_STATUS_FULL
                tvBattery.text = if (isCharging) "$pct% · charging" else "$pct%"
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvAccessStatus = findViewById(R.id.tvAccessStatus)
        tvBattery = findViewById(R.id.tvBattery)
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
    }

    private fun updateStatus() {
        if (isAccessibilityEnabled()) {
            tvAccessStatus.text = "AKTIF"
            tvAccessStatus.setTextColor(0xFF4CAF50.toInt())
            btnAccessibility.text = "Aktif ✓"
            btnAccessibility.backgroundTintList = ColorStateList.valueOf(0xFF4CAF50.toInt())
        } else {
            tvAccessStatus.text = "BELUM AKTIF"
            tvAccessStatus.setTextColor(0xFFF44336.toInt())
            btnAccessibility.text = "Aktifkan"
            btnAccessibility.backgroundTintList = ColorStateList.valueOf(0xFFE91E63.toInt())
        }
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
        try { unregisterReceiver(batteryReceiver) } catch (_: Exception) {}
        ttsManager.shutdown()
    }
}
