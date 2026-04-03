package com.aiassistant

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * CommandProcessor: Sends a transcript to the AI engine's /parse_command endpoint
 * and routes the parsed intent + steps to TaskExecutor for execution.
 *
 * Response schema expected from the AI engine:
 * {
 *   "intent": "send_photo",
 *   "contact": "Rahul",
 *   "app": "whatsapp",
 *   "time": "06:00",
 *   "steps": ["get_latest_photo", "open_whatsapp", "send_to_contact"]
 * }
 */
class CommandProcessor(private val service: VoiceService) {

    companion object {
        private const val TAG = "CommandProcessor"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val httpClient = OkHttpClient()
    private val taskExecutor = TaskExecutor(service)
    private val hapticManager = HapticManager(service)

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------
    /**
     * Parses the transcript on the AI engine and executes the resulting steps.
     */
    suspend fun process(transcript: String) {
        service.broadcastLog("[CMD] Processing: \"$transcript\"")

        val json = parseCommandOnEngine(transcript)
        if (json == null) {
            service.broadcastLog("[CMD] Failed to parse command.")
            hapticManager.vibrateError()
            service.broadcastTTS("Sorry, I could not understand that command.")
            return
        }

        val intent = json.optString("intent", "unknown")
        val contact = json.optString("contact", "")
        val app = json.optString("app", "")
        val time = json.optString("time", "")
        val messageBody = json.optString("message_body", "")
        val answerText = json.optString("answer_text", "")
        val stepsArray = json.optJSONArray("steps")
        val steps = mutableListOf<String>()
        if (stepsArray != null) {
            for (i in 0 until stepsArray.length()) steps.add(stepsArray.getString(i))
        }

        service.broadcastLog("[CMD] Intent: $intent | Steps: $steps | Msg: \"$messageBody\" | Ans: \"$answerText\"")
        hapticManager.vibrateCommandDetected()

        // Execute each step sequentially
        taskExecutor.executeSteps(intent, steps, contact, app, time, messageBody, answerText)
    }

    // -------------------------------------------------------------------------
    // AI engine REST call — Parse Command
    // -------------------------------------------------------------------------
    private suspend fun parseCommandOnEngine(transcript: String): JSONObject? =
        withContext(Dispatchers.IO) {
            val payload = JSONObject().apply { put("text", transcript) }.toString()
            val body = payload.toRequestBody("application/json".toMediaTypeOrNull())
            val request = Request.Builder()
                .url("${VoiceService.AI_ENGINE_BASE_URL}/parse_command")
                .post(body)
                .build()

            try {
                val response = httpClient.newCall(request).execute()
                val responseBody = response.body?.string() ?: return@withContext null
                Log.d(TAG, "Parse response: $responseBody")
                JSONObject(responseBody)
            } catch (e: Exception) {
                Log.e(TAG, "Parse command error: ${e.message}")
                service.broadcastLog("[Error] Cannot reach AI engine: ${e.message}")
                null
            }
        }
}
