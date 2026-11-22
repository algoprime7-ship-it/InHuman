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
    /** Extra context for the next AI question only: {"checkin":true} after "are you okay?", {"mood":"sad"} … */
    @Volatile var nextHint: JSONObject? = null
    /** Context from the web app (recent songs etc.), kept for the next questions */
    @Volatile var webExtra: JSONObject? = null

    val SAD = Regex("\\b(i'?m|i am|feeling|i feel)\\s+(not\\s+(okay|ok|good|fine|alright|great|well)|sad|down|depressed|terrible|awful|bad|low|horrible|stressed|lonely|tired of everything)\\b|^not\\s+(okay|ok|good|great)\\b", RegexOption.IGNORE_CASE)
    val CAMERA = Regex("\\b(take|snap|capture|record|film|shoot|make)\\b.*\\b(photos?|pictures?|pics?|selfies?|videos?|clips?)\\b", RegexOption.IGNORE_CASE)

    fun localCommand(c: Context, raw: String): Local? {
        val t = cleanCommand(c, raw).lowercase(Locale.ROOT).trimEnd('.', '!', '?')
        val phone = PhoneControl.parse(t)
        if (phone != null) return Local("", phone)

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
        // "play X on my iphone" / "lock my samsung" → another device: let the AI build a device action
        if (Regex("\\b(on|in) my (?!phone$)[a-z0-9 ]+$").containsMatchIn(t) && !Regex("(youtube|spotify|whatsapp|instagram)").containsMatchIn(t)) return null
        if (Regex("^(lock|unlock|wake( up)?|turn (on|off)|switch (on|off)) my [a-z0-9 ]+$").containsMatchIn(t) && !t.endsWith("my phone") && !t.endsWith("my screen")) return null
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
            .put("mt5", Prefs.account(c).isNotBlank())
            .put("thisDevice", DeviceInfo.json(c).optString("name"))
            .put("owner", Prefs.ownerName(c))
            .apply {
                webExtra?.let { w -> w.keys().forEach { k -> put(k, w.opt(k)) } }
                nextHint?.let { h -> h.keys().forEach { k -> put(k, h.opt(k)) } }
                nextHint = null
            }
    }

    fun keysJson(c: Context): JSONObject = JSONObject()
        .put("gemini", Prefs.key(c, "gemini"))
        .put("claude", Prefs.key(c, "claude"))
        .put("openai", Prefs.key(c, "openai"))
        .put("preferred", Prefs.key(c, "preferred"))

    /** Ask the AI on your Vercel server. cb runs on the main thread. */
    /** The other mind cutting in on the last answer: [{"who":"ultron","text":"..."}] */
    @Volatile var lastAside: JSONArray = JSONArray()

    /** Is there internet right now? (offline: phone controls, calls and opening apps still work) */
    fun online(c: Context): Boolean = try {
        val cm = c.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        caps != null && caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    } catch (e: Exception) { true }

    fun ask(c: Context, message: String, history: JSONArray, surface: String, cb: (String, JSONObject?) -> Unit) {
        val app = c.applicationContext
        lastAside = JSONArray()
        if (!online(app)) {
            val sir = if (Prefs.ultron(app)) "human" else if (Prefs.face(app) == "friday") "boss" else "sir"
            main.post { cb("We're offline, $sir. I need data or Wi-Fi for that. I can still open apps, make calls, and control the flashlight, volume and Do Not Disturb.", null) }
            return
        }
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
                        .put("face", Prefs.face(app))
                        .put("account", Prefs.account(app))
                        .put("deviceId", Prefs.deviceId(app))
                        .put("device", DeviceInfo.json(app))
                        .put("context", contextJson(app))
                        .put("keys", keysJson(app))
                        .put("github", JSONObject().put("token", Prefs.key(app, "github")))
                    val res = Net.json("$server/api/ask", body, timeoutMs = 60000)
                    reply = res.optString("reply", reply)
                    action = res.optJSONObject("action")
                    lastAside = res.optJSONArray("aside") ?: JSONArray()
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
        // TikTok LIVE: viewers can't make it call, message, browse, open banking or anything private
        if (!TikTok.allowed(c, a)) { say("That's a violation of sector 619, I won't do that."); return }
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
            "sms" -> sms(c, a.optString("contact"), a.optString("message"), say)
            "inhuman_message" -> inhumanMessage(c, a.optString("to"), a.optString("text"), say)
            "send_email" -> sendEmail(c, a, say)
            "trade" -> trade(c, a, say)
            "phone", "volume" -> PhoneControl.run(c, if (a.optString("type") == "volume") JSONObject(a.toString()).put("what", "volume").put("state", a.optString("dir", a.optString("state", "up"))).put("value", a.optString("level", a.optString("value"))) else a, say)
            "camera" -> openAppWithCommand(c, a.optString("text").ifBlank { "take a photo" })
            "device" -> deviceCommand(c, a, say)
            "lock_screen" -> if (!InHumanAccessibility.perform("lock")) say("Please switch on InHuman AI in Accessibility settings so I can lock the screen, sir.")
            "wake_screen" -> wakeScreen(c)
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
    /** "+27 82 123 4567" → "Mom" from your contacts (null if unknown or no permission) */
    fun contactName(c: Context, number: String): String? {
        if (c.checkSelfPermission(Manifest.permission.READ_CONTACTS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return null
        return try {
            val uri = Uri.withAppendedPath(android.provider.ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            c.contentResolver.query(uri, arrayOf(android.provider.ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use { cur ->
                if (cur.moveToFirst()) cur.getString(0) else null
            }
        } catch (e: Exception) { null }
    }

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

    // ---------------- MT5 trades (through the InHuman server; the bridge link is on Vercel) ----------------
    private fun trade(c: Context, a: JSONObject, say: (String) -> Unit) {
        val app = c.applicationContext
        val token = Prefs.account(app)
        if (token.isBlank()) return say("Sign in to your InHuman account and connect MT5 in the MT5 page first, sir.")
        val symbol = a.optString("symbol").ifBlank { "XAUUSD" }
        val count = a.optInt("count", 1).coerceIn(1, 20)
        val vol = a.optDouble("volume", Double.NaN)
        val dir = a.optString("direction").uppercase(Locale.ROOT).let { if (it == "BUY" || it == "SELL") it else "" }
        say("Now opening MetaTrader 5 and opening your trades, sir.")
        openApp(c, "MetaTrader 5")
        Thread {
            val msg = try {
                val body = JSONObject().put("token", token).put("symbol", symbol).put("count", count).put("direction", dir)
                if (!vol.isNaN() && vol > 0) body.put("lot", vol)
                val res = Net.json("${Prefs.serverUrl(app)}/api/mt5?action=voice", body, timeoutMs = 60000)
                val opened = res.optInt("opened", 0)
                val sym = res.optString("symbol", symbol)
                val d = res.optString("direction").lowercase(Locale.ROOT)
                val an = res.optJSONObject("analysis")
                val conf = an?.optInt("confidence", 0) ?: 0
                val spoken = sym.replace(Regex("[a-z.#_+-]+$"), "")          // XAUUSDm → XAUUSD
                val px = an?.let { if (d == "buy") it.optDouble("ask", 0.0) else it.optDouble("bid", 0.0) } ?: 0.0
                val reason = an?.optString("reason").orEmpty()
                when {
                    res.has("error") -> "No trades opened, sir. ${res.optString("error")}"
                    opened > 0 -> "Done, sir. $opened $spoken $d trade${if (opened > 1) "s" else ""}${if (px > 0) " at ${px}" else ""}${if (conf > 0) ", $conf percent confidence" else ""}." +
                        (if (opened < count) " Only $opened of $count went through." else "") + (if (reason.isNotBlank()) " $reason" else "")
                    else -> "No trades opened, sir. ${res.optJSONArray("errors")?.optString(0) ?: ""}"
                }
            } catch (e: Exception) {
                "I could not reach the trading server, sir."
            }
            main.post { say(msg) }
        }.start()
    }

    // ---------------- other devices on the same account ----------------
    /** "lock my samsung", "play Drake on my iPhone": sent through the server, the other device picks it up */
    private fun deviceCommand(c: Context, a: JSONObject, say: (String) -> Unit) {
        val app = c.applicationContext
        val token = Prefs.account(app)
        if (token.isBlank()) return say("Sign in to your InHuman account on both devices first, sir.")
        Thread {
            val msg = try {
                val command = a.optString("command")
                val arg = a.optString("arg").ifBlank { a.optString("query") }
                val body = JSONObject().put("token", token).put("deviceId", Prefs.deviceId(app))
                    .put("target", a.optString("target")).put("command", command).put("arg", arg)
                    .put("from", DeviceInfo.json(app).optString("name", "phone"))
                val res = Net.json("${Prefs.serverUrl(app)}/api/client?action=command", body, timeoutMs = 15000)
                if (res.has("error")) res.optString("error")
                else {
                    val d = res.optJSONObject("device") ?: JSONObject()
                    val name = d.optString("name", "device")
                    val verb = when (command) { "lock" -> "Locking"; "wake" -> "Waking"; "play" -> "Playing ${arg} on"; "stop" -> "Stopping the music on"; "open" -> "Opening $arg on"; else -> "Sending to" }
                    "$verb your $name, sir." + if (!d.optBoolean("online", true)) " It looks offline, so it will happen when it next connects." else ""
                }
            } catch (e: Exception) {
                "I could not reach the server, sir."
            }
            main.post { say(msg) }
        }.start()
    }

    /** Normal text message: opens your SMS app with the text ready (Android needs you to press send) */
    private fun sms(c: Context, contact: String, message: String, say: (String) -> Unit) {
        val p = resolvePhone(c, contact, say) ?: return
        try {
            c.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${p.second}")).putExtra("sms_body", message).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            say("Your text to ${p.first} is ready, sir. Just press send.")
        } catch (e: Exception) { say("I couldn't open your messages app, sir.") }
    }

    /** Message a connected InHuman user by name */
    private fun inhumanMessage(c: Context, to: String, text: String, say: (String) -> Unit) {
        val app = c.applicationContext
        val token = Prefs.account(app)
        if (token.isBlank()) return say("Sign in to InHuman first, sir.")
        if (!online(app)) return say("We're offline, sir. Messages need data or Wi-Fi.")
        Thread {
            val msg = try {
                val server = Prefs.serverUrl(app)
                val fr = Net.json("$server/api/social?action=friends", JSONObject().put("token", token), timeoutMs = 20000).optJSONArray("friends") ?: JSONArray()
                val want = to.lowercase(Locale.ROOT).trim()
                var hit: JSONObject? = null
                for (i in 0 until fr.length()) {
                    val f = fr.optJSONObject(i) ?: continue
                    val names = listOf(f.optString("name"), f.optString("nick"), f.optString("first"), f.optString("realName")).filter { it.isNotBlank() }.map { it.lowercase(Locale.ROOT) }
                    if (names.any { it.startsWith(want) || want.startsWith(it) }) { hit = f; break }
                }
                if (hit == null) "$to isn't one of your connected users, sir."
                else {
                    val r = Net.json("$server/api/social?action=send", JSONObject().put("token", token).put("to", hit.optString("id")).put("text", text), timeoutMs = 20000)
                    if (r.optBoolean("ok")) "Sent to ${hit.optString("name")} on InHuman, sir." else "I couldn't send it, sir. ${r.optString("error")}"
                }
            } catch (e: Exception) { "I couldn't reach the server, sir." }
            main.post { say(msg) }
        }.start()
    }

    /** Photos and videos are taken by the app's own screen (Android only lets the camera run there) */
    fun openAppWithCommand(c: Context, text: String) {
        try {
            c.startActivity(Intent(c, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("run", text))
        } catch (e: Exception) {}
    }

    /** Turn the screen on (Android allows waking the screen; switching the phone off needs the power button) */
    fun wakeScreen(c: Context) {
        try {
            val pm = c.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            @Suppress("DEPRECATION")
            val wl = pm.newWakeLock(android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK or android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP or android.os.PowerManager.ON_AFTER_RELEASE, "inhuman:wake")
            wl.acquire(8000)
        } catch (e: Exception) {}
    }

    /**
     * Credits for things done without the AI (local commands, next song).
     * kinds: "message", "action", "song". cb(ok, messageIfBlocked) on the main thread.
     */
    fun useCredits(c: Context, kinds: List<String>, cb: (Boolean, String) -> Unit) {
        val app = c.applicationContext
        Thread {
            var ok = true
            var msg = ""
            try {
                val server = Prefs.serverUrl(app)
                if (server.isNotEmpty()) {
                    val body = JSONObject()
                        .put("token", Prefs.account(app))
                        .put("deviceId", Prefs.deviceId(app))
                        .put("kinds", JSONArray(kinds))
                        .put("face", Prefs.face(app))
                    val res = Net.json("$server/api/client?action=usage", body, timeoutMs = 15000)
                    if (res.has("ok") && !res.optBoolean("ok", true)) {
                        ok = false
                        msg = res.optString("message", "Daily free credits reached.")
                    }
                }
            } catch (e: Exception) { /* offline or accounts off: don't block */ }
            val o = ok
            val m = msg
            main.post { cb(o, m) }
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
