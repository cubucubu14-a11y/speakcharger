package com.speakcharger.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var tvBattery: TextView
    private lateinit var tvStatus: TextView
    private lateinit var btnToggle: Button
    private lateinit var btnTest: Button
    private lateinit var btnPermissions: Button
    private lateinit var ttsManager: TtsManager
    private var isServiceRunning = false

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
        btnToggle = findViewById(R.id.btnToggle)
        btnTest = findViewById(R.id.btnTest)
        btnPermissions = findViewById(R.id.btnPermissions)

        ttsManager = TtsManager(this)

        // Minta POST_NOTIFICATIONS
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }

        btnToggle.setOnClickListener {
            if (isServiceRunning) {
                stopService(Intent(this, BatteryService::class.java))
                isServiceRunning = false
                updateButtonUI()
                tvStatus.text = "Service tidak aktif"
            } else {
                val intent = Intent(this, BatteryService::class.java)
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent)
                else startService(intent)
                isServiceRunning = true
                updateButtonUI()
                tvStatus.text = "Aktif - silakan colok charger"
                Toast.makeText(this, "Aktif! Sekarang colok charger.", Toast.LENGTH_LONG).show()
            }
        }

        btnTest.setOnClickListener {
            ttsManager.speak("Tes suara. Baterai lima puluh persen. Baterai full, cabut kabel.")
            Toast.makeText(this, "Memutar tes suara...", Toast.LENGTH_SHORT).show()
        }

        btnPermissions.setOnClickListener {
            openPermissionSettings()
        }

        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }

    // Buka pengaturan supaya user kasih izin battery optimization & alarm
    private fun openPermissionSettings() {
        // 1. Minta ignore battery optimization
        try {
            if (Build.VERSION.SDK_INT >= 23) {
                val pm = getSystemService(POWER_SERVICE) as PowerManager
                if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    intent.data = Uri.parse("package:$packageName")
                    startActivity(intent)
                }
            }
        } catch (_: Exception) {}

        // 2. Minta izin exact alarm (Android 12+)
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                val am = getSystemService(ALARM_SERVICE) as android.app.AlarmManager
                if (!am.canScheduleExactAlarms()) {
                    val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                    intent.data = Uri.parse("package:$packageName")
                    startActivity(intent)
                }
            }
        } catch (_: Exception) {}

        Toast.makeText(this,
            "Aktifkan izin yang muncul. Kalau sudah, balik ke app & tekan AKTIFKAN.",
            Toast.LENGTH_LONG).show()
    }

    private fun updateButtonUI() {
        if (isServiceRunning) {
            btnToggle.text = "MATIKAN"
            btnToggle.backgroundTintList = ColorStateList.valueOf(0xFFF44336.toInt())
        } else {
            btnToggle.text = "AKTIFKAN"
            btnToggle.backgroundTintList = ColorStateList.valueOf(0xFF4CAF50.toInt())
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(batteryReceiver) } catch (_: Exception) {}
        ttsManager.shutdown()
    }
}
