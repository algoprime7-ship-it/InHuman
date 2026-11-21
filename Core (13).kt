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
    /** The main site (it has the database, email, AI and voice settings). The old second project has none. */
    const val MAIN_SITE = "https://inhuman56.vercel.app"
    fun serverUrl(c: Context): String = str(c, "server").trimEnd('/').let { if (it.isBlank() || it.contains("inhumanxyz56")) MAIN_SITE else it }
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
    /** Chosen face/mode: aether, jarvis, specter, matrix, ultron, sentinel, friday */
    fun face(c: Context): String = str(c, "mode").let { if (it in FACES) it else if (it == "ultron") "ultron" else "aether" }
    val FACES = listOf("aether", "jarvis", "specter", "matrix", "ultron", "him", "sentinel", "friday")
    /** Ultron and Sentinel use the cold, ruthless voice */
    fun ultron(c: Context) = face(c) in listOf("ultron", "him", "sentinel")

    /** Same id for the app and the floating circle, so free limits are counted once per phone */
    fun deviceId(c: Context): String {
        var id = str(c, "device_id")
        if (id.isBlank()) {
            id = "and-" + UUID.randomUUID().toString().replace("-", "").take(20)
            setStr(c, "device_id", id)
        }
        return id
    }

    /** Signed-up account (free or paid) — set by the app's sign-up screen */
    fun account(c: Context) = str(c, "account")

    fun bool(c: Context, key: String, def: Boolean = false): Boolean = sp(c).getBoolean(key, def)
    fun setBool(c: Context, key: String, v: Boolean) = sp(c).edit().putBoolean(key, v).apply()
    fun long(c: Context, key: String, def: Long = 0L): Long = sp(c).getLong(key, def)
    fun setLong(c: Context, key: String, v: Long) = sp(c).edit().putLong(key, v).apply()

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
            .apply {
                try {
                    val bm = c.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
                    put("battery", bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY))
                    put("charging", bm.isCharging)
                } catch (e: Exception) {}
            }
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
    @Volatile var face = "aether"              // whose voice: each face sounds different
    var femaleMode: Boolean
        get() = face == "friday"
        set(v) { if (v) face = "friday" else if (face == "friday") face = "aether" }
    private var app: Context? = null

    /** pitch, speed: Jarvis/Aether butler, Ultron & Him old sage, Matrix announcer, Sentinel Lich-King deep, Specter raspy */
    private val PROFILE = mapOf(
        "aether" to Pair(0.88f, 0.97f),
        "jarvis" to Pair(0.92f, 1.0f),
        "ultron" to Pair(0.55f, 0.86f),
        "him" to Pair(0.6f, 0.84f),
        "matrix" to Pair(0.72f, 1.06f),
        "sentinel" to Pair(0.38f, 0.8f),
        "specter" to Pair(0.66f, 1.1f),
        "friday" to Pair(1.05f, 1.03f)
    )
    private var maleVoice: android.speech.tts.Voice? = null
    private var femaleVoice: android.speech.tts.Voice? = null
    private var initDone = false
    private val main = Handler(Looper.getMainLooper())
    private val callbacks = ConcurrentHashMap<String, () -> Unit>()
    private val queued = mutableListOf<Triple<String, Boolean, (() -> Unit)?>>()

    fun init(c: Context) {
        face = Prefs.face(c)
        app = c.applicationContext
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
            maleVoice = male ?: gb.firstOrNull()
            femaleVoice = gb.firstOrNull { v -> listOf("gba", "gbc", "gbg", "female").any { v.name.lowercase().contains(it) } && v != maleVoice }
            maleVoice?.let { t.voice = it }
        } catch (e: Exception) { /* keep default voice */ }
    }

    private val CUE = Regex("\\[(laugh|pause|hmph|sigh)]", RegexOption.IGNORE_CASE)

    /** Text to show on screen: "[laugh]" → "(laughs)", "[pause]" → "…" */
    fun display(text: String): String = text
        .replace(Regex("\\[laugh]", RegexOption.IGNORE_CASE), "(laughs)")
        .replace(Regex("\\[pause]", RegexOption.IGNORE_CASE), "…")
        .replace(Regex("\\[(hmph|sigh)]", RegexOption.IGNORE_CASE), "Hmph.")
        .replace(Regex("\\s+"), " ").trim()

    // ---------------- cloned voices from your voice server (droplet) through <site>/api/tts ----------------
    @Volatile var cloud = false
    @Volatile private var cloudGen = 0
    @Volatile private var cloudFailUntil = 0L
    @Volatile private var cloudPlayer: android.media.MediaPlayer? = null
    @Volatile private var cloudLatch: java.util.concurrent.CountDownLatch? = null
    private val pool = java.util.concurrent.Executors.newCachedThreadPool()

    /** InHuman's own music player: quieter while the assistant talks or listens (set by the floating circle) */
    @Volatile var duckListener: ((Boolean) -> Unit)? = null
    private var duckFocus: android.media.AudioFocusRequest? = null
    private val ducks = java.util.concurrent.atomic.AtomicInteger(0)

    private fun duck(on: Boolean) {
        val c = app ?: return
        val am = c.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        if (on) {
            if (ducks.incrementAndGet() > 1) return
            val f = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .build()
            duckFocus = f
            try { am.requestAudioFocus(f) } catch (e: Exception) {}       // YouTube & other apps get quieter, not off
            main.post { duckListener?.invoke(true) }
        } else {
            if (ducks.decrementAndGet() > 0) return
            ducks.set(0)
            duckFocus?.let { try { am.abandonAudioFocusRequest(it) } catch (e: Exception) {} }
            duckFocus = null
            main.post { duckListener?.invoke(false) }
        }
    }

    /**
     * Speak. [onStart] runs the moment the voice actually starts (so text on screen and voice line up).
     * [asFace] speaks one line in another face's voice (Ultron cutting in).
     */
    fun speak(text: String, angry: Boolean = false, onDone: (() -> Unit)? = null, onStart: (() -> Unit)? = null, asFace: String? = null) {
        val c = app
        val released = java.util.concurrent.atomic.AtomicBoolean(false)
        duck(true)
        val done: () -> Unit = { if (released.compareAndSet(false, true)) duck(false); onDone?.invoke() }
        if (cloud && c != null && text.isNotBlank() && System.currentTimeMillis() > cloudFailUntil) {
            cloudSpeak(c, text, angry, done, onStart, asFace ?: face)
            return
        }
        onStart?.let { main.post(it) }
        if (asFace != null && asFace != face) {
            val keep = face
            face = asFace
            speakDevice(text, angry, done)
            main.postDelayed({ face = keep }, 300)
            return
        }
        speakDevice(text, angry, done)
    }

    /** A quick first piece (your voice starts fast), then the rest as ONE piece where the inner full stops
     *  become commas: no long gaps between sentences, only a full stop at the very end. */
    private fun cloudChunks(text: String): List<String> {
        // the whole reply as ONE piece: commas inside, a full stop only at the very end (no gaps between sentences)
        val clean = text.replace(CUE, " ").replace(Regex("\\s+"), " ").trim()
        return if (clean.isEmpty()) emptyList() else splitLong(softStops(clean))
    }

    /** "Done. Anything else?" → "Done, Anything else?" (the very last mark stays) */
    private fun softStops(t: String): String {
        val body = t.trimEnd()
        val end = Regex("[.!?…]+[\"')\\]]*$").find(body)?.value ?: ""
        val core = body.substring(0, body.length - end.length).replace(Regex("[.!?…;:]+([\"')\\]]*)\\s+(?=\\S)"), ",$1 ")
        return core + end.ifEmpty { "." }
    }

    private fun splitLong(t: String): List<String> {
        val out = mutableListOf<String>()
        var s = t
        while (s.length > 280) {
            var cut = s.lastIndexOf(", ", 260)
            if (cut < 80) cut = s.lastIndexOf(' ', 260)
            if (cut <= 0) cut = 260
            out.add(s.substring(0, cut + 1).trim())
            s = s.substring(cut + 1).trim()
        }
        if (s.isNotEmpty()) out.add(s)
        return out
    }

    private fun fetchCloud(c: Context, text: String, face: String): java.io.File? = try {
        val con = java.net.URL("${Prefs.serverUrl(c)}/api/tts").openConnection() as java.net.HttpURLConnection
        con.requestMethod = "POST"
        con.connectTimeout = 10000
        con.readTimeout = 55000
        con.doOutput = true
        con.setRequestProperty("Content-Type", "application/json")
        con.outputStream.use { it.write(JSONObject().put("text", text).put("face", face).toString().toByteArray()) }
        if (con.responseCode != 200 || !(con.contentType ?: "").contains("audio")) null
        else {
            val f = java.io.File.createTempFile("voice", ".mp3", c.cacheDir)
            con.inputStream.use { inp -> f.outputStream().use { inp.copyTo(it) } }
            f
        }
    } catch (e: Exception) { null }

    private fun cloudSpeak(c: Context, text: String, angry: Boolean, onDone: (() -> Unit)?, onStart: (() -> Unit)?, who: String) {
        val gen = ++cloudGen
        try { tts?.stop() } catch (e: Exception) {}
        val chunks = cloudChunks(text)
        if (chunks.isEmpty()) { onDone?.let { main.post(it) }; return }
        pool.execute {
            var next = pool.submit<java.io.File?> { fetchCloud(c, chunks[0], who) }
            for (i in chunks.indices) {
                // always wait for YOUR voice; the phone's voice is only used if the server is really down
                val first = try { next.get(65L, java.util.concurrent.TimeUnit.SECONDS) } catch (e: Exception) { next.cancel(true); null }
                val file = first ?: if (gen == cloudGen) fetchCloud(c, chunks[i], who) else null     // one retry
                if (gen != cloudGen) { file?.delete(); return@execute }
                if (file == null) {
                    // voice server really down: say the rest with the phone's voice, try the server again in 15 s
                    cloudFailUntil = System.currentTimeMillis() + 15000
                    val rest = chunks.drop(i).joinToString(" ")
                    main.post { if (i == 0) onStart?.invoke(); speakDevice(rest, angry, onDone) }
                    return@execute
                }
                if (i + 1 < chunks.size) next = pool.submit<java.io.File?> { fetchCloud(c, chunks[i + 1], who) }
                val latch = java.util.concurrent.CountDownLatch(1)
                cloudLatch = latch
                val holder = java.util.concurrent.atomic.AtomicReference<android.media.MediaPlayer?>(null)
                main.post {
                    if (gen != cloudGen) { latch.countDown(); return@post }
                    val mp = android.media.MediaPlayer()
                    holder.set(mp)
                    cloudPlayer = mp
                    try {
                        mp.setAudioAttributes(android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_ASSISTANT)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build())
                        mp.setDataSource(file.absolutePath)
                        mp.setOnCompletionListener { latch.countDown() }
                        mp.setOnErrorListener { _, _, _ -> latch.countDown(); true }
                        mp.prepare()
                        if (i == 0) onStart?.invoke()
                        mp.start()
                    } catch (e: Exception) { latch.countDown() }
                }
                latch.await(90, java.util.concurrent.TimeUnit.SECONDS)
                main.post {
                    val mp = holder.get()
                    try { mp?.release() } catch (e: Exception) {}
                    if (cloudPlayer === mp) cloudPlayer = null
                }
                file.delete()
                if (gen != cloudGen) return@execute
            }
            onDone?.let { main.post(it) }
        }
    }

    private fun speakDevice(text: String, angry: Boolean = false, onDone: (() -> Unit)? = null) {
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
        val friday = face == "friday" && !angry
        try { (if (friday) femaleVoice ?: maleVoice else maleVoice)?.let { if (t.voice != it) t.voice = it } } catch (e: Exception) {}
        val (pitch, rate) = PROFILE[face] ?: Pair(0.88f, 0.97f)
        val ruthless = face in listOf("ultron", "him", "sentinel")
        t.setPitch(if (angry && !ruthless) 0.55f else if (friday && femaleVoice == null) 1.4f else pitch)
        t.setSpeechRate(if (angry && !ruthless) 0.9f else rate)
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
                "[laugh]" -> if (Sfx.has("laugh")) { Sfx.play(app, "laugh"); t.playSilentUtterance(1800, mode, uid) } else t.speak("Ha. Ha ha.", mode, null, uid)
                "[hmph]", "[sigh]" -> t.speak("Hmmph.", mode, null, uid)
                else -> t.speak(part, mode, null, uid)
            }
        }
    }

    fun stop() {
        try { tts?.stop() } catch (e: Exception) {}
        cloudGen++
        cloudPlayer?.let { p -> main.post { try { p.stop() } catch (e: Exception) {} } }
        cloudLatch?.countDown()
        Sfx.stop()
    }

    /**
     * A fixed line: plays your recorded clip <site>/voices/<face>/<key>.mp3 (made on nicevoice.org)
     * when you uploaded one, otherwise speaks [text] with this face's voice.
     */
    fun line(c: Context, key: String, text: String, angry: Boolean = false, onDone: (() -> Unit)? = null) {
        val url = "${Prefs.serverUrl(c)}/voices/$face/$key.mp3"
        Sfx.playUrl(c, url) { played -> if (!played) speak(text, angry, onDone) else onDone?.let { main.post(it) } }
    }
}

