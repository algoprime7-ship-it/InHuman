package ai.inhuman.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject

private fun Context.hasMic() =
    checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

// =====================================================================
// Home: your hosted InHuman web app (3D head, chat, music) inside the phone app
// =====================================================================
class MainActivity : Activity() {
    private lateinit var web: WebView
    private var fileCallback: ValueCallback<Array<Uri>>? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Voice.init(this)
        if (!SetupActivity.isReady(this)) {
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
            return
        }

        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        web = WebView(this)
        web.setBackgroundColor(Color.rgb(2, 3, 6))
        setContentView(web)

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
        }
        web.addJavascriptInterface(Bridge(), "InHumanNative")

        val home = Uri.parse(Prefs.serverUrl(this))
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                if (request == null) return false
                val url = request.url ?: return false
                if (!request.isForMainFrame || url.host == home.host) return false
                Brain.openUrl(this@MainActivity, url.toString()) // other sites open in their own app/browser
                return true
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView?,
                callback: ValueCallback<Array<Uri>>?,
                params: FileChooserParams?
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                return try {
                    startActivityForResult(params!!.createIntent(), REQ_FILE)
                    true
                } catch (e: Exception) {
                    fileCallback = null
                    false
                }
            }
        }
        web.loadUrl(home.toString())
    }

    override fun onResume() {
        super.onResume()
        // The floating circle needs to be started while the app is on screen (Android rule)
        if (::web.isInitialized && Prefs.overlayEnabled(this) && !OverlayService.running &&
            Settings.canDrawOverlays(this) && hasMic()
        ) {
            OverlayService.start(this)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_FILE) {
            val uris: Array<Uri>? = data?.clipData?.let { clip -> Array(clip.itemCount) { clip.getItemAt(it).uri } }
                ?: WebChromeClient.FileChooserParams.parseResult(resultCode, data)
            fileCallback?.onReceiveValue(if (resultCode == RESULT_OK) uris else null)
            fileCallback = null
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (::web.isInitialized && web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    private fun js(code: String) = web.post { web.evaluateJavascript(code, null) }

    /** Functions the web app can call as window.InHumanNative.xxx() */
    inner class Bridge {
        @JavascriptInterface
        fun getDeviceInfo(): String = DeviceInfo.json(this@MainActivity).toString()

        @JavascriptInterface
        fun speak(text: String, angry: Boolean) = runOnUiThread {
            Voice.speak(text, angry) { js("window.__nativeSpeechDone && window.__nativeSpeechDone()") }
        }

        @JavascriptInterface
        fun stopSpeaking() = Voice.stop()

        @JavascriptInterface
        fun listen() = runOnUiThread {
            if (!hasMic()) {
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
                js("window.__nativeHeard && window.__nativeHeard('')")
                return@runOnUiThread
            }
            OverlayService.instance?.pauseWakeWord() // free the microphone
            Ears.listenOnce(this@MainActivity) { text ->
                OverlayService.instance?.resumeWakeWord()
                js("window.__nativeHeard && window.__nativeHeard(${JSONObject.quote(text ?: "")})")
            }
        }

        @JavascriptInterface
        fun doAction(json: String) = runOnUiThread {
            try {
                Brain.runAction(this@MainActivity, JSONObject(json)) { Voice.speak(it) }
            } catch (e: Exception) {}
        }

        @JavascriptInterface
        fun isOverlayOn(): Boolean = OverlayService.running

        @JavascriptInterface
        fun setOverlay(on: Boolean) = runOnUiThread {
            Prefs.setOverlayEnabled(this@MainActivity, on)
            if (on) OverlayService.start(this@MainActivity) else OverlayService.stop(this@MainActivity)
        }

        @JavascriptInterface
        fun openSetup() = runOnUiThread {
            startActivity(Intent(this@MainActivity, SetupActivity::class.java))
        }
    }

    companion object {
        private const val REQ_FILE = 42
        private const val REQ_MIC = 43
    }
}

// =====================================================================
// Setup: web link, wake-word key, and every "Allow" the app needs
// =====================================================================
class SetupActivity : Activity() {
    private lateinit var serverInput: EditText
    private lateinit var keyInput: EditText
    private val rows = mutableListOf<Pair<TextView, () -> Boolean>>()

    companion object {
        fun isReady(c: Context): Boolean =
            Prefs.serverUrl(c).startsWith("https://") && Settings.canDrawOverlays(c) && c.hasMic()
    }

    private val dp get() = resources.displayMetrics.density
    private fun Int.px() = (this * dp).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Voice.init(this)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.px(), 28.px(), 20.px(), 28.px())
            setBackgroundColor(Color.rgb(2, 3, 6))
        }

        col.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_logo)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.BLACK) }
            clipToOutline = true
            layoutParams = LinearLayout.LayoutParams(96.px(), 96.px()).apply { gravity = Gravity.CENTER_HORIZONTAL }
        })
        col.addView(label("InHuman AI", 26f, Color.WHITE, bold = true).apply { gravity = Gravity.CENTER; setPadding(0, 12.px(), 0, 2.px()) })
        col.addView(label("Detected: ${DeviceInfo.summary(this)}", 12f, Color.rgb(148, 163, 184)).apply { gravity = Gravity.CENTER })

        col.addView(section("1. Your InHuman web link"))
        serverInput = input("https://your-project.vercel.app", Prefs.serverUrl(this), InputType.TYPE_TEXT_VARIATION_URI)
        col.addView(serverInput)

        col.addView(section("2. \"Hey Jarvis\" key (optional)"))
        col.addView(label("Free AccessKey from console.picovoice.ai. Without it, tap the floating circle to talk.", 12f, Color.rgb(148, 163, 184)))
        keyInput = input("Picovoice AccessKey", Prefs.picoKey(this), InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)
        col.addView(keyInput)

        col.addView(section("3. Allow permissions"))
        col.addView(permRow("Display over other apps (required)", { Settings.canDrawOverlays(this) }) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        })
        col.addView(permRow("Microphone (required)", { hasMic() }) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        })
        if (Build.VERSION.SDK_INT >= 33) {
            col.addView(permRow("Notifications", {
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            }) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
            })
        }
        col.addView(permRow("Keep running in background", {
            (getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)
        }) {
            try {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        })
        col.addView(permRow("Accessibility: Home / Back / Lock (optional)", { InHumanAccessibility.isOn() }) {
            Toast.makeText(this, "Find \"InHuman AI\" and switch it on", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        col.addView(label(
            "If Android says \"Restricted setting\" for Accessibility: tap \"App info\" below, tap ⋮ (top right), choose \"Allow restricted settings\", then try again.",
            12f, Color.rgb(148, 163, 184)
        ))
        col.addView(button("App info", filled = false) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        })

        col.addView(View(this), LinearLayout.LayoutParams(1, 18.px()))
        col.addView(button("Start InHuman", filled = true) { finishSetup() })

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.rgb(2, 3, 6))
            addView(col)
        })
    }

    override fun onResume() {
        super.onResume()
        rows.forEach { (view, check) -> setStatus(view, check()) }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        rows.forEach { (view, check) -> setStatus(view, check()) }
    }

    private fun finishSetup() {
        var url = serverInput.text.toString().trim()
        if (url.isNotEmpty() && !url.startsWith("http")) url = "https://$url"
        if (!url.startsWith("https://")) {
            toast("Enter your Vercel link, e.g. https://your-project.vercel.app")
            return
        }
        Prefs.setServerUrl(this, url)
        Prefs.setPicoKey(this, keyInput.text.toString())
        if (!Settings.canDrawOverlays(this)) { toast("Please allow \"Display over other apps\""); return }
        if (!hasMic()) { toast("Please allow the microphone"); return }

        Prefs.setOverlayEnabled(this, true)
        if (OverlayService.running) OverlayService.stop(this) // restart with the new key
        Voice.speak("All systems online. At your service, sir.")
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
    }

    // ---------- tiny UI helpers ----------
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun label(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        this.text = s
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun section(s: String) = label(s, 15f, Color.WHITE, bold = true).apply { setPadding(0, 22.px(), 0, 6.px()) }

    private fun input(hint: String, value: String, type: Int) = EditText(this).apply {
        this.hint = hint
        setText(value)
        inputType = InputType.TYPE_CLASS_TEXT or type
        setSingleLine(true)
        setTextColor(Color.WHITE)
        setHintTextColor(Color.rgb(100, 116, 139))
        textSize = 14f
        setPadding(14.px(), 12.px(), 14.px(), 12.px())
        background = GradientDrawable().apply {
            cornerRadius = 14 * dp
            setColor(Color.argb(20, 255, 255, 255))
            setStroke(1.px(), Color.argb(50, 255, 255, 255))
        }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = 6.px() }
    }

    private fun button(title: String, filled: Boolean, onClick: () -> Unit) = Button(this).apply {
        this.text = title
        isAllCaps = false
        setTextColor(if (filled) Color.BLACK else Color.WHITE)
        background = GradientDrawable().apply {
            cornerRadius = 14 * dp
            setColor(if (filled) Color.WHITE else Color.argb(30, 255, 255, 255))
        }
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = 8.px() }
    }

    private fun permRow(title: String, check: () -> Boolean, onAllow: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(14.px(), 10.px(), 10.px(), 10.px())
            background = GradientDrawable().apply {
                cornerRadius = 14 * dp
                setColor(Color.argb(12, 255, 255, 255))
                setStroke(1.px(), Color.argb(30, 255, 255, 255))
            }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = 8.px() }
        }
        val name = label(title, 13f, Color.WHITE)
        row.addView(name, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val status = Button(this).apply {
            isAllCaps = false
            textSize = 12f
            setOnClickListener { if (!check()) onAllow() }
        }
        row.addView(status)
        rows.add(Pair<TextView, () -> Boolean>(status, check))
        setStatus(status, check())
        return row
    }

    private fun setStatus(b: TextView, ok: Boolean) {
        b.text = if (ok) "✓ Allowed" else "Allow"
        b.setTextColor(if (ok) Color.rgb(52, 211, 153) else Color.BLACK)
        b.background = GradientDrawable().apply {
            cornerRadius = 12 * dp
            setColor(if (ok) Color.argb(25, 52, 211, 153) else Color.WHITE)
        }
    }
}
