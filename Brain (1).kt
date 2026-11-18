package ai.inhuman.app

import android.Manifest
import android.app.SearchManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale

// =====================================================================
// Brain: understands commands (instantly or via the AI) and carries them out
// =====================================================================
object Brain {
    private const val YT = "com.google.android.youtube"
    private const val YT_MUSIC = "com.google.android.apps.youtube.music"
    private const val SPOTIFY = "com.spotify.music"
    private val WHATSAPP = listOf("com.whatsapp", "com.whatsapp.w4b")
    private const val INSTAGRAM = "com.instagram.android"
    private val MESSENGER = listOf("com.facebook.orca", "com.facebook.katana")
    private val main = Handler(Looper.getMainLooper())

    class Local(val reply: String, val action: JSONObject)

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
    private fun act(type: String) = JSONObject().put("type", type)

    /** Remove the trigger phrase from the start: "hey jarvis open instagram" → "open instagram" */
    fun cleanCommand(c: Context, text: String): String {
        var t = text.trim()
        val trig = Text.norm(Prefs.trigger(c))
        val words = t.split(Regex("\\s+"))
        val n = trig.split(" ").size
        if (words.size > n && Text.similar(Text.norm(words.take(n).joinToString(" ")), trig) >= 0.7) {
            t = words.drop(n).joinToString(" ")
        }
        return t.replace(Regex("^(hey|hi|ok|okay)?\\s*jarvis[,.!]?\\s*", RegexOption.IGNORE_CASE), "").trim()
    }

    /** Simple commands handled on the phone with no internet. Messages/emails/trades go to the AI. */
    fun localCommand(c: Context, raw: String): Local? {
        val t = cleanCommand(c, raw).lowercase(Locale.ROOT).trimEnd('.', '!', '?')

        when (t) {
            "pause", "pause music", "pause the music", "stop the music", "stop music" ->
                return Local("Paused, sir.", act("music").put("cmd", if (t.startsWith("stop")) "stop" else "pause"))
            "resume", "resume music", "continue", "continue music", "play music" ->
                return Local("Resuming, sir.", act("music").put("cmd", "resume"))
            "next", "next song", "skip", "skip song", "skip this song", "next track" ->
                return Local("Next song, sir.", act("music").put("cmd", "next"))
            "go home", "home", "home screen" -> return Local("Going home, sir.", act("system").put("name", "home"))
            "go back", "back" -> return Local("Going back, sir.", act("system").put("name", "back"))
            "recent apps", "show recent apps" -> return Local("Recent apps, sir.", act("system").put("name", "recents"))
            "open notifications", "show notifications" -> return Local("Your notifications, sir.", act("system").put("name", "notifications"))
            "lock the screen", "lock screen", "lock my phone", "lock the phone" ->
                return Local("Locking the screen, sir.", act("system").put("name", "lock"))
        }

        Regex("^(?:open youtube music and )?(?:play|put on)\\s+(.+?)\\s+(?:on|in)\\s+youtube music$").find(t)?.let {
            return Local("Playing ${it.groupValues[1]} on YouTube Music, sir.", act("youtube_music_play").put("query", it.groupValues[1]))
        }
        Regex("^open youtube music and (?:play|put on)\\s+(.+)$").find(t)?.let {
            return Local("Playing ${it.groupValues[1]} on YouTube Music, sir.", act("youtube_music_play").put("query", it.groupValues[1]))
        }
        Regex("^(?:play|put on)\\s+(.+?)\\s+(?:on|in)\\s+spotify$").find(t)?.let {
            return Local("Playing ${it.groupValues[1]} on Spotify, sir.", act("spotify_play").put("query", it.groupValues[1]))
        }
        Regex("^(?:open youtube and |go to youtube and )?(?:play|put on)\\s+(.+?)\\s+(?:on|in)\\s+youtube$").find(t)?.let {
            return Local("Playing ${it.groupValues[1]} on YouTube, sir.", act("youtube_play").put("query", it.groupValues[1]))
        }
        Regex("^(?:open|go to) youtube and (?:play|put on)\\s+(.+)$").find(t)?.let {
            return Local("Playing ${it.groupValues[1]} on YouTube, sir.", act("youtube_play").put("query", it.groupValues[1]))
        }
        Regex("^(?:open|go to) youtube and search(?: for)?\\s+(.+)$").find(t)?.let {
            return Local("Searching YouTube for ${it.groupValues[1]}, sir.", act("youtube_search").put("query", it.groupValues[1]))
        }
        Regex("^(?:play|put on)\\s+(.+)$").find(t)?.let {
            return Local("Now playing ${it.groupValues[1]}, sir.", act("play_here").put("query", it.groupValues[1]))
        }
        Regex("^call\\s+(.+)$").find(t)?.let {
            if (!t.contains(" on whatsapp")) return Local("Calling ${it.groupValues[1]}, sir.", act("call").put("contact", it.groupValues[1]))
        }
        Regex("^(?:open|launch|start)\\s+(?:the\\s+)?([a-z0-9 ]+?)(?:\\s+app)?$").find(t)?.let {
            return Local("Opening ${it.groupValues[1]}, sir.", act("open_app").put("app", it.groupValues[1]))
        }
        return null
    }

