package com.aiassistant

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.Calendar

/**
 * TaskExecutor: Executes concrete phone actions based on the AI engine's step plan.
 *
 * Supported steps / intents:
 *  - set_alarm / setAlarm
 *  - call_contact / callContact
 *  - get_latest_photo
 *  - open_whatsapp / send_to_contact / open_app
 *  - send_to_contact (WhatsApp deep-link)
 *
 * Each action broadcasts a log line and a TTS confirmation back to the UI.
 */
class TaskExecutor(private val service: VoiceService) {

    companion object {
        private const val TAG = "TaskExecutor"

        // Maps short app names (from intent parser) to full package names
        private val APP_PACKAGE_MAP = mapOf(
            "youtube"      to "com.google.android.youtube",
            "whatsapp"     to "com.whatsapp",
            "instagram"    to "com.instagram.android",
            "chrome"       to "com.android.chrome",
            "maps"         to "com.google.android.apps.maps",
            "google maps"  to "com.google.android.apps.maps",
            "settings"     to "com.android.settings",
            "camera"       to "camera",
            "spotify"      to "com.spotify.music",
            "twitter"      to "com.twitter.android",
            "x"            to "com.twitter.android",
            "facebook"     to "com.facebook.katana",
            "telegram"     to "org.telegram.messenger",
            "gmail"        to "com.google.android.gm",
            "photos"       to "com.google.android.apps.photos",
            "google photos" to "com.google.android.apps.photos",
            "phone"        to "com.google.android.dialer",
            "dialer"       to "com.google.android.dialer",
            "messages"     to "com.google.android.apps.messaging",
            "calculator"   to "com.google.android.calculator",
            "clock"        to "com.google.android.deskclock",
            "calendar"     to "com.google.android.calendar",
            "drive"        to "com.google.android.apps.docs",
            "google drive" to "com.google.android.apps.docs",
            "meet"         to "com.google.android.apps.meetings",
            "zoom"         to "us.zoom.videomeetings",
            "netflix"      to "com.netflix.mediaclient",
            "amazon"       to "com.amazon.mShop.android.shopping",
            "flipkart"     to "com.flipkart.android",
            "zomato"       to "com.application.zomato",
            "swiggy"       to "in.swiggy.android",
            "paytm"        to "net.one97.paytm",
            "gpay"         to "com.google.android.apps.nbu.paisa.user",
            "google pay"   to "com.google.android.apps.nbu.paisa.user",
            "phonepay"     to "com.phonepe.app",
            "phonepe"      to "com.phonepe.app"
        )
    }

    private val ctx: Context = service.applicationContext
    private val hapticManager = HapticManager(service)

    // Temporary state shared between steps in a multi-step command
    private var latestPhotoUri: Uri? = null
    private var resolvedContactNumber: String? = null

    // -------------------------------------------------------------------------
    // Step orchestration
    // -------------------------------------------------------------------------
    /**
     * Iterates through [steps] and dispatches each to the appropriate handler.
     * After all steps are done, provides haptic + voice confirmation.
     */
    suspend fun executeSteps(
        intent: String,
        steps: List<String>,
        contact: String,
        app: String,
        time: String,
        messageBody: String = "",
        answerText: String = ""
    ) {
        latestPhotoUri = null
        resolvedContactNumber = null

        // If steps are empty, try a direct intent execution
        val effectiveSteps = steps.ifEmpty { listOf(intent) }

        for ((index, step) in effectiveSteps.withIndex()) {
            service.broadcastLog("[Task] Executing step: $step")
            executeStep(step.lowercase().trim(), contact, app, time, messageBody, answerText)
            // Longer pause before enable_speaker so the call has time to connect
            val stepDelay = if (effectiveSteps.getOrNull(index + 1)?.contains("enable_speaker") == true) 3000L else 600L
            delay(stepDelay)
        }

        hapticManager.vibrateTaskComplete()
        service.broadcastLog("[Task] All steps complete.")
    }

