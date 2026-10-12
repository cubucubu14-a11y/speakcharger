package com.speakcharger.app

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent

class BatteryAccessibilityService : AccessibilityService() {

    private lateinit var tts: TtsManager
    private lateinit var toneGen: ToneGenerator

    private var lastSpokenLevel = -1
    private var firstFire = true
    private var everCharged = false

    private val handler = Handler(Looper.getMainLooper())
    private var alertGeneration = 0

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_BATTERY_CHANGED) return
            handleBattery(intent)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        try {
            tts = TtsManager(this)
            toneGen = ToneGenerator(AudioManager.STREAM_ALARM, 100)
            registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            checkBatteryNow()
        } catch (_: Exception) {}
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(batteryReceiver) } catch (_: Exception) {}
        try { stopAllPlayback() } catch (_: Exception) {}
        try { tts.shutdown() } catch (_: Exception) {}
        try { toneGen.release() } catch (_: Exception) {}
    }

    private fun checkBatteryNow() {
        try {
            val sticky = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (sticky != null) handleBattery(sticky)
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
            if (everCharged) stopAllPlayback()
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
        stopAllPlayback()
        alertGeneration++
        val gen = alertGeneration
        if (pct == 100) playFullLoop(gen) else playLevelSequence(gen, pct, 0)
    }

    private fun playBeepThenFile(pct: Int, onFinish: () -> Unit) {
        try { toneGen.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 350) } catch (_: Exception) {}
        handler.postDelayed({
            try { tts.playFor(pct) { onFinish() } } catch (_: Exception) { onFinish() }
        }, 500)
    }

    private fun playLevelSequence(gen: Int, pct: Int, iteration: Int) {
        if (gen != alertGeneration) return
        if (iteration >= 3) return
        playBeepThenFile(pct) {
            if (gen != alertGeneration) return@playBeepThenFile
            handler.postDelayed({
                if (gen == alertGeneration) playLevelSequence(gen, pct, iteration + 1)
            }, 1200)
        }
    }

    private fun playFullLoop(gen: Int) {
        if (gen != alertGeneration) return
        playBeepThenFile(100) {
            if (gen != alertGeneration) return@playBeepThenFile
            handler.postDelayed({
                if (gen == alertGeneration) playFullLoop(gen)
            }, 1500)
        }
    }

    private fun stopAllPlayback() {
        alertGeneration++
        handler.removeCallbacksAndMessages(null)
        try { tts.stop() } catch (_: Exception) {}
        try { toneGen.stopTone() } catch (_: Exception) {}
    }
}