    // ---------------- AI ----------------
    fun contextJson(c: Context): JSONObject {
        val emails = JSONArray()
        val list = Prefs.emails(c)
        for (i in 0 until list.length()) {
            val e = list.optJSONObject(i) ?: continue
            emails.put(JSONObject().put("label", e.optString("label")).put("type", e.optString("type")).put("address", e.optString("address")))
        }
        val groups = JSONArray()
        val g = Prefs.groups(c)
        for (i in 0 until g.length()) g.optJSONObject(i)?.let { groups.put(it.optString("name")) }
        return JSONObject()
            .put("emails", emails)
            .put("groups", groups)
            .put("mt5", Prefs.mt5(c, "token").isNotBlank())
            .put("owner", Prefs.ownerName(c))
    }

    fun keysJson(c: Context): JSONObject = JSONObject()
        .put("gemini", Prefs.key(c, "gemini"))
        .put("claude", Prefs.key(c, "claude"))
        .put("openai", Prefs.key(c, "openai"))
        .put("preferred", Prefs.key(c, "preferred"))

    /** Ask the AI on your Vercel server. cb runs on the main thread. */
    fun ask(c: Context, message: String, history: JSONArray, surface: String, cb: (String, JSONObject?) -> Unit) {
        val app = c.applicationContext
        Thread {
            var reply = "I cannot reach my neural core right now, sir. Please check your connection."
            var action: JSONObject? = null
            try {
                val server = Prefs.serverUrl(app)
                if (server.isEmpty()) {
                    reply = "Please add your InHuman web link in Settings first, sir."
                } else {
                    val body = JSONObject()
                        .put("message", message)
                        .put("history", history)
                        .put("surface", surface)
                        .put("mood", if (Prefs.ultron(app)) "ultron" else "calm")
                        .put("device", DeviceInfo.json(app))
                        .put("context", contextJson(app))
                        .put("keys", keysJson(app))
                        .put("github", JSONObject().put("token", Prefs.key(app, "github")))
                    val res = Net.json("$server/api/ask", body, timeoutMs = 60000)
                    reply = res.optString("reply", reply)
                    action = res.optJSONObject("action")
                }
            } catch (e: Exception) { /* keep the default reply */ }
            val r = reply
            val a = action
            main.post { cb(r, a) }
        }.start()
    }