// =====================================================================
// Sound effects and recorded voice clips from the website (/sounds/laugh.mp3, /voices/jarvis/greet_morning.mp3)
// Missing files are remembered so we don't try them again.
// =====================================================================
object Sfx {
    private val main = Handler(Looper.getMainLooper())
    private val missing = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private val present = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private var player: android.media.MediaPlayer? = null

    fun has(name: String) = present.any { it.endsWith("/sounds/$name.mp3") }

    fun play(c: Context?, name: String, onDone: ((Boolean) -> Unit)? = null) {
        if (c == null) { onDone?.invoke(false); return }
        playUrl(c, "${Prefs.serverUrl(c)}/sounds/$name.mp3") { ok ->
            // no faah.mp3 uploaded yet: Sentinel "hacks" the sound with his own voice
            if (!ok && name == "faah") Voice.speak("Faaaaah!", false, onDone = { onDone?.invoke(true) }, asFace = "sentinel")
            else onDone?.invoke(ok)
        }
    }

    /** Learn which sound effects exist (called once when the assistant starts) */
    fun warmUp(c: Context) {
        val base = Prefs.serverUrl(c)
        if (base.isBlank()) return
        Thread {
            listOf("laugh", "faah").forEach { n ->
                val url = "$base/sounds/$n.mp3"
                try {
                    val con = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                    con.requestMethod = "HEAD"; con.connectTimeout = 6000; con.readTimeout = 6000
                    if (con.responseCode in 200..299 && (con.contentType ?: "").let { it.startsWith("audio") || it.contains("mpeg") || it.contains("octet") }) present.add(url) else missing.add(url)
                    con.disconnect()
                } catch (e: Exception) {}
            }
        }.start()
    }

