package com.speakcharger.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class AlarmTriggerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pct = intent.getIntExtra("pct", -1)
        Log.d("SpeakCharger", "AlarmTriggerReceiver fired: pct=$pct")
        if (pct <= 0) return

        try {
            val svc = Intent(context, AudioPlayerService::class.java).apply {
                putExtra("pct", pct)
            }
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(svc)
            } else {
                context.startService(svc)
            }
            Log.d("SpeakCharger", "Service started from receiver")
        } catch (e: Exception) {
            Log.e("SpeakCharger", "Start service error", e)
        }
    }
}
