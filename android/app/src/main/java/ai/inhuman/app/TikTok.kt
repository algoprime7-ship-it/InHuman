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

    private data class Step(val labels: List<String>, val exact: Boolean, val ask: String)
    private val STEPS = listOf(
        Step(listOf("Create", "Post", "+"), true, "Tap the plus button for me"),
        Step(listOf("LIVE"), true, "Tap LIVE at the bottom for me"),
        Step(listOf("Mobile gaming", "Screen share", "Share screen", "Gaming"), false, "Pick Mobile gaming or Screen share"),
        Step(listOf("Go LIVE", "Go live"), true, "Tap Go LIVE"),
        Step(listOf("Start now", "Start"), true, "Tap Start now")
    )

    /** Open TikTok and tap through a screen-share LIVE step by step, then come back to InHuman.
     *  If a button can't be found (TikTok changes its screens), Jarvis asks you to tap it and carries on from there. */
    fun goLive(c: Context): Boolean {
        val app = c.applicationContext
        if (!InHumanAccessibility.ready) {
            Voice.speak("Switch on InHuman in Accessibility settings first, so I can tap through TikTok for you.")
            return false
        }
        if (!openTikTok(app)) return false
        var step = 0
        var since = System.currentTimeMillis()
        var asked = -1
        val started = System.currentTimeMillis()
        val tick = object : Runnable {
            override fun run() {
                if (step >= STEPS.size) { main.postDelayed({ backToApp(app) }, 2500); return }
                if (System.currentTimeMillis() - started > 120000) { backToApp(app); return }       // give up after 2 minutes
                // a later step already on screen (you tapped one yourself)? jump ahead
                for (i in STEPS.indices.reversed()) {
                    if (i < step) break
                    val st = STEPS[i]
                    if (InHumanAccessibility.tapOnce(st.labels, st.exact)) {
                        step = i + 1
                        since = System.currentTimeMillis()
                        main.postDelayed(this, 1600)
                        return
                    }
                }
                if (System.currentTimeMillis() - since > 8000 && asked != step) {
                    asked = step
                    Voice.speak("${STEPS[step].ask}, sir.")
                }
                main.postDelayed(this, 600)
            }
        }
        main.postDelayed(tick, 2200)
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
