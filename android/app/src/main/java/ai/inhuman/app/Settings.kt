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
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject

// =====================================================================
// Settings: Setup · Configurations · Emails · Connect Apps
// =====================================================================
class SetupActivity : Activity() {

    companion object {
        fun isReady(c: Context): Boolean =
            Prefs.serverUrl(c).startsWith("https://") && Settings.canDrawOverlays(c) && c.hasMic()
    }

    private val tabs = listOf("Setup", "Configurations", "Emails", "Connect Apps")
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
        headText.addView(label("InHuman AI Settings", 20f, Color.WHITE, bold = true))
        headText.addView(label(DeviceInfo.summary(this), 11f, grey))
        head.addView(headText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(head)

        tabBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 14.px(), 0, 8.px()) }
        root.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(tabBar) })

        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, 40.px()) }
        root.addView(ScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        if (isReady(this)) currentTab = intent.getIntExtra("tab", 0)
        showTab(currentTab)
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
        when (i) {
            0 -> buildSetup()
            1 -> buildConfigurations()
            2 -> buildEmails()
            3 -> buildConnectApps()
        }
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

    // ================= Configurations =================
    private fun buildConfigurations() {
        body.addView(section("Trigger phrase"))
        body.addView(hint("Say this to wake InHuman, e.g. \"hey jarvis\" or \"hey parvis\". Two short words work best."))
        val trigger = input("hey jarvis", Prefs.trigger(this), InputType.TYPE_TEXT_VARIATION_NORMAL)
        body.addView(trigger)

        body.addView(section("InHuman answers with"))
        val reply = input("Yes sir", Prefs.replyPhrase(this), InputType.TYPE_TEXT_VARIATION_NORMAL)
        body.addView(reply)

        body.addView(section("Your name (used to sign emails)"))
        val owner = input("e.g. Thabo Mokoena", Prefs.ownerName(this), InputType.TYPE_TEXT_VARIATION_PERSON_NAME)
        body.addView(owner)

        val modelText = if (WakeWord.isModelReady(this)) "Voice trigger engine: downloaded ✓"
        else "Voice trigger engine: downloads automatically (about 40 MB, once) when InHuman starts. Use Wi-Fi."
        body.addView(hint(modelText))

        body.addView(button("Save", filled = true) {
            val t = Text.norm(trigger.text.toString())
            if (t.length < 3) return@button toast("Trigger phrase is too short")
            Prefs.setStr(this, "trigger", t)
            Prefs.setStr(this, "reply", reply.text.toString().ifBlank { "Yes sir" })
            Prefs.setStr(this, "owner", owner.text.toString())
            restartOverlay()
            toast("Saved. Say \"$t\" to wake InHuman.")
        })
    }

    // ================= Emails =================
    private fun buildEmails() {
        body.addView(section("Your email accounts"))
        val list = Prefs.emails(this)
        if (list.length() == 0) body.addView(hint("No emails yet. Add personal and business emails below."))
        for (i in 0 until list.length()) {
            val e = list.optJSONObject(i) ?: continue
            body.addView(itemRow("${e.optString("label")} · ${e.optString("type")}\n${e.optString("address")}") {
                Prefs.setEmails(this, removeAt(Prefs.emails(this), i))
                showTab(2)
            })
        }

        body.addView(section("Add an email"))
        val labelIn = input("Name, e.g. Work or My Gmail", "", InputType.TYPE_TEXT_VARIATION_NORMAL)
        var type = "personal"
        val typeBtn = button("Type: Personal (tap to change)", filled = false) {}
        typeBtn.setOnClickListener {
            type = if (type == "personal") "business" else "personal"
            typeBtn.text = "Type: ${type.replaceFirstChar { it.uppercase() }} (tap to change)"
        }
        val addr = input("you@gmail.com", "", InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        val pass = input("App password", "", InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val host = input("SMTP server (leave empty for Gmail/Outlook/Yahoo/iCloud)", "", InputType.TYPE_TEXT_VARIATION_URI)
        val port = input("Port (optional, e.g. 465)", "", InputType.TYPE_CLASS_NUMBER)
        listOf<View>(labelIn, typeBtn, addr, pass, host, port).forEach { body.addView(it) }
        body.addView(hint("Gmail: turn on 2-Step Verification, then create an app password at myaccount.google.com/apppasswords. Business emails on Google Workspace use smtp.gmail.com."))
        body.addView(button("Add email", filled = true) {
            val a = addr.text.toString().trim()
            if (!a.contains('@')) return@button toast("Enter a valid email address")
            if (pass.text.isBlank()) return@button toast("Enter the app password")
            val arr = Prefs.emails(this).put(
                JSONObject()
                    .put("label", labelIn.text.toString().ifBlank { a })
                    .put("type", type)
                    .put("address", a)
                    .put("pass", pass.text.toString().replace(" ", ""))
                    .put("host", host.text.toString().trim())
                    .put("port", port.text.toString().trim())
            )
            Prefs.setEmails(this, arr)
            toast("Email added")
            showTab(2)
        })

        body.addView(section("Email groups"))
        body.addView(hint("Say \"send a business proposal to my clients\" and InHuman emails everyone in the \"clients\" group."))
        val groups = Prefs.groups(this)
        for (i in 0 until groups.length()) {
            val g = groups.optJSONObject(i) ?: continue
            body.addView(itemRow("${g.optString("name")}\n${g.optString("emails")}") {
                Prefs.setGroups(this, removeAt(Prefs.groups(this), i))
                showTab(2)
            })
        }
        val gName = input("Group name, e.g. clients", "", InputType.TYPE_TEXT_VARIATION_NORMAL)
        val gEmails = input("Emails, separated by commas", "", InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS).apply {
            isSingleLine = false
            minLines = 2
        }
        body.addView(gName)
        body.addView(gEmails)
        body.addView(button("Add group", filled = true) {
            val n = gName.text.toString().trim()
            val emails = gEmails.text.toString().split(',', ';', ' ', '\n').map { it.trim() }.filter { it.contains('@') }
            if (n.isEmpty() || emails.isEmpty()) return@button toast("Enter a group name and at least one email")
            Prefs.setGroups(this, Prefs.groups(this).put(JSONObject().put("name", n).put("emails", emails.joinToString(", "))))
            toast("Group added")
            showTab(2)
        })
    }

    // ================= Connect Apps =================
    private fun buildConnectApps() {
        body.addView(section("AI brains"))
        body.addView(hint("Optional. Add your own key and InHuman uses it instead of the app's shared one."))
        val gemini = keyInput("Gemini API key (aistudio.google.com/apikey)", "gemini")
        val claude = keyInput("Claude API key (console.anthropic.com)", "claude")
        val openai = keyInput("ChatGPT / OpenAI API key (platform.openai.com)", "openai")
        val choices = listOf("auto", "gemini", "claude", "openai")
        var pref = Prefs.key(this, "preferred").ifBlank { "auto" }
        val prefBtn = button("Answer with: ${pref.replaceFirstChar { it.uppercase() }} (tap to change)", filled = false) {}
        prefBtn.setOnClickListener {
            pref = choices[(choices.indexOf(pref) + 1) % choices.size]
            prefBtn.text = "Answer with: ${pref.replaceFirstChar { it.uppercase() }} (tap to change)"
        }
        body.addView(prefBtn)

        body.addView(section("GitHub"))
        body.addView(hint("Personal access token (github.com/settings/tokens). Lets InHuman answer about your repos and recent activity."))
        val github = keyInput("GitHub token", "github")

        body.addView(button("Save keys", filled = true) {
            Prefs.setKey(this, "gemini", gemini.text.toString())
            Prefs.setKey(this, "claude", claude.text.toString())
            Prefs.setKey(this, "openai", openai.text.toString())
            Prefs.setKey(this, "github", github.text.toString())
            Prefs.setKey(this, "preferred", if (pref == "auto") "" else pref)
            toast("Saved")
        })

        body.addView(section("MetaTrader 5 (your bridge)"))
        val connected = Prefs.mt5(this, "token").isNotBlank()
        val status = label(if (connected) "Status: connected ✓" else "Status: not connected", 13f, if (connected) Color.rgb(52, 211, 153) else grey)
        body.addView(status)
        val bridge = input("Bridge link, e.g. https://bridge.algoproedge.com", Prefs.mt5(this, "bridge"), InputType.TYPE_TEXT_VARIATION_URI)
        val login = input("MT5 login number", Prefs.mt5(this, "login"), InputType.TYPE_CLASS_NUMBER)
        val password = input("MT5 password (not saved)", "", InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val server = input("Broker server, e.g. Exness-MT5Real", Prefs.mt5(this, "server"), InputType.TYPE_TEXT_VARIATION_NORMAL)
        val lot = input("Default lot size", Prefs.mt5(this, "lot", "0.01"), InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        val suffix = input("Symbol suffix if your broker uses one, e.g. m", Prefs.mt5(this, "suffix"), InputType.TYPE_TEXT_VARIATION_NORMAL)
        listOf<View>(bridge, login, password, server, lot, suffix).forEach { body.addView(it) }
        body.addView(button("Connect MT5", filled = true) {
            val b = bridge.text.toString().trim().trimEnd('/')
            if (!b.startsWith("http")) return@button toast("Enter your bridge link")
            Prefs.setMt5(this, "bridge", b)
            Prefs.setMt5(this, "login", login.text.toString())
            Prefs.setMt5(this, "server", server.text.toString())
            Prefs.setMt5(this, "lot", lot.text.toString().ifBlank { "0.01" })
            Prefs.setMt5(this, "suffix", suffix.text.toString())
            status.text = "Status: connecting…"
            Brain.mt5Connect(this, b, login.text.toString(), password.text.toString(), server.text.toString()) { ok, msg ->
                status.text = "Status: $msg"
                status.setTextColor(if (ok) Color.rgb(52, 211, 153) else Color.rgb(255, 99, 99))
                password.setText("")
            }
        })
        body.addView(hint("Or paste a connection token you already have:"))
        val token = input("Connection token", Prefs.mt5(this, "token"), InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)
        body.addView(token)
        body.addView(button("Save MT5 settings", filled = false) {
            Prefs.setMt5(this, "bridge", bridge.text.toString().trim().trimEnd('/'))
            Prefs.setMt5(this, "lot", lot.text.toString().ifBlank { "0.01" })
            Prefs.setMt5(this, "suffix", suffix.text.toString())
            Prefs.setMt5(this, "token", token.text.toString())
            toast("Saved")
            showTab(3)
        })
        body.addView(hint("Say e.g. \"open 5 trades on gold\". InHuman checks the direction with AI first, then places the trades through your bridge at the default lot size."))
    }

    // ================= helpers =================
    private fun restartOverlay() {
        if (OverlayService.running) OverlayService.stop(this)
        if (Settings.canDrawOverlays(this) && hasMic() && Prefs.overlayEnabled(this)) {
            window.decorView.postDelayed({ OverlayService.start(this) }, 600)
        }
    }

    private fun removeAt(a: JSONArray, index: Int): JSONArray {
        val out = JSONArray()
        for (i in 0 until a.length()) if (i != index) out.put(a.opt(i))
        return out
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

    private fun keyInput(hint: String, name: String) =
        input(hint, Prefs.key(this, name), InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD).also { body.addView(it) }

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

    private fun itemRow(text: String, onRemove: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(14.px(), 10.px(), 8.px(), 10.px())
            background = GradientDrawable().apply {
                cornerRadius = 14 * dp
                setColor(Color.argb(12, 255, 255, 255))
                setStroke(1.px(), Color.argb(30, 255, 255, 255))
            }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = 8.px() }
        }
        row.addView(label(text, 13f, Color.WHITE), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(Button(this).apply {
            this.text = "Remove"
            isAllCaps = false
            textSize = 12f
            setTextColor(Color.rgb(255, 120, 120))
            background = GradientDrawable().apply { cornerRadius = 12 * dp; setColor(Color.argb(30, 255, 80, 80)) }
            setOnClickListener { onRemove() }
        })
        return row
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
