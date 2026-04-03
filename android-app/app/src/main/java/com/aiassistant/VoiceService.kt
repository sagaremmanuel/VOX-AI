package com.aiassistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.media.MediaRecorder
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * Foreground voice service with wake-word loop.
 */
class VoiceService : Service() {

	private enum class ListeningState { WAKE_WORD, COMMAND, EXECUTING }

	companion object {
		private const val TAG = "VoiceService"
		private const val CHANNEL_ID = "voice_service_channel"
		private const val NOTIFICATION_ID = 101

		const val ACTION_PROCESS_TEXT = "com.aiassistant.ACTION_PROCESS_TEXT"
		const val EXTRA_TEXT = "extra_text"

		// For USB-debugging with `adb reverse tcp:8000 tcp:8000`, use localhost.
		// This avoids phone↔laptop Wi-Fi routing issues.
		const val AI_ENGINE_BASE_URL = "http://127.0.0.1:8000"

		private const val WAKE_CHUNK_MS = 3000L
		private const val COMMAND_CHUNK_MS = 5500L

		private val WAKE_WORD_VARIANTS = setOf(
			"hey vox", "heyvox", "hey-vox", "hevox",
			"hey box", "hey fox", "hey wox", "hey pox", "hey bocks",
			"hey faux", "hey fax", "hey vaux", "hey vock", "hey vax",
			"a vox", "ok vox", "okay vox", "avox",
			"hay vox", "he vox", "hi vox",
			"hey vocks", "hey vocs", "hey boks"
		)
	}

	private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
	private var mediaRecorder: MediaRecorder? = null
	private val httpClient = OkHttpClient()
	private lateinit var commandProcessor: CommandProcessor
	private var currentState = ListeningState.WAKE_WORD
	private var pendingWakeChunk: Deferred<File>? = null
	private var listenLoopJob: Job? = null

	override fun onCreate() {
		super.onCreate()
		commandProcessor = CommandProcessor(this)
		createNotificationChannel()
		startForeground(NOTIFICATION_ID, buildNotification("Waiting for ‘Hey Vox’…"))
	}

	override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
		if (intent?.action == ACTION_PROCESS_TEXT) {
			val text = intent.getStringExtra(EXTRA_TEXT).orEmpty().trim()
			if (text.isNotBlank()) {
				serviceScope.launch {
					broadcastTranscript(text)
					broadcastLog("[Chat] Executing typed command: \"$text\"")
					broadcastState(ListeningState.EXECUTING)
					commandProcessor.process(text)
					if (listenLoopJob?.isActive == true) {
						broadcastState(ListeningState.WAKE_WORD)
					}
				}
			}
			return START_STICKY
		}

