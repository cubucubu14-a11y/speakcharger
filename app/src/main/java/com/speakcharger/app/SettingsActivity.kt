=== MULAI ===
package com.speakcharger.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    private lateinit var ttsManager: TtsManager
    private val PICK_AUDIO = 300
    private var pendingPct = -1

    private val levels: List<Int> = (1..20).map { it * 5 }
    private val refreshMap = mutableMapOf<Int, () -> Unit>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        ttsManager = TtsManager(this)
        val container = findViewById<LinearLayout>(R.id.container)

        for (pct in levels) {
            val row = layoutInflater.inflate(R.layout.row_sound_item, container, false)
            val tvPct = row.findViewById<TextView>(R.id.tvPct)
            val tvStatus = row.findViewById<TextView>(R.id.tvStatus)
            val btnImport = row.findViewById<Button>(R.id.btnImport)
            val btnPreview = row.findViewById<Button>(R.id.btnPreview)
            val btnReset = row.findViewById<Button>(R.id.btnReset)

            tvPct.text = "Baterai $pct%"

            val refresh = {
                if (ttsManager.hasCustomSound(pct)) {
                    tvStatus.text = "MP3 Custom aktif"
                    tvStatus.setTextColor(0xFF4CAF50.toInt())
                    btnReset.visibility = View.VISIBLE
                } else {
                    tvStatus.text = "TTS Default"
                    tvStatus.setTextColor(0xFF9E9E9E.toInt())
                    btnReset.visibility = View.GONE
                }
                Unit
            }
            refreshMap[pct] = refresh
            refresh()

            btnImport.setOnClickListener {
                pendingPct = pct
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    type = "audio/*"
                    addCategory(Intent.CATEGORY_OPENABLE)
                }
                try {
                    startActivityForResult(intent, PICK_AUDIO)
                } catch (_: Exception) {
                    val intent2 = Intent(Intent.ACTION_GET_CONTENT).apply {
                        type = "audio/*"
                    }
                    startActivityForResult(intent2, PICK_AUDIO)
                }
            }

            btnPreview.setOnClickListener {
                Toast.makeText(this, "Memutar $pct%...", Toast.LENGTH_SHORT).show()
                ttsManager.playFor(pct) {}
            }

            btnReset.setOnClickListener {
                ttsManager.deleteCustomSound(pct)
                refresh()
                Toast.makeText(this, "Reset $pct% ke TTS", Toast.LENGTH_SHORT).show()
            }

            container.addView(row)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PICK_AUDIO && resultCode == Activity.RESULT_OK) {
            val uri: Uri = data?.data ?: return
            val pct = pendingPct
            if (pct <= 0) return

            if (ttsManager.saveCustomSound(pct, uri)) {
                Toast.makeText(this, "MP3 untuk $pct% tersimpan", Toast.LENGTH_SHORT).show()
                refreshMap[pct]?.invoke()
            } else {
                Toast.makeText(this, "Gagal simpan file", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { ttsManager.stop() } catch (_: Exception) {}
    }
}
=== SELESAI ===
