package ai.inhuman.app

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

// =====================================================================
// "Read notifications" (sidebar switch): reads out each NEW notification that arrives after you
// switch it on, e.g. "WhatsApp. Mom says: are you coming?". Never reads old ones.
// =====================================================================
class NotificationReader : NotificationListenerService() {

    companion object {
        @Volatile var connected = false

        fun hasAccess(c: Context): Boolean {
            val flat = Settings.Secure.getString(c.contentResolver, "enabled_notification_listeners") ?: return false
            return flat.contains(ComponentName(c, NotificationReader::class.java).flattenToString())
        }

        fun openAccessSettings(c: Context) {
            try {
                c.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) {}
        }

        private val recent = LinkedHashMap<String, Long>()
        private val SKIP = setOf("android", "com.android.systemui", "com.android.vending", "com.google.android.gms")
    }

    override fun onListenerConnected() { connected = true }
    override fun onListenerDisconnected() { connected = false }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val n = sbn ?: return
        val c = applicationContext
        // TikTok LIVE: never read notifications out on stream; a TikTok warning ends the LIVE straight away
        if (TikTok.live(c)) {
            val ex = n.notification?.extras
            val txt = "${ex?.getCharSequence(Notification.EXTRA_TITLE) ?: ""} ${ex?.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex?.getCharSequence(Notification.EXTRA_TEXT) ?: ""}"
            if (TikTok.isWarning(n.packageName, txt)) {
                val act = MainActivity.instance
                if (act != null) act.runOnUiThread { act.liveWarning() } else TikTok.endLive(c)
            }
            return
        }
        if (!Prefs.bool(c, "readNotifs", false)) return
        if (n.postTime < Prefs.long(c, "readNotifsSince", 0L)) return
        if (n.packageName == packageName || n.packageName in SKIP) return
        if (n.isOngoing || !n.isClearable) return
        val nf = n.notification ?: return
        if (nf.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (nf.category == Notification.CATEGORY_CALL || nf.category == Notification.CATEGORY_TRANSPORT ||
            nf.category == Notification.CATEGORY_PROGRESS || nf.category == Notification.CATEGORY_SERVICE) return

        val ex = nf.extras
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val body = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString()?.trim().orEmpty()
        if (title.isBlank() && body.isBlank()) return

        // the same message twice (apps update notifications) → read once
        val key = "${n.packageName}|$title|$body"
        val now = System.currentTimeMillis()
        synchronized(recent) {
            recent.entries.removeAll { now - it.value > 120000 }
            if (recent.containsKey(key)) return
            recent[key] = now
        }

        val app = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(n.packageName, 0)).toString()
        } catch (e: Exception) { "A notification" }
        val clipped = if (body.length > 220) body.take(220) + "…" else body
        val line = when {
            title.isNotBlank() && clipped.isNotBlank() -> "$app. $title says: $clipped"
            title.isNotBlank() -> "$app. $title"
            else -> "$app. $clipped"
        }
        OverlayService.instance?.announce(line) ?: Voice.speak(line)
    }
}