    fun playUrl(c: Context, url: String, onDone: ((Boolean) -> Unit)? = null) {
        if (url.startsWith("/") || url in missing) { onDone?.invoke(false); return }
        main.post {
            stop()
            val mp = android.media.MediaPlayer()
            player = mp
            var finished = false
            fun finish(ok: Boolean) {
                if (finished) return
                finished = true
                try { mp.release() } catch (e: Exception) {}
                if (player === mp) player = null
                onDone?.invoke(ok)
            }
            try {
                mp.setAudioAttributes(android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build())
                mp.setDataSource(url)
                mp.setOnPreparedListener { present.add(url); it.start() }
                mp.setOnCompletionListener { finish(true) }
                mp.setOnErrorListener { _, _, _ -> if (!present.contains(url)) missing.add(url); finish(false); true }
                mp.prepareAsync()
                main.postDelayed({ if (!mp.isPlayingSafe() && !finished && !present.contains(url)) { missing.add(url); finish(false) } }, 5000)
            } catch (e: Exception) {
                missing.add(url)
                finish(false)
            }
        }
    }

    private fun android.media.MediaPlayer.isPlayingSafe() = try { isPlaying } catch (e: Exception) { false }

    fun stop() {
        val p = player ?: return
        player = null
        try { p.stop() } catch (e: Exception) {}
        try { p.release() } catch (e: Exception) {}
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
        // while listening: other apps (YouTube, Spotify) pause so music doesn't drown your voice
        val am = c.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        val focus = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_ASSISTANT).build())
            .build()
        try { am.requestAudioFocus(focus) } catch (e: Exception) {}
        Voice.duckListener?.invoke(true)
        fun finish(text: String?) {
            if (done) return
            done = true
            try { sr.destroy() } catch (e: Exception) {}
            try { am.abandonAudioFocusRequest(focus) } catch (e: Exception) {}
            Voice.duckListener?.invoke(false)
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
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, !Brain.online(c))
        try {
            sr.startListening(intent)
        } catch (e: Exception) {
            finish(null)
        }
        Handler(Looper.getMainLooper()).postDelayed({ finish(null) }, 15000)
    }
}
