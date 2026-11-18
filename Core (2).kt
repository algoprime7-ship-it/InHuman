package ai.inhuman.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.telephony.TelephonyManager
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

fun Context.hasPerm(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
fun Context.hasMic() = hasPerm(Manifest.permission.RECORD_AUDIO)

// =====================================================================
// Saved settings
// =====================================================================
object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("inhuman", Context.MODE_PRIVATE)
    fun str(c: Context, key: String, def: String = ""): String = (sp(c).getString(key, def) ?: def).trim()
    fun setStr(c: Context, key: String, v: String) = sp(c).edit().putString(key, v.trim()).apply()

    // Setup
    fun serverUrl(c: Context): String = str(c, "server").trimEnd('/')
    fun setServerUrl(c: Context, v: String) = setStr(c, "server", v.trim().trimEnd('/'))
    fun overlayEnabled(c: Context): Boolean = sp(c).getBoolean("overlay", true)
    fun setOverlayEnabled(c: Context, v: Boolean) = sp(c).edit().putBoolean("overlay", v).apply()
    fun posX(c: Context, def: Int): Int = sp(c).getInt("x", def)
    fun posY(c: Context, def: Int): Int = sp(c).getInt("y", def)
    fun savePos(c: Context, x: Int, y: Int) = sp(c).edit().putInt("x", x).putInt("y", y).apply()

    // Configurations
    fun trigger(c: Context) = str(c, "trigger", "hey jarvis").ifBlank { "hey jarvis" }
    fun replyPhrase(c: Context) = str(c, "reply", "Yes sir").ifBlank { "Yes sir" }
    fun ownerName(c: Context) = str(c, "owner")
    /** Ultron Mode: cold, menacing personality + deep voice (toggled in the app's sidebar) */
    fun ultron(c: Context) = str(c, "mode") == "ultron"

    // Emails: [{label,type,address,pass,host,port}]   Groups: [{name, emails}]
    fun emails(c: Context): JSONArray = try { JSONArray(str(c, "emails", "[]")) } catch (e: Exception) { JSONArray() }
    fun setEmails(c: Context, a: JSONArray) = setStr(c, "emails", a.toString())
    fun groups(c: Context): JSONArray = try { JSONArray(str(c, "groups", "[]")) } catch (e: Exception) { JSONArray() }
    fun setGroups(c: Context, a: JSONArray) = setStr(c, "groups", a.toString())

    // Connect Apps
    fun key(c: Context, name: String) = str(c, "key_$name")
    fun setKey(c: Context, name: String, v: String) = setStr(c, "key_$name", v)

    // MT5 bridge
    fun mt5(c: Context, name: String, def: String = "") = str(c, "mt5_$name", def)
    fun setMt5(c: Context, name: String, v: String) = setStr(c, "mt5_$name", v)

    // Recently played (newest first, max 50)
    fun recent(c: Context): JSONArray = try { JSONArray(str(c, "recent", "[]")) } catch (e: Exception) { JSONArray() }
    fun addRecent(c: Context, track: JSONObject) {
        val old = recent(c)
        val out = JSONArray().put(track)
        for (i in 0 until old.length()) {
            val t = old.optJSONObject(i) ?: continue
            if (t.optString("title").equals(track.optString("title"), true)) continue
            if (out.length() >= 50) break
            out.put(t)
        }
        setStr(c, "recent", out.toString())
    }
}

// =====================================================================
// Small helpers
// =====================================================================
object Net {
    /** Returns (httpCode, body). Never throws for HTTP errors; throws for no connection. */
    fun request(url: String, body: JSONObject? = null, headers: Map<String, String> = emptyMap(), timeoutMs: Int = 30000): Pair<Int, String> {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = timeoutMs
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        if (body != null) {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        }
        val code = conn.responseCode
        val stream = if (code >= 400) conn.errorStream else conn.inputStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
        return code to text
    }

    fun json(url: String, body: JSONObject? = null, headers: Map<String, String> = emptyMap(), timeoutMs: Int = 30000): JSONObject {
        val (_, text) = request(url, body, headers, timeoutMs)
        return try { JSONObject(text) } catch (e: Exception) { JSONObject().put("error", text.take(200)) }
    }
}

object Text {
    fun norm(s: String) = s.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()

    /** 0..1 similarity (1 = same) */
    fun similar(a: String, b: String): Double {
        if (a.isEmpty() && b.isEmpty()) return 1.0
        val d = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = d[0]
            d[0] = i
            for (j in 1..b.length) {
                val tmp = d[j]
                d[j] = minOf(d[j] + 1, d[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = tmp
            }
        }
        return 1.0 - d[b.length].toDouble() / maxOf(a.length, b.length)
    }
}

// =====================================================================
// Device type & name
// =====================================================================
object DeviceInfo {
    fun json(c: Context): JSONObject {
        val cr = c.contentResolver
        val userName = try { Settings.Global.getString(cr, Settings.Global.DEVICE_NAME) } catch (e: Exception) { null }
            ?: try { Settings.Secure.getString(cr, "bluetooth_name") } catch (e: Exception) { null }
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
// Contacts: saved name → phone number / email
// =====================================================================
object Contacts {
    private fun score(name: String, wanted: String): Int {
        val n = Text.norm(name)
        val w = Text.norm(wanted)
        if (n.isEmpty() || w.isEmpty()) return 0
        return when {
            n == w -> 100
            n.startsWith("$w ") || n.startsWith(w) -> 80
            n.split(" ").any { it == w } -> 70
            n.contains(w) -> 55
            Text.similar(n, w) >= 0.75 -> 45
            n.split(" ").any { Text.similar(it, w) >= 0.8 } -> 40
            else -> 0
        }
    }

    /** Returns (display name, phone number) or null */
    fun findPhone(c: Context, wanted: String): Pair<String, String>? {
        if (!c.hasPerm(Manifest.permission.READ_CONTACTS)) return null
        var best: Pair<String, String>? = null
        var bestScore = 0
        c.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            null, null, null
        )?.use { cur ->
            while (cur.moveToNext()) {
                val name = cur.getString(0) ?: continue
                val number = cur.getString(1) ?: continue
                val s = score(name, wanted)
                if (s > bestScore) { bestScore = s; best = name to number }
            }
        }
        return best
    }

    fun findEmail(c: Context, wanted: String): String? {
        if (!c.hasPerm(Manifest.permission.READ_CONTACTS)) return null
        var best: String? = null
        var bestScore = 0
        c.contentResolver.query(
            ContactsContract.CommonDataKinds.Email.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Email.DISPLAY_NAME_PRIMARY, ContactsContract.CommonDataKinds.Email.ADDRESS),
            null, null, null
        )?.use { cur ->
            while (cur.moveToNext()) {
                val name = cur.getString(0) ?: continue
                val addr = cur.getString(1) ?: continue
                val s = score(name, wanted)
                if (s > bestScore) { bestScore = s; best = addr }
            }
        }
        return best
    }

