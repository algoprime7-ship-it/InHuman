package ai.inhuman.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
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
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

// =====================================================================
// Install setup: web link + permissions only.
// (Configurations, Emails and Connect Apps live in the app's own Settings page.)
// =====================================================================
class SetupActivity : Activity() {

    companion object {
        fun isReady(c: Context): Boolean =
            Prefs.serverUrl(c).startsWith("https://") && Settings.canDrawOverlays(c) && c.hasMic()
    }

    private val tabs = listOf("Setup")
    private var currentTab = 0
    private lateinit var tabBar: LinearLayout
    private lateinit var body: LinearLayout
    private val permRows = mutableListOf<Pair<TextView, () -> Boolean>>()

    private val dp get() = resources.displayMetrics.density
    private fun Int.px() = (this * dp).toInt()
    private val grey = Color.rgb(148, 163, 184)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Voice.init(this)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(2, 3, 6))
            setPadding(16.px(), 20.px(), 16.px(), 0)
        }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_logo)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.BLACK) }
            clipToOutline = true
        }, LinearLayout.LayoutParams(48.px(), 48.px()))
        val headText = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(12.px(), 0, 0, 0) }
        headText.addView(label("InHuman AI Setup", 20f, Color.WHITE, bold = true))
        headText.addView(label(DeviceInfo.summary(this), 11f, grey))
        head.addView(headText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(head)

        tabBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 14.px(), 0, 8.px()) }

        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, 40.px()) }
        root.addView(ScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        showTab(0)
    }

    override fun onResume() {
        super.onResume()
        refreshPerms()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshPerms()
    }

    private fun refreshPerms() = permRows.forEach { (view, check) -> setStatus(view, check()) }

    private fun showTab(i: Int) {
        currentTab = i
        tabBar.removeAllViews()
        tabs.forEachIndexed { idx, name ->
            tabBar.addView(Button(this).apply {
                text = name
                isAllCaps = false
                textSize = 13f
                setTextColor(if (idx == i) Color.BLACK else Color.WHITE)
                background = GradientDrawable().apply {
                    cornerRadius = 20 * dp
                    setColor(if (idx == i) Color.WHITE else Color.argb(26, 255, 255, 255))
                }
                setPadding(16.px(), 0, 16.px(), 0)
                setOnClickListener { showTab(idx) }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, 40.px()).apply { rightMargin = 8.px() })
        }
        body.removeAllViews()
        permRows.clear()
        buildSetup()
    }

    // ================= Setup =================
    private fun buildSetup() {
        body.addView(section("Your InHuman web link"))
        val server = input("https://your-project.vercel.app", Prefs.serverUrl(this), InputType.TYPE_TEXT_VARIATION_URI)
        body.addView(server)

        body.addView(section("Allow permissions"))
        body.addView(permRow("Display over other apps (required)", { Settings.canDrawOverlays(this) }) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        })
        body.addView(permRow("Microphone (required)", { hasMic() }) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        })
        body.addView(permRow("Contacts (WhatsApp, calls, emails by name)", { hasPerm(Manifest.permission.READ_CONTACTS) }) {
            requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), 2)
        })
        body.addView(permRow("Phone calls", { hasPerm(Manifest.permission.CALL_PHONE) }) {
            requestPermissions(arrayOf(Manifest.permission.CALL_PHONE), 3)
        })
        if (Build.VERSION.SDK_INT >= 33) {
            body.addView(permRow("Notifications", { hasPerm(Manifest.permission.POST_NOTIFICATIONS) }) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 4)
            })
        }
        body.addView(permRow("Keep running in background", {
            (getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)
        }) {
            try {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        })
        body.addView(permRow("Accessibility (send messages, Home/Back/Lock)", { InHumanAccessibility.isOn() }) {
            toast("Find \"InHuman AI\" and switch it on")
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        body.addView(hint("If Android says \"Restricted setting\" for Accessibility: tap App info, tap ⋮ (top right), choose \"Allow restricted settings\", then try again."))
        body.addView(button("App info", filled = false) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        })
        body.addView(hint("Trigger phrase, emails, AI keys and MT5 are in the app: open the sidebar, then Settings."))
        body.addView(button("Save & Start InHuman", filled = true) {
            var url = server.text.toString().trim()
            if (url.isNotEmpty() && !url.startsWith("http")) url = "https://$url"
            if (!url.startsWith("https://")) return@button toast("Enter your Vercel link, e.g. https://your-project.vercel.app")
            Prefs.setServerUrl(this, url)
            if (!Settings.canDrawOverlays(this)) return@button toast("Please allow \"Display over other apps\"")
            if (!hasMic()) return@button toast("Please allow the microphone")
            Prefs.setOverlayEnabled(this, true)
            restartOverlay()
            Voice.speak("All systems online. At your service, sir.")
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
            finish()
        })
    }

    // ================= helpers =================
    private fun restartOverlay() {
        if (OverlayService.running) OverlayService.stop(this)
        if (Settings.canDrawOverlays(this) && hasMic() && Prefs.overlayEnabled(this)) {
            window.decorView.postDelayed({ OverlayService.start(this) }, 600)
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun label(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        this.text = s
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun section(s: String) = label(s, 15f, Color.WHITE, bold = true).apply { setPadding(0, 20.px(), 0, 6.px()) }
    private fun hint(s: String) = label(s, 12f, grey).apply { setPadding(0, 4.px(), 0, 4.px()) }

    private fun input(hint: String, value: String, type: Int) = EditText(this).apply {
        this.hint = hint
        setText(value)
        inputType = if (type == InputType.TYPE_CLASS_NUMBER || (type and InputType.TYPE_CLASS_NUMBER) != 0) type else InputType.TYPE_CLASS_TEXT or type
        isSingleLine = true
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
        row.addView(label(title, 13f, Color.WHITE), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val status = Button(this).apply {
            isAllCaps = false
            textSize = 12f
            setOnClickListener { if (!check()) onAllow() }
        }
        row.addView(status)
        permRows.add(Pair<TextView, () -> Boolean>(status, check))
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
