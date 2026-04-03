package com.aiassistant

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * AutoSendAccessibilityService
 *
 * Strategy (runs on every WhatsApp window event while pending=true):
 *
 *   1. Try to find and click the SEND button immediately.
 *      → Works when WhatsApp's jid extra routes us to the right chat.
 *
 *   2. If no Send button found AND contact not yet tapped,
 *      try to find the contact name in the share picker and tap it.
 *      → Works when WhatsApp ignores jid and shows its contact picker.
 *
 *   3. After tapping the contact, wait 2 s, then try Send again.
 *
 * State resets automatically when a new share request arrives (new timestamp).
 */
class AutoSendAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "AutoSendService"
        const val PREFS_NAME        = "auto_send_prefs"
        const val KEY_PENDING       = "auto_send_pending"
        const val KEY_TIMESTAMP     = "auto_send_timestamp"
        const val KEY_CONTACT_NAME  = "auto_send_contact"
        const val KEY_CAMERA_CAPTURE = "camera_capture_pending"
        private const val WINDOW_MS = 15_000L

        /** Singleton reference set when the service connects — used by TaskExecutor. */
        @Volatile
        var instance: AutoSendAccessibilityService? = null
            private set
    }

    private val handler = Handler(Looper.getMainLooper())
    private var lastTimestamp  = 0L
    private var contactTapped  = false
    private var sendFired      = false
    // Camera capture state
    private var lastCameraTimestamp = 0L
    private var cameraShotFired     = false

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString() ?: return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // ── Camera capture branch ─────────────────────────────────────────────
        if (prefs.getBoolean(KEY_CAMERA_CAPTURE, false)) {
            handleCameraCapture(pkg, prefs)
        }

        // ── WhatsApp share branch ─────────────────────────────────────────────
        if (pkg == "com.whatsapp") {
            handleWhatsAppShare(prefs)
        }
    }

    private fun handleCameraCapture(pkg: String, prefs: android.content.SharedPreferences) {
        val timestamp = prefs.getLong(KEY_TIMESTAMP, 0L)

        // Reset if new capture request
        if (timestamp != lastCameraTimestamp) {
            lastCameraTimestamp = timestamp
            cameraShotFired = false
            Log.d(TAG, "New camera capture request")
        }

        if (cameraShotFired) return
        if (System.currentTimeMillis() - timestamp > WINDOW_MS) {
            prefs.edit().putBoolean(KEY_CAMERA_CAPTURE, false).apply()
            cameraShotFired = false; return
        }

        val root = rootInActiveWindow ?: return
        val shutterNode = findShutterButton(root)
        root.recycle()
        if (shutterNode != null) {
            Log.d(TAG, "Shutter button found in $pkg — clicking.")
            shutterNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            shutterNode.recycle()
            cameraShotFired = true
            prefs.edit().putBoolean(KEY_CAMERA_CAPTURE, false).apply()
        } else {
            Log.d(TAG, "Shutter not found yet in $pkg — waiting for next event.")
        }
    }

    private fun handleWhatsAppShare(prefs: android.content.SharedPreferences) {
        if (!prefs.getBoolean(KEY_PENDING, false)) return

        val timestamp = prefs.getLong(KEY_TIMESTAMP, 0L)

        // New request — reset state machine
        if (timestamp != lastTimestamp) {
            lastTimestamp = timestamp
            contactTapped = false
            sendFired     = false
            handler.removeCallbacksAndMessages(null)
            Log.d(TAG, "New share request. Contact: ${prefs.getString(KEY_CONTACT_NAME, "?")}")
        }

        if (System.currentTimeMillis() - timestamp > WINDOW_MS) {
            Log.d(TAG, "Window expired — clearing state.")
            clear(prefs); return
        }

        if (sendFired) return  // already handled

        val root = rootInActiveWindow ?: return
        val contactName = prefs.getString(KEY_CONTACT_NAME, "") ?: ""

        // --- Step 1: Try Send button immediately (works when jid was honoured) ---
        val sendNode = findSendButton(root)
        if (sendNode != null) {
            Log.d(TAG, "Send button found — clicking.")
            sendNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            sendNode.recycle()
            sendFired = true
            root.recycle()
            handler.postDelayed({ clear(prefs) }, 500)
            return
        }

        // --- Step 2: No Send button → try to tap contact in picker ---
        if (!contactTapped && contactName.isNotBlank()) {
            val contactNode = findNodeWithText(root, contactName)
            if (contactNode != null) {
                Log.d(TAG, "Contact picker: tapping \"$contactName\"")
                contactNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                contactNode.recycle()
                contactTapped = true
                // Wait 2 s for chat to open, then attempt Send
                handler.postDelayed({
                    val r = rootInActiveWindow
                    if (r?.packageName?.toString() == "com.whatsapp") {
                        val sb = findSendButton(r)
                        if (sb != null) {
                            Log.d(TAG, "Send button clicked after contact tap.")
                            sb.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            sb.recycle()
                            sendFired = true
                        } else {
                            Log.w(TAG, "Send button not found after 2 s delay — will retry on next event.")
                            // allow retry via next window event
                            contactTapped = true  // keep contact as tapped, skip picker
                        }
                        r.recycle()
                    } else {
                        r?.recycle()
                    }
                    if (sendFired) clear(getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))
                }, 2000)
            } else {
                Log.d(TAG, "Contact \"$contactName\" not visible in picker yet — waiting.")
            }
        }

        root.recycle()
    } // end handleWhatsAppShare

    /** Find the camera shutter button by content description or resource ID. */
    private fun findShutterButton(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val resId = node.viewIdResourceName?.lowercase() ?: ""
        val desc  = node.contentDescription?.toString()?.lowercase() ?: ""
        val text  = node.text?.toString()?.lowercase() ?: ""

        val isShutter = node.isClickable && (
            resId.contains("shutter") || resId.contains("capture") || resId.contains("take_photo") ||
            desc.contains("shutter") || desc.contains("capture") || desc.contains("take photo") ||
            desc.contains("take a photo") || desc.contains("click photo") ||
            text.contains("shutter") || text.contains("capture")
        )
        if (isShutter) {
            Log.d(TAG, "  → shutter node: resId=$resId desc=$desc text=$text")
            return AccessibilityNodeInfo.obtain(node)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val r = findShutterButton(child)
            if (r != null) { child.recycle(); return r }
            child.recycle()
        }
        return null
    }

    /** Find WhatsApp send / forward button by resource ID, desc, or text. */
    private fun findSendButton(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val resId = node.viewIdResourceName?.lowercase() ?: ""
        val desc  = node.contentDescription?.toString()?.lowercase() ?: ""
        val text  = node.text?.toString()?.lowercase() ?: ""

        if (node.isClickable && (
                resId == "com.whatsapp:id/send" ||
                resId == "com.whatsapp:id/send_btn" ||
                desc == "send" || text == "send")) {
            Log.d(TAG, "  → send node: resId=$resId desc=$desc text=$text")
            return AccessibilityNodeInfo.obtain(node)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val r = findSendButton(child)
            if (r != null) { child.recycle(); return r }
            child.recycle()
        }
        return null
    }

    /** Find a clickable node (or nearest clickable ancestor) whose text/desc contains [name]. */
    private fun findNodeWithText(node: AccessibilityNodeInfo, name: String): AccessibilityNodeInfo? {
        if (name.isBlank()) return null
        val q = name.lowercase()
        val text = node.text?.toString()?.lowercase() ?: ""
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        val matches = text.contains(q) || desc.contains(q)

        if (matches) {
            if (node.isClickable) return AccessibilityNodeInfo.obtain(node)
            // walk up to first clickable ancestor
            var p = node.parent
            while (p != null) {
                if (p.isClickable) return AccessibilityNodeInfo.obtain(p)
                val next = p.parent; p.recycle(); p = next
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val r = findNodeWithText(child, name)
            if (r != null) { child.recycle(); return r }
            child.recycle()
        }
        return null
    }

    private fun clear(prefs: android.content.SharedPreferences) {
        prefs.edit().putBoolean(KEY_PENDING, false).remove(KEY_CONTACT_NAME).apply()
        contactTapped = false; sendFired = false
        handler.removeCallbacksAndMessages(null)
    }

    override fun onInterrupt() {
        handler.removeCallbacksAndMessages(null)
        instance = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "AutoSendAccessibilityService connected.")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    /**
     * Traverses the current window's accessibility tree and collects all
     * visible text and content descriptions. Returns a newline-joined string
     * suitable for sending to the /describe_screen LLM endpoint.
     */
    fun getScreenText(): String {
        val root = rootInActiveWindow ?: return ""
        val lines = mutableListOf<String>()
        collectText(root, lines)
        root.recycle()
        return lines.distinct().joinToString("\n")
    }

    private fun collectText(node: AccessibilityNodeInfo, out: MutableList<String>) {
        val text = node.text?.toString()?.trim() ?: ""
        val desc = node.contentDescription?.toString()?.trim() ?: ""
        if (text.isNotBlank()) out.add(text)
        if (desc.isNotBlank() && desc != text) out.add(desc)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectText(child, out)
            child.recycle()
        }
    }
}
