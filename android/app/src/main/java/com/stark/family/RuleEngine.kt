package com.stark.family

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** type: time (data = "HH:MM"), enter_home, exit_home, app_open (data = "label package"), battery (data = level). */
class RuleEvent(val type: String, val data: String = "")

/**
 * Runs taught rules when their trigger fires. Level 1 only suggests (a notification with buttons), Level 2 does small
 * safe things by itself and asks for feedback, Level 3 asks first. Risky actions never run without a tap.
 * Every decision is logged with the reason ("why did this run").
 */
object RuleEngine {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile var appKeys: Set<String> = emptySet()
    private val labels = ConcurrentHashMap<String, String>()
    private const val COOLDOWN_MS = 5 * 60_000L
    private const val CH_RULES = "stark_rules"
    const val A_RUN = "com.stark.family.RULE_RUN"
    const val A_NO = "com.stark.family.RULE_NO"
    const val A_UP = "com.stark.family.RULE_UP"
    const val A_GOOD = "com.stark.family.RULE_GOOD"
    const val A_BAD = "com.stark.family.RULE_BAD"

    fun fireAsync(ctx: Context, ev: RuleEvent) {
        val app = ctx.applicationContext
        scope.launch { try { handle(app, ev) } catch (_: Exception) { } }
    }

    /** Called by the accessibility service on every window change: cheap exit when no rule watches apps. */
    fun onAppOpened(ctx: Context, pkg: String) {
        if (KillSwitch.on(ctx)) return
        Proactive.logApp(ctx, pkg)
        if (appKeys.isEmpty()) return
        val label = labels.getOrPut(pkg) {
            try {
                val pm = ctx.packageManager
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            } catch (e: Exception) {
                pkg
            }
        }
        val hay = (label + " " + pkg).lowercase()
        if (appKeys.any { hay.contains(it) }) fireAsync(ctx, RuleEvent("app_open", hay))
    }

    private fun matches(t: JSONObject, ev: RuleEvent, sit: Situation): Boolean = when (t.optString("type")) {
        "time" -> ev.type == "time" && t.optString("at") == ev.data && run {
            val d = t.optJSONArray("days")
            d == null || d.length() == 0 || (0 until d.length()).any { d.optInt(it) == sit.weekday }
        }
        "enter_home" -> ev.type == "enter_home"
        "exit_home" -> ev.type == "exit_home"
        "app_open" -> ev.type == "app_open" && ev.data.contains(t.optString("app").lowercase())
        "battery_below" -> ev.type == "battery" && (ev.data.toIntOrNull() ?: 100) < t.optInt("level") && sit.charging != true
        else -> false
    }

    private fun hm(s: String): Int = (s.substringBefore(":").toIntOrNull() ?: 0) * 60 + (s.substringAfter(":").toIntOrNull() ?: 0)

    /** (true, "") or (false, reason). A sensor that is off/unknown makes its condition fail, honestly. */
    private fun conditions(c: JSONObject, sit: Situation): Pair<Boolean, String> {
        val now = sit.minutes
        if (c.has("after") || c.has("before")) {
            val a = if (c.has("after")) hm(c.optString("after")) else null
            val b = if (c.has("before")) hm(c.optString("before")) else null
            val ok = when {
                a != null && b != null -> if (a <= b) now in a until b else now >= a || now < b
                a != null -> now >= a
                b != null -> now < b!!
                else -> true
            }
            if (!ok) return false to "समय की शर्त (${sit.time}) पूरी नहीं"
        }
        c.optJSONArray("days")?.let { d ->
            if (d.length() > 0 && (0 until d.length()).none { d.optInt(it) == sit.weekday }) return false to "आज का दिन शर्त में नहीं"
        }
        if (c.has("place") && sit.place != c.optString("place")) return false to "जगह: ${sit.place ?: "पता नहीं"}"
        if (c.has("charging") && sit.charging != c.optBoolean("charging")) return false to "चार्जिंग की शर्त पूरी नहीं"
        if (c.has("headphones") && sit.headphones != c.optBoolean("headphones")) return false to "हेडफ़ोन की शर्त पूरी नहीं"
        if (c.has("network") && sit.network != c.optString("network")) return false to "नेटवर्क: ${sit.network ?: "पता नहीं"}"
        if (c.has("screen_on") && sit.screenOn != c.optBoolean("screen_on")) return false to "स्क्रीन की शर्त पूरी नहीं"
        if (c.has("battery_below") && !((sit.battery ?: 100) < c.optInt("battery_below"))) return false to "बैटरी ${sit.battery ?: "?"}%"
        if (c.has("battery_above") && !((sit.battery ?: 0) > c.optInt("battery_above"))) return false to "बैटरी ${sit.battery ?: "?"}%"
        return true to ""
    }

