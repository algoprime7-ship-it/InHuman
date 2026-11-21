package ai.inhuman.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject

// =====================================================================
// Home: your hosted InHuman web app (3D head, chat, music) inside the phone app.
// When you leave or close the app, the song moves to the floating circle and keeps playing.
// =====================================================================
class MainActivity : Activity() {
    private lateinit var web: WebView
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var pageReady = false

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
            setGeolocationEnabled(true)
        }
        web.addJavascriptInterface(Bridge(), "InHumanNative")

        val home = Uri.parse(Prefs.serverUrl(this))
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                if (request == null) return false
                val url = request.url ?: return false
                if (!request.isForMainFrame || url.host == home.host) return false
                Brain.openUrl(this@MainActivity, url.toString())
                return true
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                pageReady = true
                web.postDelayed({ takeMusicBack() }, 1200)
                web.postDelayed({ runPending() }, 2500)
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            // camera + microphone for "take a photo / record a video" inside the app
            override fun onPermissionRequest(request: android.webkit.PermissionRequest?) {
                val r = request ?: return
                if (r.origin?.host != home.host) { r.deny(); return }
                val need = mutableListOf<String>()
                if (r.resources.contains(android.webkit.PermissionRequest.RESOURCE_VIDEO_CAPTURE) && checkSelfPermission(Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.CAMERA)
                if (r.resources.contains(android.webkit.PermissionRequest.RESOURCE_AUDIO_CAPTURE) && !hasMic()) need.add(Manifest.permission.RECORD_AUDIO)
                if (need.isEmpty()) { r.grant(r.resources); return }
                pendingWebPermission = r
                requestPermissions(need.toTypedArray(), REQ_CAM)
            }

            // location for the map and "share my location": Android asks once, then the page gets it
            override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: android.webkit.GeolocationPermissions.Callback?) {
                val cb = callback ?: return
                if (origin == null || Uri.parse(origin).host != home.host) { cb.invoke(origin, false, false); return }
                if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
                    checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    cb.invoke(origin, true, false)
                    return
                }
                pendingGeo = Pair(origin, cb)
                requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), REQ_LOC)
            }

            override fun onShowFileChooser(view: WebView?, callback: ValueCallback<Array<Uri>>?, params: FileChooserParams?): Boolean {
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
        pendingRun = intent?.getStringExtra("run")
        handleSignInLink(intent)
        askExtraPermissions()
    }

    private var pendingRun: String? = null
    private var pendingWebPermission: android.webkit.PermissionRequest? = null
    private var pendingGeo: Pair<String, android.webkit.GeolocationPermissions.Callback>? = null

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent?.getStringExtra("run")?.let { pendingRun = it; if (pageReady) web.postDelayed({ runPending() }, 600) }
        handleSignInLink(intent)
    }

    /** A command from the floating circle that needs this screen (camera) */
    private fun runPending() {
        val t = pendingRun ?: return
        pendingRun = null
        js("window.__runCommand && window.__runCommand(${JSONObject.quote(t)})")
    }

    /** Google sign-in finished in the browser: inhuman://signin?token=…&first=…&last=…&email=… */
    private fun handleSignInLink(i: Intent?) {
        val u = i?.data ?: return
        // invite link from the email: inhuman://invite?code=…
        if (u.scheme == "inhuman" && u.host == "invite") {
            val code = u.getQueryParameter("code") ?: return
            val run = Runnable { js("window.__openInvite && window.__openInvite(${JSONObject.quote(code)})") }
            if (pageReady) run.run() else web.postDelayed(run, 3500)
            return
        }
        if (u.scheme != "inhuman" || u.host != "signin") return
        val acc = JSONObject()
            .put("token", u.getQueryParameter("token") ?: return)
            .put("email", u.getQueryParameter("email") ?: "")
            .put("first", u.getQueryParameter("first") ?: "")
            .put("last", u.getQueryParameter("last") ?: "")
        Prefs.setStr(this, "account", acc.optString("token"))
        val run = Runnable { js("window.__googleSignedIn && window.__googleSignedIn(${acc})") }
        if (pageReady) run.run() else web.postDelayed(run, 3000)
    }

    /** Phone calls (who's calling, answer/drop), camera, Bluetooth: asked once */
    private fun askExtraPermissions() {
        if (Prefs.bool(this, "askedExtraPerms", false)) return
        Prefs.setBool(this, "askedExtraPerms", true)
        val want = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.ANSWER_PHONE_CALLS,
            Manifest.permission.READ_CONTACTS
        )
        if (android.os.Build.VERSION.SDK_INT >= 31) want.add(Manifest.permission.BLUETOOTH_CONNECT)
        val missing = want.filter { checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) web.postDelayed({ requestPermissions(missing.toTypedArray(), REQ_EXTRA) }, 4000)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CAM) {
            val r = pendingWebPermission ?: return
            pendingWebPermission = null
            if (grantResults.isNotEmpty() && grantResults.all { it == android.content.pm.PackageManager.PERMISSION_GRANTED }) r.grant(r.resources) else r.deny()
        }
        if (requestCode == REQ_LOC) {
            val g = pendingGeo
            pendingGeo = null
            if (g != null) g.second.invoke(g.first, grantResults.any { it == android.content.pm.PackageManager.PERMISSION_GRANTED }, false)
        }
        if (requestCode == REQ_EXTRA && OverlayService.running) {
            // the phone-call listener needs a restart to start hearing calls
            OverlayService.stop(this)
            web.postDelayed({ OverlayService.start(this) }, 700)
        }
    }

    /** The wake word heard a name while this screen is open: the face on screen answers (and switches) */
    fun wakeInApp(face: String, command: String) {
        js("window.__nativeWake && window.__nativeWake(${JSONObject.quote(face)}, ${JSONObject.quote(command)})")
    }

    /** TikTok sent a warning during the LIVE: the page stops LIVE mode and ends it in TikTok */
    fun liveWarning() {
        if (pageReady) js("window.__liveWarning && window.__liveWarning()") else TikTok.endLive(this)
    }

    /** face changed by the overlay ("Friday…") → the page shows the same face */
    fun syncFace() {
        if (pageReady) js("window.__syncFace && window.__syncFace(${JSONObject.quote(Prefs.face(this))})")
    }

    override fun onPause() {
        super.onPause()
        visible = false
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        visible = true
        instance = this
        syncFace()
        if (!::web.isInitialized) return
        if (Prefs.overlayEnabled(this) && !OverlayService.running && Settings.canDrawOverlays(this) && hasMic()) {
            OverlayService.start(this)
        } else if (OverlayService.running) {
            OverlayService.instance?.refreshWakeWord()     // get the microphone back for "Friday / Jarvis …"
        }
        if (pageReady) takeMusicBack()
    }

    override fun onStop() {
        super.onStop()
        if (::web.isInitialized && !isChangingConfigurations) handMusicToOverlay()
    }

    /** Leaving the app: the floating circle carries on with the current song */
    private fun handMusicToOverlay() {
        val svc = OverlayService.instance ?: return
        web.evaluateJavascript("window.__handoffMusic ? window.__handoffMusic() : ''") { result ->
            val raw = decodeJsString(result)
            if (raw.isBlank()) return@evaluateJavascript
            try { svc.player.startFromHandoff(JSONObject(raw)) } catch (e: Exception) {}
        }
    }

    /** Back in the app: the song continues in the app from the same second */
    private fun takeMusicBack() {
        val p = OverlayService.instance?.player ?: return
        if (!p.isActive()) return
        p.takeOver { state ->
            if (state != null) web.evaluateJavascript("window.__resumeMusic && window.__resumeMusic(${state})", null)
        }
    }

    /** evaluateJavascript returns strings JSON-quoted: "\"{...}\"" → {...} */
    private fun decodeJsString(v: String?): String {
        if (v == null || v == "null" || v == "\"\"") return ""
        return try { JSONArray("[$v]").getString(0) } catch (e: Exception) { "" }
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
            OverlayService.instance?.pauseWakeWord()        // don't hear our own voice say "Jarvis"
            Voice.speak(text, angry, onDone = { OverlayService.instance?.resumeWakeWord(); js("window.__nativeSpeechDone && window.__nativeSpeechDone()") })
        }

        /** one line in another face's voice (Ultron cutting in) */
        // ---- TikTok LIVE ----
        @JavascriptInterface
        fun setLiveMode(on: Boolean) { TikTok.setLive(this@MainActivity, on) }

        @JavascriptInterface
        fun tiktokGoLive(): Boolean = TikTok.goLive(this@MainActivity)

        @JavascriptInterface
        fun tiktokEndLive() { runOnUiThread { TikTok.endLive(this@MainActivity) } }

        /** back to InHuman on screen (after showing MetaTrader 5 on the LIVE) */
        @JavascriptInterface
        fun showApp() { runOnUiThread { TikTok.backToApp(this@MainActivity) } }

        @JavascriptInterface
        fun speakAs(text: String, face: String, angry: Boolean) = runOnUiThread {
            OverlayService.instance?.pauseWakeWord()
            Voice.speak(text, angry, onDone = { OverlayService.instance?.resumeWakeWord(); js("window.__nativeSpeechDone && window.__nativeSpeechDone()") }, asFace = face)
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
            OverlayService.instance?.pauseWakeWord()
            Ears.listenOnce(this@MainActivity) { text ->
                OverlayService.instance?.resumeWakeWord()
                js("window.__nativeHeard && window.__nativeHeard(${JSONObject.quote(text ?: "")})")
            }
        }

        /** The chat box: same brain as the floating circle (emails, contacts, keys, MT5...) */
        @JavascriptInterface
        fun ask(text: String, historyJson: String) = runOnUiThread {
            val history = try { JSONArray(historyJson) } catch (e: Exception) { JSONArray() }
            Brain.ask(this@MainActivity, text, history, "web") { reply, action ->
                val out = JSONObject().put("reply", reply).put("action", action ?: JSONObject.NULL).put("aside", Brain.lastAside)
                js("window.__nativeAnswer && window.__nativeAnswer(${out})")
            }
        }

        @JavascriptInterface
        fun doAction(json: String) = runOnUiThread {
            try {
                Brain.runAction(this@MainActivity, JSONObject(json)) { msg ->
                    js("window.__nativeSay && window.__nativeSay(${JSONObject.quote(msg)})")
                }
            } catch (e: Exception) {}
        }

        @JavascriptInterface
        fun getRecent(): String = Prefs.recent(this@MainActivity).toString()

        /** Extra context for the AI (recent songs, "user just said they're sad"…) */
        @JavascriptInterface
        fun setExtraContext(json: String) {
            try {
                val o = JSONObject(json)
                o.optJSONObject("hint")?.let { Brain.nextHint = it }
                o.remove("hint")
                Brain.webExtra = o
            } catch (e: Exception) {}
        }

        @JavascriptInterface
        fun readNotificationsState(): String = JSONObject()
            .put("on", Prefs.bool(this@MainActivity, "readNotifs", false))
            .put("access", NotificationReader.hasAccess(this@MainActivity)).toString()

        /** Photos/videos from the web camera → the phone's gallery (Pictures/InHuman, Movies/InHuman) */
        @JavascriptInterface
        fun saveMedia(base64: String, mime: String, name: String): String {
            return try {
                val bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
                val video = mime.startsWith("video")
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mime)
                    if (android.os.Build.VERSION.SDK_INT >= 29) put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, (if (video) "Movies" else "Pictures") + "/InHuman")
                }
                val coll = if (video) android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI else android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                val uri = contentResolver.insert(coll, values) ?: return "error: no gallery"
                contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                "ok"
            } catch (e: Exception) {
                "error: ${e.message}"
            }
        }

        @JavascriptInterface
        fun getDeviceId(): String = Prefs.deviceId(this@MainActivity)

        // ---------- app Settings page (Configurations · Emails · Connect Apps) ----------
        @JavascriptInterface
        fun getSettings(): String {
            val c = this@MainActivity
            val emails = JSONArray()
            val saved = Prefs.emails(c)
            for (i in 0 until saved.length()) {
                val e = saved.optJSONObject(i) ?: continue
                emails.put(
                    JSONObject()
                        .put("label", e.optString("label")).put("type", e.optString("type"))
                        .put("address", e.optString("address")).put("host", e.optString("host"))
                        .put("port", e.optString("port")).put("hasPass", e.optString("pass").isNotBlank())
                )
            }
            return JSONObject()
                .put("native", true)
                .put("mode", Prefs.face(c))
                .put("trigger", Prefs.trigger(c))
                .put("reply", Prefs.replyPhrase(c))
                .put("owner", Prefs.ownerName(c))
                .put("voiceReady", WakeWord.isModelReady(c))
                .put("emails", emails)
                .put("groups", Prefs.groups(c))
                .put("keys", JSONObject()
                    .put("gemini", Prefs.key(c, "gemini")).put("claude", Prefs.key(c, "claude"))
                    .put("openai", Prefs.key(c, "openai")).put("github", Prefs.key(c, "github"))
                    .put("preferred", Prefs.key(c, "preferred")))
                .put("mt5", JSONObject()
                    .put("bridge", Prefs.mt5(c, "bridge")).put("login", Prefs.mt5(c, "login"))
                    .put("server", Prefs.mt5(c, "server")).put("lot", Prefs.mt5(c, "lot", "0.01"))
                    .put("suffix", Prefs.mt5(c, "suffix")).put("token", Prefs.mt5(c, "token")))
                .toString()
        }

        /** Saves what the Settings page sends. An email sent with an empty password keeps its saved one. */
        @JavascriptInterface
        fun saveSettings(json: String): String {
            val c = this@MainActivity
            return try {
                val s = JSONObject(json)
                val oldTrigger = Prefs.trigger(c)
                val oldReply = Prefs.replyPhrase(c)
                if (s.has("trigger")) {
                    val t = Text.norm(s.optString("trigger"))
                    if (t.length >= 3) Prefs.setStr(c, "trigger", t)
                }
                if (s.has("reply")) Prefs.setStr(c, "reply", s.optString("reply").ifBlank { "Yes sir" })
                if (s.has("owner")) Prefs.setStr(c, "owner", s.optString("owner"))
                if (s.has("mode")) {
                    val m = s.optString("mode")
                    Prefs.setStr(c, "mode", if (m in Prefs.FACES) m else "aether")
                    Voice.face = Prefs.face(c)
                }
                if (s.has("account")) Prefs.setStr(c, "account", s.optString("account"))
                if (s.has("checkins")) Prefs.setBool(c, "checkins", s.optBoolean("checkins", true))
                if (s.has("readNotifications")) {
                    val on = s.optBoolean("readNotifications")
                    Prefs.setBool(c, "readNotifs", on)
                    if (on) {
                        Prefs.setLong(c, "readNotifsSince", System.currentTimeMillis())
                        if (!NotificationReader.hasAccess(c)) runOnUiThread { NotificationReader.openAccessSettings(c) }
                    }
                }
                if (s.has("adminBridge")) Prefs.setMt5(c, "adminbridge", s.optString("adminBridge").trim().trimEnd('/'))

                s.optJSONArray("emails")?.let { list ->
                    val old = Prefs.emails(c)
                    val out = JSONArray()
                    for (i in 0 until list.length()) {
                        val e = list.optJSONObject(i) ?: continue
                        var pass = e.optString("pass").replace(" ", "")
                        if (pass.isBlank()) {
                            for (j in 0 until old.length()) {
                                val o = old.optJSONObject(j) ?: continue
                                if (o.optString("address").equals(e.optString("address"), true)) pass = o.optString("pass")
                            }
                        }
                        out.put(
                            JSONObject()
                                .put("label", e.optString("label")).put("type", e.optString("type", "personal"))
                                .put("address", e.optString("address")).put("pass", pass)
                                .put("host", e.optString("host")).put("port", e.optString("port"))
                        )
                    }
                    Prefs.setEmails(c, out)
                }
                s.optJSONArray("groups")?.let { Prefs.setGroups(c, it) }
                s.optJSONObject("keys")?.let { k ->
                    listOf("gemini", "claude", "openai", "github", "preferred").forEach { n ->
                        if (k.has(n)) Prefs.setKey(c, n, k.optString(n))
                    }
                }
                s.optJSONObject("mt5")?.let { m ->
                    listOf("bridge", "login", "server", "lot", "suffix", "token").forEach { n ->
                        if (m.has(n)) Prefs.setMt5(c, n, if (n == "bridge") m.optString(n).trim().trimEnd('/') else m.optString(n))
                    }
                }
                // New trigger phrase or answer: restart the floating assistant so it listens for it
                if ((Prefs.trigger(c) != oldTrigger || Prefs.replyPhrase(c) != oldReply) && OverlayService.running) {
                    runOnUiThread {
                        OverlayService.stop(c)
                        web.postDelayed({ OverlayService.start(c) }, 700)
                    }
                }
                "ok"
            } catch (e: Exception) {
                "error: ${e.message}"
            }
        }

        @JavascriptInterface
        fun mt5Connect(bridge: String, login: String, password: String, server: String) = runOnUiThread {
            val b = bridge.trim().trimEnd('/')
            Prefs.setMt5(this@MainActivity, "bridge", b)
            Prefs.setMt5(this@MainActivity, "login", login)
            Prefs.setMt5(this@MainActivity, "server", server)
            Brain.mt5Connect(this@MainActivity, b, login, password, server) { ok, msg ->
                js("window.__mt5Result && window.__mt5Result(${ok}, ${JSONObject.quote(msg)})")
            }
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
        @Volatile var visible = false
        @Volatile var instance: MainActivity? = null
        private const val REQ_FILE = 42
        private const val REQ_MIC = 43
        private const val REQ_CAM = 44
        private const val REQ_EXTRA = 45
        private const val REQ_LOC = 46
    }
}
