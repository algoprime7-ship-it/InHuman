package ai.inhuman.app

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.abs

/** One song for the floating player. kind = "stream" (audio URL) or "youtube" (videoId). */
class Track(val kind: String, val url: String, val videoId: String, val title: String, val artist: String, val id: String) {
    fun json(): JSONObject = JSONObject().put("kind", kind).put("url", url).put("videoId", videoId)
        .put("title", title).put("artist", artist).put("id", id)

    companion object {
        fun from(j: JSONObject) = Track(
            j.optString("kind"), j.optString("url"), j.optString("videoId"),
            j.optString("title"), j.optString("artist"), j.optString("id")
        )
    }
}

// =====================================================================
// Floating InHuman circle over other apps: trigger phrase, answers card, voice notes, music
// =====================================================================
class OverlayService : Service() {

    companion object {
        @Volatile var running = false
        @Volatile var instance: OverlayService? = null
        private const val CHANNEL = "inhuman_listening"

        fun start(c: Context) {
            val i = Intent(c, OverlayService::class.java)
            try {
                c.startForegroundService(i)
            } catch (e: Exception) { /* Android refused: retried next time the app opens */ }
        }

        fun stop(c: Context) {
            c.stopService(Intent(c, OverlayService::class.java))
        }
    }

    private enum class Mode { IDLE, LISTENING, THINKING, SPEAKING, RECORDING }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private lateinit var circle: FrameLayout
    private lateinit var logo: ImageView
    private lateinit var ring: GradientDrawable
    private lateinit var card: TextView
    private lateinit var circleLp: WindowManager.LayoutParams
    private lateinit var cardLp: WindowManager.LayoutParams
    private var cardShown = false
    private var pulse: ObjectAnimator? = null
    private var spin: ObjectAnimator? = null
    private var wake: WakeWord? = null
    private var busy = false
    private var resumeMusicAfter = false
    private val history = mutableListOf<JSONObject>()
    private val hideCard = Runnable { setCardVisible(false) }
    val player = Player()

    // voice note recording
    private var recorder: MediaRecorder? = null
    private var recordFinish: (() -> Unit)? = null

