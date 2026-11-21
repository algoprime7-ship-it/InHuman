package ai.inhuman.app

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper

/**
 * TikTok LIVE helpers. Android gives no official way to start a LIVE, so this opens TikTok and taps through
 * the screens with the accessibility service (Create → LIVE → Mobile gaming / Screen share → Go LIVE → Start now),
 * then brings InHuman back on screen so viewers see the app. Button names differ between TikTok versions,
 * so if a step isn't found it just waits for you to tap it.
 */
object TikTok {
    private val main = Handler(Looper.getMainLooper())
    private val PACKAGES = listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill")

    fun live(c: Context) = Prefs.bool(c, "live", false)
    fun setLive(c: Context, on: Boolean) = Prefs.setBool(c, "live", on)

    private fun openTikTok(c: Context): Boolean {
        for (p in PACKAGES) {
            val i = c.packageManager.getLaunchIntentForPackage(p) ?: continue
            c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return true
        }
        return false
    }

    fun backToApp(c: Context) {
        c.startActivity(Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
    }

    /** Open TikTok and tap through a screen-share LIVE, then come back to InHuman */
    fun goLive(c: Context): Boolean {
        val app = c.applicationContext
        if (!openTikTok(app)) return false
        val steps = listOf(
            2500L to listOf("Create", "Post", "+"),
            4500L to listOf("LIVE"),
            6500L to listOf("Mobile gaming", "Screen share", "Share screen", "Gaming", "Game"),
            8500L to listOf("Go LIVE", "Go live"),
            11000L to listOf("Start now", "Start", "Allow")
        )
        steps.forEach { (at, labels) -> main.postDelayed({ InHumanAccessibility.tapLabel(labels, null, wholeWord = false, tries = 8) }, at) }
        main.postDelayed({ backToApp(app) }, 15000)
        return true
    }

    /** End the LIVE in TikTok (warning from TikTok, or "end the live") */
    fun endLive(c: Context) {
        val app = c.applicationContext
        setLive(app, false)
        if (!openTikTok(app)) return
        main.postDelayed({ InHumanAccessibility.tapLabel(listOf("End LIVE", "End live", "End"), null, wholeWord = false, tries = 10) }, 2000)
        main.postDelayed({ InHumanAccessibility.tapLabel(listOf("End now", "End", "Confirm"), null, wholeWord = false, tries = 10) }, 4500)
        main.postDelayed({ backToApp(app) }, 8000)
    }

    private val WARNING = Regex("\\b(warning|violat|community guidelines|restricted|suspend|banned|ban|removed|paused your live|live (has been|was) (ended|stopped)|minor safety)\\b", RegexOption.IGNORE_CASE)
    fun isWarning(pkg: String, text: String) = pkg in PACKAGES && WARNING.containsMatchIn(text)

    /** While LIVE: nothing private, no calls, no WhatsApp, no browsers, no banking. */
    private val ALLOWED = setOf("play_here", "music", "play_music", "trade")
    private val MT5_APP = Regex("meta ?trader|\\bmt ?5\\b", RegexOption.IGNORE_CASE)
    /** While LIVE: songs, trades and opening MetaTrader 5 only */
    fun allowed(c: Context, a: org.json.JSONObject): Boolean {
        if (!live(c)) return true
        val type = a.optString("type")
        if (type == "open_app") return MT5_APP.containsMatchIn(a.optString("app"))
        return type in ALLOWED
    }
}