    private val CODES = mapOf(
        "ZA" to "27", "US" to "1", "CA" to "1", "GB" to "44", "NG" to "234", "KE" to "254", "GH" to "233", "ZW" to "263",
        "BW" to "267", "NA" to "264", "ZM" to "260", "MZ" to "258", "LS" to "266", "SZ" to "268", "MW" to "265", "TZ" to "255",
        "UG" to "256", "RW" to "250", "ET" to "251", "EG" to "20", "MA" to "212", "IN" to "91", "PK" to "92", "BD" to "880",
        "AE" to "971", "SA" to "966", "AU" to "61", "NZ" to "64", "DE" to "49", "FR" to "33", "IT" to "39", "ES" to "34",
        "PT" to "351", "NL" to "31", "BE" to "32", "IE" to "353", "BR" to "55", "MX" to "52", "PH" to "63", "ID" to "62",
        "MY" to "60", "SG" to "65", "CN" to "86", "JP" to "81", "KR" to "82", "TR" to "90", "RU" to "7"
    )

    /** "082 123 4567" → "27821234567" (WhatsApp format: country code, no +) */
    fun international(c: Context, raw: String): String {
        val t = raw.trim()
        val digits = t.filter { it.isDigit() }
        if (t.startsWith("+")) return digits
        if (digits.startsWith("00")) return digits.drop(2)
        if (digits.startsWith("0")) {
            val tm = c.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            val sim = tm?.simCountryIso ?: ""
            val iso = (if (sim.isNotBlank()) sim else (tm?.networkCountryIso ?: "")).uppercase(Locale.ROOT)
            val cc = CODES[iso] ?: return digits
            return cc + digits.drop(1)
        }
        return digits
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
            val male = gb.firstOrNull { v -> listOf("gbd", "rjs", "male").any { v.name.lowercase().contains(it) } }
            (male ?: gb.firstOrNull())?.let { t.voice = it }
        } catch (e: Exception) { /* keep default voice */ }
    }

    private val CUE = Regex("\\[(laugh|pause|hmph|sigh)]", RegexOption.IGNORE_CASE)

    /** Text to show on screen: "[laugh]" → "(laughs)", "[pause]" → "…" */
    fun display(text: String): String = text
        .replace(Regex("\\[laugh]", RegexOption.IGNORE_CASE), "(laughs)")
        .replace(Regex("\\[pause]", RegexOption.IGNORE_CASE), "…")
        .replace(Regex("\\[(hmph|sigh)]", RegexOption.IGNORE_CASE), "Hmph.")
        .replace(Regex("\\s+"), " ").trim()

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
            val pauses = CUE.findAll(text).count { it.value.equals("[pause]", true) }
            main.postDelayed({ callbacks.remove(id); finish() }, maxOf(3500L, text.length * 95L) + pauses * 4000L)
        }
        t.setPitch(if (angry) 0.55f else 0.88f)
        t.setSpeechRate(if (angry) 0.9f else 0.97f)
        if (!CUE.containsMatchIn(text)) {
            t.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
            return
        }
        // Ultron cues: [laugh] [pause] (4 s of silence) [hmph]
        val parts = mutableListOf<String>()
        var last = 0
        CUE.findAll(text).forEach { m ->
            text.substring(last, m.range.first).trim().takeIf { it.isNotEmpty() }?.let { parts.add(it) }
            parts.add(m.value.lowercase())
            last = m.range.last + 1
        }
        text.substring(last).trim().takeIf { it.isNotEmpty() }?.let { parts.add(it) }
        parts.forEachIndexed { i, part ->
            val mode = if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            val uid = if (i == parts.size - 1) id else "$id-$i"
            when (part) {
                "[pause]" -> t.playSilentUtterance(4000, mode, uid)
                "[laugh]" -> t.speak("Ha. Ha ha.", mode, null, uid)
                "[hmph]", "[sigh]" -> t.speak("Hmmph.", mode, null, uid)
                else -> t.speak(part, mode, null, uid)
            }
        }
    }

    fun stop() {
        try { tts?.stop() } catch (e: Exception) {}
    }
}

// =====================================================================
// Ears: one-shot speech recognition for the command after the trigger (main thread only)
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
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1000L)
        try {
            sr.startListening(intent)
        } catch (e: Exception) {
            finish(null)
        }
        Handler(Looper.getMainLooper()).postDelayed({ finish(null) }, 15000)
    }
}
