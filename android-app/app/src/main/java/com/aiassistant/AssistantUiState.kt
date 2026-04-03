package com.aiassistant

object AssistantUiState {
    @Volatile var statusLabel: String = "Status: Idle"
    @Volatile var transcript: String = "Waiting for speech..."
    @Volatile var lastTts: String = ""

    private val logs = mutableListOf<String>()

    @Synchronized
    fun appendLog(line: String) {
        logs.add(line)
        if (logs.size > 400) logs.removeAt(0)
    }

    @Synchronized
    fun allLogs(): String = logs.joinToString("\n")
}
