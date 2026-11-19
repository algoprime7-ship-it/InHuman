package ai.inhuman.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

// =====================================================================
// Wake listener: the trigger phrase from Configurations ("hey jarvis") OR just a face name:
// "Jarvis", "Friday", "Matrix", "Him", "Aether", "Specter", "Ultron", "Sentinel" (with or without "hey").
// "Friday play Drake" in one breath → onWake("play drake"): the command runs straight away.
// Runs fully offline with Vosk. The small English model (~40 MB) downloads once on first start.
// =====================================================================
class WakeWord(
    private val ctx: Context,
    private val onWake: (String, String) -> Unit,      // (command said after the name, face name heard or "")
    private val onStatus: (String) -> Unit
) {
    companion object {
        const val MODEL_NAME = "vosk-model-small-en-us-0.15"
        const val MODEL_URL = "https://alphacephei.com/vosk/models/$MODEL_NAME.zip"
        fun modelDir(c: Context) = File(c.filesDir, MODEL_NAME)
        fun isModelReady(c: Context) = File(modelDir(c), "am").exists() && File(modelDir(c), "conf").exists()
        @Volatile private var model: Model? = null
        @Volatile private var downloading = false

        /** Face names and how the offline model may hear them */
        val NAMES: Map<String, List<String>> = mapOf(
            "jarvis" to listOf("jarvis", "jarvas", "jervis", "travis", "jarvi"),
            "friday" to listOf("friday", "fryday"),
            "matrix" to listOf("matrix", "metrics", "matrics"),
            "aether" to listOf("aether", "ether", "either", "ither"),
            "specter" to listOf("specter", "spectre", "spector", "spectra"),
            "ultron" to listOf("ultron", "ultra", "altron", "ultrons"),
            "sentinel" to listOf("sentinel", "sentinal", "centennial"),
            "him" to listOf("him")
        )
    }

    private val main = Handler(Looper.getMainLooper())
    private var service: SpeechService? = null
    private var phrase = ""
    @Volatile private var wanted = false
    private var pendingSince = 0L          // a name was heard in a partial result: wait for the rest of the sentence
    private val firePending = Runnable { if (pendingSince > 0) fire("") }
    private var retries = 0
    private var heardFace = ""

    fun start() {
        wanted = true
        phrase = Text.norm(Prefs.trigger(ctx))
        if (!isModelReady(ctx)) {
            download()
            return
        }
        Thread {
            try {
                if (model == null) {
                    LibVosk.setLogLevel(LogLevel.WARNINGS)
                    model = Model(modelDir(ctx).absolutePath)
                }
                main.post { if (wanted) listen() }
            } catch (e: Exception) {
                // a broken download: delete it and fetch again
                modelDir(ctx).deleteRecursively()
                model = null
                main.post { onStatus("Voice trigger is being set up again…"); if (wanted) download() }
            }
        }.start()
    }

    private fun listen() {
        val m = model ?: return
        if (service != null) return
        try {
            val rec = Recognizer(m, 16000.0f)
            val s = SpeechService(rec, 16000.0f)
            s.startListening(object : RecognitionListener {
                override fun onPartialResult(hypothesis: String?) = check(hypothesis, "partial")
                override fun onResult(hypothesis: String?) = check(hypothesis, "text")
                override fun onFinalResult(hypothesis: String?) = check(hypothesis, "text")
                override fun onError(exception: Exception?) {
                    stop()
                    main.postDelayed({ if (wanted) listen() }, 2000)
                }
                override fun onTimeout() {}
            })
            service = s
        } catch (e: Exception) {
            onStatus("Microphone busy: ${e.message}")
        }
    }

    private fun check(hypothesis: String?, field: String) {
        val heard = try { Text.norm(JSONObject(hypothesis ?: "{}").optString(field)) } catch (e: Exception) { "" }
        if (heard.isEmpty()) return
        val isFinal = field == "text"
        val at = wakeAt(heard, isFinal)
        if (at < 0) {
            if (isFinal && pendingSince > 0) fire("")       // the name was in a partial; sentence ended without it
            return
        }
        if (!isFinal) {
            // heard the name: give the user ~1.2 s to keep talking ("Friday, play Drake")
            if (pendingSince == 0L) {
                pendingSince = System.currentTimeMillis()
                main.postDelayed(firePending, 1300)
            }
            return
        }
        val words = heard.split(" ")
        val rest = words.drop(at).joinToString(" ").trim()
        fire(if (rest.split(" ").size >= 2) rest else "")
    }

    private fun fire(command: String) {
        main.removeCallbacks(firePending)
        pendingSince = 0L
        stop()
        val f = heardFace
        heardFace = ""
        main.post { onWake(command, f) }
    }

    /** Index just after the wake words in [heard], or -1 */
    private fun wakeAt(heard: String, isFinal: Boolean): Int {
        val words = heard.split(" ")
        // 1. the trigger phrase from Configurations
        if (phrase.isNotEmpty()) {
            val n = phrase.split(" ").size
            for (i in 0..maxOf(0, words.size - n)) {
                val w = words.subList(i, minOf(words.size, i + n)).joinToString(" ")
                if (w == phrase || Text.similar(w, phrase) >= 0.78 || Text.similar(w.replace(" ", ""), phrase.replace(" ", "")) >= 0.8) { heardFace = ""; return i + n }
            }
        }
        // 2. rare names (Jarvis, Ultron, Sentinel, Specter) anywhere in the sentence
        for (i in words.indices) {
            for (name in listOf("jarvis", "ultron", "sentinel", "specter")) {
                val sounds = NAMES[name] ?: continue
                if (sounds.any { it == words[i] && it.length >= 5 } || (words[i].length >= 5 && Text.similar(words[i], name) >= 0.84)) { heardFace = name; return i + 1 }
            }
        }
        // 3. everyday-word names (Friday, Matrix, Aether, Him) only at the start ("hey friday …", "friday …")
        val skip = if (words.firstOrNull() in listOf("hey", "hi", "ok", "okay", "yo", "a")) 1 else 0
        if (words.size <= skip) return -1
        val first = words[skip]
        for ((name, sounds) in NAMES) {
            val hit = sounds.any { it == first } || (first.length >= 5 && Text.similar(first, name) >= 0.8)
            if (!hit) continue
            // "him" and "either" are everyday words: only alone or with "hey"
            if (name == "him" || name == "aether" && first == "either") {
                if (!(skip == 1 || (isFinal && words.size == 1))) continue
            }
            heardFace = name
            return skip + 1
        }
        return -1
    }

    /** Get the microphone again (Android only gives it while the app is on screen after a restart) */
    fun restart() {
        stop()
        if (wanted) main.postDelayed({ if (wanted) listen() }, 300)
    }

    fun stop() {
        main.removeCallbacks(firePending)
        pendingSince = 0L
        try { service?.stop() } catch (e: Exception) {}
        try { service?.shutdown() } catch (e: Exception) {}
        service = null
    }

    /** Free the microphone (while we listen for the command or record). */
    fun pause() {
        wanted = false
        stop()
    }

    fun resume() {
        wanted = true
        if (model != null) listen() else start()
    }

    private fun download() {
        if (downloading) return
        downloading = true
        main.post { onStatus("Downloading voice trigger (about 40 MB, one time only)…") }
        Thread {
            try {
                val conn = URL(MODEL_URL).openConnection() as HttpURLConnection
                conn.connectTimeout = 20000
                conn.readTimeout = 60000
                val total = conn.contentLength.toLong()
                var read = 0L
                var lastPct = -1
                val tmp = File(ctx.filesDir, "model_tmp")
                tmp.deleteRecursively()
                tmp.mkdirs()
                ZipInputStream(object : java.io.FilterInputStream(conn.inputStream) {
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        val r = super.read(b, off, len)
                        if (r > 0) {
                            read += r
                            val pct = if (total > 0) (read * 100 / total).toInt() else -1
                            if (pct >= 0 && pct / 10 != lastPct / 10) {
                                lastPct = pct
                                main.post { onStatus("Downloading voice trigger… $pct%") }
                            }
                        }
                        return r
                    }
                }).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        val out = File(tmp, entry.name)
                        if (!out.canonicalPath.startsWith(tmp.canonicalPath)) throw SecurityException("bad zip")
                        if (entry.isDirectory) out.mkdirs() else {
                            out.parentFile?.mkdirs()
                            FileOutputStream(out).use { zip.copyTo(it) }
                        }
                        entry = zip.nextEntry
                    }
                }
                val unpacked = File(tmp, MODEL_NAME)
                modelDir(ctx).deleteRecursively()
                if (!unpacked.renameTo(modelDir(ctx))) throw IllegalStateException("could not save model")
                tmp.deleteRecursively()
                downloading = false
                main.post {
                    onStatus("Voice trigger ready. Say a face name like \"Friday\" or \"${Prefs.trigger(ctx)}\".")
                    if (wanted) start()
                }
            } catch (e: Exception) {
                downloading = false
                retries++
                main.post {
                    onStatus("Voice trigger download failed (${e.message}). Retrying in ${if (retries < 4) 30 else 120} seconds…")
                    main.postDelayed({ if (wanted && !isModelReady(ctx)) download() }, if (retries < 4) 30000L else 120000L)
                }
            }
        }.start()
    }

    fun release() {
        pause()
    }
}
