package com.stark.family

import android.Manifest
import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.Process
import android.os.StatFs
import android.provider.CalendarContract
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.Settings
import android.telephony.SmsManager
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import java.util.Calendar
import java.util.Locale

/**
 * The second half of the PDF's tool table (section 9): calendar, brightness, Bluetooth, device info, clipboard,
 * media, memory recall, usage stats, notifications, SMS / WhatsApp / share, cache cleanup, rule editing.
 * Risk is decided here, in code: anything that sends something out, reads other apps' notifications,
 * deletes or changes rules is HIGH, so the app asks first, every time. Tools.plan() falls through to this object.
 */
object MoreTools {
    val RISK: Map<String, Risk> = mapOf(
        "add_calendar_event" to Risk.LOW, "set_brightness" to Risk.LOW, "bluetooth_panel" to Risk.LOW,
        "device_info" to Risk.LOW, "copy_text" to Risk.LOW, "media_control" to Risk.LOW, "recall" to Risk.LOW,
        "usage_stats" to Risk.LOW, "delegate" to Risk.LOW,
        "read_notifications" to Risk.HIGH, "dismiss_notifications" to Risk.HIGH, "send_sms" to Risk.HIGH,
        "send_whatsapp" to Risk.HIGH, "share_text" to Risk.HIGH, "clear_cache" to Risk.HIGH, "edit_rule" to Risk.HIGH,
        "camera_look" to Risk.HIGH, "reply_notification" to Risk.HIGH, "find_file" to Risk.LOW,
        "organise_downloads" to Risk.HIGH, "undo_organise" to Risk.HIGH, "delete_file" to Risk.HIGH, "restore_file" to Risk.HIGH,
        "search_in_app" to Risk.LOW,
    )

    /** Tools whose result is raw data: the character should explain it to the user in its own words. */
    val EXPLAIN: Set<String> = setOf("read_notifications", "usage_stats", "device_info", "recall")

    private fun launch(ctx: Context, i: Intent): Boolean = try {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }

    private fun fail(msg: String) = ToolPlan(Risk.LOW, "") { ToolResult(false, msg) }

