package ai.inhuman.app

import ai.picovoice.porcupine.Porcupine
import ai.picovoice.porcupine.PorcupineManager
import ai.picovoice.porcupine.PorcupineManagerCallback
import android.accessibilityservice.AccessibilityService
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
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
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

// =====================================================================
// Floating InHuman circle over other apps + "Hey Jarvis" wake word
// =====================================================================
class OverlayService : Service() {

    companion object {
        @Volatile var running = false
        @Volatile var instance: OverlayService? = null
        private const val CHANNEL = "inhuman_listening"

        fun start(c: Context) {
            val i = Intent(c, OverlayService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i) else c.startService(i)
            } catch (e: Exception) { /* Android refused: will retry next time the app opens */ }
        }

        fun stop(c: Context) {
            c.stopService(Intent(c, OverlayService::class.java))
        }
    }

    private enum class Mode { IDLE, LISTENING, THINKING }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private lateinit var circle: FrameLayout
    private lateinit var ring: GradientDrawable
    private lateinit var card: TextView
    private lateinit var circleLp: WindowManager.LayoutParams
    private lateinit var cardLp: WindowManager.LayoutParams
    private var cardShown = false
    private var pulse: ObjectAnimator? = null
    private var porcupine: PorcupineManager? = null
    private var busy = false
    private val history = mutableListOf<JSONObject>()
    private val hideCard = Runnable { setCardVisible(false) }

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
        startWakeWord()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        running = false
        instance = null
        main.removeCallbacksAndMessages(null)
        try { porcupine?.stop() } catch (e: Exception) {}
        try { porcupine?.delete() } catch (e: Exception) {}
        porcupine = null
        if (::circle.isInitialized) try { wm.removeView(circle) } catch (e: Exception) {}
        if (::card.isInitialized && cardShown) try { wm.removeView(card) } catch (e: Exception) {}
        super.onDestroy()
    }

    // ---------------- foreground notification (Android requires it to keep listening) ----------------
    private fun goForeground(): Boolean {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "InHuman listening", NotificationManager.IMPORTANCE_LOW))
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val openSetup = PendingIntent.getActivity(
            this, 1, Intent(this, SetupActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val hint = if (Prefs.picoKey(this).isBlank()) "Tap the floating circle to talk" else "Say \"Hey Jarvis\" or tap the circle"
        val n = Notification.Builder(this, CHANNEL)
            .setContentTitle("InHuman AI is online")
            .setContentText(hint)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(openApp)
            .addAction(Notification.Action.Builder(null, "Settings", openSetup).build())
            .setOngoing(true)
            .build()
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(7, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(7, n)
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    // ---------------- the floating circle + answer card ----------------
    private fun buildOverlay() {
        ring = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.argb(235, 8, 11, 18))
            setStroke(2.px(), Color.argb(110, 255, 255, 255))
        }
        circle = FrameLayout(this).apply {
            background = ring
            elevation = 10 * dp
            val pad = 3.px()
            setPadding(pad, pad, pad, pad)
        }
        val logo = ImageView(this).apply {
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
        attachDrag()

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

    private fun overlayParams(w: Int, h: Int) = WindowManager.LayoutParams(
        w, h,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START }

    private fun attachDrag() {
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
                    if (moved) {
                        Prefs.savePos(this, circleLp.x, circleLp.y)
                    } else if (SystemClock.uptimeMillis() - downTime > 650) {
                        // Long-press: open the full InHuman app
                        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } else {
                        v.performClick()
                        onWake()
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
            if (show) {
                positionCard(addingNow = true)
            } else {
                wm.removeView(card)
            }
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

    private fun showCard(text: String, ms: Long = 0) {
        card.text = text
        setCardVisible(true)
        main.removeCallbacks(hideCard)
        val time = if (ms > 0) ms else maxOf(8000L, text.length * 70L)
        main.postDelayed(hideCard, time)
    }

    private fun setMode(m: Mode) {
        pulse?.cancel()
        circle.scaleX = 1f
        circle.scaleY = 1f
        circle.alpha = 1f
        when (m) {
            Mode.IDLE -> ring.setStroke(2.px(), Color.argb(110, 255, 255, 255))
            Mode.LISTENING -> {
                ring.setStroke(3.px(), Color.rgb(34, 230, 255))
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

    // ---------------- "Hey Jarvis" ----------------
    private fun startWakeWord() {
        val key = Prefs.picoKey(this)
        if (key.isBlank()) return
        try {
            porcupine = PorcupineManager.Builder()
                .setAccessKey(key)
                .setKeyword(Porcupine.BuiltInKeyword.JARVIS)
                .setSensitivity(0.65f)
                .build(applicationContext, PorcupineManagerCallback { main.post { onWake() } })
            porcupine?.start()
        } catch (e: Exception) {
            porcupine = null
            showCard("\"Hey Jarvis\" could not start: ${e.message}\nCheck your Picovoice key in Settings. You can still tap me to talk.", 12000)
        }
    }

    fun pauseWakeWord() {
        try { porcupine?.stop() } catch (e: Exception) {}
    }

    fun resumeWakeWord() {
        main.postDelayed({
            if (running && !busy) try { porcupine?.start() } catch (e: Exception) {}
        }, 500)
    }

    // ---------------- conversation ----------------
    private fun onWake() {
        if (busy) return
        busy = true
        pauseWakeWord()
        setMode(Mode.LISTENING)
        showCard("Yes sir?", 15000)
        Voice.speak("Yes sir") {
            main.postDelayed({ listenForCommand() }, 250)
        }
    }

    private fun listenForCommand() {
        Ears.listenOnce(this) { heard ->
            val text = Brain.cleanCommand(heard ?: "")
            if (text.isBlank()) {
                showCard("I didn't catch that, sir. Say \"Hey Jarvis\" or tap me to try again.", 5000)
                done()
            } else {
                handle(text)
            }
        }
    }

    private fun handle(text: String) {
        setMode(Mode.THINKING)
        showCard("“$text”", 20000)

        val local = Brain.localCommand(text)
        if (local != null) {
            val type = local.action.optString("type")
            // "open X": only answer locally if that app is really installed, else ask the AI
            if (type != "open_app" || Brain.openApp(this, local.action.optString("app"))) {
                if (type != "open_app") Brain.runAction(this, local.action) { say(it) }
                remember(text, local.reply)
                say(local.reply)
                done()
                return
            }
        }

        val past = JSONArray()
        history.takeLast(12).forEach { past.put(it) }
        Brain.ask(this, text, past, "overlay") { reply, action ->
            remember(text, reply)
            say(reply)
            action?.let { Brain.runAction(this, it) { msg -> say(msg) } }
            done()
        }
    }

    private fun say(text: String) {
        showCard(text)
        Voice.speak(text)
    }

    private fun remember(user: String, ai: String) {
        history.add(JSONObject().put("role", "user").put("text", user))
        history.add(JSONObject().put("role", "ai").put("text", ai))
        while (history.size > 24) history.removeAt(0)
    }

    private fun done() {
        busy = false
        setMode(Mode.IDLE)
        resumeWakeWord()
    }
}

// =====================================================================
// Optional Accessibility service: Home, Back, Recents, Notifications, Lock
// =====================================================================
class InHumanAccessibility : AccessibilityService() {
    companion object {
        @Volatile private var instance: InHumanAccessibility? = null

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
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

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
