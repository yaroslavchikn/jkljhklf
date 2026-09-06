package com.cotrix.funkedit

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.cotrix.funkedit.engine.EditEngine

class MainActivity : AppCompatActivity() {

    private var clips: List<Uri> = emptyList()
    private var music: Uri? = null

    private lateinit var tvClips: TextView
    private lateinit var tvMusic: TextView
    private lateinit var etTitle: EditText
    private lateinit var etWater: EditText
    private lateinit var btnGo: Button
    private lateinit var progress: ProgressBar
    private lateinit var tvStatus: TextView

    private val pickClips = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        clips = uris.toList()
        uris.forEach {
            runCatching {
                contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        tvClips.text = if (uris.isEmpty()) "ничего не выбрано" else "Нарезок выбрано: ${uris.size}"
    }

    private val pickMusic = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        music = uri
        uri?.let {
            runCatching {
                contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            tvMusic.text = nameOf(it)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        tvClips = findViewById(R.id.tvClips)
        tvMusic = findViewById(R.id.tvMusic)
        etTitle = findViewById(R.id.etTitle)
        etWater = findViewById(R.id.etWater)
        btnGo = findViewById(R.id.btnGo)
        progress = findViewById(R.id.progress)
        tvStatus = findViewById(R.id.tvStatus)

        findViewById<Button>(R.id.btnClips).setOnClickListener {
            pickClips.launch(arrayOf("video/*"))
        }
        findViewById<Button>(R.id.btnMusic).setOnClickListener {
            pickMusic.launch(arrayOf("audio/*"))
        }
        btnGo.setOnClickListener { startRender() }
    }

    private fun nameOf(uri: Uri): String {
        return runCatching {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (i >= 0 && c.moveToFirst()) c.getString(i) else "трек"
            } ?: "трек"
        }.getOrDefault("трек")
    }

    private fun startRender() {
        val m = music
        if (clips.isEmpty()) { toast("Сначала выбери нарезки"); return }
        if (m == null) { toast("Сначала выбери фонк"); return }

        val title = etTitle.text.toString().ifBlank { "SUMMER" }
        val wm = etWater.text.toString().ifBlank { "COTRIX" }

        btnGo.isEnabled = false
        progress.visibility = View.VISIBLE
        progress.progress = 0
        tvStatus.text = "Стартую…"

        Thread {
            try {
                val out = EditEngine(this, clips, m, title, wm) { msg, pct ->
                    runOnUiThread {
                        tvStatus.text = msg
                        progress.progress = pct
                    }
                }.run()
                runOnUiThread { showDone(out) }
            } catch (e: Exception) {
                runOnUiThread {
                    tvStatus.text = "Ошибка: ${e.message}"
                    toast("Ошибка: ${e.message}")
                }
            } finally {
                runOnUiThread {
                    btnGo.isEnabled = true
                    progress.visibility = View.GONE
                }
            }
        }.start()
    }

    private fun showDone(uri: Uri) {
        AlertDialog.Builder(this)
            .setTitle("Эдит готов")
            .setMessage("Сохранено:\n$uri")
            .setPositiveButton("Открыть") { _, _ ->
                runCatching {
                    startActivity(
                        Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, "video/mp4")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                    )
                }
            }
            .setNegativeButton("Ок", null)
            .show()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
