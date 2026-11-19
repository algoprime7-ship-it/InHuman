package ai.inhuman.app

import android.Manifest
import android.app.NotificationManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import org.json.JSONObject
import java.util.Locale

// =====================================================================
// Phone controls by voice: volume, flashlight, Do Not Disturb, Bluetooth, Wi-Fi,
// hotspot, flight mode and screen mirroring.
// Android lets apps change volume, flash and Do Not Disturb directly. Wi-Fi, Bluetooth (Android 13+),
// hotspot, flight mode and mirroring can only be switched from the system screens, so InHuman opens
// the right screen and (with Accessibility on) taps the switch or the named network/device for you.
// =====================================================================
object PhoneControl {
    private val main = Handler(Looper.getMainLooper())

    private val NUM = mapOf("zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7,
        "eight" to 8, "nine" to 9, "ten" to 10, "twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50, "sixty" to 60,
        "seventy" to 70, "eighty" to 80, "ninety" to 90, "hundred" to 100, "max" to 100, "maximum" to 100, "full" to 100, "half" to 50)

    /** "turn on the flash" → {"type":"phone","what":"flash","state":"on"}; null if it isn't a phone control */
    fun parse(text: String): JSONObject? {
        val t = text.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9% '-]"), " ").replace(Regex("\\s+"), " ").trim()
        fun out(what: String, state: String, value: String = "") = JSONObject().put("type", "phone").put("what", what).put("state", state).put("value", value)
        val on = Regex("\\b(on|enable|activate|start|open)\\b").containsMatchIn(t)
        val off = Regex("\\b(off|disable|deactivate|stop|close)\\b").containsMatchIn(t)
        val st = if (off && !Regex("\\bturn on\\b|\\bswitch on\\b").containsMatchIn(t)) "off" else "on"

        // volume
        val volWord = Regex("\\b(louder|quieter|softer|mute|unmute)\\b").containsMatchIn(t) ||
            (Regex("\\bvolume\\b").containsMatchIn(t) && Regex("\\b(up|down|raise|lower|increase|decrease|reduce|turn|set|max|maximum|full|half|to|percent)\\b|%").containsMatchIn(t))
        if (volWord && !Regex("\\bon my\\b").containsMatchIn(t)) {
            Regex("(\\d{1,3})\\s*(%|percent)?").find(t)?.let { return out("volume", "set", it.groupValues[1]) }
            NUM.entries.firstOrNull { Regex("\\b${it.key}\\b").containsMatchIn(t) && Regex("\\b(to|at)\\b").containsMatchIn(t) }?.let { return out("volume", "set", it.value.toString()) }
            return when {
                Regex("\\bunmute\\b").containsMatchIn(t) -> out("volume", "unmute")
                Regex("\\bmute\\b").containsMatchIn(t) -> out("volume", "mute")
                Regex("\\b(down|lower|decrease|reduce|quieter|softer)\\b").containsMatchIn(t) -> out("volume", "down")
                else -> out("volume", "up")
            }
        }
        if (Regex("\\b(flash ?light|torch|flash)\\b").containsMatchIn(t) && (on || off)) return out("flash", st)
        if (Regex("\\b(do not disturb|don't disturb|dnd|silent mode)\\b").containsMatchIn(t)) return out("dnd", st)
        if (Regex("\\b(flight|airplane|aeroplane) mode\\b").containsMatchIn(t)) return out("airplane", st)
        if (Regex("\\b(hot ?spot|tethering)\\b").containsMatchIn(t)) return out("hotspot", st)
        if (Regex("\\b(screen ?mirror(ing)?|smart view|cast (my )?screen|mirror (my )?screen)\\b").containsMatchIn(t)) {
            val dev = Regex("\\bto (?:the |my )?(.+)$").find(t)?.groupValues?.get(1)?.trim().orEmpty()
            return out("mirror", if (off) "off" else "on", dev)
        }
        if (Regex("\\bbluetooth\\b").containsMatchIn(t) || Regex("^connect (?:to )?(?:my )?(.+?) (?:headphones|earbuds|buds|speaker|headset)").containsMatchIn(t)) {
            val dev = Regex("\\b(?:connect|pair)(?: to)?(?: my| the)? (.+?)(?: via bluetooth| on bluetooth| bluetooth)?$").find(t)?.groupValues?.get(1)?.trim().orEmpty()
            return out("bluetooth", if (dev.isNotBlank() && dev != "bluetooth") "connect" else st, if (dev == "bluetooth") "" else dev)
        }
        if (Regex("\\b(wi ?fi|wireless)\\b").containsMatchIn(t) || (Regex("\\bnetwork\\b").containsMatchIn(t) && Regex("\\bconnect\\b").containsMatchIn(t))) {
            if (!(on || off || Regex("\\b(connect|turn|switch|join)\\b").containsMatchIn(t))) return null
            val net = Regex("\\bconnect (?:to |me to )?(?:the )?(?:wi ?fi )?(?:network )?(?:called |named )?(.+)$").find(t)?.groupValues?.get(1)
                ?.replace(Regex("\\b(wi ?fi|network)\\b"), "")?.trim().orEmpty()
            return out("wifi", if (net.isNotBlank()) "connect" else st, net)
        }
        return null
    }