    // ---------------- doing things ----------------
    /** Carry out an action. say() speaks + shows a short message. */
    fun runAction(c: Context, a: JSONObject, say: (String) -> Unit) {
        val q = a.optString("query")
        when (a.optString("type")) {
            "open_app" -> {
                val app = a.optString("app")
                if (!openApp(c, app)) say("I could not find $app on this phone, sir.")
            }
            "play_here", "play_music" -> {
                val svc = OverlayService.instance
                if (svc != null && q.isNotBlank()) svc.player.playQuery(q) else playOnYouTube(c, q)
            }
            "music" -> {
                val p = OverlayService.instance?.player
                if (p == null || !p.isActive()) say("Nothing is playing on the floating player, sir.")
                else when (a.optString("cmd")) {
                    "pause" -> p.pause()
                    "resume" -> p.resume()
                    "next" -> p.next()
                    "stop" -> p.stop()
                }
            }
            "youtube_play" -> if (q.isNotBlank()) playOnYouTube(c, q) else openApp(c, "YouTube")
            "youtube_search" -> youtubeSearch(c, q)
            "youtube_music_play" -> playFromSearch(c, YT_MUSIC, q, "https://music.youtube.com/search?q=${enc(q)}")
            "spotify_play" -> playFromSearch(c, SPOTIFY, q, "https://open.spotify.com/search/${enc(q)}")
            "whatsapp_message" -> whatsappMessage(c, a.optString("contact"), a.optString("message"), say)
            "whatsapp_voice_note" -> {
                val svc = OverlayService.instance
                if (svc == null) say("Switch on the floating assistant first, sir, then ask me again.")
                else svc.recordVoiceNote(a.optString("contact"))
            }
            "instagram_message" -> socialMessage(c, listOf(INSTAGRAM), "https://ig.me/m/", a.optString("username"), a.optString("message"), "Instagram", say)
            "facebook_message" -> socialMessage(c, MESSENGER, "https://m.me/", a.optString("username"), a.optString("message"), "Messenger", say)
            "call" -> call(c, a.optString("contact"), say)
            "send_email" -> sendEmail(c, a, say)
            "trade" -> trade(c, a, say)
            "web_search" -> openUrl(c, "https://www.google.com/search?q=${enc(q)}")
            "open_url" -> {
                val u = a.optString("url")
                if (u.startsWith("https://") || u.startsWith("http://")) openUrl(c, u)
            }
            "system" -> {
                val name = a.optString("name")
                if (!InHumanAccessibility.perform(name)) {
                    if (name == "home") startSafe(c, Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))
                    else say("Please switch on InHuman AI in Accessibility settings first, sir.")
                }
            }
        }
    }

    // ---------------- apps ----------------
    fun openApp(c: Context, name: String): Boolean {
        val target = Text.norm(name)
        if (target.isEmpty()) return false
        val pm = c.packageManager
        val apps: List<Pair<ResolveInfo, String>> = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it to Text.norm(it.loadLabel(pm).toString()) }
        val best = apps.firstOrNull { it.second == target }
            ?: apps.firstOrNull { it.second.startsWith(target) }
            ?: apps.firstOrNull { it.second.contains(target) }
            ?: apps.firstOrNull { it.second.length > 2 && target.contains(it.second) }
            ?: apps.maxByOrNull { Text.similar(it.second, target) }?.takeIf { Text.similar(it.second, target) >= 0.75 }
            ?: return false
        val info: ResolveInfo = best.first
        val launch = pm.getLaunchIntentForPackage(info.activityInfo.packageName) ?: return false
        return startSafe(c, launch)
    }

    private fun installed(c: Context, pkg: String): Boolean = try {
        c.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (e: Exception) {
        false
    }

    fun playOnYouTube(c: Context, query: String) {
        val app = c.applicationContext
        Thread {
            var videoId: String? = null
            try {
                val server = Prefs.serverUrl(app)
                if (server.isNotEmpty()) {
                    val res = Net.json("$server/api/yt?q=${enc(query)}")
                    videoId = res.optJSONArray("results")?.optJSONObject(0)?.optString("videoId")?.takeIf { it.isNotBlank() }
                }
            } catch (e: Exception) {}
            val id = videoId
            main.post {
                if (id != null) {
                    val inApp = Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:$id")).setPackage(YT)
                    if (!startSafe(app, inApp)) openUrl(app, "https://www.youtube.com/watch?v=$id")
                } else {
                    youtubeSearch(app, query)
                }
            }
        }.start()
    }

    fun youtubeSearch(c: Context, query: String) {
        val i = Intent(Intent.ACTION_SEARCH).setPackage(YT).putExtra(SearchManager.QUERY, query)
        if (!startSafe(c, i)) openUrl(c, "https://www.youtube.com/results?search_query=${enc(query)}")
    }

    /** YouTube Music / Spotify: Android's "play from search" opens the app and starts the song */
    private fun playFromSearch(c: Context, pkg: String, query: String, webFallback: String) {
        val i = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
            .setPackage(pkg)
            .putExtra(SearchManager.QUERY, query)
            .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
        if (!startSafe(c, i)) {
            if (!startSafe(c, Intent(Intent.ACTION_VIEW, Uri.parse(webFallback)).setPackage(pkg))) openUrl(c, webFallback)
        }
    }

    fun openUrl(c: Context, url: String) {
        startSafe(c, Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    fun startSafe(c: Context, i: Intent): Boolean = try {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        c.startActivity(i)
        true
    } catch (e: Exception) {
        false
    }

    // ---------------- contacts, calls, WhatsApp ----------------
    /** contact name or number → (name, number) */
    fun resolvePhone(c: Context, contact: String, say: (String) -> Unit): Pair<String, String>? {
        val digits = contact.filter { it.isDigit() }
        if (digits.length >= 7) return contact to contact
        if (!c.hasPerm(Manifest.permission.READ_CONTACTS)) {
            say("Please allow Contacts in InHuman Settings first, sir.")
            return null
        }
        val found = Contacts.findPhone(c, contact)
        if (found == null) say("I couldn't find $contact in your contacts, sir.")
        return found
    }

    fun call(c: Context, contact: String, say: (String) -> Unit) {
        val (_, number) = resolvePhone(c, contact, say) ?: return
        val uri = Uri.parse("tel:${number.filter { it.isDigit() || it == '+' }}")
        if (c.hasPerm(Manifest.permission.CALL_PHONE)) {
            if (!startSafe(c, Intent(Intent.ACTION_CALL, uri))) startSafe(c, Intent(Intent.ACTION_DIAL, uri))
        } else {
            startSafe(c, Intent(Intent.ACTION_DIAL, uri))
            say("Tap call, sir. Allow Phone calls in Settings so I can dial for you.")
        }
    }

    fun whatsappPackage(c: Context): String? = WHATSAPP.firstOrNull { installed(c, it) }

    fun whatsappMessage(c: Context, contact: String, message: String, say: (String) -> Unit) {
        val pkg = whatsappPackage(c) ?: return say("WhatsApp isn't installed on this phone, sir.")
        val (name, number) = resolvePhone(c, contact, say) ?: return
        val intl = Contacts.international(c, number)
        val uri = Uri.parse("https://api.whatsapp.com/send?phone=$intl&text=${enc(message)}")
        InHumanAccessibility.queueSend(setOf(pkg), null) { ok -> if (ok) say("Sent to $name, sir.") }
        if (!startSafe(c, Intent(Intent.ACTION_VIEW, uri).setPackage(pkg))) return say("I couldn't open WhatsApp, sir.")
        if (!InHumanAccessibility.isOn()) say("Your message to $name is ready, sir. Tap send. Switch on Accessibility so I can send for you.")
    }

    /** Instagram / Messenger: open the chat, type the message and press send (needs Accessibility) */
    private fun socialMessage(c: Context, pkgs: List<String>, linkBase: String, username: String, message: String, appName: String, say: (String) -> Unit) {
        val user = username.trim().removePrefix("@").replace(" ", "")
        if (user.isEmpty()) return say("Which $appName username, sir?")
        val pkg = pkgs.firstOrNull { installed(c, it) }
        val i = Intent(Intent.ACTION_VIEW, Uri.parse(linkBase + enc(user)))
        if (pkg != null) i.setPackage(pkg)
        if (InHumanAccessibility.isOn() && pkg != null) {
            InHumanAccessibility.queueSend(setOf(pkg), message) { ok ->
                say(if (ok) "Sent to $user on $appName, sir." else "I opened the chat but couldn't press send, sir.")
            }
        } else {
            (c.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("message", message))
            say("I've opened $user on $appName and copied your message, sir. Paste it and tap send.")
        }
        if (!startSafe(c, i)) openUrl(c, linkBase + enc(user))
    }

    // ---------------- email ----------------
    private fun smtpFor(address: String): Pair<String, Int>? {
        val domain = address.substringAfter('@').lowercase(Locale.ROOT)
        return when {
            domain == "gmail.com" || domain == "googlemail.com" -> "smtp.gmail.com" to 465
            domain in listOf("outlook.com", "hotmail.com", "live.com", "msn.com") -> "smtp-mail.outlook.com" to 587
            domain.startsWith("yahoo.") || domain == "ymail.com" -> "smtp.mail.yahoo.com" to 465
            domain in listOf("icloud.com", "me.com", "mac.com") -> "smtp.mail.me.com" to 587
            domain.startsWith("zoho.") -> "smtp.zoho.com" to 465
            else -> null
        }
    }

    private fun sendEmail(c: Context, a: JSONObject, say: (String) -> Unit) {
        val accounts = Prefs.emails(c)
        if (accounts.length() == 0) return say("Add your email account in Settings, Emails first, sir.")
        val fromSpec = a.optString("from").lowercase(Locale.ROOT).trim()
        var account: JSONObject? = null
        for (pass in 0..2) {
            for (i in 0 until accounts.length()) {
                val e = accounts.optJSONObject(i) ?: continue
                val hit = when (pass) {
                    0 -> e.optString("address").equals(fromSpec, true)
                    1 -> fromSpec.isNotEmpty() && e.optString("label").equals(fromSpec, true)
                    else -> fromSpec.isNotEmpty() && e.optString("type").equals(fromSpec, true)
                }
                if (hit) { account = e; break }
            }
            if (account != null) break
        }
        val acct = account ?: accounts.optJSONObject(0) ?: return

        // Recipients: addresses, "group:clients" / group names, or contact names
        val to = linkedSetOf<String>()
        val groups = Prefs.groups(c)
        val wanted = a.optJSONArray("to") ?: JSONArray().put(a.optString("to"))
        for (i in 0 until wanted.length()) {
            val item = wanted.optString(i).trim()
            if (item.isEmpty()) continue
            if (item.contains('@') && !item.startsWith("group:")) { to.add(item); continue }
            val gname = item.removePrefix("group:").trim()
            var matched = false
            for (g in 0 until groups.length()) {
                val grp = groups.optJSONObject(g) ?: continue
                if (Text.norm(grp.optString("name")) == Text.norm(gname) || Text.norm(grp.optString("name")).removeSuffix("s") == Text.norm(gname).removeSuffix("s")) {
                    grp.optString("emails").split(',', ';', ' ', '\n').map { it.trim() }.filter { it.contains('@') }.forEach { to.add(it) }
                    matched = true
                }
            }
            if (!matched) Contacts.findEmail(c, gname)?.let { to.add(it) }
        }
        if (to.isEmpty()) return say("I couldn't find who to send that email to, sir.")

        val address = acct.optString("address")
        val auto = smtpFor(address)
        val host = acct.optString("host").ifBlank { auto?.first ?: "" }
        val port = acct.optString("port").toIntOrNull() ?: auto?.second ?: 465
        if (host.isBlank()) return say("Add the SMTP server for $address in Settings, Emails, sir.")

        val app = c.applicationContext
        Thread {
            val msg = try {
                val body = JSONObject()
                    .put("smtp", JSONObject().put("host", host).put("port", port).put("user", address).put("pass", acct.optString("pass")))
                    .put("fromName", Prefs.ownerName(app))
                    .put("to", JSONArray(to.toList()))
                    .put("subject", a.optString("subject"))
                    .put("text", a.optString("body"))
                val res = Net.json("${Prefs.serverUrl(app)}/api/email", body, timeoutMs = 60000)
                val sent = res.optInt("sent", 0)
                when {
                    res.has("error") && sent == 0 -> "Email failed, sir. ${res.optString("error")}"
                    sent == to.size -> if (sent == 1) "Email sent from $address, sir." else "All $sent emails sent from $address, sir."
                    else -> "$sent of ${to.size} emails sent, sir."
                }
            } catch (e: Exception) {
                "I couldn't reach the email server, sir."
            }
            main.post { say(msg) }
        }.start()
    }

    // ---------------- MT5 trades (through your bridge) ----------------
    private fun trade(c: Context, a: JSONObject, say: (String) -> Unit) {
        val bridge = Prefs.mt5(c, "bridge").trimEnd('/')
        val token = Prefs.mt5(c, "token")
        if (bridge.isBlank() || token.isBlank()) return say("Connect your MT5 account in Settings, Connect Apps first, sir.")
        val base = a.optString("symbol").uppercase(Locale.ROOT).replace(Regex("[^A-Z0-9.]"), "")
        if (base.isBlank()) return say("Which symbol, sir?")
        val symbol = base + Prefs.mt5(c, "suffix")
        val count = a.optInt("count", 1).coerceIn(1, 20)
        val vol = a.optDouble("volume", Double.NaN)
        val volume = if (!vol.isNaN() && vol > 0) vol else (Prefs.mt5(c, "lot", "0.01").toDoubleOrNull() ?: 0.01)
        val requested = a.optString("direction").uppercase(Locale.ROOT)
        val app = c.applicationContext

        Thread {
            var direction = requested
            var reason = ""
            if (direction != "BUY" && direction != "SELL") {
                try {
                    val res = Net.json("${Prefs.serverUrl(app)}/api/analyze", JSONObject().put("symbol", base).put("keys", keysJson(app)), timeoutMs = 60000)
                    direction = res.optString("direction")
                    reason = res.optString("reason")
                } catch (e: Exception) {}
                if (direction != "BUY" && direction != "SELL") {
                    main.post { say("I couldn't analyse $base right now, sir, so no trades were opened.") }
                    return@Thread
                }
                val r = reason
                val d = direction
                main.post { say("Analysis says $d on $base. $r Opening $count ${d.lowercase()} trades now.") }
            }

            var opened = 0
            var lastError = ""
            repeat(count) {
                try {
                    // Field names kept flexible to match your bridge: change here if your /ordersend expects others
                    val body = JSONObject()
                        .put("token", token)
                        .put("symbol", symbol)
                        .put("type", direction)
                        .put("action", direction.lowercase())
                        .put("volume", volume)
                        .put("lot", volume)
                        .put("comment", "InHuman AI")
                    val (code, text) = Net.request("$bridge/ordersend", body, mapOf("Authorization" to "Bearer $token", "X-Connection-Token" to token))
                    val j = try { JSONObject(text) } catch (e: Exception) { JSONObject() }
                    val retcode = j.optInt("retcode", 10009)
                    val ok = code in 200..299 && !j.has("error") && j.optBoolean("success", true) && (retcode == 10009 || retcode == 10008)
                    if (ok) opened++ else lastError = j.optString("error", j.optString("comment", "HTTP $code"))
                } catch (e: Exception) {
                    lastError = "bridge unreachable"
                }
            }
            val msg = when {
                opened == count -> "Done, sir. $opened ${direction.lowercase()} trades opened on $symbol at $volume lots."
                opened > 0 -> "$opened of $count trades opened on $symbol, sir. Last error: $lastError."
                else -> "No trades were opened on $symbol, sir. $lastError."
            }
            main.post { say(msg) }
        }.start()
    }

    /** Settings → Connect Apps → MT5: log in through the bridge and keep only the returned token */
    fun mt5Connect(c: Context, bridge: String, login: String, password: String, server: String, cb: (Boolean, String) -> Unit) {
        Thread {
            val result = try {
                val res = Net.json("${bridge.trimEnd('/')}/connect", JSONObject().put("login", login).put("password", password).put("server", server))
                val token = res.optString("token").ifBlank { res.optString("connection_token").ifBlank { res.optString("access_token") } }
                if (token.isNotBlank()) {
                    Prefs.setMt5(c, "token", token)
                    true to "Connected ✓"
                } else {
                    false to "Bridge said: ${res.optString("error", res.toString().take(120))}"
                }
            } catch (e: Exception) {
                false to "Could not reach the bridge"
            }
            main.post { cb(result.first, result.second) }
        }.start()
    }
}