    suspend fun handle(ctx: Context, ev: RuleEvent) {
        if (KillSwitch.on(ctx)) return
        val repo = RuleRepo.get(ctx)
        val rules = repo.dao.rules().filter { it.enabled }
        if (rules.isEmpty()) return
        val sit = ContextEngine.snapshot(ctx)
        val now = System.currentTimeMillis()
        for (r in rules) {
            val t = JSONObject(r.triggerJson)
            if (!matches(t, ev, sit)) continue
            if (now - r.lastFired < COOLDOWN_MS) {
                repo.log(r.id, ev.type, "skipped", "अभी-अभी चला था (5 मिनट का विराम)")
                continue
            }
            val (ok, why) = conditions(JSONObject(r.conditionsJson), sit)
            if (!ok) {
                repo.log(r.id, ev.type, "skipped", "शर्त पूरी नहीं: $why")
                continue
            }
            val cur = r.copy(lastFired = now)
            repo.dao.updateRule(cur)
            val reason = "${RuleModel.describeTrigger(t)} हुआ और शर्तें ठीक थीं"
            when (RuleModel.effectiveLevel(cur, Autonomy.allowedLevels(ctx, cur.speaker))) {
                1 -> suggest(ctx, cur, reason, ev.type)
                2 -> {
                    val res = runActions(ctx, cur, approved = false)
                    repo.log(cur.id, ev.type, "ran", "L2 अपने-आप: $reason", res.joinToString("; "))
                    report(ctx, cur, "किया", res.joinToString("\n"), feedback = true)
                }
                else -> ask(ctx, cur, reason, ev.type)
            }
        }
    }

    // ---------------- doing things ----------------

    suspend fun runActions(ctx: Context, r: Rule, approved: Boolean): List<String> {
        val out = mutableListOf<String>()
        val arr = JSONArray(r.actionsJson)
        for (i in 0 until arr.length()) {
            val a = arr.getJSONObject(i)
            val tool = a.optString("tool")
            val args = a.optJSONObject("args") ?: JSONObject()
            if (tool == "say") {
                val who = Characters.byId(args.optString("character"))
                speak(ctx, who, args.optString("text"))
                out.add("✓ ${who.name}: ‘${args.optString("text")}’")
                continue
            }
            val call = ToolCall("r$i", tool, args, "low", false)
            if (Tools.needsConfirm(call) && 3 !in Autonomy.allowedLevels(ctx, r.speaker)) {
                out.add("✗ ‘$tool’ ${Characters.byId(r.speaker).name} नहीं कर सकते (उनका स्तर: ${Autonomy.describe(ctx, r.speaker)})")
                continue
            }
            if (Tools.needsConfirm(call) && !approved) {
                out.add("✗ ‘$tool’ आपकी हाँ के बिना नहीं चलता")
                continue
            }
            val res = try {
                withContext(Dispatchers.Main) { Tools.plan(ctx, call, r.speaker).run() }
            } catch (e: Exception) {
                ToolResult(false, "क्रिया नहीं चल पाई")
            }
            out.add((if (res.ok) "✓ " else "✗ ") + res.message)
        }
        return out
    }

    // ---------------- notifications ----------------

    private fun notifId(ruleId: Long) = 20_000 + (ruleId % 5_000).toInt()

