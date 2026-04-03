package com.aiassistant

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class BackendActivity : AppCompatActivity() {

    private lateinit var tvBackendStatus: TextView
    private lateinit var tvBackendTranscript: TextView
    private lateinit var tvBackendTts: TextView
    private lateinit var tvBackendLog: TextView
    private lateinit var scrollBackendLog: ScrollView

    private val receiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: Intent) {
            when (intent.action) {
                MainActivity.ACTION_TRANSCRIPT -> {
                    val text = intent.getStringExtra("text") ?: return
                    AssistantUiState.transcript = text
                    tvBackendTranscript.text = text
                }
                MainActivity.ACTION_COMMAND_LOG -> {
                    val log = intent.getStringExtra("log") ?: return
                    AssistantUiState.appendLog(log)
                    tvBackendLog.text = AssistantUiState.allLogs()
                    scrollBackendLog.post { scrollBackendLog.fullScroll(View.FOCUS_DOWN) }
                }
                MainActivity.ACTION_TTS_SPEAK -> {
                    val msg = intent.getStringExtra("message") ?: return
                    AssistantUiState.lastTts = msg
                    tvBackendTts.text = msg
                }
                MainActivity.ACTION_STATE_CHANGE -> {
                    val state = intent.getStringExtra("state") ?: return
                    val label = when (state) {
                        "standby" -> "Status: Waiting for Hey Vox"
                        "listening" -> "Status: Listening for command"
                        "executing" -> "Status: Executing"
                        else -> "Status: $state"
                    }
                    AssistantUiState.statusLabel = label
                    tvBackendStatus.text = label
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_backend)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        tvBackendStatus = findViewById(R.id.tvBackendStatus)
        tvBackendTranscript = findViewById(R.id.tvBackendTranscript)
        tvBackendTts = findViewById(R.id.tvBackendTts)
        tvBackendLog = findViewById(R.id.tvBackendLog)
        scrollBackendLog = findViewById(R.id.scrollBackendLog)

        tvBackendStatus.text = AssistantUiState.statusLabel
        tvBackendTranscript.text = AssistantUiState.transcript
        tvBackendTts.text = if (AssistantUiState.lastTts.isBlank()) "-" else AssistantUiState.lastTts
        tvBackendLog.text = AssistantUiState.allLogs()

        val filter = android.content.IntentFilter().apply {
            addAction(MainActivity.ACTION_TRANSCRIPT)
            addAction(MainActivity.ACTION_COMMAND_LOG)
            addAction(MainActivity.ACTION_TTS_SPEAK)
            addAction(MainActivity.ACTION_STATE_CHANGE)
        }
        registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(receiver)
    }
}
