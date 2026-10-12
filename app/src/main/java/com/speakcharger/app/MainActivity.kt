package com.speakcharger.app

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var tvBattery: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvPrepare: TextView
    private lateinit var tvAccessStatus: TextView
    private lateinit var btnAccessibility: Button
    private lateinit var btnSettings: Button
    private lateinit var btnTest: Button
    private lateinit var ttsManager: TtsManager

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_BATTERY_CHANGED) {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                val pct = level * 100 / scale
                val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
                val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                val isCharging = plugged == BatteryManager.BATTERY_PLUGGED_AC ||
                                 plugged == BatteryManager.BATTERY_PLUGGED_USB ||
                                 plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS ||
                                 status == BatteryManager.BATTERY_STATUS_CHARGING ||
                                 status == BatteryManager.BATTERY_STATUS_FULL
                tvBattery.text = "Baterai: $pct%\n" +
                    if (isCharging) "Status: Sedang charging" else "Status: Tidak charging"
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvBattery = findViewById(R.id.tvBattery)
        tvStatus = findViewById(R.id.tvStatus)
        tvPrepare = findViewById(R.id.tvPrepare)
        tvAccessStatus = findViewById(R.id.tvAccessStatus)
        btnAccessibility = findViewById(R.id.btnAccessibility)
        btnSettings = findViewById(R.id.btnSettings)
        btnTest = findViewById(R.id.btnTest)

        ttsManager = TtsManager(this)

        tvPrepare.text = "Menyiapkan file suara..."
        tvPrepare.visibility = android.view.View.VISIBLE
        ttsManager.renderAllToCache {
            tvPrepare.text = "Suara siap"
            tvPrepare.postDelayed({ tvPrepare.visibility = android.view.View.GONE }, 2000)
        }

        btnAccessibility.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                Toast.makeText(this, "Cari 'SpeakCharger' dan aktifkan", Toast.LENGTH_LONG).show()
            } catch (_: Exception) {
                Toast.makeText(this, "Gak bisa buka Settings", Toast.LENGTH_SHORT).show()
            }
        }

        btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        btnTest.setOnClickListener {
            Toast.makeText(this, "Memutar tes suara...", Toast.LENGTH_SHORT).show()
            ttsManager.playFor(50) {
                android.os.Handler(mainLooper).postDelayed({
                    ttsManager.playFor(100) {}
                }, 1500)
            }
        }

        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        updateAccessStatus()
    }

    private fun updateAccessStatus() {
        if (isAccessibilityServiceEnabled()) {
            tvAccessStatus.text = "Aksesibilitas: AKTIF"
            tvAccessStatus.setTextColor(0xFF4CAF50.toInt())
            btnAccessibility.text = "AKSESIBILITAS AKTIF"
            btnAccessibility.isEnabled = false
            tvStatus.text = "Siap! Colok charger, suara otomatis bunyi tiap 5% walau layar mati."
        } else {
            tvAccessStatus.text = "Aksesibilitas: BELUM AKTIF"
            tvAccessStatus.setTextColor(0xFFF44336.toInt())
            btnAccessibility.text = "1. AKTIFKAN AKSESIBILITAS"
            btnAccessibility.isEnabled = true
            tvStatus.text = "Tekan tombol merah muda di atas, cari SpeakCharger, aktifkan."
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        return try {
            val am = getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
            val enabled = am.getEnabledAccessibilityServiceList(
                AccessibilityServiceInfo.FEEDBACK_ALL_MASK
            )
            for (svc in enabled) {
                val info = svc.resolveInfo.serviceInfo
                if (info.packageName == packageName &&
                    info.name == BatteryAccessibilityService::class.java.name) {
                    return true
                }
            }
            false
        } catch (_: Exception) { false }
    }

    override fun onResume() {
        super.onResume()
        updateAccessStatus()
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(batteryReceiver) } catch (_: Exception) {}
        ttsManager.shutdown()
    }
}