    private fun channel(ctx: Context): NotificationManager {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_RULES, "नियम और सुझाव", NotificationManager.IMPORTANCE_HIGH))
        return nm
    }

    private fun btn(ctx: Context, r: Rule, act: String, idx: Int): PendingIntent = PendingIntent.getBroadcast(
        ctx, (r.id * 8 + idx).toInt(), Intent(ctx, RuleActionReceiver::class.java).setAction(act).putExtra("rule", r.id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun post(ctx: Context, r: Rule, title: String, text: String, buttons: List<Pair<String, PendingIntent>>) {
        val nm = channel(ctx)
        val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val b = Notification.Builder(ctx, CH_RULES).setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(title)
            .setContentText(text.lineSequence().firstOrNull().orEmpty()).setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open).setAutoCancel(true)
        buttons.forEach { b.addAction(Notification.Action.Builder(0, it.first, it.second).build()) }
        nm.notify(notifId(r.id), b.build())
    }

    private suspend fun suggest(ctx: Context, r: Rule, why: String, event: String) {
        val who = Characters.byId(r.speaker)
        val btns = mutableListOf("✅ करो" to btn(ctx, r, A_RUN, 1), "❌ नहीं" to btn(ctx, r, A_NO, 2))
        if (r.maxLevel >= 2 && !RuleModel.hasHigh(r)) btns.add("⚡ हमेशा अपने-आप" to btn(ctx, r, A_UP, 3))
        post(ctx, r, "${who.name} · सुझाव", "${r.name}\n${RuleModel.describeActions(JSONArray(r.actionsJson)).joinToString("\n") { "• $it" }}\n\nक्यों: $why", btns)
        RuleRepo.get(ctx).log(r.id, event, "suggested", "L1 सिर्फ़ सुझाव: $why")
    }

    private suspend fun ask(ctx: Context, r: Rule, why: String, event: String) {
        val who = Characters.byId(r.speaker)
        post(
            ctx, r, "${who.name} · पूछ रहा हूँ",
            "${r.name}: करूँ?\n${RuleModel.describeActions(JSONArray(r.actionsJson)).joinToString("\n") { "• $it" }}\n\nक्यों: $why",
            listOf("✅ हाँ" to btn(ctx, r, A_RUN, 1), "❌ नहीं" to btn(ctx, r, A_NO, 2)),
        )
        RuleRepo.get(ctx).log(r.id, event, "asked", "L3 पहले पूछना: $why")
    }

    private fun report(ctx: Context, r: Rule, head: String, text: String, feedback: Boolean) {
        val who = Characters.byId(r.speaker)
        post(ctx, r, "${who.name} · $head: ${r.name}", text, if (feedback) listOf("👍 अच्छा" to btn(ctx, r, A_GOOD, 4), "👎 मत करो" to btn(ctx, r, A_BAD, 5)) else emptyList())
    }

    /** A character "speaks": a notification for now (real voices come with the voice milestone). */
    private fun speak(ctx: Context, who: Character, text: String) {
        val nm = channel(ctx)
        val n = Notification.Builder(ctx, CH_RULES).setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(who.name)
            .setContentText(text).setStyle(Notification.BigTextStyle().bigText(text)).setAutoCancel(true).build()
        nm.notify(30_000 + (System.currentTimeMillis() % 5_000).toInt(), n)
    }

    // ---------------- the user's answers ----------------

    suspend fun onUserAction(ctx: Context, id: Long, act: String) {
        val repo = RuleRepo.get(ctx)
        val r = repo.dao.rule(id) ?: return
        ctx.getSystemService(NotificationManager::class.java).cancel(notifId(id))
        when (act) {
            A_RUN -> {
                val res = runActions(ctx, r, approved = true)
                var cur = r
                if (r.level == 1 && !RuleModel.hasHigh(r)) {
                    cur = r.copy(good = r.good + 1)
                    repo.dao.updateRule(cur)
                    if (cur.good == 3 && cur.maxLevel >= 2) offerUpgrade(ctx, cur)
                }
                repo.log(id, "user", "ran", "आपने हाँ कहा", res.joinToString("; "))
                report(ctx, cur, "हो गया", res.joinToString("\n"), feedback = false)
            }
            A_NO -> repo.log(id, "user", "declined", "आपने मना किया")
            A_UP -> {
                if (!RuleModel.hasHigh(r) && r.maxLevel >= 2) {
                    repo.dao.updateRule(r.copy(level = 2))
                    repo.log(id, "user", "level", "आपने मंज़ूर किया: अब से L2 (अपने-आप)")
                    val res = runActions(ctx, r, approved = true)
                    report(ctx, r, "अब से अपने-आप", res.joinToString("\n"), feedback = true)
                }
            }
            A_GOOD -> {
                repo.dao.updateRule(r.copy(good = r.good + 1))
                repo.log(id, "user", "feedback", "👍 आपने कहा: अच्छा")
            }
            A_BAD -> {
                val b = r.bad + 1
                val off = b >= 3
                repo.dao.updateRule(r.copy(bad = b, level = maxOf(1, r.level - 1), enabled = !off))
                repo.log(id, "user", "feedback", "👎 आपने कहा: मत करो" + if (off) " (3 बार: नियम बंद)" else " (अब L${maxOf(1, r.level - 1)})")
                if (off) post(ctx, r, "नियम बंद किया", "‘${r.name}’ को आपने 3 बार मना किया, इसलिए इसे बंद कर दिया। 🧭 में चालू कर सकते हैं।", emptyList())
            }
        }
        repo.afterChange()
    }

    private fun offerUpgrade(ctx: Context, r: Rule) {
        post(
            ctx, r, "${Characters.byId(r.speaker).name} · एक सवाल",
            "‘${r.name}’ पर आप तीन बार हाँ कह चुके हैं। अब से इसे अपने-आप करूँ? (बाद में 👎 से वापस सुझाव-मोड में ला सकते हैं।)",
            listOf("⚡ हाँ, अपने-आप" to btn(ctx, r, A_UP, 3), "❌ नहीं" to btn(ctx, r, A_NO, 2)),
        )
    }
}

class RuleActionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getLongExtra("rule", -1L)
        val act = intent.action ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try { RuleEngine.onUserAction(ctx.applicationContext, id, act) } catch (_: Exception) { } finally { pending.finish() }
        }
    }
}

/** Time triggers: one inexact-but-reliable alarm for the earliest upcoming time rule; re-armed after each fire. */
object RuleScheduler {
    private fun weekday(c: Calendar) = ((c.get(Calendar.DAY_OF_WEEK) + 5) % 7) + 1

    private fun next(t: JSONObject): Pair<Long, String>? {
        val at = t.optString("at")
        val h = at.substringBefore(":").toIntOrNull() ?: return null
        val m = at.substringAfter(":").toIntOrNull() ?: return null
        val days = t.optJSONArray("days")
        val now = System.currentTimeMillis()
        for (d in 0..7) {
            val c = Calendar.getInstance()
            c.add(Calendar.DAY_OF_YEAR, d)
            c.set(Calendar.HOUR_OF_DAY, h); c.set(Calendar.MINUTE, m); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
            val okDay = days == null || days.length() == 0 || (0 until days.length()).any { days.optInt(it) == weekday(c) }
            if (c.timeInMillis > now + 1000 && okDay) return c.timeInMillis to String.format(Locale.US, "%02d:%02d", h, m)
        }
        return null
    }

    fun cancelAlarm(ctx: Context) {
        val app = ctx.applicationContext
        app.getSystemService(AlarmManager::class.java).cancel(
            PendingIntent.getBroadcast(
                app, 777, Intent(app, RuleAlarmReceiver::class.java).putExtra("at", ""),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
    }

    suspend fun reschedule(ctx: Context) {
        val app = ctx.applicationContext
        if (KillSwitch.on(app)) {
            cancelAlarm(app)
            return
        }
        val am = app.getSystemService(AlarmManager::class.java)
        val pi = { at: String ->
            PendingIntent.getBroadcast(
                app, 777, Intent(app, RuleAlarmReceiver::class.java).putExtra("at", at),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        val best = RuleRepo.get(app).dao.rules().filter { it.enabled }.mapNotNull {
            val t = JSONObject(it.triggerJson)
            if (t.optString("type") == "time") next(t) else null
        }.minByOrNull { it.first }
        if (best == null) {
            am.cancel(pi(""))
            return
        }
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, best.first, pi(best.second))
    }
}

class RuleAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val at = intent.getStringExtra("at") ?: return
        val app = ctx.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                RuleEngine.handle(app, RuleEvent("time", at))
                RuleScheduler.reschedule(app)
            } catch (_: Exception) {
            } finally {
                pending.finish()
            }
        }
    }
}

