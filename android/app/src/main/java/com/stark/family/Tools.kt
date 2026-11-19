package com.stark.family

import android.Manifest
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.Settings
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class Risk { LOW, HIGH }

/** One tool call proposed by the model (already validated by the relay, re-checked here). */
data class ToolCall(val id: String, val name: String, val args: JSONObject, val risk: String, val confirm: Boolean)

data class ToolResult(val ok: Boolean, val message: String)

/** description is shown to the user before anything risky runs; run() does the real work. */
class ToolPlan(val risk: Risk, val description: String, val run: suspend () -> ToolResult)

object Tools {
    /** Risk is decided in app code. The model's or relay's word alone never lowers it. */
    private val RISK = mapOf(
        "set_alarm" to Risk.LOW, "set_timer" to Risk.LOW, "set_reminder" to Risk.LOW,
        "open_app" to Risk.LOW, "torch" to Risk.LOW, "call_contact" to Risk.HIGH, "remember_fact" to Risk.LOW,
        "screen_task" to Risk.LOW, "set_dnd" to Risk.LOW, "set_volume" to Risk.LOW, "wifi_panel" to Risk.LOW, "battery_saver" to Risk.LOW,
        "propose_rule" to Risk.LOW,
    )
    val NAMES: List<String> = RISK.keys.toList()

    /** In the main chat screen only memory tools run; phone actions (alarm, call, torch…) come from the bubble. */
    val MEMORY_NAMES: List<String> = listOf("remember_fact", "propose_rule")

    fun needsConfirm(c: ToolCall): Boolean = (RISK[c.name] ?: Risk.HIGH) != Risk.LOW || c.confirm

    /** Small situation snapshot sent with each request (the model needs 'now' to resolve "6 बजे"). */
    fun context(): JSONObject {
        val now = Date()
        return JSONObject()
            .put("now", SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(now))
            .put("weekday", SimpleDateFormat("EEEE", Locale("hi", "IN")).format(now))
    }

    /** now + weekday + whatever sensors are switched on (battery, place, foreground app, DND, next event ...). */
    fun context(ctx: Context): JSONObject {
        val o = context()
        try {
            val s = ContextEngine.snapshot(ctx).toJson()
            s.keys().forEach { k -> if (!o.has(k)) o.put(k, s.get(k)) }
        } catch (_: Exception) { }
        return o
    }

    private fun fail(msg: String) = ToolPlan(Risk.LOW, "") { ToolResult(false, msg) }

    private fun hhmm(h: Int, m: Int) = String.format(Locale.US, "%02d:%02d", h, m)

    private fun launch(ctx: Context, i: Intent): Boolean = try {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }

