package ai.inhuman.app

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

// =====================================================================
// Saved settings
// =====================================================================
object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("inhuman", Context.MODE_PRIVATE)

    fun serverUrl(c: Context): String = (sp(c).getString("server", "") ?: "").trim().trimEnd('/')
    fun setServerUrl(c: Context, v: String) = sp(c).edit().putString("server", v.trim().trimEnd('/')).apply()

    fun picoKey(c: Context): String = (sp(c).getString("pico", "") ?: "").trim()
    fun setPicoKey(c: Context, v: String) = sp(c).edit().putString("pico", v.trim()).apply()

    fun overlayEnabled(c: Context): Boolean = sp(c).getBoolean("overlay", true)
    fun setOverlayEnabled(c: Context, v: Boolean) = sp(c).edit().putBoolean("overlay", v).apply()

    fun posX(c: Context, def: Int): Int = sp(c).getInt("x", def)
    fun posY(c: Context, def: Int): Int = sp(c).getInt("y", def)
    fun savePos(c: Context, x: Int, y: Int) = sp(c).edit().putInt("x", x).putInt("y", y).apply()
}

// =====================================================================
// Device type & name (shown in the app and sent to the AI)
// =====================================================================
object DeviceInfo {
    fun json(c: Context): JSONObject {
        val cr = c.contentResolver
        val userName = try {
            Settings.Global.getString(cr, Settings.Global.DEVICE_NAME)
        } catch (e: Exception) {
            null
        } ?: try {
            Settings.Secure.getString(cr, "bluetooth_name")
        } catch (e: Exception) {
            null
        }
        val maker = Build.MANUFACTURER.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
        val type = if (c.resources.configuration.smallestScreenWidthDp >= 600) "Tablet" else "Phone"
        return JSONObject()
            .put("name", if (userName.isNullOrBlank()) "$maker ${Build.MODEL}" else userName)
            .put("manufacturer", maker)
            .put("model", Build.MODEL)
            .put("os", "Android ${Build.VERSION.RELEASE}")
            .put("type", "Android $type")
    }

    fun summary(c: Context): String {
        val j = json(c)
        return "${j.optString("name")} • ${j.optString("manufacturer")} ${j.optString("model")} • ${j.optString("os")} • ${j.optString("type")}"
    }
}

// =====================================================================
// Voice: British text-to-speech, shared by the app and the floating circle
// =====================================================================
object Voice {
    private var tts: TextToSpeech? = null
    private var ready = false
    private var initDone = false
    private val main = Handler(Looper.getMainLooper())
    private val callbacks = ConcurrentHashMap<String, () -> Unit>()
    private val queued = mutableListOf<Triple<String, Boolean, (() -> Unit)?>>()

    fun init(c: Context) {
        if (tts != null) return
        tts = TextToSpeech(c.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            initDone = true
            if (ready) configure()
            val pending = queued.toList()
            queued.clear()
            pending.forEach { speak(it.first, it.second, it.third) }
        }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                utteranceId?.let { id -> callbacks.remove(id)?.let { cb -> main.post { cb() } } }
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                utteranceId?.let { id -> callbacks.remove(id)?.let { cb -> main.post { cb() } } }
            }
            override fun onError(utteranceId: String?, errorCode: Int) {
                utteranceId?.let { id -> callbacks.remove(id)?.let { cb -> main.post { cb() } } }
            }
        })
    }

    private fun configure() {
        val t = tts ?: return
        try {
            t.language = Locale.UK
            val gb = t.voices?.filter {
                it.locale.language == "en" && it.locale.country == "GB" && !it.isNetworkConnectionRequired
            }.orEmpty()
            // Google's male British voices are usually named ...gbd... or ...rjs...
            val male = gb.firstOrNull { v -> listOf("gbd", "rjs", "male").any { v.name.lowercase().contains(it) } }
            (male ?: gb.firstOrNull())?.let { t.voice = it }
        } catch (e: Exception) { /* keep default voice */ }
    }

    fun speak(text: String, angry: Boolean = false, onDone: (() -> Unit)? = null) {
        if (!initDone) {
            queued.add(Triple(text, angry, onDone))
            return
        }
        val t = tts
        if (!ready || t == null) {
            onDone?.let { main.post(it) }
            return
        }
        val once = AtomicBoolean(false)
        val finish: () -> Unit = { if (once.compareAndSet(false, true)) onDone?.invoke() }
        val id = UUID.randomUUID().toString()
        if (onDone != null) {
            callbacks[id] = finish
            // Safety net: some phones never report "done"
            main.postDelayed({ callbacks.remove(id); finish() }, maxOf(3500L, text.length * 95L))
        }
        t.setPitch(if (angry) 0.78f else 0.88f)
        t.setSpeechRate(if (angry) 1.06f else 0.97f)
        t.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
    }

    fun stop() {
        try { tts?.stop() } catch (e: Exception) {}
    }
}