/** Home / outside: a 150 m geofence around the spot the user marked as home. Needs "allow all the time" location. */
object Geo {
    private fun granted(ctx: Context, p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    fun canRunInBackground(ctx: Context): Boolean =
        granted(ctx, android.Manifest.permission.ACCESS_FINE_LOCATION) &&
            (Build.VERSION.SDK_INT < 29 || granted(ctx, android.Manifest.permission.ACCESS_BACKGROUND_LOCATION))

    private fun pi(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx, 4242, Intent(ctx, GeofenceReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )

    @SuppressLint("MissingPermission")
    fun ensure(ctx: Context) {
        val p = Prefs(ctx)
        if (!p.hasHome || !canRunInBackground(ctx)) return
        val g = Geofence.Builder().setRequestId("home").setCircularRegion(p.homeLat, p.homeLon, 150f)
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT).build()
        val req = GeofencingRequest.Builder().setInitialTrigger(0).addGeofence(g).build()
        LocationServices.getGeofencingClient(ctx).addGeofences(req, pi(ctx))
    }

    @SuppressLint("MissingPermission")
    fun saveHere(ctx: Context, done: (Boolean, String) -> Unit) {
        if (!granted(ctx, android.Manifest.permission.ACCESS_FINE_LOCATION)) {
            done(false, "स्थान की अनुमति नहीं है।")
            return
        }
        LocationServices.getFusedLocationProviderClient(ctx).getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
            .addOnSuccessListener { loc ->
                if (loc == null) {
                    done(false, "अभी की जगह नहीं मिली; खुले में जाकर फिर कोशिश कीजिए।")
                } else {
                    val p = Prefs(ctx)
                    p.setHome(loc.latitude, loc.longitude)
                    p.place = "home"
                    ensure(ctx)
                    done(
                        true,
                        if (canRunInBackground(ctx)) "घर तय हो गया ✓ (150 मीटर का घेरा)।"
                        else "घर तय हो गया, पर 'स्थान: हमेशा' की अनुमति नहीं है, इसलिए ऐप बंद होने पर आना-जाना नहीं पहचाना जाएगा।",
                    )
                }
            }
            .addOnFailureListener { done(false, "जगह पढ़ नहीं पाया।") }
    }
}

class GeofenceReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val ev = GeofencingEvent.fromIntent(intent) ?: return
        if (ev.hasError()) return
        val p = Prefs(ctx)
        when (ev.geofenceTransition) {
            Geofence.GEOFENCE_TRANSITION_ENTER -> { p.place = "home"; Proactive.logPlace(ctx, "enter_home"); RuleEngine.fireAsync(ctx, RuleEvent("enter_home")) }
            Geofence.GEOFENCE_TRANSITION_EXIT -> { p.place = "outside"; Proactive.logPlace(ctx, "exit_home"); RuleEngine.fireAsync(ctx, RuleEvent("exit_home")) }
        }
    }
}

/** Every 15 minutes: remember the hour's screen state (sleep guess), check battery rules, re-arm alarms and geofence. */
class ContextWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext
        if (KillSwitch.on(app)) return Result.success()
        try {
            ContextEngine.recordHour(app)
            val sit = ContextEngine.snapshot(app)
            val b = sit.battery
            if (b != null && sit.charging != true) RuleEngine.handle(app, RuleEvent("battery", b.toString()))
            RuleScheduler.reschedule(app)
            Geo.ensure(app)
            Proactive.tick(app)
        } catch (_: Exception) { }
        return Result.success()
    }
}

object Background {
    fun start(ctx: Context) {
        val app = ctx.applicationContext
        if (KillSwitch.on(app)) return
        val req = PeriodicWorkRequestBuilder<ContextWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(app).enqueueUniquePeriodicWork("stark_ctx", ExistingPeriodicWorkPolicy.KEEP, req)
        CoroutineScope(Dispatchers.Default).launch {
            try {
                RuleRepo.get(app).refreshCache()
                RuleScheduler.reschedule(app)
                Geo.ensure(app)
            } catch (_: Exception) { }
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Background.start(ctx)
    }
}