    /** Runs a {"type":"phone"} action. say() is spoken by the caller. */
    fun run(c: Context, a: JSONObject, say: (String) -> Unit) {
        val what = a.optString("what")
        val state = a.optString("state", "on")
        val value = a.optString("value").trim()
        when (what) {
            "volume" -> say(volume(c, state, value))
            "flash" -> say(flash(c, state != "off"))
            "dnd" -> say(dnd(c, state != "off"))
            "bluetooth" -> bluetooth(c, state, value, say)
            "wifi" -> wifi(c, state, value, say)
            "hotspot" -> {
                openFirst(c, listOf(
                    Intent().setClassName("com.android.settings", "com.android.settings.TetherSettings"),
                    Intent("android.settings.TETHER_SETTINGS"),
                    Intent(Settings.ACTION_WIRELESS_SETTINGS)
                ))
                tap(c, listOf("mobile hotspot", "wi-fi hotspot", "hotspot", "portable hotspot", "personal hotspot"), state != "off")
                say(if (state == "off") "Turning the hotspot off, sir." else "Turning the hotspot on, sir.")
            }
            "airplane" -> {
                openFirst(c, listOf(Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS), Intent(Settings.ACTION_WIRELESS_SETTINGS)))
                tap(c, listOf("airplane mode", "flight mode", "aeroplane mode"), state != "off")
                say(if (state == "off") "Turning flight mode off, sir." else "Turning flight mode on, sir. I'll lose the internet until you switch it off.")
            }
            "mirror" -> {
                openFirst(c, listOf(
                    c.packageManager.getLaunchIntentForPackage("com.samsung.android.smartmirroring") ?: Intent(),
                    Intent("android.settings.CAST_SETTINGS"),
                    Intent(Settings.ACTION_DISPLAY_SETTINGS)
                ))
                if (value.isNotBlank()) tap(c, listOf(value), null, wholeWord = false)
                say(if (value.isNotBlank()) "Looking for $value to mirror your screen, sir." else "Opening screen mirroring, sir. Pick the screen.")
            }
            else -> say("I can't control that yet, sir.")
        }
    }

    // ---------------- volume ----------------
    private fun volume(c: Context, state: String, value: String): String {
        val am = c.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val s = AudioManager.STREAM_MUSIC
        val max = am.getStreamMaxVolume(s)
        val flags = AudioManager.FLAG_SHOW_UI
        return try {
            when (state) {
                "set" -> {
                    val pct = (value.filter { it.isDigit() }.toIntOrNull() ?: NUM[value] ?: 50).coerceIn(0, 100)
                    am.setStreamVolume(s, Math.round(max * pct / 100f), flags)
                    "Volume set to $pct percent, sir."
                }
                "mute" -> { am.adjustStreamVolume(s, AudioManager.ADJUST_MUTE, flags); "Muted, sir." }
                "unmute" -> { am.adjustStreamVolume(s, AudioManager.ADJUST_UNMUTE, flags); "Sound is back on, sir." }
                "down" -> {
                    repeat(2) { am.adjustStreamVolume(s, AudioManager.ADJUST_LOWER, if (it == 0) flags else 0) }
                    "Volume down, sir. ${am.getStreamVolume(s) * 100 / max} percent."
                }
                else -> {
                    repeat(2) { am.adjustStreamVolume(s, AudioManager.ADJUST_RAISE, if (it == 0) flags else 0) }
                    "Volume up, sir. ${am.getStreamVolume(s) * 100 / max} percent."
                }
            }
        } catch (e: SecurityException) {
            "Android won't let me change the volume while Do Not Disturb is on, sir."
        }
    }

    // ---------------- flashlight ----------------
    private fun flash(c: Context, on: Boolean): String {
        return try {
            val cm = c.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
                ?: return "This phone has no flashlight, sir."
            cm.setTorchMode(id, on)
            if (on) "Flashlight on, sir." else "Flashlight off, sir."
        } catch (e: Exception) {
            "The camera is busy, so I can't use the flash right now, sir."
        }
    }

    // ---------------- Do Not Disturb ----------------
    private fun dnd(c: Context, on: Boolean): String {
        val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!nm.isNotificationPolicyAccessGranted) {
            c.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return "Please allow InHuman AI in Do Not Disturb access, then ask me again, sir."
        }
        nm.setInterruptionFilter(if (on) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL)
        return if (on) "Do Not Disturb is on, sir." else "Do Not Disturb is off, sir."
    }

    // ---------------- Bluetooth ----------------
    private fun hasBtPermission(c: Context) =
        Build.VERSION.SDK_INT < 31 || c.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    @Suppress("DEPRECATION", "MissingPermission")
    private fun bluetooth(c: Context, state: String, deviceName: String, say: (String) -> Unit) {
        val adapter: BluetoothAdapter? = (c.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        if (adapter == null) return say("This phone has no Bluetooth, sir.")
        if (!hasBtPermission(c)) {
            openFirst(c, listOf(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)))
            return say("Please allow InHuman AI to use nearby devices in the app's permissions, sir. I opened Bluetooth for now.")
        }
        val wantOn = state != "off"
        if (!wantOn) {
            if (Build.VERSION.SDK_INT < 33 && adapter.disable()) return say("Bluetooth off, sir.")
            openFirst(c, listOf(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)))
            tap(c, listOf("bluetooth", "use bluetooth"), false)
            return say("Turning Bluetooth off, sir.")
        }
        if (!adapter.isEnabled) {
            if (Build.VERSION.SDK_INT < 33) adapter.enable()
            else {
                openFirst(c, listOf(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)))
                tap(c, listOf("bluetooth", "use bluetooth"), true)
            }
        }
        if (state != "connect" || deviceName.isBlank()) return say("Bluetooth on, sir.")
        // connect to a paired device by name (headphones, speaker, car…)
        main.postDelayed({
            val bonded = try { adapter.bondedDevices?.toList().orEmpty() } catch (e: Exception) { emptyList<BluetoothDevice>() }
            val want = deviceName.lowercase(Locale.ROOT)
            val dev = bonded.firstOrNull { (it.name ?: "").lowercase(Locale.ROOT) == want }
                ?: bonded.firstOrNull { (it.name ?: "").lowercase(Locale.ROOT).contains(want) || want.contains((it.name ?: "~").lowercase(Locale.ROOT)) }
                ?: bonded.firstOrNull { d -> want.split(" ").filter { it.length > 2 }.any { (d.name ?: "").lowercase(Locale.ROOT).contains(it) } }
            if (dev == null) {
                openFirst(c, listOf(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)))
                tap(c, listOf(deviceName), null, wholeWord = false)
                say("I couldn't find $deviceName in your paired devices, sir. I opened Bluetooth so you can pair it.")
                return@postDelayed
            }
            var asked = 0
            listOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET).forEach { profile ->
                adapter.getProfileProxy(c, object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(p: Int, proxy: BluetoothProfile) {
                        try { proxy.javaClass.getMethod("connect", BluetoothDevice::class.java).invoke(proxy, dev) } catch (e: Exception) {}
                        main.postDelayed({ try { adapter.closeProfileProxy(p, proxy) } catch (e: Exception) {} }, 8000)
                    }
                    override fun onServiceDisconnected(p: Int) {}
                }, profile)
                asked++
            }
            say("Connecting to ${dev.name}, sir.")
        }, if (adapter.isEnabled) 100L else 3500L)
    }

    // ---------------- Wi-Fi ----------------
    @Suppress("DEPRECATION")
    private fun wifi(c: Context, state: String, network: String, say: (String) -> Unit) {
        val wm = c.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val wantOn = state != "off"
        if (Build.VERSION.SDK_INT < 29) {
            try { wm.isWifiEnabled = wantOn } catch (e: Exception) {}
            if (!wantOn) return say("Wi-Fi off, sir.")
            if (network.isBlank()) return say("Wi-Fi on, sir. It will join your saved network.")
        }
        val panel = if (Build.VERSION.SDK_INT >= 29) Intent(Settings.Panel.ACTION_WIFI) else Intent(Settings.ACTION_WIFI_SETTINGS)
        openFirst(c, listOf(panel, Intent(Settings.ACTION_WIFI_SETTINGS)))
        if (Build.VERSION.SDK_INT >= 29 && (wantOn != wm.isWifiEnabled)) tap(c, listOf("wi-fi", "wifi", "use wi-fi"), wantOn)
        when {
            !wantOn -> say("Turning Wi-Fi off, sir.")
            network.isNotBlank() -> {
                main.postDelayed({ tap(c, listOf(network), null, wholeWord = false) }, if (wm.isWifiEnabled) 600L else 4000L)
                say("Connecting to $network, sir. If it needs a password, type it in.")
            }
            else -> say("Wi-Fi on, sir. It will join your saved network automatically.")
        }
    }

    // ---------------- helpers ----------------
    private fun openFirst(c: Context, intents: List<Intent>) {
        for (i in intents) {
            if (i.component == null && i.action == null) continue
            try {
                c.startActivity(Intent(i).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (e: Exception) {}
        }
    }

    /** With Accessibility on: tap the switch next to one of [labels] (want = desired on/off, null = just tap it) */
    private fun tap(c: Context, labels: List<String>, want: Boolean?, wholeWord: Boolean = true) {
        if (!InHumanAccessibility.isOn()) return
        main.postDelayed({ InHumanAccessibility.tapLabel(labels, want, wholeWord) }, 700)
    }
}
