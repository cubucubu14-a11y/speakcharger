package com.speakcharger.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
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
    private lateinit var ttsManager: TtsManager
    private var isServiceRunning = false

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_BATTERY_CHANGED) {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                val pct = level * 100 / scale
                val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                                 status == BatteryManager.BATTERY_STATUS_FULL

                tvBattery.text = "Baterai: $pct%\n" +
                    if (isCharging) "Status: Sedang charging ⚡"
                    else "Status: Tidak charging"
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

        ttsManager = TtsManager(this)

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
                tvStatus.text = "✅ Aktif — silakan colok charger"
                Toast.makeText(this, "Aktif! Sekarang colok charger.", Toast.LENGTH_LONG).show()
            }
        }

        btnTest.setOnClickListener {
            ttsManager.speak("Tes suara. Baterai lima puluh persen. Baterai full, cabut kabel.")
            Toast.makeText(this, "Memutar tes suara...", Toast.LENGTH_SHORT).show()
        }

        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
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