    // -------------------------------------------------------------------------
    // Step dispatcher
    // -------------------------------------------------------------------------
    private suspend fun executeStep(
        step: String,
        contact: String,
        app: String,
        time: String,
        messageBody: String = "",
        answerText: String = ""
    ) = withContext(Dispatchers.Main) {
        when {
            step.contains("set_alarm") || step.contains("alarm") ->
                setAlarm(time, contact)

            step.contains("call") ->
                callContact(contact)

            step.contains("get_latest_photo") || step.contains("photo") ->
                getLatestPhoto()

            step.contains("open_camera") ->
                openCamera()

            // Dedicated text-message step — does NOT send a photo
            step.contains("send_whatsapp_message") ->
                sendWhatsappMessage(contact, messageBody)

            // Photo-sharing step — still routes to sendPhotoToContact
            step.contains("send_to_contact") || step.contains("send_contact") ->
                sendPhotoToContact(contact)

            step.contains("open_whatsapp") ->
                openApp("com.whatsapp")

            step.contains("open_app") ->
                openApp(app)

            step.contains("open_youtube") ->
                openApp("com.google.android.youtube")

            step.contains("open_maps") ->
                openApp("com.google.android.apps.maps")

            step.contains("flashlight_on") || step == "flashlight on" ->
                toggleFlashlight(true)

            step.contains("flashlight_off") || step == "flashlight off" ->
                toggleFlashlight(false)

            step.contains("enable_speaker") ->
                enableSpeakerphone()

            step.contains("capture_photo") ->
                scheduleCameraCapture()

            step.contains("describe_screen") ->
                describeScreen()

            step.contains("speak_answer") ->
                speakAnswer(answerText)

            else -> {
                service.broadcastLog("[Task] Unknown step: $step — skipping.")
                service.broadcastTTS("Sorry, I don't know how to do that yet.")
            }
        }
    }

    private fun speakAnswer(answerText: String) {
        val text = answerText.ifBlank { "I don't have an answer for that yet." }
        service.broadcastLog("[Answer] $text")
        service.broadcastTTS(text)
    }

    // -------------------------------------------------------------------------
    // Action implementations
    // -------------------------------------------------------------------------

    /**
     * Creates a system alarm using AlarmManager.
     * [timeStr] format: "HH:MM" (24-hour) or descriptive like "6 AM"
     */
    fun setAlarm(timeStr: String, label: String = "AI Assistant Alarm") {
        try {
            val (hour, minute) = parseTime(timeStr)
            val calendar = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                // If time already passed today, schedule for tomorrow
                if (before(Calendar.getInstance())) add(Calendar.DATE, 1)
            }

            val alarmIntent = Intent(android.provider.AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(android.provider.AlarmClock.EXTRA_HOUR, hour)
                putExtra(android.provider.AlarmClock.EXTRA_MINUTES, minute)
                putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, label)
                putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, true)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            ctx.startActivity(alarmIntent)