// =====================================================================
// Ears: one-shot speech recognition (must be called on the main thread)
// =====================================================================
object Ears {
    fun listenOnce(c: Context, cb: (String?) -> Unit) {
        if (!SpeechRecognizer.isRecognitionAvailable(c)) {
            cb(null)
            return
        }
        val sr = SpeechRecognizer.createSpeechRecognizer(c)
        var done = false
        fun finish(text: String?) {
            if (done) return
            done = true
            try { sr.destroy() } catch (e: Exception) {}
            cb(text)
        }
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) = finish(null)
            override fun onResults(results: Bundle?) =
                finish(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull())
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        try {
            sr.startListening(intent)
        } catch (e: Exception) {
            finish(null)
        }
        // Give up after 12 seconds of nothing
        Handler(Looper.getMainLooper()).postDelayed({ finish(null) }, 12000)
    }
}

// =====================================================================
// Brain: quick phone commands + the AI server (/api/ask on Vercel)
// =====================================================================
object Brain {
    private const val YT = "com.google.android.youtube"
    private val main = Handler(Looper.getMainLooper())

    class Local(val reply: String, val action: JSONObject)

    /** Strip the wake word: "hey jarvis open instagram" -> "open instagram" */
    fun cleanCommand(text: String): String =
        text.trim().replace(Regex("^(hey|hi|ok|okay)?\\s*jarvis[,.!]?\\s*", RegexOption.IGNORE_CASE), "").trim()

    /** Common commands handled instantly on the phone, no internet needed */
    fun localCommand(raw: String): Local? {
        val t = cleanCommand(raw).lowercase(Locale.ROOT).trimEnd('.', '!', '?')

        Regex("^(?:open youtube and |go to youtube and )?(?:play|put on)\\s+(.+?)\\s+(?:on|in)\\s+youtube$").find(t)?.let {
            return Local("Playing ${it.groupValues[1]} on YouTube, sir.", JSONObject().put("type", "youtube_play").put("query", it.groupValues[1]))
        }
        Regex("^(?:open|go to) youtube and (?:play|put on)\\s+(.+)$").find(t)?.let {
            return Local("Playing ${it.groupValues[1]} on YouTube, sir.", JSONObject().put("type", "youtube_play").put("query", it.groupValues[1]))
        }
        Regex("^(?:open|go to) youtube and search(?: for)?\\s+(.+)$").find(t)?.let {
            return Local("Searching YouTube for ${it.groupValues[1]}, sir.", JSONObject().put("type", "youtube_search").put("query", it.groupValues[1]))
        }
        Regex("^(?:play|put on)\\s+(.+)$").find(t)?.let {
            return Local("Playing ${it.groupValues[1]}, sir.", JSONObject().put("type", "youtube_play").put("query", it.groupValues[1]))
        }
        when (t) {
            "go home", "home", "home screen", "go to home screen" ->
                return Local("Going home, sir.", JSONObject().put("type", "system").put("name", "home"))
            "go back", "back" ->
                return Local("Going back, sir.", JSONObject().put("type", "system").put("name", "back"))
            "recent apps", "show recent apps", "recents" ->
                return Local("Recent apps, sir.", JSONObject().put("type", "system").put("name", "recents"))
            "open notifications", "show notifications", "notifications" ->
                return Local("Your notifications, sir.", JSONObject().put("type", "system").put("name", "notifications"))
            "lock", "lock screen", "lock the screen", "lock my phone", "lock the phone" ->
                return Local("Locking the screen, sir.", JSONObject().put("type", "system").put("name", "lock"))
        }
        Regex("^(?:open|launch|start)\\s+(?:the\\s+)?(.+?)(?:\\s+app)?$").find(t)?.let {
            val app = it.groupValues[1]
            return Local("Opening $app, sir.", JSONObject().put("type", "open_app").put("app", app))
        }
        return null
    }