    /** Every run() body is wrapped: a failure becomes an honest message, never a crash of the bubble service. */
    private fun act(risk: Risk, description: String, block: suspend () -> ToolResult) = ToolPlan(risk, description) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            ToolResult(false, "इसके लिए अनुमति नहीं है।")
        } catch (e: RelayClient.RelayException) {
            ToolResult(false, e.message ?: "relay से जवाब नहीं मिला।")
        } catch (e: Exception) {
            ToolResult(false, "यह नहीं हो पाया (${e.message ?: e.javaClass.simpleName}).")
        }
    }

    fun plan(ctx: Context, c: ToolCall, character: String): ToolPlan? = when (c.name) {
        "add_calendar_event" -> {
            val title = c.args.getString("title")
            val date = c.args.getString("date")
            val h = c.args.getInt("hour")
            val m = c.args.optInt("minute", 0)
            val dur = c.args.optInt("duration_min", 60)
            act(Risk.LOW, "कैलेंडर में: $title ($date ${two(h)}:${two(m)})") {
                val p = date.split("-").map { it.toInt() }
                val cal = Calendar.getInstance().apply { set(p[0], p[1] - 1, p[2], h, m, 0); set(Calendar.MILLISECOND, 0) }
                val begin = cal.timeInMillis
                val i = Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
                    .putExtra(CalendarContract.Events.TITLE, title)
                    .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin)
                    .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, begin + dur * 60_000L)
                if (launch(ctx, i)) ToolResult(true, "कैलेंडर में '$title' भरकर खोल दिया। 'सेव' आप दबाइए।")
                else ToolResult(false, "कैलेंडर ऐप नहीं खुला।")
            }
        }
        "set_brightness" -> {
            val pct = c.args.getInt("percent")
            act(Risk.LOW, "चमक $pct%") {
                if (!Settings.System.canWrite(ctx)) {
                    launch(ctx, Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).setData(Uri.parse("package:" + ctx.packageName)))
                    ToolResult(false, "चमक बदलने की अनुमति नहीं है। जो सेटिंग खुली है उसमें 'Stark Family' को चालू कीजिए।")
                } else {
                    Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                    Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, (pct * 255 / 100).coerceIn(0, 255))
                    ToolResult(true, "चमक $pct% कर दी।")
                }
            }
        }
        "bluetooth_panel" -> act(Risk.LOW, "Bluetooth की सेटिंग") {
            if (launch(ctx, Intent(Settings.ACTION_BLUETOOTH_SETTINGS))) ToolResult(true, "Bluetooth की सेटिंग खोल दी। स्विच आप दबाइए (Android अपने-आप नहीं करने देता)।")
            else ToolResult(false, "Bluetooth की सेटिंग नहीं खुली।")
        }
        "device_info" -> {
            val what = c.args.optString("what", "all")
            act(Risk.LOW, "फ़ोन की जानकारी: $what") {
                val lines = mutableListOf<String>()
                if (what == "battery" || what == "all") lines.add(batteryLine(ctx))
                if (what == "storage" || what == "all") lines.add(storageLine())
                if (what == "network" || what == "all") lines.add(networkLine(ctx))
                ToolResult(true, lines.joinToString("\n"))
            }
        }
        "copy_text" -> {
            val text = c.args.getString("text")
            act(Risk.LOW, "कॉपी करना") {
                if (AgentAccessibilityService.redact(text) != text) {
                    ToolResult(false, "इसमें OTP या कार्ड जैसा नंबर लग रहा है; सुरक्षा के लिए कॉपी नहीं किया।")
                } else {
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("Stark Family", text))
                    ToolResult(true, "क्लिपबोर्ड में कॉपी कर दिया।")
                }
            }
        }
        "media_control" -> {
            val action = c.args.getString("action")
            act(Risk.LOW, "मीडिया: $action") {
                val code = when (action) {
                    "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
                    "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
                    "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
                    else -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
                }
                val am = ctx.getSystemService(AudioManager::class.java)
                am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
                am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
                ToolResult(true, "'$action' भेज दिया। असर तभी होगा जब कोई म्यूज़िक/वीडियो ऐप चल रहा हो।")
            }
        }
        "recall" -> {
            val q = c.args.getString("query")
            act(Risk.LOW, "याददाश्त में खोज: $q") {
                val hits = MemoryRepo.get(ctx).relevant(q, character).take(6)
                if (hits.isEmpty()) ToolResult(true, "याददाश्त में '$q' से जुड़ा कुछ नहीं मिला।")
                else ToolResult(true, hits.joinToString("\n") { "• " + it.take(220) })
            }
        }
        "usage_stats" -> {
            val days = c.args.optInt("days", 1)
            act(Risk.LOW, "पिछले $days दिन का ऐप-उपयोग") {
                if (!usageAllowed(ctx)) {
                    launch(ctx, Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                    ToolResult(false, "ऐप-उपयोग देखने की अनुमति नहीं है। जो सेटिंग खुली है उसमें 'Stark Family' चालू कीजिए।")
                } else {
                    ToolResult(true, usageLines(ctx, days))
                }
            }
        }
        "read_notifications" -> {
            val n = c.args.optInt("limit", 10).coerceIn(1, 20)
            act(Risk.HIGH, "नोटिफ़िकेशन पढ़ना (आख़िरी $n)") {
                if (!NotifyListener.enabled(ctx)) {
                    launch(ctx, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    ToolResult(false, "नोटिफ़िकेशन पढ़ने की अनुमति नहीं है। जो सेटिंग खुली है उसमें 'Stark Family' चालू कीजिए।")
                } else {
                    val items = NotifyListener.recent(n)
                    if (items.isEmpty()) ToolResult(true, "अभी कोई नया नोटिफ़िकेशन नहीं है (सेवा अभी चालू हुई हो तो पुराने नहीं दिखते)।")
                    else ToolResult(true, items.joinToString("\n") { "• ${it.app}: ${it.title} — ${it.text}".take(220) })
                }
            }
        }
        "dismiss_notifications" -> {
            val app = c.args.getString("app")
            act(Risk.HIGH, "'$app' के नोटिफ़िकेशन हटाना") {
                if (!NotifyListener.enabled(ctx)) {
                    ToolResult(false, "नोटिफ़िकेशन-अनुमति नहीं है, इसलिए कुछ हटा नहीं सकता।")
                } else {
                    val n = NotifyListener.dismiss(app)
                    if (n > 0) ToolResult(true, "'$app' के $n नोटिफ़िकेशन हटा दिए।") else ToolResult(false, "'$app' का कोई हटाने लायक़ नोटिफ़िकेशन नहीं मिला।")
                }
            }
        }
        "send_sms" -> contactPlan(ctx, c.args.getString("contact"), c.args.getString("text"), whatsapp = false)
        "send_whatsapp" -> contactPlan(ctx, c.args.getString("contact"), c.args.getString("text"), whatsapp = true)
        "share_text" -> {
            val text = c.args.getString("text")
            act(Risk.HIGH, "साझा करना: \"${text.take(80)}\"") {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                if (launch(ctx, Intent.createChooser(send, "साझा करें"))) ToolResult(true, "साझा करने की सूची खुल गई। किसे भेजना है, वह आप चुनिए।")
                else ToolResult(false, "साझा करने की सूची नहीं खुली।")
            }
        }
        "clear_cache" -> act(Risk.HIGH, "इस ऐप का कैश साफ़ करना और स्टोरेज-सेटिंग खोलना") {
            var bytes = 0L
            for (d in listOfNotNull(ctx.cacheDir, ctx.externalCacheDir)) {
                bytes += d.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                d.listFiles()?.forEach { it.deleteRecursively() }
            }
            val opened = launch(ctx, Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS))
            val mb = String.format(Locale.US, "%.1f", bytes / 1_048_576.0)
            ToolResult(
                true,
                "इस ऐप का $mb MB कैश साफ़ किया। दूसरे ऐप का कैश Android मुझे साफ़ नहीं करने देता" +
                    if (opened) "; स्टोरेज-सेटिंग खोल दी है, वहाँ ऐप चुनकर 'कैश साफ़ करें' दबाइए।" else "; सेटिंग > स्टोरेज में जाकर ख़ुद साफ़ कीजिए।",
            )
        }
        "edit_rule" -> {
            val name = c.args.getString("name")
            val action = c.args.getString("action")
            val verb = when (action) { "enable" -> "चालू करना"; "disable" -> "बंद करना"; else -> "हटाना" }
            act(Risk.HIGH, "नियम '$name' $verb") {
                val repo = RuleRepo.get(ctx)
                val all = repo.dao.rules()
                val r = all.firstOrNull { it.name.equals(name, ignoreCase = true) }
                    ?: all.firstOrNull { it.name.lowercase().contains(name.lowercase()) }
                if (r == null) {
                    ToolResult(false, "'$name' नाम का कोई नियम नहीं मिला (नियम-स्क्रीन 🧭 में नाम देखिए)।")
                } else {
                    when (action) {
                        "enable" -> repo.dao.updateRule(r.copy(enabled = true))
                        "disable" -> repo.dao.updateRule(r.copy(enabled = false))
                        else -> { repo.dao.deleteRule(r.id); repo.dao.deleteLogsOf(r.id) }
                    }
                    repo.afterChange()
                    ToolResult(true, "नियम '${r.name}' $verb हो गया।")
                }
            }
        }
        "camera_look" -> {
            val mode = c.args.optString("mode", "ask")
            val q = c.args.optString("question", "")
            act(Risk.HIGH, "कैमरे से एक तस्वीर लेना ($mode)।\nतस्वीर सिर्फ़ आपके relay और LLM को जाएगी और पढ़ने के बाद मिटा दी जाएगी।") {
                val p = Prefs(ctx)
                val file = CameraBridge.capture(ctx) ?: return@act ToolResult(false, "तस्वीर नहीं ली गई (कैमरा बंद हुआ या समय पूरा हुआ)।")
                try {
                    val b64 = CameraBridge.encode(file) ?: return@act ToolResult(false, "तस्वीर पढ़ने लायक़ नहीं बन पाई।")
                    val j = RelayClient().visionDescribe(p.relayUrl, p.token, character, mode, q, b64)
                    if (j.optBoolean("degraded", false)) {
                        ToolResult(false, "अभी तस्वीर पढ़ने वाला मॉडल व्यस्त है। थोड़ी देर बाद फिर कोशिश कीजिए।")
                    } else {
                        val text = j.optString("text")
                        ToolResult(true, j.optString("answer") + if (text.isNotBlank()) "\n\nतस्वीर में लिखा:\n$text" else "")
                    }
                } finally {
                    file.delete()
                }
            }
        }
        "reply_notification" -> {
            val app = c.args.getString("app")
            val text = c.args.getString("text")
            val last = NotifyListener.recent(40).firstOrNull { it.app.lowercase().contains(app.lowercase()) }
            val preview = last?.let { "${it.app}: ${it.title} — ${it.text}".take(120) } ?: "(हाल की कोई सूचना नहीं दिख रही)"
            act(Risk.HIGH, "जवाब देना → $app\nसूचना: $preview\nजवाब: \"$text\"") {
                if (!NotifyListener.enabled(ctx)) {
                    ToolResult(false, "नोटिफ़िकेशन-अनुमति चालू नहीं है।")
                } else {
                    val err = NotifyListener.reply(ctx, app, text)
                    if (err == null) ToolResult(true, "'$app' की सूचना का जवाब भेज दिया।") else ToolResult(false, err)
                }
            }
        }
        "find_file" -> {
            val name = c.args.getString("name")
            act(Risk.LOW, "फ़ाइल ढूँढना: $name") {
                if (!FileTools.hasAccess(ctx)) openFilesAccess(ctx)
                val (ok, msg) = FileTools.findText(ctx, name)
                ToolResult(ok, msg)
            }
        }
        "organise_downloads" -> act(Risk.HIGH, FileTools.previewOrganise()) {
            if (!FileTools.hasAccess(ctx)) openFilesAccess(ctx)
            val (ok, msg) = FileTools.organise(ctx)
            ToolResult(ok, msg)
        }
        "undo_organise" -> act(Risk.HIGH, "पिछली 'Downloads सजाना' कार्रवाई पलटना") {
            val (ok, msg) = FileTools.undoOrganise(ctx)
            ToolResult(ok, msg)
        }
        "delete_file" -> {
            val name = c.args.getString("name")
            act(Risk.HIGH, FileTools.previewDelete(name)) {
                if (!FileTools.hasAccess(ctx)) openFilesAccess(ctx)
                val (ok, msg) = FileTools.delete(ctx, name)
                ToolResult(ok, msg)
            }
        }
        "restore_file" -> {
            val name = c.args.getString("name")
            act(Risk.HIGH, "कचरे से वापस लाना: $name") {
                val (ok, msg) = FileTools.restore(ctx, name)
                ToolResult(ok, msg)
            }
        }
        "search_in_app" -> fail("ऐप के अंदर खोज सिर्फ़ बबल (स्क्रीन एजेंट) से चलती है।")
        else -> null
    }

    private fun openFilesAccess(ctx: Context) {
        if (Build.VERSION.SDK_INT >= 30) {
            launch(ctx, Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + ctx.packageName)))
        }
    }

    // ---------------- helpers ----------------

    private fun two(n: Int) = String.format(Locale.US, "%02d", n)

    private fun batteryLine(ctx: Context): String {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val status = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
        return if (pct < 0) "बैटरी: पता नहीं चला" else "बैटरी: $pct%" + if (charging) " (चार्ज हो रही है)" else ""
    }

    private fun storageLine(): String {
        val st = StatFs(Environment.getDataDirectory().path)
        val free = st.availableBytes / 1e9
        val total = st.totalBytes / 1e9
        return "स्टोरेज: " + String.format(Locale.US, "%.1f", free) + " GB ख़ाली / " + String.format(Locale.US, "%.1f", total) + " GB"
    }

    private fun networkLine(ctx: Context): String {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        val kind = when {
            caps == null -> "कोई नेटवर्क नहीं"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "मोबाइल डेटा"
            else -> "नेटवर्क चालू"
        }
        return "नेटवर्क: $kind"
    }

    @Suppress("DEPRECATION")
    fun usageAllowed(ctx: Context): Boolean {
        val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        return ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName) == AppOpsManager.MODE_ALLOWED
    }

    private fun usageLines(ctx: Context, days: Int): String {
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val end = System.currentTimeMillis()
        val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, end - days * 86_400_000L, end) ?: return "ऐप-उपयोग का डेटा नहीं मिला।"
        val per = HashMap<String, Long>()
        for (s in stats) per[s.packageName] = (per[s.packageName] ?: 0L) + s.totalTimeInForeground
        val pm = ctx.packageManager
        val rows = per.entries
            .filter { e ->
                val p = e.key.lowercase()
                e.value >= 60_000L && e.key != ctx.packageName && !p.contains("launcher") && !p.contains("systemui") && !p.contains("inputmethod")
            }
            .sortedByDescending { it.value }.take(8)
        if (rows.isEmpty()) return "पिछले $days दिन का कोई ख़ास ऐप-उपयोग दर्ज नहीं हुआ।"
        return "पिछले $days दिन में सबसे ज़्यादा समय:\n" + rows.mapIndexed { idx, e ->
            val label = try { pm.getApplicationLabel(pm.getApplicationInfo(e.key, 0)).toString() } catch (_: Exception) { e.key }
            "${idx + 1}. $label — ${e.value / 60_000L} मिनट"
        }.joinToString("\n")
    }

    // ---------------- SMS / WhatsApp: resolve the person, read the message back, then ask ----------------

    private fun waDigits(n: String): String {
        var d = n.filter { it.isDigit() }
        if (d.length == 11 && d.startsWith("0")) d = d.drop(1)
        if (d.length == 10) d = "91$d" // bare 10-digit Indian numbers; numbers saved with a country code are kept as they are
        return d
    }

    @Suppress("DEPRECATION")
    private fun smsManager(ctx: Context): SmsManager =
        if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(SmsManager::class.java) else SmsManager.getDefault()

    private fun contactPlan(ctx: Context, who: String, text: String, whatsapp: Boolean): ToolPlan {
        val q = who.trim()
        val number: String
        val shown: String
        if (q.count { it.isDigit() } >= 5 && q.all { it.isDigit() || it in "+ -()" }) {
            number = q.filter { it.isDigit() || it == '+' }
            shown = number
        } else {
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
                return fail("संपर्क पढ़ने की अनुमति नहीं है। ऐप खोलकर अनुमति दीजिए, या सीधा नंबर बताइए।")
            }
            val found = mutableListOf<Pair<String, String>>()
            ctx.contentResolver.query(
                Phone.CONTENT_URI, arrayOf(Phone.DISPLAY_NAME, Phone.NUMBER),
                "${Phone.DISPLAY_NAME} LIKE ?", arrayOf("%$q%"), "${Phone.DISPLAY_NAME} ASC",
            )?.use { cur -> while (cur.moveToNext() && found.size < 5) found.add(cur.getString(0) to cur.getString(1)) }
            val best = found.firstOrNull { it.first.equals(q, ignoreCase = true) } ?: found.firstOrNull()
                ?: return fail("'$q' नाम का संपर्क नहीं मिला। संपर्क में जैसा नाम है वैसा बोलिए, या नंबर बताइए।")
            number = best.second.filter { it.isDigit() || it == '+' }
            shown = best.first + if (found.map { it.first }.distinct().size > 1) " (और भी मिले, पहला लिया)" else ""
        }
        val kind = if (whatsapp) "WhatsApp" else "SMS"
        return act(Risk.HIGH, "$kind → $shown ($number)\nसंदेश: \"$text\"") {
            if (whatsapp) {
                val uri = Uri.parse("https://wa.me/" + waDigits(number) + "?text=" + Uri.encode(text))
                if (launch(ctx, Intent(Intent.ACTION_VIEW, uri))) ToolResult(true, "WhatsApp में संदेश तैयार है। भेजने का बटन आप दबाइए।")
                else ToolResult(false, "WhatsApp नहीं खुला।")
            } else if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED) {
                val sm = smsManager(ctx)
                sm.sendMultipartTextMessage(number, null, sm.divideMessage(text), null, null)
                ToolResult(true, "SMS सिस्टम को सौंप दिया ($shown)। पहुँचने की पुष्टि फ़ोन नहीं देता।")
            } else {
                val i = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(number))).putExtra("sms_body", text)
                if (launch(ctx, i)) ToolResult(true, "SMS लिखा हुआ खुल गया (सीधे भेजने की अनुमति नहीं है)। भेजने का बटन आप दबाइए।")
                else ToolResult(false, "SMS ऐप नहीं खुला।")
            }
        }
    }
}