            service.broadcastLog("[Alarm] Set for ${hour}:${minute.toString().padStart(2,'0')}")
            service.broadcastTTS("Alarm set for $timeStr")
        } catch (e: Exception) {
            Log.e(TAG, "setAlarm error: ${e.message}")
            service.broadcastLog("[Error] Could not set alarm: ${e.message}")
        }
    }

    /**
     * Resolves a contact name to a phone number and places a call.
     */
    fun callContact(name: String) {
        try {
            val number = resolveContactNumber(name)
            if (number == null) {
                service.broadcastLog("[Call] Contact \"$name\" not found.")
                service.broadcastTTS("Contact $name not found.")
                return
            }
            resolvedContactNumber = number
            val callIntent = Intent(Intent.ACTION_CALL).apply {
                data = Uri.parse("tel:$number")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            ctx.startActivity(callIntent)
            service.broadcastLog("[Call] Calling $name ($number)")
            service.broadcastTTS("Calling $name")
        } catch (e: Exception) {
            Log.e(TAG, "callContact error: ${e.message}")
            service.broadcastLog("[Error] Could not call contact: ${e.message}")
            service.broadcastTTS("Failed to call $name")
        }
    }

    /**
     * Retrieves the URI of the most recently added photo from the gallery.
     */
    fun getLatestPhoto(): Uri? {
        return try {
            val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATA)
            val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"
            val cursor = ctx.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection, null, null, sortOrder
            )
            cursor?.use {
                if (it.moveToFirst()) {
                    val id = it.getLong(it.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
                    val uri = Uri.withAppendedPath(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString()
                    )
                    latestPhotoUri = uri
                    service.broadcastLog("[Photo] Latest photo URI: $uri")
                    uri
                } else null
            }.also { if (it == null) service.broadcastLog("[Photo] No photos found in gallery.") }
        } catch (e: Exception) {
            Log.e(TAG, "getLatestPhoto error: ${e.message}")
            null
        }
    }

    /**
     * Opens a WhatsApp chat to [contactName] with [messageBody] pre-filled via deep-link.
     * Unlike sendPhotoToContact, this sends a text message — no photo involved.
     * AutoSendAccessibilityService taps the Send button automatically when a message body
     * is present.
     */
    fun sendWhatsappMessage(contactName: String, messageBody: String) {
        try {
            val number = resolvedContactNumber ?: resolveContactNumber(contactName)

            val normalised: String? = number?.let {
                val d = it.replace(Regex("[^\\d]"), "")
                when {
                    d.length == 13 && d.startsWith("091") -> d.drop(1)
                    d.startsWith("91") && d.length == 12  -> d
                    d.startsWith("0")  && d.length == 11  -> "91" + d.drop(1)
                    d.length == 10                        -> "91" + d
                    else                                  -> d
                }
            }

            val encodedBody = Uri.encode(messageBody.ifBlank { "" })
            val waUrl = if (normalised != null) {
                service.broadcastLog("[Message] Resolved number: $normalised")
                "https://api.whatsapp.com/send?phone=$normalised&text=$encodedBody"
            } else {
                service.broadcastLog("[Message] Contact '$contactName' not found — opening picker")
                "https://api.whatsapp.com/send?text=$encodedBody"
            }

            // Arm AutoSendAccessibilityService to tap the Send button
            if (messageBody.isNotBlank()) {
                ctx.getSharedPreferences(AutoSendAccessibilityService.PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(AutoSendAccessibilityService.KEY_PENDING, true)
                    .putLong(AutoSendAccessibilityService.KEY_TIMESTAMP, System.currentTimeMillis())
                    .putString(AutoSendAccessibilityService.KEY_CONTACT_NAME, contactName)
                    .apply()
            }

            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse(waUrl)
                setPackage("com.whatsapp")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            ctx.startActivity(intent)

            val spoken = messageBody.ifBlank { "" }
            service.broadcastLog("[Message] WhatsApp opened for $contactName | body: \"$spoken\"")
            service.broadcastTTS(
                if (spoken.isNotBlank()) "Sending message to $contactName"
                else "Opening WhatsApp to $contactName"
            )
        } catch (e: Exception) {
            Log.e(TAG, "sendWhatsappMessage error: ${e.message}", e)
            service.broadcastLog("[Error] Could not send message: ${e.message}")
            service.broadcastTTS("Failed to send message to $contactName")
        }
    }

    /**
     * Sends the latest retrieved photo to a WhatsApp contact.
     *
     * Uses FileProvider to create a shareable content URI — required because
     * WhatsApp cannot access raw MediaStore URIs on Android 10+.
     * The jid extra pre-selects the recipient inside WhatsApp.
     * The user sees their chat open with the photo attached, then taps Send.
     */
    fun sendPhotoToContact(contactName: String) {
        try {
            val mediaUri = latestPhotoUri ?: run {
                service.broadcastLog("[Send] No photo cached — fetching latest...")
                getLatestPhoto()
            }
            if (mediaUri == null) {
                service.broadcastLog("[Send] No photo available.")
                service.broadcastTTS("No photo found to send.")
                return
            }

            // --- Copy image to FileProvider-accessible cache dir ---
            val sharedDir = java.io.File(ctx.cacheDir, "shared_images").also { it.mkdirs() }
            val sharedFile = java.io.File(sharedDir, "photo_share.jpg")
            ctx.contentResolver.openInputStream(mediaUri)?.use { input ->
                sharedFile.outputStream().use { output -> input.copyTo(output) }
            } ?: run {
                service.broadcastLog("[Send] Could not read photo from gallery.")
                return
            }
            val contentUri = androidx.core.content.FileProvider.getUriForFile(
                ctx, "com.aiassistant.fileprovider", sharedFile
            )

            // Grant WhatsApp explicit read permission on this URI
            ctx.grantUriPermission("com.whatsapp", contentUri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)

            val number = resolvedContactNumber ?: resolveContactNumber(contactName)

            if (number != null) {
                // Strip everything except digits
                val digitsOnly = number.replace(Regex("[^\\d]"), "")
                // Normalise to full international format (India default = 91)
                val normalised = when {
                    digitsOnly.length == 13 && digitsOnly.startsWith("091") -> digitsOnly.drop(1)  // e.g. 091XXXXXXXXXX
                    digitsOnly.startsWith("91") && digitsOnly.length == 12  -> digitsOnly          // already 91XXXXXXXXXX
                    digitsOnly.startsWith("0")  && digitsOnly.length == 11  -> "91" + digitsOnly.drop(1) // 0XXXXXXXXXX
                    digitsOnly.length == 10                                  -> "91" + digitsOnly   // plain 10-digit
                    else                                                     -> digitsOnly          // use as-is
                }
                val jid = "$normalised@s.whatsapp.net"
                service.broadcastLog("[Send] Resolved number: '$number'")
                service.broadcastLog("[Send] Digits: $digitsOnly | Normalised: $normalised")
                service.broadcastLog("[Send] WhatsApp JID: $jid")

                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "image/jpeg"
                    putExtra(Intent.EXTRA_STREAM, contentUri)
                    putExtra("jid", jid)
                    setPackage("com.whatsapp")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                }
                // Set flag so AutoSendAccessibilityService taps contact name then Send
                ctx.getSharedPreferences(AutoSendAccessibilityService.PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(AutoSendAccessibilityService.KEY_PENDING, true)
                    .putLong(AutoSendAccessibilityService.KEY_TIMESTAMP, System.currentTimeMillis())
                    .putString(AutoSendAccessibilityService.KEY_CONTACT_NAME, contactName)
                    .apply()
                ctx.startActivity(shareIntent)
                service.broadcastLog("[Send] WhatsApp opened to $contactName — auto-send armed")
                service.broadcastTTS("Sending photo to $contactName")
            } else {
                // Fallback: open WhatsApp share picker (contact not in phone book)
                service.broadcastLog("[Send] Contact '$contactName' not found — opening WhatsApp picker")
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "image/jpeg"
                    putExtra(Intent.EXTRA_STREAM, contentUri)
                    setPackage("com.whatsapp")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                }
                ctx.startActivity(shareIntent)
                service.broadcastTTS("Select contact in WhatsApp to send")
            }
        } catch (e: Exception) {
            Log.e(TAG, "sendPhotoToContact error: ${e.message}", e)
            service.broadcastLog("[Error] Could not send photo: ${e.message}")
        }
    }

    /**
     * Opens the device's default camera app using the system camera intent.
     * Works on all manufacturers (Samsung, OnePlus, Xiaomi, etc.) unlike
     * getLaunchIntentForPackage("com.android.camera2") which only exists on
     * stock Android devices.
     */
    fun openCamera() {
        try {
            val cameraIntent = Intent(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            ctx.startActivity(cameraIntent)
            service.broadcastLog("[Camera] Opened system camera")
            service.broadcastTTS("Opening camera")
        } catch (e: Exception) {
            // Fallback: try launching via camera package map entries
            Log.e(TAG, "openCamera error: ${e.message}")
            val fallbackPackages = listOf(
                "com.samsung.android.camera",
                "com.oneplus.camera",
                "com.miui.camera",
                "com.android.camera2",
                "com.android.camera"
            )
            for (pkg in fallbackPackages) {
                val intent = ctx.packageManager.getLaunchIntentForPackage(pkg)
                if (intent != null) {
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    ctx.startActivity(intent)
                    service.broadcastLog("[Camera] Opened via fallback package: $pkg")
                    service.broadcastTTS("Opening camera")
                    return
                }
            }
            service.broadcastLog("[Camera] No camera app found")
            service.broadcastTTS("Could not open camera")
        }
    }

    /**
     * Toggles the device flashlight/torch on or off using CameraManager.
     */
    fun toggleFlashlight(enable: Boolean) {
        try {
            val cameraManager = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
            if (cameraId == null) {
                service.broadcastLog("[Flashlight] No flash available on this device.")
                service.broadcastTTS("This device doesn't have a flashlight.")
                return
            }
            cameraManager.setTorchMode(cameraId, enable)
            val state = if (enable) "on" else "off"
            service.broadcastLog("[Flashlight] Turned $state")
            service.broadcastTTS("Flashlight $state")
        } catch (e: Exception) {
            Log.e(TAG, "toggleFlashlight error: ${e.message}")
            service.broadcastLog("[Error] Flashlight: ${e.message}")
            service.broadcastTTS("Could not toggle flashlight.")
        }
    }

    /**
     * Launches an installed app by name or package name.
     * Accepts short names like "youtube" or full package names.
     */
    fun openApp(appNameOrPackage: String) {
        try {
            // Resolve short name → package name via map (case-insensitive)
            val pkg = APP_PACKAGE_MAP[appNameOrPackage.lowercase().trim()]
                ?: appNameOrPackage  // already a package name or unknown

            // Camera is a special case — use system intent for cross-device compatibility
            if (pkg == "camera" || appNameOrPackage.lowercase().contains("camera")) {
                openCamera()
                return
            }

            val launchIntent = ctx.packageManager.getLaunchIntentForPackage(pkg)
            if (launchIntent != null) {
                launchIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                ctx.startActivity(launchIntent)
                service.broadcastLog("[App] Opened: $pkg")
                service.broadcastTTS("Opening ${appNameOrPackage.replaceFirstChar { it.uppercase() }}")
            } else {
                service.broadcastLog("[App] Not installed: $pkg (\"$appNameOrPackage\")")
                service.broadcastTTS("$appNameOrPackage is not installed")
            }
        } catch (e: Exception) {
            Log.e(TAG, "openApp error: ${e.message}")
            service.broadcastLog("[Error] openApp: ${e.message}")
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------
    /**
     * Looks up a contact's phone number by display name.
     * Tries: exact LIKE → lower-cased → first-word-only → full scan.
     */
    private fun resolveContactNumber(name: String): String? {
        val trimmed = name.trim()
        service.broadcastLog("[Contact] Looking up: \"$trimmed\"")

        // Pass 1: SQL LIKE (works when contacts API is case-insensitive)
        val pass1 = queryContact("%$trimmed%")
        if (pass1 != null) {
            service.broadcastLog("[Contact] Found (pass1): $pass1")
            return pass1
        }

        // Pass 2: first word only (e.g. "Rahul Sharma" → try "Rahul")
        val firstWord = trimmed.split(" ").first()
        if (firstWord != trimmed) {
            val pass2 = queryContact("%$firstWord%")
            if (pass2 != null) {
                service.broadcastLog("[Contact] Found (pass2 first-word): $pass2")
                return pass2
            }
        }

        // Pass 3: full cursor scan with Kotlin lowercase comparison
        val cursor = ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
            ),
            null, null, null
        )
        val result = cursor?.use { c ->
            val nameIdx = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numIdx  = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (c.moveToNext()) {
                val displayName = c.getString(nameIdx) ?: continue
                if (displayName.lowercase().contains(trimmed.lowercase()) ||
                    displayName.lowercase().contains(firstWord.lowercase())) {
                    service.broadcastLog("[Contact] Found (pass3 scan): $displayName")
                    return@use c.getString(numIdx)
                }
            }
            null
        }
        if (result == null) {
            service.broadcastLog("[Contact] NOT FOUND for \"$trimmed\" — add contact to phone book")
        }
        return result
    }

    private fun queryContact(likePattern: String): String? {
        val cursor = ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
            ),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
            arrayOf(likePattern),
            null
        )
        return cursor?.use {
            if (it.moveToFirst()) it.getString(
                it.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
            ) else null
        }
    }

    /**
     * Reads the current screen content via AccessibilityService, sends it to
     * the /describe_screen endpoint, and speaks the LLM summary.
     */
    private suspend fun describeScreen() = withContext(Dispatchers.IO) {
        service.broadcastTTS("Reading your screen...")

        // Collect text from accessibility tree
        val screenText = AutoSendAccessibilityService.instance?.getScreenText()
        if (screenText.isNullOrBlank()) {
            service.broadcastLog("[Screen] No text found — accessibility service may not be active")
            withContext(Dispatchers.Main) {
                service.broadcastTTS(
                    "I couldn't read the screen. Please make sure the accessibility service is enabled in Settings."
                )
            }
            return@withContext
        }

        service.broadcastLog("[Screen] Collected ${screenText.lines().size} lines of text")

        // POST to /describe_screen
        try {
            val payload = JSONObject().apply { put("screen_text", screenText) }.toString()
            val body = payload.toRequestBody("application/json".toMediaTypeOrNull())
            val request = Request.Builder()
                .url("${VoiceService.AI_ENGINE_BASE_URL}/describe_screen")
                .post(body)
                .build()

            val client = OkHttpClient()
            val response = client.newCall(request).execute()
            val json = response.body?.string()?.let { JSONObject(it) }
            val summary = json?.optString("summary", "") ?: ""

            service.broadcastLog("[Screen] Summary received: ${summary.take(100)}")
            withContext(Dispatchers.Main) {
                service.broadcastTTS(summary.ifBlank { "I could not summarize the screen." })
            }
        } catch (e: Exception) {
            Log.e(TAG, "describeScreen error: ${e.message}")
            service.broadcastLog("[Error] Describe screen: ${e.message}")
            withContext(Dispatchers.Main) {
                service.broadcastTTS("Sorry, I could not reach the server to describe the screen.")
            }
        }
    }

    /**
     * Enables speakerphone on the active call using AudioManager.
     * Called after call_contact step; a delay in executeSteps gives the call time to connect.
     */
    fun enableSpeakerphone() {
        try {
            val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audio.mode = AudioManager.MODE_IN_CALL
            audio.isSpeakerphoneOn = true
            service.broadcastLog("[Speaker] Speakerphone enabled")
            service.broadcastTTS("Speaker on")
        } catch (e: Exception) {
            Log.e(TAG, "enableSpeakerphone error: ${e.message}")
            service.broadcastLog("[Error] Speaker: ${e.message}")
            service.broadcastTTS("Could not enable speaker")
        }
    }

    /**
     * Signals AutoSendAccessibilityService to tap the camera shutter button.
     * The camera must already be open (open_camera step runs first).
     */
    fun scheduleCameraCapture() {
        try {
            ctx.getSharedPreferences(AutoSendAccessibilityService.PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(AutoSendAccessibilityService.KEY_CAMERA_CAPTURE, true)
                .putLong(AutoSendAccessibilityService.KEY_TIMESTAMP, System.currentTimeMillis())
                .apply()
            service.broadcastLog("[Camera] Capture scheduled via AccessibilityService")
            service.broadcastTTS("Taking photo")
        } catch (e: Exception) {
            Log.e(TAG, "scheduleCameraCapture error: ${e.message}")
            service.broadcastLog("[Error] Camera capture: ${e.message}")
            service.broadcastTTS("Could not capture photo")
        }
    }

    /**
     * Parses time strings like "6 AM", "06:30", "18:00" → Pair(hour24, minute).
     */
    private fun parseTime(timeStr: String): Pair<Int, Int> {
        // Try HH:MM format
        val colonRegex = Regex("""(\d{1,2}):(\d{2})""")
        colonRegex.find(timeStr)?.let {
            return Pair(it.groupValues[1].toInt(), it.groupValues[2].toInt())
        }
        // Try "6 AM" / "6 PM" format
        val amPmRegex = Regex("""(\d{1,2})\s*(am|pm)""", RegexOption.IGNORE_CASE)
        amPmRegex.find(timeStr)?.let {
            var hour = it.groupValues[1].toInt()
            val isPm = it.groupValues[2].lowercase() == "pm"
            if (isPm && hour != 12) hour += 12
            if (!isPm && hour == 12) hour = 0
            return Pair(hour, 0)
        }
        // Default fallback
        return Pair(6, 0)
    }
}