		if (listenLoopJob?.isActive != true) {
			listenLoopJob = serviceScope.launch { listenLoop() }
		}
		return START_STICKY
	}

	override fun onDestroy() {
		super.onDestroy()
		listenLoopJob?.cancel()
		listenLoopJob = null
		serviceScope.cancel()
		stopRecording()
	}

	override fun onBind(intent: Intent?): IBinder? = null

	private suspend fun listenLoop() {
		broadcastState(ListeningState.WAKE_WORD)

		while (serviceScope.isActive) {
			try {
				when (currentState) {
					ListeningState.WAKE_WORD -> {
						val file = pendingWakeChunk?.await() ?: recordAudio(WAKE_CHUNK_MS)
						pendingWakeChunk = null

						val nextChunk = serviceScope.async { recordAudio(WAKE_CHUNK_MS) }
						val transcript = speechToText(file)
						file.delete()

						if (isWakeWord(transcript)) {
							nextChunk.cancel()
							broadcastLog("[Wake] \"Hey Vox\" detected — listening for command...")
							broadcastTTS("Listening")
							delay(700)
							currentState = ListeningState.COMMAND
							broadcastState(ListeningState.COMMAND)
							updateNotification("Listening for command…")
						} else {
							pendingWakeChunk = nextChunk
						}
					}

					ListeningState.COMMAND -> {
						broadcastLog("[Voice] Recording command...")
						val file = recordAudio(COMMAND_CHUNK_MS)
						val transcript = speechToText(file)
						file.delete()

						if (transcript.isBlank() || transcript.length < 3) {
							broadcastLog("[STT] No command heard, returning to standby.")
							broadcastTTS("Hmm, I didn't catch that. Say Hey Vox to try again.")
							pendingWakeChunk = null
							currentState = ListeningState.WAKE_WORD
							broadcastState(ListeningState.WAKE_WORD)
							updateNotification("Waiting for ‘Hey Vox’…")
						} else {
							broadcastTranscript(transcript)
							broadcastLog("[STT] Command: \"$transcript\"")
							currentState = ListeningState.EXECUTING
							broadcastState(ListeningState.EXECUTING)
							updateNotification("Executing command…")

							commandProcessor.process(transcript)

							pendingWakeChunk = null
							currentState = ListeningState.WAKE_WORD
							broadcastState(ListeningState.WAKE_WORD)
							updateNotification("Waiting for ‘Hey Vox’…")
							delay(500)
						}
					}

					ListeningState.EXECUTING -> delay(200)
				}
			} catch (e: Exception) {
				Log.e(TAG, "Error in listen loop: ${e.message}", e)
				broadcastLog("[Error] ${e.message}")
				pendingWakeChunk?.cancel()
				pendingWakeChunk = null
				currentState = ListeningState.WAKE_WORD
				broadcastState(ListeningState.WAKE_WORD)
				updateNotification("Waiting for ‘Hey Vox’…")
				delay(2000)
			}
		}
	}

	private fun isWakeWord(transcript: String): Boolean {
		if (transcript.isBlank()) return false
		val normalised = transcript.lowercase()
			.replace(Regex("[^a-z ]"), " ")
			.replace(Regex(" +"), " ")
			.trim()

		if (WAKE_WORD_VARIANTS.any { normalised.contains(it) }) return true
		if (Regex("""\bvox\b""").containsMatchIn(normalised)) return true

		val words = normalised.split(" ").filter { it.isNotEmpty() }
		for (i in 0 until words.size - 1) {
			val bigram = "${words[i]} ${words[i + 1]}"
			if (editDistance(bigram, "hey vox") <= 2) return true
		}
		for (w in words) {
			if (w.length >= 2 && editDistance(w, "vox") <= 1) return true
		}
		return false
	}

	private fun editDistance(a: String, b: String): Int {
		val m = a.length
		val n = b.length
		val dp = Array(m + 1) { IntArray(n + 1) }
		for (i in 0..m) dp[i][0] = i
		for (j in 0..n) dp[0][j] = j
		for (i in 1..m) for (j in 1..n) {
			dp[i][j] = if (a[i - 1] == b[j - 1]) dp[i - 1][j - 1]
			else 1 + minOf(dp[i - 1][j], dp[i][j - 1], dp[i - 1][j - 1])
		}
		return dp[m][n]
	}

	private suspend fun recordAudio(durationMs: Long): File = withContext(Dispatchers.IO) {
		val outFile = File(cacheDir, "audio_${System.currentTimeMillis()}.m4a")

		val recorder = MediaRecorder(applicationContext).apply {
			setAudioSource(MediaRecorder.AudioSource.MIC)
			setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
			setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
			setAudioSamplingRate(16000)
			setAudioEncodingBitRate(64000)
			setOutputFile(outFile.absolutePath)
			prepare()
			start()
		}
		mediaRecorder = recorder
		delay(durationMs)
		recorder.stop()
		recorder.release()
		mediaRecorder = null

		outFile
	}

	private fun stopRecording() {
		try {
			mediaRecorder?.stop()
			mediaRecorder?.release()
			mediaRecorder = null
		} catch (_: Exception) {
		}
	}

	private suspend fun speechToText(audioFile: File): String = withContext(Dispatchers.IO) {
		val requestBody = MultipartBody.Builder()
			.setType(MultipartBody.FORM)
			.addFormDataPart(
				"audio",
				audioFile.name,
				audioFile.asRequestBody("audio/mp4".toMediaTypeOrNull())
			)
			.build()

		val request = Request.Builder()
			.url("$AI_ENGINE_BASE_URL/speech_to_text")
			.post(requestBody)
			.build()

		try {
			val response = httpClient.newCall(request).execute()
			val body = response.body?.string() ?: return@withContext ""
			val json = JSONObject(body)
			json.optString("text", "")
		} catch (e: IOException) {
			Log.e(TAG, "STT network error: ${e.message}")
			broadcastLog("[Error] Cannot reach AI engine. Is it running?")
			""
		}
	}

	fun broadcastLog(message: String) {
		Intent(MainActivity.ACTION_COMMAND_LOG).also {
			it.putExtra("log", message)
			it.setPackage(packageName)
		}.let { sendBroadcast(it) }
	}

	fun broadcastTTS(message: String) {
		Intent(MainActivity.ACTION_TTS_SPEAK).also {
			it.putExtra("message", message)
			it.setPackage(packageName)
		}.let { sendBroadcast(it) }
	}

	private fun broadcastTranscript(text: String) {
		Intent(MainActivity.ACTION_TRANSCRIPT).also {
			it.putExtra("text", text)
			it.setPackage(packageName)
		}.let { sendBroadcast(it) }
	}

	private fun broadcastState(state: ListeningState) {
		val label = when (state) {
			ListeningState.WAKE_WORD -> "standby"
			ListeningState.COMMAND -> "listening"
			ListeningState.EXECUTING -> "executing"
		}
		Intent(MainActivity.ACTION_STATE_CHANGE).also {
			it.putExtra("state", label)
			it.setPackage(packageName)
		}.let { sendBroadcast(it) }
	}

	private fun createNotificationChannel() {
		val channel = NotificationChannel(
			CHANNEL_ID,
			"AI Assistant",
			NotificationManager.IMPORTANCE_LOW
		).apply { description = "Wake word listening" }
		getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
	}

	private fun buildNotification(text: String): Notification =
		Notification.Builder(this, CHANNEL_ID)
			.setContentTitle("AI Assistant")
			.setContentText(text)
			.setSmallIcon(android.R.drawable.ic_btn_speak_now)
			.build()

	private fun updateNotification(text: String) {
		getSystemService(NotificationManager::class.java)
			.notify(NOTIFICATION_ID, buildNotification(text))
	}
}