    fun plan(ctx: Context, c: ToolCall, character: String = ""): ToolPlan = try {
        when (c.name) {
            "set_alarm", "set_reminder" -> {
                val h = c.args.getInt("hour")
                val m = c.args.optInt("minute", 0)
                val label = if (c.name == "set_reminder") "याद: " + c.args.optString("text") else c.args.optString("label")
                ToolPlan(Risk.LOW, "अलार्म ${hhmm(h, m)}") {
                    val i = Intent(AlarmClock.ACTION_SET_ALARM)
                        .putExtra(AlarmClock.EXTRA_HOUR, h)
                        .putExtra(AlarmClock.EXTRA_MINUTES, m)
                        .putExtra(AlarmClock.EXTRA_MESSAGE, label)
                        .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    if (launch(ctx, i)) ToolResult(true, "क्लॉक ऐप को ${hhmm(h, m)} का अलार्म भेज दिया। क्लॉक में एक बार देख लीजिए।")
                    else ToolResult(false, "क्लॉक ऐप नहीं खुला, अलार्म नहीं लगा।")
                }
            }
            "set_timer" -> {
                val s = c.args.getInt("seconds")
                ToolPlan(Risk.LOW, "टाइमर $s सेकंड") {
                    val i = Intent(AlarmClock.ACTION_SET_TIMER)
                        .putExtra(AlarmClock.EXTRA_LENGTH, s)
                        .putExtra(AlarmClock.EXTRA_MESSAGE, c.args.optString("label"))
                        .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    if (launch(ctx, i)) ToolResult(true, "क्लॉक ऐप को ${s / 60} मिनट ${s % 60} सेकंड का टाइमर भेज दिया। क्लॉक में देख लीजिए।")
                    else ToolResult(false, "क्लॉक ऐप नहीं खुला, टाइमर नहीं लगा।")
                }
            }
            "open_app" -> {
                val name = c.args.getString("app")
                ToolPlan(Risk.LOW, "ऐप खोलना: $name") { openApp(ctx, name) }
            }
            "torch" -> {
                val on = c.args.getBoolean("on")
                ToolPlan(Risk.LOW, if (on) "टॉर्च चालू" else "टॉर्च बंद") { torch(ctx, on) }
            }
            "remember_fact" -> {
                val f = c.args.getString("fact")
                val imp = c.args.optInt("importance", 3)
                ToolPlan(Risk.LOW, "याद रखना: $f") {
                    if (MemoryRepo.get(ctx).addFact(f, imp, "told", character)) ToolResult(true, "याद रख लिया: $f")
                    else ToolResult(false, "यह याद नहीं रखा (बहुत छोटी या संवेदनशील बात)।")
                }
            }
            "set_dnd" -> {
                val on = c.args.getBoolean("on")
                ToolPlan(Risk.LOW, if (on) "DND चालू" else "DND बंद") {
                    val nm = ctx.getSystemService(NotificationManager::class.java)
                    if (!nm.isNotificationPolicyAccessGranted) {
                        launch(ctx, Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                        ToolResult(false, "DND बदलने की अनुमति नहीं है। जो सेटिंग खुली है उसमें 'Stark Family' को चालू कीजिए।")
                    } else {
                        nm.setInterruptionFilter(if (on) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL)
                        ToolResult(true, if (on) "DND चालू कर दिया।" else "DND बंद कर दिया।")
                    }
                }
            }
            "set_volume" -> {
                val name = c.args.optString("stream", "media")
                val pct = c.args.getInt("percent")
                val stream = when (name) {
                    "ring" -> AudioManager.STREAM_RING
                    "alarm" -> AudioManager.STREAM_ALARM
                    "notification" -> AudioManager.STREAM_NOTIFICATION
                    else -> AudioManager.STREAM_MUSIC
                }
                ToolPlan(Risk.LOW, "आवाज़ ($name) $pct%") {
                    try {
                        val am = ctx.getSystemService(AudioManager::class.java)
                        val max = am.getStreamMaxVolume(stream)
                        am.setStreamVolume(stream, Math.round(max * pct / 100.0).toInt().coerceIn(0, max), 0)
                        ToolResult(true, "आवाज़ ($name) $pct% पर कर दी।")
                    } catch (e: SecurityException) {
                        ToolResult(false, "यह आवाज़ बदलने के लिए DND की अनुमति चाहिए।")
                    }
                }
            }
            "wifi_panel" -> ToolPlan(Risk.LOW, "Wi-Fi का पैनल") {
                if (Build.VERSION.SDK_INT >= 29 && launch(ctx, Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY))) {
                    ToolResult(true, "Wi-Fi का पैनल खोल दिया। Android अपने-आप Wi-Fi चालू नहीं करने देता, स्विच आप दबाइए।")
                } else if (launch(ctx, Intent(Settings.ACTION_WIFI_SETTINGS))) {
                    ToolResult(true, "Wi-Fi की सेटिंग खोल दी। स्विच आप दबाइए।")
                } else {
                    ToolResult(false, "Wi-Fi का पैनल नहीं खुला।")
                }
            }
            "battery_saver" -> ToolPlan(Risk.LOW, "बैटरी सेवर की सेटिंग") {
                if (launch(ctx, Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS))) ToolResult(true, "बैटरी सेवर की सेटिंग खोल दी। चालू करने का स्विच आप दबाइए।")
                else ToolResult(false, "बैटरी सेवर की सेटिंग नहीं खुली।")
            }
            "propose_rule" -> fail("नियम सिखाना सिर्फ़ चैट से होता है (मंज़ूरी के साथ)।")
            "screen_task" -> fail("स्क्रीन-काम सिर्फ़ बबल से चलता है।")
            "call_contact" -> planCall(ctx, c.args.getString("contact"))
            else -> fail("यह औज़ार मेरे पास नहीं है: ${c.name}")
        }
    } catch (e: Exception) {
        fail("औज़ार के विवरण में गड़बड़ थी, इसलिए कुछ नहीं किया।")
    }

    private val ALIASES = mapOf(
        "यूट्यूब" to "youtube", "व्हाट्सएप" to "whatsapp", "व्हाट्सऐप" to "whatsapp", "वॉट्सऐप" to "whatsapp",
        "क्रोम" to "chrome", "कैमरा" to "camera", "सेटिंग" to "settings", "सेटिंग्स" to "settings",
        "गैलरी" to "gallery", "मैप्स" to "maps", "फोन" to "phone", "फ़ोन" to "phone",
        "घड़ी" to "clock", "क्लॉक" to "clock", "कैलकुलेटर" to "calculator", "जीमेल" to "gmail",
        "इंस्टाग्राम" to "instagram",
    )

    private fun norm(s: String) = s.lowercase().filter { !it.isWhitespace() && it != '-' && it != '.' && it != '_' }

    fun openApp(ctx: Context, asked: String): ToolResult {
        val pm = ctx.packageManager
        val q = norm(ALIASES[asked.trim()] ?: asked)
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
        val hit = apps.firstOrNull { norm(it.first) == q }
            ?: apps.filter { norm(it.first).contains(q) }.minByOrNull { it.first.length }
            ?: apps.firstOrNull { it.second.lowercase().contains(q) }
            ?: return ToolResult(false, "'$asked' नाम का ऐप नहीं मिला।")
        val i = pm.getLaunchIntentForPackage(hit.second) ?: return ToolResult(false, "${hit.first} खोला नहीं जा सका।")
        return if (launch(ctx, i)) ToolResult(true, "${hit.first} खोल दिया।") else ToolResult(false, "${hit.first} खुला नहीं।")
    }

    private fun torch(ctx: Context, on: Boolean): ToolResult = try {
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
        if (id == null) {
            ToolResult(false, "इस फ़ोन में टॉर्च नहीं मिली।")
        } else {
            cm.setTorchMode(id, on)
            ToolResult(true, if (on) "टॉर्च चालू कर दी।" else "टॉर्च बंद कर दी।")
        }
    } catch (e: Exception) {
        ToolResult(false, "टॉर्च नहीं बदल पाई (कैमरा किसी और ऐप में चल रहा हो सकता है)।")
    }

    private fun planCall(ctx: Context, who: String): ToolPlan {
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
            )?.use { cur ->
                while (cur.moveToNext() && found.size < 5) found.add(cur.getString(0) to cur.getString(1))
            }
            val best = found.firstOrNull { it.first.equals(q, ignoreCase = true) } ?: found.firstOrNull()
                ?: return fail("'$q' नाम का संपर्क नहीं मिला। संपर्क में जैसा नाम है वैसा बोलिए, या नंबर बताइए।")
            number = best.second.filter { it.isDigit() || it == '+' }
            shown = best.first + if (found.map { it.first }.distinct().size > 1) " (और भी मिले, पहला लिया)" else ""
        }
        return ToolPlan(Risk.HIGH, "कॉल: $shown  ($number)") {
            if (launch(ctx, Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number))))) {
                ToolResult(true, "डायलर खुल गया। वहाँ हरा बटन दबाकर कॉल कीजिए।")
            } else {
                ToolResult(false, "डायलर नहीं खुला।")
            }
        }
    }
}
