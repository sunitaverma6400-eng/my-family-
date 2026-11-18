package com.stark.family

import android.app.NotificationManager
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.PowerManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** A compact picture of "now". A null field means that sensor is off, unavailable or unknown. */
class Situation(
    val time: String, val weekday: Int, val minutes: Int, val battery: Int?, val charging: Boolean?, val network: String?,
    val app: String?, val screenOn: Boolean?, val headphones: Boolean?, val dnd: Boolean?, val place: String?,
    val nextEvent: String?, val sleepWindow: String,
) {
    fun toJson(): JSONObject {
        val o = JSONObject().put("time", time).put("weekday", weekday).put("sleep_window", sleepWindow)
        battery?.let { o.put("battery", it) }
        charging?.let { o.put("charging", it) }
        network?.let { o.put("network", it) }
        app?.let { o.put("foreground_app", it) }
        screenOn?.let { o.put("screen_on", it) }
        headphones?.let { o.put("headphones", it) }
        dnd?.let { o.put("dnd", it) }
        place?.let { o.put("place", it) }
        nextEvent?.let { o.put("next_event", it) }
        return o
    }
}

/**
 * The "eyes": reads cheap system state on demand (no always-on service, no polling loop), and only for the sensors
 * the user left switched on. Foreground app comes from the accessibility service; home/outside from the geofence.
 */
object ContextEngine {
    @Volatile var foregroundPkg: String = ""

    val SENSORS = listOf(
        "battery" to "बैटरी और चार्जिंग", "network" to "नेटवर्क (Wi-Fi / मोबाइल)", "app" to "सामने खुला ऐप",
        "screen" to "स्क्रीन चालू/बंद", "headphones" to "हेडफ़ोन लगे हैं या नहीं", "dnd" to "Do Not Disturb की स्थिति",
        "place" to "जगह: घर / बाहर", "calendar" to "कैलेंडर की अगली घटना",
    )

    private fun hhmm(min: Int) = String.format(Locale.US, "%02d:%02d", min / 60, min % 60)

    fun snapshot(ctx: Context): Situation {
        val p = Prefs(ctx)
        val cal = Calendar.getInstance()
        val minutes = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val wd = ((cal.get(Calendar.DAY_OF_WEEK) + 5) % 7) + 1  // 1 = Monday ... 7 = Sunday

        var battery: Int? = null
        var charging: Boolean? = null
        if (p.sensorOn("battery")) {
            val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (i != null) {
                val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                if (level >= 0 && scale > 0) battery = level * 100 / scale
                charging = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
            }
        }
        val network: String? = if (!p.sensorOn("network")) null else try {
            val cm = ctx.getSystemService(ConnectivityManager::class.java)
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            when {
                caps == null -> "none"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile"
                else -> "other"
            }
        } catch (e: Exception) {
            null
        }
        val app: String? = if (!p.sensorOn("app") || foregroundPkg.isEmpty()) null else try {
            val pm = ctx.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(foregroundPkg, 0)).toString()
        } catch (e: Exception) {
            foregroundPkg
        }
        val screen: Boolean? = if (!p.sensorOn("screen")) null else ctx.getSystemService(PowerManager::class.java).isInteractive
        val phones: Boolean? = if (!p.sensorOn("headphones")) null else try {
            val am = ctx.getSystemService(AudioManager::class.java)
            val wanted = setOf(
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_USB_HEADSET,
            )
            am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in wanted }
        } catch (e: Exception) {
            null
        }
        val dnd: Boolean? = if (!p.sensorOn("dnd")) null else try {
            val f = ctx.getSystemService(NotificationManager::class.java).currentInterruptionFilter
            f != NotificationManager.INTERRUPTION_FILTER_ALL && f != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
        } catch (e: Exception) {
            null
        }
        val place: String? = if (!p.sensorOn("place") || !p.hasHome || p.place.isEmpty()) null else p.place
        val next: String? = if (!p.sensorOn("calendar")) null else nextEvent(ctx)
        return Situation(
            SimpleDateFormat("HH:mm", Locale.US).format(Date()), wd, minutes, battery, charging, network, app, screen, phones,
            dnd, place, next, sleepWindow(p),
        )
    }

    private fun nextEvent(ctx: Context): String? {
        if (ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) return null
        return try {
            val now = System.currentTimeMillis()
            val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
                .also { ContentUris.appendId(it, now); ContentUris.appendId(it, now + 24 * 3_600_000L) }.build()
            ctx.contentResolver.query(
                uri, arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN), null, null,
                "${CalendarContract.Instances.BEGIN} ASC",
            )?.use { c ->
                if (c.moveToFirst()) "${SimpleDateFormat("HH:mm", Locale.US).format(Date(c.getLong(1)))} ${c.getString(0).orEmpty().take(40)}" else null
            }
        } catch (e: Exception) {
            null
        }
    }

    // ---- sleep-window guess: hours in which the screen was (almost) always off ----

    private fun readStats(p: Prefs): Array<IntArray> {
        val parts = p.hourStats.split("|")
        return Array(24) { h ->
            val q = parts.getOrNull(h)?.split(",")
            intArrayOf(q?.getOrNull(0)?.toIntOrNull() ?: 0, q?.getOrNull(1)?.toIntOrNull() ?: 0)
        }
    }

    /** Called by the 15-minute background worker. */
    fun recordHour(ctx: Context) {
        val p = Prefs(ctx)
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val st = readStats(p)
        st[h][0]++
        if (!ctx.getSystemService(PowerManager::class.java).isInteractive) st[h][1]++
        if (st[h][0] > 400) { st[h][0] /= 2; st[h][1] /= 2 }  // keep it recent
        p.hourStats = st.joinToString("|") { "${it[0]},${it[1]}" }
    }

    fun sleepWindow(p: Prefs): String {
        val st = readStats(p)
        val quiet = BooleanArray(24) { st[it][0] >= 3 && st[it][1] * 10 >= st[it][0] * 9 }
        var best = 0
        var bestStart = 0
        for (s in 0 until 24) {
            var len = 0
            while (len < 24 && quiet[(s + len) % 24]) len++
            if (len > best) { best = len; bestStart = s }
        }
        return if (best >= 3) "${hhmm(bestStart * 60)}-${hhmm(((bestStart + best) % 24) * 60)} (अंदाज़ा)" else "23:00-07:00 (डिफ़ॉल्ट अंदाज़ा)"
    }
}
