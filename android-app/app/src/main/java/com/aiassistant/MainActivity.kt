package com.aiassistant

import android.Manifest
import android.animation.ArgbEvaluator
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.EditText
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ImageButton
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.Locale

/**
 * MainActivity: entry point with assistant-style home UI.
 */
class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private enum class UiMode { VOICE, CHAT }

    private data class ThemeColors(
        val bgStart: Int,
        val bgEnd: Int,
        val shellStart: Int,
        val shellEnd: Int,
        val topStart: Int,
        val topEnd: Int,
        val cardStart: Int,
        val cardEnd: Int,
        val chatStart: Int,
        val chatEnd: Int,
        val drawerStart: Int,
        val drawerEnd: Int,
        val primaryStart: Int,
        val primaryEnd: Int,
        val secondaryStart: Int,
        val secondaryEnd: Int,
        val chipStart: Int,
        val chipEnd: Int,
        val inputStart: Int,
        val inputEnd: Int,
        val stroke: Int
    )

    private enum class ThemePreset {
        OCEAN,
        MIDNIGHT,
        AURORA,
        GRAPHITE,
        SUNSET
    }

    companion object {
        private const val TAG = "MainActivity"

        private val REQUIRED_PERMISSIONS: Array<String> get() {
            val base = mutableListOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.CALL_PHONE,
                Manifest.permission.READ_CONTACTS
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                base.add(Manifest.permission.READ_MEDIA_IMAGES)
            } else {
                base.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            return base.toTypedArray()
        }

        private const val PERMISSION_REQUEST_CODE = 1001

        const val ACTION_TRANSCRIPT = "com.aiassistant.ACTION_TRANSCRIPT"
        const val ACTION_COMMAND_LOG = "com.aiassistant.ACTION_COMMAND_LOG"
        const val ACTION_TTS_SPEAK = "com.aiassistant.ACTION_TTS_SPEAK"
        const val ACTION_STATE_CHANGE = "com.aiassistant.ACTION_STATE_CHANGE"
    }

    private lateinit var btnMic: Button
    private lateinit var btnBackend: Button
    private lateinit var btnMenu: ImageButton
    private lateinit var btnVoiceMode: Button
    private lateinit var btnChatMode: Button
    private lateinit var btnChatSend: Button
    private lateinit var btnThemeOcean: Button
    private lateinit var btnThemeMidnight: Button
    private lateinit var btnThemeAurora: Button
    private lateinit var btnThemeGraphite: Button
    private lateinit var btnThemeSunset: Button
    private lateinit var tvTranscript: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvModeHint: TextView
    private lateinit var tvThinkingIndicator: TextView
    private lateinit var etChatInput: EditText
    private lateinit var voiceScroll: ScrollView
    private lateinit var chatPanel: LinearLayout
    private lateinit var contentShell: LinearLayout
    private lateinit var topCard: FrameLayout
    private lateinit var interactionCard: LinearLayout
    private lateinit var bottomTray: LinearLayout
    private lateinit var sideDrawer: LinearLayout
    private lateinit var chatMessagesContainer: LinearLayout
    private lateinit var scrollChatHistory: ScrollView
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var rootHome: View
    private lateinit var avatarContainer: FrameLayout
    private lateinit var vAvatarGlow: View
    private lateinit var vAmbientRing: View
    private lateinit var vAmbientCore: View

    private lateinit var tts: TextToSpeech
    private lateinit var hapticManager: HapticManager
    private val uiScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val httpClient = OkHttpClient()

    private var isListening = false
    private var uiMode = UiMode.VOICE
    private var currentTheme = ThemePreset.OCEAN
    private var thinkingJob: Job? = null
    private var thinkingPulseAnimator: ObjectAnimator? = null
    private var receiverRegistered = false

    private var ringPulseAnimator: ObjectAnimator? = null
    private var corePulseAnimator: ObjectAnimator? = null
    private var glowAnimator: ObjectAnimator? = null
    private var bobAnimator: ObjectAnimator? = null
    private var ringRotateAnimator: ObjectAnimator? = null

    private val broadcastReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: Intent) {
            when (intent.action) {
                ACTION_TRANSCRIPT -> {
                    val text = intent.getStringExtra("text") ?: return
                    AssistantUiState.transcript = text
                    tvTranscript.text = text
                    if (uiMode == UiMode.VOICE && text.isNotBlank()) {
                        appendChatLine("You", text)
                    }
                }

                ACTION_COMMAND_LOG -> {
                    val log = intent.getStringExtra("log") ?: return
                    appendLog(log)
                }

                ACTION_TTS_SPEAK -> {
                    val message = intent.getStringExtra("message") ?: return
                    AssistantUiState.lastTts = message
                    appendChatLine("Noaii", message)
                    triggerSpeakingAnimation()
                    speak(message)
                }

                ACTION_STATE_CHANGE -> {
                    val state = intent.getStringExtra("state") ?: return
                    val label = when (state) {
                        "standby" -> "Status: Waiting for Hey Vox"
                        "listening" -> "Status: Listening for command"
                        "executing" -> "Status: Executing"
                        else -> "Status: $state"
                    }
                    AssistantUiState.statusLabel = label
                    tvStatus.text = label

                    if (state == "listening" || state == "executing") {
                        startListeningAnimation()
                    } else {
                        stopListeningAnimation()
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()
        setupClickListeners()

        tts = TextToSpeech(this, this)
        hapticManager = HapticManager(this)

        checkAndRequestPermissions()
        appendLog("[System] AI Android Assistant ready.")
        appendChatLine("Noaii", "Vox is ready.")

        tvStatus.text = AssistantUiState.statusLabel
        tvTranscript.text = AssistantUiState.transcript
        updateMode(UiMode.VOICE)
        applyTheme(currentTheme)
    }

    override fun onStart() {
        super.onStart()
        registerUiReceiverIfNeeded()
    }

    override fun onStop() {
        unregisterUiReceiverIfNeeded()
        super.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopThinkingAnimation()
        stopListeningAnimation()
        uiScope.cancel()
        tts.shutdown()
        unregisterUiReceiverIfNeeded()
        stopVoiceService()
    }

    private fun registerUiReceiverIfNeeded() {
        if (receiverRegistered) return
        val filter = android.content.IntentFilter().apply {
            addAction(ACTION_TRANSCRIPT)
            addAction(ACTION_COMMAND_LOG)
            addAction(ACTION_TTS_SPEAK)
            addAction(ACTION_STATE_CHANGE)
        }
        registerReceiver(broadcastReceiver, filter, RECEIVER_NOT_EXPORTED)
        receiverRegistered = true
    }

    private fun unregisterUiReceiverIfNeeded() {
        if (!receiverRegistered) return
        unregisterReceiver(broadcastReceiver)
        receiverRegistered = false
    }

    private fun bindViews() {
        drawerLayout = findViewById(R.id.rootHome)
        btnMenu = findViewById(R.id.btnMenu)
        btnMic = findViewById(R.id.btnMic)
        btnBackend = findViewById(R.id.btnBackend)
        btnVoiceMode = findViewById(R.id.btnVoiceMode)
        btnChatMode = findViewById(R.id.btnChatMode)
        btnChatSend = findViewById(R.id.btnChatSend)
        btnThemeOcean = findViewById(R.id.btnThemeOcean)
        btnThemeMidnight = findViewById(R.id.btnThemeMidnight)
        btnThemeAurora = findViewById(R.id.btnThemeAurora)
        btnThemeGraphite = findViewById(R.id.btnThemeGraphite)
        btnThemeSunset = findViewById(R.id.btnThemeSunset)
        tvTranscript = findViewById(R.id.tvTranscript)
        tvStatus = findViewById(R.id.tvStatus)
        tvModeHint = findViewById(R.id.tvModeHint)
        tvThinkingIndicator = findViewById(R.id.tvThinkingIndicator)
        etChatInput = findViewById(R.id.etChatInput)
        voiceScroll = findViewById(R.id.voiceScroll)
        chatPanel = findViewById(R.id.chatPanel)
        contentShell = findViewById(R.id.contentShell)
        topCard = findViewById(R.id.topCard)
        interactionCard = findViewById(R.id.interactionCard)
        bottomTray = findViewById(R.id.bottomTray)
        sideDrawer = findViewById(R.id.sideDrawer)
        chatMessagesContainer = findViewById(R.id.chatMessagesContainer)
        scrollChatHistory = findViewById(R.id.scrollChatHistory)

        rootHome = findViewById(R.id.rootHome)
        avatarContainer = findViewById(R.id.avatarContainer)
        vAvatarGlow = findViewById(R.id.vAvatarGlow)
        vAmbientRing = findViewById(R.id.vAmbientRing)
        vAmbientCore = findViewById(R.id.vAmbientCore)
    }

    private fun setupClickListeners() {
        btnMenu.setOnClickListener { showNavigationMenu() }

        btnMic.setOnClickListener {
            if (uiMode == UiMode.CHAT) {
                updateMode(UiMode.VOICE)
            } else if (isListening) {
                stopListening()
            } else {
                startListening()
            }
        }

        btnVoiceMode.setOnClickListener {
            updateMode(UiMode.VOICE)
            drawerLayout.closeDrawer(GravityCompat.START)
        }

        btnChatMode.setOnClickListener {
            updateMode(UiMode.CHAT)
        }

        btnChatSend.setOnClickListener {
            submitChatMessage(etChatInput.text?.toString().orEmpty())
        }

        etChatInput.setOnEditorActionListener { _, _, _ ->
            submitChatMessage(etChatInput.text?.toString().orEmpty())
            true
        }

        btnBackend.setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
            startActivity(Intent(this, BackendActivity::class.java))
        }

        btnThemeOcean.setOnClickListener { applyThemeAndClose(ThemePreset.OCEAN) }
        btnThemeMidnight.setOnClickListener { applyThemeAndClose(ThemePreset.MIDNIGHT) }
        btnThemeAurora.setOnClickListener { applyThemeAndClose(ThemePreset.AURORA) }
        btnThemeGraphite.setOnClickListener { applyThemeAndClose(ThemePreset.GRAPHITE) }
        btnThemeSunset.setOnClickListener { applyThemeAndClose(ThemePreset.SUNSET) }
    }

    private fun applyThemeAndClose(theme: ThemePreset) {
        applyTheme(theme)
        drawerLayout.closeDrawer(GravityCompat.START)
    }

    private fun applyTheme(theme: ThemePreset) {
        currentTheme = theme

        val colors = when (theme) {
            ThemePreset.OCEAN -> ThemeColors(
                Color.parseColor("#0D1F3D"), Color.parseColor("#112B58"),
                Color.parseColor("#1A3260"), Color.parseColor("#102445"),
                Color.parseColor("#5EC5FF"), Color.parseColor("#2A57B8"),
                Color.parseColor("#243C6A"), Color.parseColor("#1A2F55"),
                Color.parseColor("#1D3A68"), Color.parseColor("#152A4A"),
                Color.parseColor("#1A2F53"), Color.parseColor("#13233F"),
                Color.parseColor("#66B7F8"), Color.parseColor("#2F6DBF"),
                Color.parseColor("#3A5076"), Color.parseColor("#233750"),
                Color.parseColor("#6D8EBC"), Color.parseColor("#4F6F9C"),
                Color.parseColor("#2B4368"), Color.parseColor("#1C2F49"),
                Color.parseColor("#8FB4E2")
            )
            ThemePreset.MIDNIGHT -> ThemeColors(
                Color.parseColor("#070B16"), Color.parseColor("#111935"),
                Color.parseColor("#151F3F"), Color.parseColor("#0D1430"),
                Color.parseColor("#4C73E6"), Color.parseColor("#263D8E"),
                Color.parseColor("#1D2A55"), Color.parseColor("#111B38"),
                Color.parseColor("#1A2750"), Color.parseColor("#0F1833"),
                Color.parseColor("#111B39"), Color.parseColor("#0B1226"),
                Color.parseColor("#5C7CEB"), Color.parseColor("#30499D"),
                Color.parseColor("#2A3552"), Color.parseColor("#1A243D"),
                Color.parseColor("#4C5E8A"), Color.parseColor("#32446D"),
                Color.parseColor("#1D2A48"), Color.parseColor("#131D34"),
                Color.parseColor("#768DBB")
            )
            ThemePreset.AURORA -> ThemeColors(
                Color.parseColor("#102533"), Color.parseColor("#182B4B"),
                Color.parseColor("#1D3A57"), Color.parseColor("#153044"),
                Color.parseColor("#75E3D8"), Color.parseColor("#3A7DD6"),
                Color.parseColor("#254C63"), Color.parseColor("#1A374B"),
                Color.parseColor("#25556E"), Color.parseColor("#183D55"),
                Color.parseColor("#1E445C"), Color.parseColor("#163347"),
                Color.parseColor("#69D8C8"), Color.parseColor("#397ACB"),
                Color.parseColor("#30556A"), Color.parseColor("#1F3C4D"),
                Color.parseColor("#5D94A2"), Color.parseColor("#427786"),
                Color.parseColor("#204458"), Color.parseColor("#153546"),
                Color.parseColor("#86BFCB")
            )
            ThemePreset.GRAPHITE -> ThemeColors(
                Color.parseColor("#14171F"), Color.parseColor("#1F2430"),
                Color.parseColor("#2A3240"), Color.parseColor("#1C222D"),
                Color.parseColor("#9AA8BF"), Color.parseColor("#5D6C84"),
                Color.parseColor("#2B3443"), Color.parseColor("#1D2430"),
                Color.parseColor("#333D4F"), Color.parseColor("#222A37"),
                Color.parseColor("#242B38"), Color.parseColor("#1B212B"),
                Color.parseColor("#97A5BB"), Color.parseColor("#5A6880"),
                Color.parseColor("#3B4454"), Color.parseColor("#2A313D"),
                Color.parseColor("#667385"), Color.parseColor("#4A5566"),
                Color.parseColor("#2D3440"), Color.parseColor("#1F252E"),
                Color.parseColor("#8B96A6")
            )
            ThemePreset.SUNSET -> ThemeColors(
                Color.parseColor("#25152A"), Color.parseColor("#332043"),
                Color.parseColor("#4B2C50"), Color.parseColor("#2F1F3A"),
                Color.parseColor("#FF9D7A"), Color.parseColor("#C44E8A"),
                Color.parseColor("#563251"), Color.parseColor("#3A2441"),
                Color.parseColor("#66395B"), Color.parseColor("#452C46"),
                Color.parseColor("#4B2A46"), Color.parseColor("#301F33"),
                Color.parseColor("#F29A80"), Color.parseColor("#B54E84"),
                Color.parseColor("#68405F"), Color.parseColor("#4A2D4A"),
                Color.parseColor("#A77695"), Color.parseColor("#7A5271"),
                Color.parseColor("#50314C"), Color.parseColor("#362339"),
                Color.parseColor("#C694B2")
            )
        }

        rootHome.background = gradientBackground(colors.bgStart, colors.bgEnd)
        contentShell.background = roundedGradient(colors.shellStart, colors.shellEnd, 34f)
        topCard.background = roundedGradient(colors.topStart, colors.topEnd, 30f)
        interactionCard.background = roundedGradient(colors.cardStart, colors.cardEnd, 24f)
        chatPanel.background = roundedGradient(colors.chatStart, colors.chatEnd, 22f)
        sideDrawer.background = roundedGradient(colors.drawerStart, colors.drawerEnd, 0f)

        val chatActive = uiMode == UiMode.CHAT
        btnChatMode.background = roundedGradientWithStroke(
            if (chatActive) colors.primaryStart else colors.secondaryStart,
            if (chatActive) colors.primaryEnd else colors.secondaryEnd,
            31f,
            colors.stroke
        )
        btnMic.background = roundedGradientWithStroke(
            if (chatActive) colors.secondaryStart else colors.primaryStart,
            if (chatActive) colors.secondaryEnd else colors.primaryEnd,
            31f,
            colors.stroke
        )
        btnChatSend.background = roundedGradientWithStroke(colors.primaryStart, colors.primaryEnd, 31f, colors.stroke)
        btnBackend.background = roundedGradientWithStroke(colors.secondaryStart, colors.secondaryEnd, 31f, colors.stroke)
        btnMenu.background = roundedGradientWithStroke(colors.secondaryStart, colors.secondaryEnd, 18f, colors.stroke)
        tvModeHint.background = roundedGradientWithStroke(colors.chipStart, colors.chipEnd, 999f, colors.stroke)
        etChatInput.background = roundedGradientWithStroke(colors.inputStart, colors.inputEnd, 26f, colors.stroke)

        val themeButtons = listOf(btnThemeOcean, btnThemeMidnight, btnThemeAurora, btnThemeGraphite, btnThemeSunset)
        themeButtons.forEach {
            it.background = roundedGradientWithStroke(colors.secondaryStart, colors.secondaryEnd, 31f, colors.stroke)
        }

        val activeThemeButton = when (theme) {
            ThemePreset.OCEAN -> btnThemeOcean
            ThemePreset.MIDNIGHT -> btnThemeMidnight
            ThemePreset.AURORA -> btnThemeAurora
            ThemePreset.GRAPHITE -> btnThemeGraphite
            ThemePreset.SUNSET -> btnThemeSunset
        }
        activeThemeButton.background = roundedGradientWithStroke(colors.primaryStart, colors.primaryEnd, 31f, colors.stroke)

        bottomTray.setBackgroundColor(Color.TRANSPARENT)
    }

    private fun gradientBackground(startColor: Int, endColor: Int): GradientDrawable {
        return GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(startColor, endColor))
    }

    private fun roundedGradient(startColor: Int, endColor: Int, radiusDp: Float): GradientDrawable {
        val radiusPx = radiusDp * resources.displayMetrics.density
        return GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(startColor, endColor)).apply {
            cornerRadius = radiusPx
        }
    }

    private fun roundedGradientWithStroke(
        startColor: Int,
        endColor: Int,
        radiusDp: Float,
        strokeColor: Int
    ): GradientDrawable {
        val radiusPx = radiusDp * resources.displayMetrics.density
        return GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(startColor, endColor)).apply {
            cornerRadius = radiusPx
            setStroke((1f * resources.displayMetrics.density).toInt(), strokeColor)
        }
    }

    private fun showNavigationMenu() {
        drawerLayout.openDrawer(GravityCompat.START)
    }

    private fun startListening() {
        if (!allPermissionsGranted()) {
            checkAndRequestPermissions()
            return
        }
        isListening = true
        btnMic.text = "Stop Listening"
        tvStatus.text = "Status: Waiting for Hey Vox"
        AssistantUiState.statusLabel = tvStatus.text.toString()
        hapticManager.vibrateCommandDetected()
        startVoiceService()
        appendLog("[Mic] Wake-word listening started. Say \"Hey Vox\" to activate.")
        startListeningAnimation()
    }

    private fun stopListening() {
        isListening = false
        btnMic.text = "Start Voice"
        tvStatus.text = "Status: Idle"
        AssistantUiState.statusLabel = tvStatus.text.toString()
        stopVoiceService()
        appendLog("[Mic] Listening stopped.")
        stopListeningAnimation()
    }

    private fun startVoiceService() {
        val intent = Intent(this, VoiceService::class.java)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopVoiceService() {
        val intent = Intent(this, VoiceService::class.java)
        stopService(intent)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale("hi", "IN")
            speak("AI assistant ready")
            Log.d(TAG, "TTS initialised successfully.")
        } else {
            Log.e(TAG, "TTS initialisation failed.")
        }
    }

    private fun speak(text: String) {
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "utterance_${System.currentTimeMillis()}")
    }

    private fun appendLog(message: String) {
        AssistantUiState.appendLog(message)
        Log.d(TAG, message)
    }

    private fun updateMode(mode: UiMode) {
        uiMode = mode
        val isVoice = mode == UiMode.VOICE

        if (isVoice) {
            stopThinkingAnimation()
        }

        btnVoiceMode.background = ContextCompat.getDrawable(
            this,
            if (isVoice) R.drawable.bg_mode_active else R.drawable.bg_mode_inactive
        )

        btnVoiceMode.setTextColor(Color.parseColor(if (isVoice) "#0C1424" else "#F7F8FC"))
        btnChatMode.setTextColor(Color.parseColor("#EAF4FF"))

        btnMic.visibility = View.VISIBLE
        btnMic.text = if (isVoice) {
            if (isListening) "Stop Listening" else "Start Voice"
        } else {
            "Voice Mode"
        }
        voiceScroll.visibility = if (isVoice) View.VISIBLE else View.GONE
        chatPanel.visibility = if (isVoice) View.GONE else View.VISIBLE
        tvModeHint.text = if (isVoice) {
            "Voice mode listens for commands."
        } else {
            "Chat mode keeps things typed and quiet."
        }

        if (!isVoice && isListening) {
            stopListening()
        }

        if (!isVoice) {
            tvStatus.text = "Status: Chat mode"
            AssistantUiState.statusLabel = tvStatus.text.toString()
            tvTranscript.text = if (AssistantUiState.lastTts.isNotBlank()) {
                AssistantUiState.lastTts
            } else {
                "Type a message to begin."
            }
            etChatInput.requestFocus()
        } else {
            tvStatus.text = if (isListening) "Status: Waiting for Hey Vox" else "Status: Idle"
            AssistantUiState.statusLabel = tvStatus.text.toString()
        }

        applyTheme(currentTheme)
    }

    private fun appendChatLine(speaker: String, text: String) {
        if (text.isBlank()) return
        val incoming = speaker != "You"
        val bubble = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { params ->
                params.topMargin = 8
                params.bottomMargin = 8
                params.marginStart = if (incoming) 0 else 48
                params.marginEnd = if (incoming) 48 else 0
                params.gravity = if (incoming) android.view.Gravity.START else android.view.Gravity.END
            }
            maxWidth = (resources.displayMetrics.widthPixels * 0.72f).toInt()
            setPadding(18, 14, 18, 14)
            background = ContextCompat.getDrawable(
                this@MainActivity,
                if (incoming) R.drawable.bg_chat_bubble_incoming else R.drawable.bg_chat_bubble_outgoing
            )
            setTextColor(if (incoming) Color.parseColor("#F7F8FC") else Color.parseColor("#0C1424"))
            textSize = 15f
            this.text = text
        }
        chatMessagesContainer.addView(bubble)
        scrollChatHistory.post { scrollChatHistory.fullScroll(View.FOCUS_DOWN) }
    }

    private fun submitChatMessage(rawMessage: String) {
        val message = rawMessage.trim()
        if (message.isBlank()) return

        if (uiMode != UiMode.CHAT) {
            updateMode(UiMode.CHAT)
        }

        etChatInput.setText("")
        appendChatLine("You", message)
        tvTranscript.text = message
        startThinkingAnimation()

        uiScope.launch {
            try {
                val response = parseCommandOnEngine(message)
                val intent = response?.optString("intent", "unknown").orEmpty()
                val answerText = response?.optString("answer_text", "").orEmpty()
                val stepsCount = response?.optJSONArray("steps")?.length() ?: 0
                val shouldExecuteAction = response != null && (stepsCount > 0 || intent != "unknown")

                if (shouldExecuteAction) {
                    stopThinkingAnimation()
                    tvStatus.text = "Status: Executing"
                    AssistantUiState.statusLabel = tvStatus.text.toString()
                    if (!dispatchTypedCommandToService(message)) {
                        appendChatLine("Noaii", "Could not execute right now. Please try again.")
                        tvStatus.text = "Status: Chat ready"
                        AssistantUiState.statusLabel = tvStatus.text.toString()
                    }
                    return@launch
                }

                val reply = when {
                    answerText.isNotBlank() -> answerText
                    response == null -> "I couldn't reach the engine right now."
                    intent != "unknown" -> "I understood this as $intent. Switch to Voice mode for device actions."
                    else -> "I can answer questions here or handle actions in Voice mode."
                }

                appendChatLine("Noaii", reply)
                tvTranscript.text = reply
                tvStatus.text = "Status: Chat ready"
                AssistantUiState.statusLabel = tvStatus.text.toString()

                if (answerText.isNotBlank()) {
                    speak(reply)
                }
            } finally {
                stopThinkingAnimation()
            }
        }
    }

    private fun dispatchTypedCommandToService(text: String): Boolean {
        return try {
            val intent = Intent(this, VoiceService::class.java).apply {
                action = VoiceService.ACTION_PROCESS_TEXT
                putExtra(VoiceService.EXTRA_TEXT, text)
            }
            ContextCompat.startForegroundService(this, intent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "dispatchTypedCommandToService failed: ${e.message}")
            false
        }
    }

    private fun startThinkingAnimation() {
        stopThinkingAnimation()
        tvThinkingIndicator.visibility = View.VISIBLE
        thinkingPulseAnimator = ObjectAnimator.ofFloat(tvThinkingIndicator, View.ALPHA, 0.45f, 1f, 0.45f).apply {
            duration = 900
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
        thinkingJob = uiScope.launch {
            var dots = 0
            while (isActive) {
                val suffix = ".".repeat(dots + 1)
                val label = "Status: Thinking$suffix"
                tvStatus.text = label
                AssistantUiState.statusLabel = label
                tvThinkingIndicator.text = "Thinking$suffix"
                dots = (dots + 1) % 3
                delay(360)
            }
        }
    }

    private fun stopThinkingAnimation() {
        thinkingJob?.cancel()
        thinkingJob = null
        thinkingPulseAnimator?.cancel()
        thinkingPulseAnimator = null
        tvThinkingIndicator.alpha = 1f
        tvThinkingIndicator.visibility = View.GONE
    }

    private suspend fun parseCommandOnEngine(message: String): JSONObject? = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply { put("text", message) }.toString()
        val body = payload.toRequestBody("application/json".toMediaTypeOrNull())
        val request = Request.Builder()
            .url("${VoiceService.AI_ENGINE_BASE_URL}/parse_command")
            .post(body)
            .build()

        try {
            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string() ?: return@withContext null
            JSONObject(responseBody)
        } catch (e: Exception) {
            Log.e(TAG, "Chat parse error: ${e.message}")
            null
        }
    }

    private fun startListeningAnimation() {
        if (ringPulseAnimator == null) {
            ringPulseAnimator = ObjectAnimator.ofFloat(vAmbientRing, View.SCALE_X, 1f, 1.025f, 1f).apply {
                duration = 1200
                repeatCount = ValueAnimator.INFINITE
                interpolator = AccelerateDecelerateInterpolator()
            }
        }
        if (corePulseAnimator == null) {
            corePulseAnimator = ObjectAnimator.ofFloat(vAmbientCore, View.SCALE_X, 1f, 1.04f, 1f).apply {
                duration = 1100
                repeatCount = ValueAnimator.INFINITE
                interpolator = AccelerateDecelerateInterpolator()
            }
        }
        if (glowAnimator == null) {
            glowAnimator = ObjectAnimator.ofFloat(vAvatarGlow, View.ALPHA, 0.75f, 0.92f, 0.75f).apply {
                duration = 1300
                repeatCount = ValueAnimator.INFINITE
                interpolator = AccelerateDecelerateInterpolator()
            }
        }
        if (bobAnimator == null) {
            bobAnimator = ObjectAnimator.ofFloat(avatarContainer, View.TRANSLATION_Y, 0f, -1.5f, 0f).apply {
                duration = 1500
                repeatCount = ValueAnimator.INFINITE
                interpolator = AccelerateDecelerateInterpolator()
            }
        }
        if (ringRotateAnimator == null) {
            ringRotateAnimator = ObjectAnimator.ofFloat(vAmbientRing, View.ROTATION, -6f, 6f, -6f).apply {
                duration = 2200
                repeatCount = ValueAnimator.INFINITE
                interpolator = AccelerateDecelerateInterpolator()
            }
        }

        if (ringPulseAnimator?.isStarted != true) ringPulseAnimator?.start()
        if (corePulseAnimator?.isStarted != true) corePulseAnimator?.start()
        if (glowAnimator?.isStarted != true) glowAnimator?.start()
        if (bobAnimator?.isStarted != true) bobAnimator?.start()
        if (ringRotateAnimator?.isStarted != true) ringRotateAnimator?.start()
    }

    private fun stopListeningAnimation() {
        ringPulseAnimator?.cancel()
        corePulseAnimator?.cancel()
        glowAnimator?.cancel()
        bobAnimator?.cancel()
        ringRotateAnimator?.cancel()

        vAmbientRing.scaleX = 1f
        vAmbientRing.scaleY = 1f
        vAmbientRing.rotation = 0f
        vAmbientCore.scaleX = 1f
        vAmbientCore.scaleY = 1f
        vAvatarGlow.alpha = 1f
        avatarContainer.translationY = 0f
        applyTheme(currentTheme)
    }

    private fun triggerSpeakingAnimation() {
        ObjectAnimator.ofFloat(vAmbientCore, View.SCALE_Y, 1f, 1.08f, 1f).apply {
            duration = 280
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
        ObjectAnimator.ofFloat(vAmbientCore, View.SCALE_X, 1f, 1.08f, 1f).apply {
            duration = 280
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
        ObjectAnimator.ofFloat(vAmbientRing, View.ALPHA, 0.8f, 1f, 0.8f).apply {
            duration = 300
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    private fun checkAndRequestPermissions() {
        val missing = REQUIRED_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), PERMISSION_REQUEST_CODE)
        }
    }

    private fun allPermissionsGranted() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            val denied = permissions.zip(grantResults.toTypedArray())
                .filter { it.second != PackageManager.PERMISSION_GRANTED }
                .map { it.first }
            if (denied.isEmpty()) {
                appendLog("[Permissions] All permissions granted.")
            } else {
                appendLog("[Permissions] Denied: ${denied.joinToString()}")
            }
        }
    }
}
