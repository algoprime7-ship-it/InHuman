package ai.inhuman.app

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

// =====================================================================
// Accessibility: Home/Back/Lock + pressing "Send" in WhatsApp, Instagram and Messenger
// (only acts inside those apps, and only right after you give a command)
// =====================================================================
class InHumanAccessibility : AccessibilityService() {

    private class Pending(
        val pkgs: Set<String>,
        val text: String?,
        val notBefore: Long,
        val until: Long,
        val onDone: ((Boolean) -> Unit)?
    ) {
        var typed = false
    }

    companion object {
        @Volatile private var instance: InHumanAccessibility? = null
        @Volatile private var pending: Pending? = null
        private val main = Handler(Looper.getMainLooper())

        fun isOn(): Boolean = instance != null

        fun perform(name: String): Boolean {
            val s = instance ?: return false
            val action = when (name) {
                "home" -> AccessibilityService.GLOBAL_ACTION_HOME
                "back" -> AccessibilityService.GLOBAL_ACTION_BACK
                "recents" -> AccessibilityService.GLOBAL_ACTION_RECENTS
                "notifications" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
                "lock" -> if (Build.VERSION.SDK_INT >= 28) AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN else return false
                else -> return false
            }
            return s.performGlobalAction(action)
        }

        /**
         * After we open a chat: optionally type [text] into the message box, then press Send.
         * Gives up after 15 seconds. onDone(true) when Send was pressed.
         */
        fun queueSend(pkgs: Set<String>, text: String?, onDone: ((Boolean) -> Unit)? = null) {
            if (instance == null) return
            val now = SystemClock.uptimeMillis()
            pending = Pending(pkgs, text, now + 900, now + 15000, onDone)
            main.postDelayed({
                val p = pending
                if (p != null && SystemClock.uptimeMillis() >= p.until) {
                    pending = null
                    p.onDone?.invoke(false)
                }
            }, 15500)
        }

        /**
         * Phone controls: find [labels] on screen (Wi-Fi panel, Bluetooth, hotspot, a network or device name)
         * and tap it. If [want] is set, only taps a switch that isn't already in that state. Tries for 6 s.
         */
        fun tapLabel(labels: List<String>, want: Boolean?, wholeWord: Boolean = true, tries: Int = 15) {
            val s = instance ?: return
            val root = s.rootInActiveWindow
            val done = root != null && s.tryTap(root, labels.map { it.lowercase().trim() }, want, wholeWord)
            if (!done && tries > 0) main.postDelayed({ tapLabel(labels, want, wholeWord, tries - 1) }, 400)
        }

        /** one try, no retries: true if the button was found and tapped */
        fun tapOnce(labels: List<String>, wholeWord: Boolean): Boolean {
            val s = instance ?: return false
            val root = s.rootInActiveWindow ?: return false
            return s.tryTap(root, labels.map { it.lowercase().trim() }, null, wholeWord)
        }
        val ready: Boolean get() = instance != null

        private val SEND_IDS = listOf(
            "com.whatsapp:id/send",
            "com.whatsapp.w4b:id/send",
            "com.instagram.android:id/row_thread_composer_send_button_container",
            "com.instagram.android:id/row_thread_composer_button_send",
            "com.facebook.orca:id/send_button"
        )
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val p = pending ?: return
        val now = SystemClock.uptimeMillis()
        if (now > p.until) {
            pending = null
            p.onDone?.invoke(false)
            return
        }
        if (now < p.notBefore) return // let the new chat open first
        val pkg = event?.packageName?.toString() ?: return
        if (pkg !in p.pkgs) return
        val root = rootInActiveWindow ?: return

        if (p.text != null && !p.typed) {
            val field = findEditable(root) ?: return
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, p.text)
            }
            field.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            if (field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) p.typed = true
            return // the Send button appears on the next screen update
        }

        val send = findSend(root) ?: return
        if (clickUp(send)) {
            pending = null
            main.post { p.onDone?.invoke(true) }
        }
    }

    private fun findEditable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isEditable && node.isVisibleToUser) return node
        for (i in 0 until node.childCount) {
            val found = findEditable(node.getChild(i))
            if (found != null) return found
        }
        return null
    }

    /** true when done (tapped, or the switch was already right) */
    private fun tryTap(root: AccessibilityNodeInfo, labels: List<String>, want: Boolean?, wholeWord: Boolean): Boolean {
        val hit = findText(root, labels, wholeWord) ?: return false
        // a switch next to the label (row → its checkable child), or the label's own row
        var row: AccessibilityNodeInfo? = hit
        var sw: AccessibilityNodeInfo? = null
        for (i in 0 until 4) {
            val r = row ?: break
            sw = findCheckable(r)
            if (sw != null) break
            row = r.parent
        }
        if (want != null && sw != null && sw.isChecked == want) return true
        val target = sw ?: hit
        return clickUp(target)
    }

    private fun findText(node: AccessibilityNodeInfo?, labels: List<String>, wholeWord: Boolean): AccessibilityNodeInfo? {
        if (node == null) return null
        val text = (node.text ?: node.contentDescription)?.toString()?.trim()?.lowercase()
        if (text != null && node.isVisibleToUser && !node.isEditable) {
            val match = labels.any { l -> if (wholeWord) text == l || text.startsWith("$l ") || text.startsWith("$l,") else text.contains(l) }
            if (match) return node
        }
        for (i in 0 until node.childCount) {
            val f = findText(node.getChild(i), labels, wholeWord)
            if (f != null) return f
        }
        return null
    }

    private fun findCheckable(node: AccessibilityNodeInfo?, depth: Int = 0): AccessibilityNodeInfo? {
        if (node == null || depth > 4) return null
        if (node.isCheckable && node.isVisibleToUser) return node
        for (i in 0 until node.childCount) {
            val f = findCheckable(node.getChild(i), depth + 1)
            if (f != null) return f
        }
        return null
    }

    private fun findSend(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        for (id in SEND_IDS) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id) ?: continue
            for (n in nodes) if (n.isVisibleToUser) return n
        }
        return findByLabel(root, "send")
    }

    private fun findByLabel(node: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
        if (node == null) return null
        val desc = node.contentDescription?.toString()?.trim()?.lowercase()
        val text = node.text?.toString()?.trim()?.lowercase()
        if (node.isVisibleToUser && !node.isEditable && (desc == label || text == label)) return node
        for (i in 0 until node.childCount) {
            val found = findByLabel(node.getChild(i), label)
            if (found != null) return found
        }
        return null
    }

    private fun clickUp(node: AccessibilityNodeInfo): Boolean {
        var n: AccessibilityNodeInfo? = node
        while (n != null && !n.isClickable) n = n.parent
        if (n?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return true
        return tapAt(node)        // apps that draw their own buttons (TikTok's LIVE tab): a real finger tap on the spot
    }

    /** A real tap on the middle of the node (for buttons that don't accept accessibility clicks) */
    private fun tapAt(node: AccessibilityNodeInfo): Boolean {
        if (Build.VERSION.SDK_INT < 24) return false
        val r = android.graphics.Rect()
        node.getBoundsInScreen(r)
        if (r.width() <= 0 || r.height() <= 0) return false
        val path = android.graphics.Path().apply { moveTo(r.exactCenterX(), r.exactCenterY()) }
        val stroke = android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 60)
        return dispatchGesture(android.accessibilityservice.GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }
}
