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
// Trigger phrase listener ("hey jarvis", "hey parvis" or anything set in Configurations).
// Runs fully offline with Vosk. The small English model (~40 MB) downloads once on first start.
// =====================================================================
class WakeWord(
    private val ctx: Context,
    private val onWake: () -> Unit,
    private val onStatus: (String) -> Unit
) {
    companion object {
        const val MODEL_NAME = "vosk-model-small-en-us-0.15"
        const val MODEL_URL = "https://alphacephei.com/vosk/models/$MODEL_NAME.zip"
        fun modelDir(c: Context) = File(c.filesDir, MODEL_NAME)
        fun isModelReady(c: Context) = File(modelDir(c), "am").exists() && File(modelDir(c), "conf").exists()
        @Volatile private var model: Model? = null
        @Volatile private var downloading = false
    }

    private val main = Handler(Looper.getMainLooper())
    private var service: SpeechService? = null
    private var phrase = ""
    @Volatile private var wanted = false

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
                main.post { onStatus("Voice trigger failed to load: ${e.message}. Tap the circle to talk.") }
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
        if (heard.isEmpty() || phrase.isEmpty()) return
        if (matches(heard)) {
            stop()
            main.post(onWake)
        }
    }

    /** Fuzzy match so "hey parvis" still works if heard as "hey jarvis" / "a par vis" */
    private fun matches(heard: String): Boolean {
        if (heard.contains(phrase)) return true
        val target = phrase.split(" ")
        val words = heard.split(" ")
        val n = target.size
        val joinedTarget = phrase.replace(" ", "")
        for (len in maxOf(1, n - 1)..(n + 1)) {
            if (len > words.size) continue
            for (i in 0..(words.size - len)) {
                val window = words.subList(i, i + len)
                if (Text.similar(window.joinToString(" "), phrase) >= 0.78) return true
                if (Text.similar(window.joinToString(""), joinedTarget) >= 0.8) return true
            }
        }
        return false
    }

    fun stop() {
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
                    onStatus("Voice trigger ready. Say \"${Prefs.trigger(ctx)}\".")
                    if (wanted) start()
                }
            } catch (e: Exception) {
                downloading = false
                main.post { onStatus("Voice trigger download failed (${e.message}). Tap the circle to talk; I'll retry next start.") }
            }
        }.start()
    }

    fun release() {
        pause()
    }
}
