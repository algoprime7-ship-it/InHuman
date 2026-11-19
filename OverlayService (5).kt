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

    private enum class Mode { IDLE, LISTENING, THINKING, RECORDING }

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
        wake = WakeWord(this, onWake = { cmd -> onWake(cmd) }, onStatus = { showCard(it, 9000) })
        wake?.start()
        main.postDelayed(heartbeat, 3000)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        running = false
        instance = null
        main.removeCallbacksAndMessages(null)
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
                    val res = Net.json("$server/api/client?action=heartbeat", body, timeoutMs = 15000)
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
            "lock" -> if (!InHumanAccessibility.perform("lock")) showCard("Turn on InHuman in Accessibility so other devices can lock this phone.", 8000)
            "wake" -> { Brain.wakeScreen(this); showCard("Screen turned on$from", 4000) }
            "play" -> if (arg.isNotBlank()) {
                Brain.wakeScreen(this)
                Brain.useCredits(this, listOf("song")) { ok, blocked ->
                    if (!ok) showCard(blocked, 6000)
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
    private fun onWake(command: String = "") {
        if (busy) return
        busy = true
        pauseWakeWord()
        resumeMusicAfter = player.isPlaying()
        if (resumeMusicAfter) player.pause()
        if (command.isNotBlank()) {
            // "Friday, play Drake" said in one go: no need to ask
            val text = Brain.cleanCommand(this, command)
            if (text.isNotBlank()) { handle(text); return }
        }
        setMode(Mode.LISTENING)
        val ultron = Prefs.ultron(this)
        val reply = if (ultron) "I'm listening, human." else Prefs.replyPhrase(this)
        showCard(if (ultron) reply else "$reply?", 15000)
        Voice.speak(reply, ultron) { main.postDelayed({ listenForCommand() }, 350) }
    }

    private fun listenForCommand(attempt: Int = 0) {
        Ears.listenOnce(this) { heard ->
            val text = Brain.cleanCommand(this, heard ?: "")
            when {
                text.isNotBlank() -> handle(text)
                attempt == 0 && heard == null -> {
                    // the microphone was still busy with the wake listener: try once more
                    main.postDelayed({ listenForCommand(1) }, 400)
                }
                else -> {
                    showCard("I didn't catch that, sir. Just say my name again.", 4000)
                    done()
                }
            }
        }
    }

    private fun handle(text: String) {
        setMode(Mode.THINKING)
        showCard("“$text”", 30000)

        val local = Brain.localCommand(this, text)
        if (local != null) {
            val type = local.action.optString("type")
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
                    say(local.reply)
                    if (type != "open_app") Brain.runAction(this, local.action) { say(it) }
                    done()
                }
                return
            } // app not found → let the AI work out what was meant
        }

        val past = JSONArray()
        history.takeLast(12).forEach { past.put(it) }
        Brain.ask(this, text, past, "overlay") { reply, action ->
            remember(text, Voice.display(reply))
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

    fun say(text: String) {
        showCard(Voice.display(text))
        Voice.speak(text, Prefs.ultron(this))
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
        pauseWakeWord()
        if (player.isPlaying()) player.pause()
        val intro = "Recording your voice note for $name, sir. Speak after I finish. Tap me when you're done."
        showCard("🎙 $intro", 20000)
        Voice.speak(intro) { main.postDelayed({ startRecording(pkg, name, number) }, 300) }
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
