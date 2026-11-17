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
        return n?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
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