    private val dp get() = resources.displayMetrics.density
    private fun Int.px() = (this * dp).toInt()
    private val circleSize get() = 64.px()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (!goForeground()) {
            stopSelf()
            return
        }
        running = true
        instance = this
        Voice.init(this)
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        buildOverlay()
        wake = WakeWord(this, onWake = { cmd, f -> onWake(cmd, f) }, onStatus = { showCard(it, 9000) })
        wake?.start()
        main.postDelayed(heartbeat, 3000)
        Sfx.warmUp(this)
        Voice.duckListener = { on -> player.duck(on) }
        // cloned voices on? (VOICE_SERVER_URL set on Vercel)
        Thread {
            try {
                val cfg = Net.json("${Prefs.serverUrl(applicationContext)}/api/client?action=config", null, timeoutMs = 10000)
                Voice.cloud = cfg.optBoolean("voiceServer", false)
            } catch (e: Exception) {}
        }.start()
        watchCalls()
        main.postDelayed(checkIn, CHECKIN_MS)
        // car / Bluetooth speaker / headphones: say where the sound went, and tell the Devices page
        AudioRoute.watch(this) { now, before -> announceRoute(now, before) }
    }

    private fun announceRoute(now: AudioRoute.Route, before: AudioRoute.Route?) {
        Prefs.setStr(this, "audioRoute", now.json().toString())
        main.removeCallbacks(heartbeat); main.post(heartbeat)     // update the Devices page straight away
        if (TikTok.live(this) || ringing) return
        val w = sirWord()
        val line = when (now.kind) {
            "car" -> "Connected to your ${now.name}, $w. I'm with you on the road, just say my name. Drive safe."
            "speaker" -> "Sound is now on your ${now.name}, $w."
            "headphones" -> "Your ${now.name} ${if (now.name.endsWith("s")) "are" else "is"} connected, $w."
            "bluetooth" -> "Connected to ${now.name}, $w."
            else -> if (before != null && before.kind != "phone") "${before.name} disconnected, $w. Back on the phone speaker." else return
        }
        if (busy) main.postDelayed({ if (!busy) say(line) }, 4000) else say(line)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        running = false
        instance = null
        AudioRoute.stop(this)
        Voice.duckListener = null
        main.removeCallbacksAndMessages(null)
        callReceiver?.let { try { unregisterReceiver(it) } catch (e: Exception) {} }
        wake?.release()
        player.stop()
        try { recorder?.release() } catch (e: Exception) {}
        if (::circle.isInitialized) try { wm.removeView(circle) } catch (e: Exception) {}
        if (::card.isInitialized && cardShown) try { wm.removeView(card) } catch (e: Exception) {}
        super.onDestroy()
    }

    // ---------------- foreground notification ----------------
    private fun goForeground(): Boolean {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "InHuman listening", NotificationManager.IMPORTANCE_LOW))
        val openApp = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val openSetup = PendingIntent.getActivity(this, 1, Intent(this, SetupActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CHANNEL)
            .setContentTitle("InHuman AI is online")
            .setContentText("Say \"${Prefs.trigger(this)}\" or tap the circle")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(openApp)
            .addAction(Notification.Action.Builder(null, "Setup", openSetup).build())
            .setOngoing(true)
            .build()
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(
                    7, n,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } else {
                startForeground(7, n)
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    // ---------------- circle + card ----------------
    private fun overlayParams(w: Int, h: Int) = WindowManager.LayoutParams(
        w, h,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START }

    private fun buildOverlay() {
        ring = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.argb(235, 8, 11, 18))
            setStroke(2.px(), Color.argb(110, 255, 255, 255))
        }
        circle = FrameLayout(this).apply {
            background = ring
            elevation = 10 * dp
            setPadding(3.px(), 3.px(), 3.px(), 3.px())
        }
        logo = ImageView(this).apply {
            setImageResource(R.drawable.ic_logo)
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.BLACK) }
            clipToOutline = true
        }
        circle.addView(logo, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        val screenW = resources.displayMetrics.widthPixels
        circleLp = overlayParams(circleSize, circleSize).apply {
            x = Prefs.posX(this@OverlayService, screenW - circleSize - 16.px())
            y = Prefs.posY(this@OverlayService, 260.px())
        }
        wm.addView(circle, circleLp)
        attachCircleTouch()

        card = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setLineSpacing(0f, 1.15f)
            maxWidth = 280.px()
            setPadding(14.px(), 12.px(), 14.px(), 12.px())
            background = GradientDrawable().apply {
                cornerRadius = 16 * dp
                setColor(Color.argb(242, 8, 11, 18))
                setStroke(1.px(), Color.argb(60, 255, 255, 255))
            }
            elevation = 10 * dp
            setOnClickListener { setCardVisible(false) }
        }
        cardLp = overlayParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachCircleTouch() {
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var downTime = 0L
        var moved = false
        circle.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = circleLp.x; startY = circleLp.y
                    downTime = SystemClock.uptimeMillis()
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!moved && (abs(dx) > 10 * dp || abs(dy) > 10 * dp)) moved = true
                    if (moved) {
                        circleLp.x = startX + dx.toInt()
                        circleLp.y = startY + dy.toInt()
                        wm.updateViewLayout(circle, circleLp)
                        positionCard()
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    when {
                        moved -> Prefs.savePos(this, circleLp.x, circleLp.y)
                        SystemClock.uptimeMillis() - downTime > 650 ->
                            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        recordFinish != null -> { v.performClick(); recordFinish?.invoke() }
                        else -> { v.performClick(); onWake() }
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun setCardVisible(show: Boolean) {
        if (show == cardShown) {
            if (show) positionCard()
            return
        }
        cardShown = show
        try {
            if (show) positionCard(addingNow = true) else wm.removeView(card)
        } catch (e: Exception) {}
    }

    private fun positionCard(addingNow: Boolean = false) {
        if (!cardShown) return
        val sw = resources.displayMetrics.widthPixels
        val sh = resources.displayMetrics.heightPixels
        card.measure(
            View.MeasureSpec.makeMeasureSpec(sw - 24.px(), View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(sh, View.MeasureSpec.AT_MOST)
        )
        val w = card.measuredWidth
        val h = card.measuredHeight
        cardLp.x = (circleLp.x + circleSize / 2 - w / 2).coerceIn(12.px(), maxOf(12.px(), sw - w - 12.px()))
        val below = circleLp.y + circleSize + 8.px()
        cardLp.y = if (below + h < sh - 24.px()) below else maxOf(12.px(), circleLp.y - h - 8.px())
        if (addingNow) wm.addView(card, cardLp) else wm.updateViewLayout(card, cardLp)
    }

    fun showCard(text: String, ms: Long = 0) {
        card.text = text
        setCardVisible(true)
        main.removeCallbacks(hideCard)
        main.postDelayed(hideCard, if (ms > 0) ms else maxOf(8000L, text.length * 70L))
    }

    private fun setMode(m: Mode) {
        pulse?.cancel()
        circle.scaleX = 1f
        circle.scaleY = 1f
        circle.alpha = 1f
        when (m) {
            Mode.IDLE -> ring.setStroke(2.px(), Color.argb(110, 255, 255, 255))
            Mode.SPEAKING -> ring.setStroke(3.px(), Color.WHITE)        // steady while talking (no fading in and out)
            Mode.LISTENING, Mode.RECORDING -> {
                ring.setStroke(3.px(), if (m == Mode.RECORDING) Color.rgb(255, 59, 92) else Color.rgb(34, 230, 255))
                pulse = ObjectAnimator.ofFloat(circle, "scaleX", 1f, 1.14f).apply {
                    duration = 520
                    repeatMode = ValueAnimator.REVERSE
                    repeatCount = ValueAnimator.INFINITE
                    addUpdateListener { circle.scaleY = circle.scaleX }
                    start()
                }
            }
            Mode.THINKING -> {
                ring.setStroke(3.px(), Color.WHITE)
                pulse = ObjectAnimator.ofFloat(circle, "alpha", 1f, 0.45f).apply {
                    duration = 420
                    repeatMode = ValueAnimator.REVERSE
                    repeatCount = ValueAnimator.INFINITE
                    start()
                }
            }
        }
    }

    /** The logo slowly spins while music plays, so InHuman looks alive even with the app closed */
    private fun spinLogo(on: Boolean) {
        if (on) {
            if (spin?.isRunning == true) return
            spin = ObjectAnimator.ofFloat(logo, "rotation", logo.rotation, logo.rotation + 360f).apply {
                duration = 9000
                interpolator = LinearInterpolator()
                repeatCount = ValueAnimator.INFINITE
                start()
            }
        } else {
            spin?.cancel()
            spin = null
        }
    }

    // ---------------- wake word ----------------
    fun pauseWakeWord() = wake?.pause()

    fun resumeWakeWord() {
        main.postDelayed({ if (running && !busy && recorder == null) wake?.resume() }, 500)
    }

    /** Called when the app comes to the front: Android may have cut the mic after a restart in the background */
    fun refreshWakeWord() {
        if (running && !busy && recorder == null) wake?.restart()
    }

    // ---------------- this phone ↔ your other devices (same InHuman account) ----------------
    // Every 30 s: tell the server the screen/battery state and pick up commands ("lock", "wake", "play …").
    private val heartbeat = object : Runnable {
        override fun run() {
            if (!running) return
            // stuck "busy" (a reply that never finished) → free the wake word again
            if (busy && !ringing && recorder == null && System.currentTimeMillis() - busySince > 300000) done()
            val app = applicationContext
            val token = Prefs.account(app)
            val server = Prefs.serverUrl(app)
            if (token.isNotBlank() && server.isNotBlank()) Thread {
                try {
                    val pm = getSystemService(POWER_SERVICE) as PowerManager
                    val bm = getSystemService(BATTERY_SERVICE) as android.os.BatteryManager
                    val info = DeviceInfo.json(app)
                    val body = JSONObject()
                        .put("token", token).put("deviceId", Prefs.deviceId(app))
                        .put("name", info.optString("name")).put("model", info.optString("model"))
                        .put("platform", "android-app").put("app", "android")
                        .put("screenOn", pm.isInteractive)
                        .put("battery", bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY))
                        .put("charging", bm.isCharging)
                        .put("canLock", InHumanAccessibility.isOn())
                        .put("audio", AudioRoute.current(app).json())         // car / speaker / headphones name
                    val res = Net.json("$server/api/client?action=heartbeat", body, timeoutMs = 15000)
                    // new InHuman messages: show and read them out
                    val pr = Net.json("$server/api/social?action=poll", JSONObject().put("token", token).put("deviceId", Prefs.deviceId(app)), timeoutMs = 15000)
                    val ms = pr.optJSONArray("messages") ?: JSONArray()
                    for (i in 0 until ms.length()) {
                        val m = ms.optJSONObject(i) ?: continue
                        main.post { announce("Message from ${m.optString("fromName", "someone")}: ${m.optString("text")}") }
                    }
                    val cmds = res.optJSONArray("commands") ?: JSONArray()
                    for (i in 0 until cmds.length()) {
                        val c = cmds.optJSONObject(i) ?: continue
                        main.post { remoteCommand(c) }
                    }
                } catch (e: Exception) { /* offline: try again next time */ }
            }.start()
            main.postDelayed(this, 30000)
        }
    }

    private fun remoteCommand(c: JSONObject) {
        val arg = c.optString("arg")
        val from = c.optString("from").let { if (it.isBlank()) "" else " from $it" }
        when (c.optString("command")) {
            "lock" -> if (!InHumanAccessibility.perform("lock")) say("Turn on InHuman in Accessibility so other devices can lock this phone, sir.")
            "wake" -> { Brain.wakeScreen(this); showCard("Screen turned on$from", 4000) }
            "play" -> if (arg.isNotBlank()) {
                Brain.wakeScreen(this)
                Brain.useCredits(this, listOf("song")) { ok, blocked ->
                    if (!ok) say(blocked)
                    else {
                        player.prepaid = true
                        showCard("▶ $arg$from", 6000)
                        player.playQuery(arg)
                    }
                }
            }
            "stop" -> player.stop()
            "say" -> if (arg.isNotBlank()) say(arg)
            "open" -> if (arg.isNotBlank()) Brain.openApp(this, arg)
        }
    }

    // ---------------- conversation ----------------
    private var busySince = 0L

    private fun onWake(command: String = "", faceName: String = "") {
        if (busy && System.currentTimeMillis() - busySince > 90000) done()     // never stay stuck
        if (busy) return
        if (ringing) { answerOrDrop(); return }
        // the app is open: the face on screen switches and answers straight away
        val shown = MainActivity.instance
        if (MainActivity.visible && shown != null) {
            if (faceName.isNotBlank() && faceName in Prefs.FACES) { Prefs.setStr(this, "mode", faceName); Voice.face = faceName }
            shown.wakeInApp(faceName, if (command.isNotBlank()) Brain.cleanCommand(this, command) else "")
            resumeWakeWord()
            return
        }
        busy = true
        busySince = System.currentTimeMillis()
        pauseWakeWord()
        resumeMusicAfter = player.isPlaying()
        if (resumeMusicAfter) player.pause()
        // calling a face by name switches to that face and its voice ("Friday", "Matrix", "Him"…)
        if (faceName.isNotBlank() && faceName in Prefs.FACES && faceName != Prefs.face(this)) {
            Prefs.setStr(this, "mode", faceName)
            Voice.face = faceName
            MainActivity.instance?.let { act -> act.runOnUiThread { act.syncFace() } }
        }
        if (command.isNotBlank()) {
            // "Friday, play Drake" said in one go: no need to ask
            val text = Brain.cleanCommand(this, command)
            if (text.isNotBlank()) { handle(text); return }
        }
        setMode(Mode.LISTENING)
        val ultron = Prefs.ultron(this)
        val reply = if (ultron) "I'm listening, human." else if (Prefs.face(this) == "friday") "Yes, boss" else Prefs.replyPhrase(this)
        showCard(if (ultron) reply else "$reply?", 15000)
        Voice.line(this, "yes_sir", if (ultron) reply else "$reply?", ultron) { main.postDelayed({ listenForCommand() }, 350) }
    }

    private fun listenForCommand(attempt: Int = 0) {
        setMode(Mode.LISTENING)
        Ears.listenOnce(this) { heard ->
            val text = Brain.cleanCommand(this, heard ?: "")
            when {
                text.isNotBlank() -> handle(text)
                attempt == 0 && heard == null -> {
                    // the microphone was still busy with the wake listener: try once more
                    main.postDelayed({ listenForCommand(1) }, 400)
                }
                attempt < 2 -> {
                    // didn't catch it: ask, then actually wait for the answer
                    val msg = if (Prefs.ultron(this)) "Speak up, human. Say it again." else "I didn't catch that, ${sirWord()}. Say it again?"
                    showCard(msg, 8000)
                    Voice.line(this, "didnt_catch", msg, Prefs.ultron(this)) { main.postDelayed({ listenForCommand(2) }, 300) }
                }
                else -> {
                    showCard("Say my name when you need me.", 3000)
                    done()
                }
            }
        }
    }

    /** A few seconds to answer back without the wake word; silence just ends the conversation quietly. */
    private fun followUp() {
        if (!busy) return
        setMode(Mode.LISTENING)
        Ears.listenOnce(this) { heard ->
            val text = Brain.cleanCommand(this, heard ?: "")
            if (text.isNotBlank() && text.length > 1) handle(text) else done()
        }
    }

    private fun sirWord() = if (Prefs.face(this) == "friday") "boss" else if (Prefs.ultron(this)) "human" else "sir"

    private fun handle(text: String) {
        setMode(Mode.THINKING)
        showCard("“$text”", 30000)

        // "switch off the floating circle / overlay": off until the app is opened again
        if (Regex("\\b(turn|switch|shut|close)\\b.*\\b(off|down|away)\\b.*\\b(overlay|floating|circle|bubble)\\b|\\b(overlay|floating circle|bubble)\\b.*\\b(off|away)\\b|^hide (the )?(overlay|circle|bubble)", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
            sayThen("Switching off the floating circle, ${sirWord()}. Open the app and I'll be back.") { stopSelf() }
            return
        }
        if (!Brain.online(this) && Regex("\\b(whatsapp|instagram|facebook|messenger|youtube|spotify|email|search|google|weather|news)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
            say("We're offline, ${sirWord()}. You'll need data or Wi-Fi for that.")
            done()
            return
        }

        // photos and videos happen in the app's screen (Android only lets the camera run there)
        if (Brain.CAMERA.containsMatchIn(text)) {
            say("Opening the camera, ${sirWord()}.")
            Brain.openAppWithCommand(this, text)
            done()
            return
        }
        // small talk: answered instantly (pre-recorded in each face's voice, no AI round trip)
        Brain.quickReply(this, text)?.let { q ->
            remember(text, q)
            sayThen(q) { done() }
            return
        }
        // "I'm not okay": Sentinel "hacks" the sound, then the face cheers you up (the AI picks a song).
        // The AI is asked straight away, so its answer is ready when the joke ends.
        if (Brain.SAD.containsMatchIn(text) && !Prefs.ultron(this)) {
            Brain.nextHint = JSONObject().put("mood", "sad")
            var answer: Pair<String, JSONObject?>? = null
            var jokeDone = false
            fun go() {
                val a = answer ?: return
                if (!jokeDone) return
                answer = null
                remember(text, a.first)
                sayThen(a.first) {
                    a.second?.let { act -> Brain.runAction(this, act) { m -> say(m) } }
                    done()
                }
            }
            val past = JSONArray()
            history.takeLast(12).forEach { past.put(it) }
            Brain.ask(this, text, past, "overlay") { reply, action -> main.post { answer = reply to action; go() } }
            Sfx.play(this, "faah") {
                Voice.line(this, "sentinel_joke", "Oh, sorry. Sentinel's acting up again. He keeps hacking my sound responses. But no biggie, that's all he can do now.") {
                    jokeDone = true
                    go()
                }
            }
            return
        }

        val local = Brain.localCommand(this, text)
        if (local != null) {
            val type = local.action.optString("type")
            if (!TikTok.allowed(this, local.action)) {
                say("That's a violation of sector 619, I won't do that.")
                done()
                return
            }
            val canOpen = type != "open_app" || Brain.openApp(this, local.action.optString("app"))
            if (canOpen) {
                // free limits / paid credits for commands handled on the phone
                val kinds = when (type) {
                    "music" -> listOf("message")
                    "play_here", "youtube_play", "youtube_music_play", "spotify_play" -> listOf("message", "song")
                    else -> listOf("message", "action")
                }
                Brain.useCredits(this, kinds) { ok, blocked ->
                    if (!ok) {
                        say(blocked)
                        done()
                        return@useCredits
                    }
                    if (type == "music" || type == "play_here") resumeMusicAfter = false
                    if (type == "play_here") player.prepaid = true
                    remember(text, local.reply)
                    if (type in PLAY_TYPES && local.reply.isNotBlank()) {
                        // "Now playing …" first, then the song (not over it)
                        sayThen(local.reply) { Brain.runAction(this, local.action) { say(it) }; done() }
                        return@useCredits
                    }
                    if (local.reply.isNotBlank()) say(local.reply)
                    if (type != "open_app") Brain.runAction(this, local.action) { say(it) }
                    done()
                }
                return
            } // app not found → let the AI work out what was meant
        }

        askAI(text)
    }

    private fun askAI(text: String) {
        val past = JSONArray()
        history.takeLast(12).forEach { past.put(it) }
        // thinking takes a moment: "One moment, sir." in your voice (pre-recorded on the voice server)
        var answered = false
        main.postDelayed({
            if (!answered && busy) {
                val line = if (Prefs.ultron(this)) "Processing, human." else if (Prefs.face(this) == "friday") "One moment, boss." else "One moment, ${sirWord()}."
                Voice.line(this, "one_moment", line, Prefs.ultron(this))
            }
        }, 1200)
        Brain.ask(this, text, past, "overlay") { reply, action ->
            answered = true
            remember(text, Voice.display(reply))
            val asides = Brain.lastAside
            if (action == null) {
                // talk: reply, then the other mind may cut in, and if a question was asked, listen for the answer
                sayThen(reply) {
                    speakAsides(asides, 0) {
                        val lastLine = if (asides.length() > 0) asides.optJSONObject(asides.length() - 1)?.optString("text").orEmpty() else reply
                        if (lastLine.trim().endsWith("?") || reply.trim().endsWith("?")) main.postDelayed({ listenForCommand(1) }, 300)
                        else main.postDelayed({ followUp() }, 300)      // keep the conversation going: reply without saying the name
                    }
                }
                return@ask
            }
            val playFirst = action.optString("type") in PLAY_TYPES && reply.isNotBlank()
            if (playFirst) {
                val t = action.optString("type")
                if (t == "play_here" || t == "play_music") player.prepaid = true
                resumeMusicAfter = false
                sayThen(reply) { Brain.runAction(this, action) { msg -> say(msg) }; done() }
                return@ask
            }
            say(reply)
            if (action != null) {
                val t = action.optString("type")
                if (t == "play_here" || t == "play_music") player.prepaid = true   // the AI already charged this song
                if (t == "music" || t == "play_here" || t == "whatsapp_voice_note") resumeMusicAfter = false
                if (t == "whatsapp_voice_note") {
                    busy = false
                    Brain.runAction(this, action) { msg -> say(msg) }
                    return@ask
                }
                Brain.runAction(this, action) { msg -> say(msg) }
            }
            done()
        }
    }

    private val PLAY_TYPES = setOf("play_here", "play_music", "youtube_play", "youtube_music_play", "spotify_play")

    fun say(text: String) = sayThen(text, null)

    /** Text appears the moment the voice starts (not before), then [after] runs when it has finished */
    fun sayThen(text: String, after: (() -> Unit)?) {
        if (text.isBlank()) { after?.invoke(); return }
        if (!Voice.cloud) showCard(Voice.display(text))
        Voice.speak(text, Prefs.ultron(this), onDone = after, onStart = {
            if (busy) setMode(Mode.SPEAKING)
            if (Voice.cloud) showCard(Voice.display(text))
        })
    }

    /** Ultron (or Jarvis) cutting in, each in its own voice */
    private fun speakAsides(list: JSONArray, i: Int, after: () -> Unit) {
        val o = list.optJSONObject(i) ?: return after()
        val who = o.optString("who")
        val text = o.optString("text")
        if (text.isBlank()) return speakAsides(list, i + 1, after)
        Voice.speak(text, who in listOf("ultron", "him", "sentinel"), onDone = { main.postDelayed({ speakAsides(list, i + 1, after) }, 250) },
            onStart = { setMode(Mode.SPEAKING); showCard("${who.uppercase()}: $text", 9000) }, asFace = who)
    }

    /** Read a notification out loud (music dips while it speaks) */
    fun announce(text: String) {
        if (ringing || recorder != null) return
        main.post {
            if (busy) { main.postDelayed({ announce(text) }, 4000); return@post }
            val wasPlaying = player.isPlaying()
            if (wasPlaying) player.pause()
            showCard(text, 8000)
            Voice.speak(text, onDone = { if (wasPlaying && !busy) player.resume() })
        }
    }

    // ---------------- check-ins: "Hey sir, I was just checking if you're still okay" ----------------
    private val CHECKIN_MS = 30 * 60 * 1000L
    private val checkIn = object : Runnable {
        override fun run() {
            if (!running) return
            main.postDelayed(this, CHECKIN_MS)
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (!Prefs.bool(this@OverlayService, "checkins", true) || TikTok.live(this@OverlayService) || busy || ringing || !pm.isInteractive || player.isPlaying() || Prefs.ultron(this@OverlayService)) return
            busy = true
            busySince = System.currentTimeMillis()
            pauseWakeWord()
            setMode(Mode.LISTENING)
            val face = Prefs.face(this@OverlayService)
            val line = listOf(
                "Hey ${sirWord()}, I was just checking if you're still okay.",
                "Still with me, ${sirWord()}? How's everything going?",
                "Quick check-in, ${sirWord()}. How are you doing?",
                if (face == "jarvis" || face == "aether") "You've been rather quiet, ${sirWord()}. Everything alright?" else "Just checking in, ${sirWord()}. All good?"
            ).random()
            showCard(line, 15000)
            Brain.nextHint = JSONObject().put("checkin", true)
            Voice.line(this@OverlayService, "checkin", line) { main.postDelayed({ listenForCommand(1) }, 350) }
        }
    }

    // ---------------- incoming calls: "Sir, Mom is calling. Answer or drop?" ----------------
    @Volatile private var ringing = false
    private var callReceiver: android.content.BroadcastReceiver? = null

    private fun watchCalls() {
        if (checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        val r = object : android.content.BroadcastReceiver() {
            override fun onReceive(ctx: android.content.Context?, i: Intent?) {
                val intent = i ?: return
                val st = intent.getStringExtra(android.telephony.TelephonyManager.EXTRA_STATE) ?: return
                when (st) {
                    android.telephony.TelephonyManager.EXTRA_STATE_RINGING -> {
                        @Suppress("DEPRECATION")
                        val number = intent.getStringExtra(android.telephony.TelephonyManager.EXTRA_INCOMING_NUMBER)
                        if (ringing || number == null) return       // Android sends it twice; the 2nd has the number
                        if (TikTok.live(this@OverlayService)) return  // never announce callers on a LIVE
                        ringing = true
                        onIncomingCall(number)
                    }
                    else -> if (ringing) {
                        ringing = false
                        main.postDelayed({ if (busy && !ringing) done() }, 300)
                    }
                }
            }
        }
        callReceiver = r
        val filter = android.content.IntentFilter(android.telephony.TelephonyManager.ACTION_PHONE_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(r, filter, Context.RECEIVER_EXPORTED) else registerReceiver(r, filter)
    }

    private fun onIncomingCall(number: String) {
        val who = Brain.contactName(this, number) ?: number.replace(Regex("(\\d)(?=\\d)"), "$1 ")
        busy = true
        busySince = System.currentTimeMillis()
        pauseWakeWord()
        if (player.isPlaying()) { player.pause(); resumeMusicAfter = true }
        val line = "${sirWord().replaceFirstChar { it.uppercase() }}, $who is calling. Would you like to answer or drop?"
        showCard("📞 $line", 30000)
        main.postDelayed({ Voice.speak(line, onDone = { if (ringing) answerOrDrop() }) }, 1200)
    }

    @Suppress("DEPRECATION", "MissingPermission")
    private fun answerOrDrop(attempt: Int = 0) {
        if (!ringing) { done(); return }
        Ears.listenOnce(this) { heard ->
            val t = (heard ?: "").lowercase(Locale.ROOT)
            val tm = getSystemService(TELECOM_SERVICE) as android.telecom.TelecomManager
            val allowed = checkSelfPermission(android.Manifest.permission.ANSWER_PHONE_CALLS) == android.content.pm.PackageManager.PERMISSION_GRANTED
            when {
                !ringing -> done()
                Regex("\\b(answer|pick|accept|yes|take it|open)\\b").containsMatchIn(t) -> {
                    if (allowed) {
                        try { tm.acceptRingingCall() } catch (e: Exception) {}
                    } else {
                        showCard("Allow InHuman to answer calls in its permissions.", 6000)
                    }
                    ringing = false
                    busy = false
                    setMode(Mode.IDLE)
                }
                Regex("\\b(drop|decline|reject|no|end|ignore|hang)\\b").containsMatchIn(t) -> {
                    var ok = false
                    if (allowed && Build.VERSION.SDK_INT >= 28) { try { ok = tm.endCall() } catch (e: Exception) {} }
                    ringing = false
                    say(if (ok) "Call dropped, ${sirWord()}." else "Please allow InHuman to manage calls, ${sirWord()}.")
                    done()
                }
                attempt < 2 -> Voice.speak("Answer or drop, ${sirWord()}?", onDone = { answerOrDrop(attempt + 1) })
                else -> done()
            }
        }
    }

    private fun remember(user: String, ai: String) {
        history.add(JSONObject().put("role", "user").put("text", user))
        history.add(JSONObject().put("role", "ai").put("text", ai))
        while (history.size > 24) history.removeAt(0)
    }

    private fun done() {
        busy = false
        setMode(Mode.IDLE)
        if (resumeMusicAfter && player.isActive()) player.resume()
        resumeMusicAfter = false
        resumeWakeWord()
    }

    // ---------------- WhatsApp voice note ----------------
    fun recordVoiceNote(contact: String) {
        val pkg = Brain.whatsappPackage(this) ?: return say("WhatsApp isn't installed on this phone, sir.")
        val (name, number) = Brain.resolvePhone(this, contact) { say(it) } ?: return
        busy = true
        busySince = System.currentTimeMillis()
        pauseWakeWord()
        if (player.isPlaying()) player.pause()
        val intro = "Recording your voice note for $name, sir. Speak after I finish. Tap me when you're done."
        showCard("🎙 $intro", 20000)
        Voice.speak(intro, onDone = { main.postDelayed({ startRecording(pkg, name, number) }, 300) })
    }

    @Suppress("DEPRECATION")
    private fun startRecording(pkg: String, name: String, number: String) {
        val dir = File(cacheDir, "voice").apply { mkdirs() }
        val file = File(dir, "InHuman_voice_note_${System.currentTimeMillis()}.m4a")
        val rec = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else MediaRecorder()
        try {
            rec.setAudioSource(MediaRecorder.AudioSource.MIC)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            rec.setAudioSamplingRate(44100)
            rec.setAudioEncodingBitRate(96000)
            rec.setOutputFile(file.absolutePath)
            rec.prepare()
            rec.start()
        } catch (e: Exception) {
            try { rec.release() } catch (x: Exception) {}
            say("I couldn't use the microphone, sir.")
            done()
            return
        }
        recorder = rec
        setMode(Mode.RECORDING)
        showCard("🔴 Recording… tap the circle to send", 125000)

        val started = SystemClock.uptimeMillis()
        var spoke = false
        var lastLoud = started
        var finished = false
        val finish: () -> Unit = finish@{
            if (finished) return@finish
            finished = true
            recordFinish = null
            try { rec.stop() } catch (e: Exception) { spoke = false }
            try { rec.release() } catch (e: Exception) {}
            recorder = null
            if (!spoke || !file.exists() || file.length() < 2000) {
                file.delete()
                say("I didn't hear anything, sir, so nothing was sent.")
            } else {
                sendVoiceNote(pkg, name, number, file)
            }
            done()
        }
        recordFinish = finish
        val poll = object : Runnable {
            override fun run() {
                if (finished) return
                val now = SystemClock.uptimeMillis()
                val amp = try { rec.maxAmplitude } catch (e: Exception) { 0 }
                if (amp > 2500) { spoke = true; lastLoud = now }
                if ((spoke && now - lastLoud > 2500) || now - started > 120000) finish() else main.postDelayed(this, 200)
            }
        }
        main.postDelayed(poll, 400)
    }

    private fun sendVoiceNote(pkg: String, name: String, number: String, file: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        val intl = Contacts.international(this, number)
        val i = Intent(Intent.ACTION_SEND)
            .setType("audio/*")
            .setPackage(pkg)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra("jid", "$intl@s.whatsapp.net")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        InHumanAccessibility.queueSend(setOf(pkg), null) { ok -> if (ok) say("Voice note sent to $name, sir.") }
        if (!Brain.startSafe(this, i)) return say("I couldn't open WhatsApp, sir.")
        if (!InHumanAccessibility.isOn()) say("Your voice note to $name is ready, sir. Tap send.")
    }

    // =====================================================================
    // Floating music player: keeps playing after the InHuman app is closed
    //   stream  = audio only (Audius etc.), plays in the background
    //   youtube = small video window (YouTube requires the video to stay visible)
    // =====================================================================
    inner class Player {
        private val queue = mutableListOf<Track>()
        private var index = -1
        private var mp: MediaPlayer? = null
        private var web: WebView? = null
        private var win: LinearLayout? = null
        private var winLp: WindowManager.LayoutParams? = null
        private var ytBox: FrameLayout? = null
        private var titleView: TextView? = null
        private var playBtn: TextView? = null
        private var playing = false
        var prepaid = false          // song already paid (AI command / phone command / handed over from the app)

        fun isActive() = index >= 0 && win != null
        fun isPlaying() = isActive() && playing
        private fun current(): Track? = queue.getOrNull(index)

        fun start(tracks: List<Track>, startIndex: Int = 0, position: Double = 0.0) {
            if (tracks.isEmpty()) return
            stop()
            queue.addAll(tracks)
            index = startIndex.coerceIn(0, tracks.size - 1)
            play(position)
        }

        /** Called when the main app closes: carry on with its song from the same second */
        fun startFromHandoff(state: JSONObject) {
            val cur = state.optJSONObject("current") ?: return
            prepaid = true
            val list = mutableListOf(Track.from(cur))
            val q = state.optJSONArray("queue") ?: JSONArray()
            for (i in 0 until q.length()) q.optJSONObject(i)?.let { list.add(Track.from(it)) }
            start(list, 0, state.optDouble("position", 0.0))
        }

        /** Called when the main app opens again: give the song back to it */
        fun takeOver(cb: (JSONObject?) -> Unit) {
            val t = current()
            if (t == null || !isActive()) return cb(null)
            val rest = JSONArray()
            for (i in index + 1 until queue.size) rest.put(queue[i].json())
            val wasPlaying = playing
            val finishWith: (Double) -> Unit = { pos ->
                val state = JSONObject().put("current", t.json()).put("position", pos).put("queue", rest).put("playing", wasPlaying)
                stop()
                cb(state)
            }
            if (t.kind == "stream") {
                finishWith((mp?.currentPosition ?: 0) / 1000.0)
            } else {
                val w = web
                if (w == null) finishWith(0.0)
                else w.evaluateJavascript("window.inhumanTime ? window.inhumanTime() : 0") { v -> finishWith(v?.toDoubleOrNull() ?: 0.0) }
            }
        }

        /** "play X" on the circle: full song from Audius if it really matches, otherwise YouTube */
        fun playQuery(query: String) {
            showCard("🔎 Finding $query…", 6000)
            Thread {
                var track: Track? = null
                try {
                    val res = Net.json("${Prefs.serverUrl(this@OverlayService)}/api/music?q=${URLEncoder.encode(query, "UTF-8")}")
                    val words = Text.norm(query).split(" ").filter { it.length > 2 }
                    val audius = res.optJSONArray("audius") ?: JSONArray()
                    for (i in 0 until audius.length()) {
                        val a = audius.optJSONObject(i) ?: continue
                        val hay = Text.norm("${a.optString("title")} ${a.optString("artist")}")
                        val hits = words.count { hay.contains(it) }
                        if (words.isNotEmpty() && hits >= (words.size * 0.8)) {
                            track = Track("stream", a.optString("url"), "", a.optString("title"), a.optString("artist"), "nat-${System.currentTimeMillis()}")
                            break
                        }
                    }
                    if (track == null) {
                        res.optJSONArray("youtube")?.optJSONObject(0)?.let { y ->
                            track = Track("youtube", "", y.optString("videoId"), y.optString("title"), y.optString("channel"), "nat-${System.currentTimeMillis()}")
                        }
                    }
                } catch (e: Exception) {}
                val t = track
                main.post {
                    if (t == null) say("I couldn't find $query, sir.") else start(listOf(t))
                }
            }.start()
        }

        private fun play(position: Double = 0.0) {
            val t = current() ?: return stop()
            if (!prepaid) {
                // every new song costs credits (playlist or not)
                Brain.useCredits(this@OverlayService, listOf("song")) { ok, blocked ->
                    if (!ok) {
                        say(blocked)
                        stop()
                    }
                }
            }
            prepaid = false
            ensureWindow()
            titleView?.text = if (t.artist.isNotBlank()) "${t.title} — ${t.artist}" else t.title
            Prefs.addRecent(this@OverlayService, t.json().put("playedAt", System.currentTimeMillis()))
            releaseMedia()
            if (t.kind == "stream" && t.url.isNotBlank()) playStream(t, position) else playYouTube(t, position)
            setPlaying(true)
        }

        private fun playStream(t: Track, position: Double) {
            ytBox?.visibility = View.GONE
            mp = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                setWakeMode(this@OverlayService, PowerManager.PARTIAL_WAKE_LOCK)
                setOnPreparedListener {
                    if (position > 1) it.seekTo((position * 1000).toInt())
                    it.start()
                }
                setOnCompletionListener { next() }
                setOnErrorListener { _, _, _ ->
                    main.post { showCard("That song failed to load, skipping.", 4000); next() }
                    true
                }
                try {
                    setDataSource(t.url)
                    prepareAsync()
                } catch (e: Exception) {
                    main.post { next() }
                }
            }
        }

        @SuppressLint("SetJavaScriptEnabled")
        private fun playYouTube(t: Track, position: Double) {
            val box = ytBox ?: return
            box.visibility = View.VISIBLE
            val w = web ?: WebView(this@OverlayService).also { v ->
                v.settings.javaScriptEnabled = true
                v.settings.domStorageEnabled = true
                v.settings.mediaPlaybackRequiresUserGesture = false
                v.setBackgroundColor(Color.BLACK)
                v.addJavascriptInterface(YtBridge(), "InHumanPlayer")
                box.addView(v, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
                web = v
            }
            w.loadUrl("${Prefs.serverUrl(this@OverlayService)}/player.html?v=${t.videoId}&t=${position.toInt()}")
        }

        inner class YtBridge {
            @JavascriptInterface
            fun ended() { main.post { next() } }

            @JavascriptInterface
            fun state(s: Int) { main.post { if (s == 1) setPlaying(true) else if (s == 2) setPlaying(false) } }

            @JavascriptInterface
            fun error(code: Int) {
                main.post {
                    val t = current() ?: return@post
                    showCard("This video can't play here (YouTube error $code). Opening it in YouTube.", 6000)
                    Brain.startSafe(this@OverlayService, Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:${t.videoId}")))
                    stop()
                }
            }
        }

        /** quieter (not off) while the assistant talks or listens */
        fun duck(on: Boolean) {
            try { mp?.setVolume(if (on) 0.22f else 1f, if (on) 0.22f else 1f) } catch (e: Exception) {}
            web?.evaluateJavascript("window.inhumanCmd && window.inhumanCmd('${if (on) "duck" else "unduck"}')", null)
        }

        fun pause() {
            mp?.let { if (it.isPlaying) it.pause() }
            web?.evaluateJavascript("window.inhumanCmd && window.inhumanCmd('pause')", null)
            setPlaying(false)
        }

        fun resume() {
            mp?.start()
            web?.evaluateJavascript("window.inhumanCmd && window.inhumanCmd('play')", null)
            setPlaying(true)
        }

        fun next() {
            if (queue.size <= 1) {
                stop()
                return
            }
            index = (index + 1) % queue.size
            play()
        }

        fun stop() {
            releaseMedia()
            web?.let { w -> try { w.loadUrl("about:blank"); w.destroy() } catch (e: Exception) {} }
            web = null
            win?.let { try { wm.removeView(it) } catch (e: Exception) {} }
            win = null
            queue.clear()
            index = -1
            setPlaying(false)
        }

        private fun releaseMedia() {
            mp?.let { try { it.stop() } catch (e: Exception) {}; it.release() }
            mp = null
            web?.evaluateJavascript("window.inhumanCmd && window.inhumanCmd('pause')", null)
        }

        private fun setPlaying(on: Boolean) {
            playing = on
            playBtn?.text = if (on) "⏸" else "▶"
            spinLogo(on)
        }

        @SuppressLint("ClickableViewAccessibility")
        private fun ensureWindow() {
            if (win != null) return
            val ctx = this@OverlayService
            val box = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply {
                    cornerRadius = 16 * dp
                    setColor(Color.argb(245, 8, 11, 18))
                    setStroke(1.px(), Color.argb(60, 255, 255, 255))
                }
                elevation = 10 * dp
                clipToOutline = true
            }
            val header = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(12.px(), 4.px(), 4.px(), 4.px())
            }
            val title = TextView(ctx).apply {
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
                text = "♪"
            }
            header.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            fun button(label: String, onClick: () -> Unit) = TextView(ctx).apply {
                text = label
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                setPadding(10.px(), 6.px(), 10.px(), 6.px())
                setOnClickListener { onClick() }
            }
            val pb = button("⏸") { if (playing) pause() else resume() }
            header.addView(pb)
            header.addView(button("⏭") { next() })
            header.addView(button("✕") { stop() })
            box.addView(header, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

            val yt = FrameLayout(ctx).apply { visibility = View.GONE; setBackgroundColor(Color.BLACK) }
            box.addView(yt, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 146.px()))

            val lp = overlayParams(260.px(), WindowManager.LayoutParams.WRAP_CONTENT).apply {
                x = 12.px()
                y = 64.px()
            }
            wm.addView(box, lp)

            // drag the player by its title
            var downX = 0f
            var downY = 0f
            var sx = 0
            var sy = 0
            title.setOnTouchListener { _, e ->
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; sx = lp.x; sy = lp.y; true }
                    MotionEvent.ACTION_MOVE -> {
                        lp.x = sx + (e.rawX - downX).toInt()
                        lp.y = sy + (e.rawY - downY).toInt()
                        wm.updateViewLayout(box, lp)
                        true
                    }
                    else -> true
                }
            }
            win = box
            winLp = lp
            ytBox = yt
            titleView = title
            playBtn = pb
        }
    }
}