    /** Ask the AI on your Vercel server. cb runs on the main thread. */
    fun ask(c: Context, message: String, history: JSONArray, surface: String, cb: (String, JSONObject?) -> Unit) {
        val app = c.applicationContext
        Thread {
            var reply = "I cannot reach my neural core right now, sir. Please check your connection."
            var action: JSONObject? = null
            try {
                val server = Prefs.serverUrl(app)
                if (server.isEmpty()) {
                    reply = "Please add your InHuman web link in setup first, sir."
                } else {
                    val body = JSONObject()
                        .put("message", message)
                        .put("history", history)
                        .put("surface", surface)
                        .put("device", DeviceInfo.json(app))
                    val res = JSONObject(http("$server/api/ask", body.toString()))
                    reply = res.optString("reply", reply)
                    action = res.optJSONObject("action")
                }
            } catch (e: Exception) { /* keep the default reply */ }
            val r = reply
            val a = action
            main.post { cb(r, a) }
        }.start()
    }

    private fun http(url: String, postBody: String? = null): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        if (postBody != null) {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(postBody.toByteArray(Charsets.UTF_8)) }
        }
        val stream = if (conn.responseCode >= 400) conn.errorStream else conn.inputStream
        return stream?.bufferedReader()?.use { it.readText() } ?: ""
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    /** Carry out an action from the AI or a local command. say() speaks a short message. */
    fun runAction(c: Context, action: JSONObject, say: (String) -> Unit) {
        val q = action.optString("query")
        when (action.optString("type")) {
            "open_app" -> {
                val app = action.optString("app")
                if (app.equals("youtube", true) && q.isNotBlank()) playOnYouTube(c, q)
                else if (!openApp(c, app)) say("I could not find $app on this phone, sir.")
            }
            "youtube_play", "play_music" -> if (q.isNotBlank()) playOnYouTube(c, q) else openApp(c, "YouTube")
            "youtube_search" -> youtubeSearch(c, q)
            "web_search" -> openUrl(c, "https://www.google.com/search?q=${enc(q)}")
            "open_url" -> {
                val u = action.optString("url")
                if (u.startsWith("https://") || u.startsWith("http://")) openUrl(c, u)
            }
            "system" -> {
                val name = action.optString("name")
                if (!InHumanAccessibility.perform(name)) {
                    if (name == "home") {
                        startSafe(c, Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))
                    } else {
                        say("Please switch on InHuman AI in your Accessibility settings first, sir.")
                    }
                }
            }
        }
    }

    fun openApp(c: Context, name: String): Boolean {
        val target = name.lowercase(Locale.ROOT).trim()
        if (target.isEmpty()) return false
        val pm = c.packageManager
        val apps: List<Pair<ResolveInfo, String>> = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it to it.loadLabel(pm).toString().lowercase(Locale.ROOT) }
        val best = apps.firstOrNull { it.second == target }
            ?: apps.firstOrNull { it.second.startsWith(target) }
            ?: apps.firstOrNull { it.second.contains(target) }
            ?: apps.firstOrNull { it.second.length > 2 && target.contains(it.second) }
            ?: return false
        val info: ResolveInfo = best.first
        val launch = pm.getLaunchIntentForPackage(info.activityInfo.packageName) ?: return false
        return startSafe(c, launch)
    }

    /** Find the video on the server, then open it straight in the YouTube app (it auto-plays). */
    fun playOnYouTube(c: Context, query: String) {
        val app = c.applicationContext
        Thread {
            var videoId: String? = null
            try {
                val server = Prefs.serverUrl(app)
                if (server.isNotEmpty()) {
                    val res = JSONObject(http("$server/api/yt?q=${enc(query)}"))
                    videoId = res.optJSONArray("results")?.optJSONObject(0)?.optString("videoId")?.takeIf { it.isNotBlank() }
                }
            } catch (e: Exception) {}
            val id = videoId
            main.post {
                if (id != null) {
                    val inApp = Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:$id")).setPackage(YT)
                    if (!startSafe(app, inApp)) openUrl(app, "https://www.youtube.com/watch?v=$id")
                } else {
                    youtubeSearch(app, query) // could not find it: show search results instead
                }
            }
        }.start()
    }

    fun youtubeSearch(c: Context, query: String) {
        val i = Intent(Intent.ACTION_SEARCH).setPackage(YT).putExtra(SearchManager.QUERY, query)
        if (!startSafe(c, i)) openUrl(c, "https://www.youtube.com/results?search_query=${enc(query)}")
    }

    fun openUrl(c: Context, url: String) {
        startSafe(c, Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    private fun startSafe(c: Context, i: Intent): Boolean = try {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        c.startActivity(i)
        true
    } catch (e: Exception) {
        false
    }
}
