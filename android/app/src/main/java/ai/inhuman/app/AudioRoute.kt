package ai.inhuman.app

import android.annotation.SuppressLint
import android.app.UiModeManager
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import org.json.JSONObject

/**
 * Where the sound is going right now: the car, a Bluetooth speaker, headphones / earbuds or the phone speaker,
 * with the device's name ("Toyota Corolla", "JBL Flip 6"). Jarvis announces changes and the Devices page shows it.
 */
object AudioRoute {
    data class Route(val kind: String, val name: String) {
        fun json(): JSONObject = JSONObject().put("kind", kind).put("name", name)
        val label: String get() = when (kind) {
            "car" -> "🚗 $name"
            "speaker" -> "🔊 $name"
            "headphones" -> "🎧 $name"
            "bluetooth" -> "🔵 $name"
            else -> "📱 Phone speaker"
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val CAR_WORDS = Regex("\\b(car|auto|carplay|android auto|toyota|vw|volkswagen|polo|golf|bmw|mercedes|benz|audi|ford|sync|hyundai|kia|nissan|honda|mazda|renault|chevrolet|chev|opel|peugeot|suzuki|isuzu|mahindra|haval|chery|gwm|jeep|lexus|volvo|uconnect|mylink|entune|mbux|idrive|head ?unit|pioneer|kenwood|alpine|sony xav|jvc)\\b", RegexOption.IGNORE_CASE)
    private val SPEAKER_WORDS = Regex("\\b(speaker|boom|flip|charge|go ?\\d|xtreme|soundlink|soundcore|partybox|bar|sound ?bar|home|echo|nest|sonos|marshall|ue |megaboom|wonderboom)\\b", RegexOption.IGNORE_CASE)
    private val HEAD_WORDS = Regex("\\b(buds|airpods|pods|headphones?|headset|earphones?|earbuds?|wh-|wf-|beats|freebuds|tws|ear)\\b", RegexOption.IGNORE_CASE)

    @SuppressLint("MissingPermission")
    private fun btClass(c: Context, name: String): Int? {
        if (Build.VERSION.SDK_INT >= 31 && c.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return null
        return try {
            val bm = c.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            bm.adapter?.bondedDevices?.firstOrNull { it.name == name }?.bluetoothClass?.deviceClass
        } catch (e: Exception) { null }
    }

    private fun kindFor(c: Context, name: String): String {
        when (btClass(c, name)) {
            BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO, BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE -> return "car"
            BluetoothClass.Device.AUDIO_VIDEO_LOUDSPEAKER, BluetoothClass.Device.AUDIO_VIDEO_HIFI_AUDIO,
            BluetoothClass.Device.AUDIO_VIDEO_PORTABLE_AUDIO -> return if (CAR_WORDS.containsMatchIn(name)) "car" else "speaker"
            BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES, BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET -> return "headphones"
        }
        return when {
            CAR_WORDS.containsMatchIn(name) -> "car"
            HEAD_WORDS.containsMatchIn(name) -> "headphones"
            SPEAKER_WORDS.containsMatchIn(name) -> "speaker"
            else -> "bluetooth"
        }
    }

    fun current(c: Context): Route {
        val am = c.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val outs = try { am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList() } catch (e: Exception) { emptyList() }
        val inCar = try { (c.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager).currentModeType == Configuration.UI_MODE_TYPE_CAR } catch (e: Exception) { false }
        val btTypes = mutableSetOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
        if (Build.VERSION.SDK_INT >= 31) btTypes += setOf(AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER)
        val bt = outs.firstOrNull { it.type in btTypes && it.productName?.toString()?.isNotBlank() == true }
        if (bt != null) {
            val name = bt.productName.toString().trim()
            val kind = if (inCar) "car" else if (Build.VERSION.SDK_INT >= 31 && bt.type == AudioDeviceInfo.TYPE_BLE_SPEAKER) "speaker" else kindFor(c, name)
            return Route(kind, name)
        }
        if (inCar) return Route("car", "Android Auto")
        val wired = outs.firstOrNull { it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_USB_HEADSET }
        if (wired != null) return Route("headphones", wired.productName?.toString()?.takeIf { it.isNotBlank() } ?: "Wired headphones")
        return Route("phone", "Phone speaker")
    }

    private var last: Route? = null
    private var callback: AudioDeviceCallback? = null

    /** Tell [onChange] (after a short settle) whenever the sound moves to another device */
    fun watch(c: Context, onChange: (now: Route, before: Route?) -> Unit) {
        val app = c.applicationContext
        val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        last = current(app)
        val check = Runnable {
            val now = current(app)
            val before = last
            if (now != before) { last = now; onChange(now, before) }
        }
        callback?.let { try { am.unregisterAudioDeviceCallback(it) } catch (e: Exception) {} }
        val cb = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) { main.removeCallbacks(check); main.postDelayed(check, 1800) }
            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>?) { main.removeCallbacks(check); main.postDelayed(check, 1200) }
        }
        callback = cb
        am.registerAudioDeviceCallback(cb, main)
    }

    fun stop(c: Context) {
        val am = c.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        callback?.let { try { am.unregisterAudioDeviceCallback(it) } catch (e: Exception) {} }
        callback = null
    }
}
