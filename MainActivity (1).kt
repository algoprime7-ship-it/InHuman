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
            }
        }
        web.webChromeClient = object : WebChromeClient() {
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
    }

    override fun onResume() {
        super.onResume()
        if (!::web.isInitialized) return
        if (Prefs.overlayEnabled(this) && !OverlayService.running && Settings.canDrawOverlays(this) && hasMic()) {
            OverlayService.start(this)
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
                val out = JSONObject().put("reply", reply).put("action", action ?: JSONObject.NULL)
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
