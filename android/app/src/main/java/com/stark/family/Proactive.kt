package com.stark.family

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.work.WorkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** What each character may do on its own: 1 = suggest only, 2 = small safe actions, 3 = risky actions, but only after a "yes". */
object Autonomy {
    fun allowedLevels(ctx: Context, id: String): Set<Int> = when (id) {
        "jarvis" -> setOf(1, 2, 3)
        "friday", "edith", "dume", "u" -> setOf(1, 2)
        "ultron" -> if (Prefs(ctx).ultronRaised) setOf(1, 3) else setOf(1)
        else -> setOf(1)
    }

    fun describe(ctx: Context, id: String): String {
        val a = allowedLevels(ctx, id)
        return when {
            3 in a && 2 in a -> "L3 तक (अपने-आप छोटे काम; कॉल जैसे काम पूछकर)"
            3 in a -> "L1 और L3 (हर बदलाव आपकी हाँ के बाद)"
            2 in a -> "L2 तक (छोटे सुरक्षित काम अपने-आप)"
            else -> "L1 (सिर्फ़ सुझाव)"
        }
    }
}

/** Stops every automation and every character at once, and keeps them stopped (even after a restart) until resumed. */
object KillSwitch {
    @Volatile private var flag = false
    @Volatile private var loaded = false

    /** Observable by Compose, so the banner and every screen change the moment it flips. */
    var state by mutableStateOf(false)

    fun on(ctx: Context): Boolean {
        if (!loaded) {
            flag = Prefs(ctx).killed
            state = flag
            loaded = true
        }
        return flag
    }

    fun activate(ctx: Context, stopService: Boolean = true) {
        val app = ctx.applicationContext
        flag = true
        loaded = true
        state = true
        Prefs(app).killed = true
        try { WorkManager.getInstance(app).cancelUniqueWork("stark_ctx") } catch (_: Exception) { }
        try { RuleScheduler.cancelAlarm(app) } catch (_: Exception) { }
        try { app.getSystemService(NotificationManager::class.java).cancelAll() } catch (_: Exception) { }
        if (stopService) {
            try { app.startService(Intent(app, OverlayService::class.java).setAction(OverlayService.ACTION_KILL_ALL)) } catch (_: Exception) { }
        }
    }

    fun resume(ctx: Context) {
        val app = ctx.applicationContext
        flag = false
        loaded = true
        state = false
        Prefs(app).killed = false
        Background.start(app)
    }
}

@Entity(tableName = "events")
data class EventLog(@PrimaryKey(autoGenerate = true) val id: Long = 0, val ts: Long, val type: String, val key: String, val extra: String = "")

@Entity(tableName = "patterns", indices = [Index(value = ["key"], unique = true)])
data class PatternState(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val key: String,
    val title: String,
    val status: String,
    val ts: Long,
)

@Entity(tableName = "proactive_log")
data class ProactiveLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ts: Long,
    val type: String,
    val key: String,
    val character: String,
    val decision: String,
    val title: String,
    val text: String,
    val payload: String = "",
)

@Dao
interface ProactiveDao {
    @Insert suspend fun insertEvent(e: EventLog): Long
    @Query("SELECT * FROM events WHERE ts >= :since ORDER BY ts") suspend fun eventsSince(since: Long): List<EventLog>
    @Query("DELETE FROM events WHERE ts < :before") suspend fun trimEvents(before: Long)
    @Query("DELETE FROM events") suspend fun clearEvents()

    @Query("SELECT * FROM patterns ORDER BY ts DESC") suspend fun patterns(): List<PatternState>
    @Query("SELECT * FROM patterns WHERE key = :k LIMIT 1") suspend fun pattern(k: String): PatternState?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putPattern(p: PatternState): Long
    @Query("DELETE FROM patterns") suspend fun clearPatterns()

    @Insert suspend fun insertLog(l: ProactiveLog): Long
    @Update suspend fun updateLog(l: ProactiveLog)
    @Query("SELECT * FROM proactive_log WHERE id = :id") suspend fun log(id: Long): ProactiveLog?
    @Query("SELECT * FROM proactive_log ORDER BY ts DESC LIMIT :n") suspend fun logs(n: Int): List<ProactiveLog>
    @Query("SELECT * FROM proactive_log WHERE ts >= :since ORDER BY ts DESC") suspend fun since(since: Long): List<ProactiveLog>
    @Query("SELECT * FROM proactive_log WHERE type = :type ORDER BY ts DESC LIMIT :n") suspend fun lastOfType(type: String, n: Int): List<ProactiveLog>
    @Query("SELECT COUNT(*) FROM proactive_log WHERE key = :k AND key != ''") suspend fun countKey(k: String): Int
}

@Database(entities = [EventLog::class, PatternState::class, ProactiveLog::class], version = 1, exportSchema = false)
abstract class ProactiveDb : RoomDatabase() {
    abstract fun dao(): ProactiveDao

    companion object {
        @Volatile private var inst: ProactiveDb? = null
        fun get(ctx: Context): ProactiveDb = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(ctx.applicationContext, ProactiveDb::class.java, "proactive.db").build().also { inst = it }
        }
    }
}

/** One thing a character wants to tell or propose. actionsJson (type != "pattern") or a rule JSON (type == "pattern"). */
class Suggestion(
    val type: String, val key: String, val character: String, val title: String, val text: String,
    val payload: String = "",
)

/**
 * Proactive behaviour: AIs speak up at the right moment, but only as suggestions (the user taps yes/no), never during
 * quiet hours, a task or a call, at most a few per day, with a gap between them, mutable per character, and never
 * while the kill switch is on. Habit patterns are mined on this phone from app/place events and offered as rules.
 */
object Proactive {
    private const val CH = "stark_proactive"
    const val A_YES = "com.stark.family.P_YES"
    const val A_NO = "com.stark.family.P_NO"
    const val A_MUTE = "com.stark.family.P_MUTE"
    private const val GAP_MS = 90 * 60_000L
    private const val DAY = 86_400_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lastApp = ConcurrentHashMap<String, Long>()

    private fun dao(ctx: Context) = ProactiveDb.get(ctx).dao()
    private fun hm(s: String): Int = (s.substringBefore(":").toIntOrNull() ?: 0) * 60 + (s.substringAfter(":").toIntOrNull() ?: 0)
    private fun hhmm(min: Int) = String.format(Locale.US, "%02d:%02d", (min / 60) % 24, min % 60)
    private fun minuteOfDay(ts: Long): Int {
        val c = Calendar.getInstance()
        c.timeInMillis = ts
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
    }
    private fun dayKey(ts: Long) = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(ts))
    private fun circ(a: Int, b: Int): Int = Math.abs(a - b).let { minOf(it, 1440 - it) }

    // ------------------------------------------------------------------ observing (stored only on this phone)

    private fun ignoredPkg(ctx: Context, pkg: String): Boolean {
        val l = pkg.lowercase()
        return pkg == ctx.packageName || l.contains("launcher") || l.contains("systemui") || l.contains("inputmethod") ||
            l.contains("permissioncontroller") || l.contains("packageinstaller") || AgentRunner.isSensitivePkg(pkg)
    }

    fun logApp(ctx: Context, pkg: String) {
        val app = ctx.applicationContext
        if (KillSwitch.on(app) || ignoredPkg(app, pkg)) return
        val p = Prefs(app)
        if (!p.patternWatch) return
        val now = System.currentTimeMillis()
        val last = lastApp[pkg]
        if (last != null && now - last < 5 * 60_000L) return
        lastApp[pkg] = now
        scope.launch {
            try {
                val label = try { app.packageManager.let { it.getApplicationLabel(it.getApplicationInfo(pkg, 0)).toString() } } catch (e: Exception) { pkg }
                dao(app).insertEvent(EventLog(ts = now, type = "app", key = pkg, extra = label))
            } catch (_: Exception) { }
        }
    }

    fun logPlace(ctx: Context, type: String) {
        val app = ctx.applicationContext
        if (KillSwitch.on(app) || !Prefs(app).patternWatch) return
        scope.launch { try { dao(app).insertEvent(EventLog(ts = System.currentTimeMillis(), type = type, key = "")) } catch (_: Exception) { } }
    }

    private val LOGGED_TOOLS = setOf("set_dnd", "set_volume", "torch", "open_app", "wifi_panel")

    fun logTool(ctx: Context, name: String, args: JSONObject) {
        val app = ctx.applicationContext
        if (name !in LOGGED_TOOLS || KillSwitch.on(app) || !Prefs(app).patternWatch) return
        scope.launch {
            try { dao(app).insertEvent(EventLog(ts = System.currentTimeMillis(), type = "tool", key = name, extra = args.toString())) } catch (_: Exception) { }
        }
    }

    // ------------------------------------------------------------------ quiet hours and other gates

    fun inQuiet(p: Prefs, minutes: Int): Boolean {
        val a = p.quietStart
        val b = p.quietEnd
        return if (a == b) false else if (a < b) minutes in a until b else minutes >= a || minutes < b
    }

    private fun inCall(ctx: Context): Boolean = try {
        val m = ctx.getSystemService(AudioManager::class.java).mode
        m == AudioManager.MODE_IN_CALL || m == AudioManager.MODE_IN_COMMUNICATION || m == AudioManager.MODE_RINGTONE
    } catch (e: Exception) {
        false
    }

    // ------------------------------------------------------------------ what to say

    private fun lateStart(p: Prefs): Int {
        val sw = ContextEngine.sleepWindow(p)
        return if (sw.contains("डिफ़ॉल्ट")) 22 * 60 + 30 else maxOf(21 * 60, hm(sw.substringBefore("-").trim()) - 60)
    }

    /** Built-in situations: tomorrow's early event + late night (Jarvis), low battery while out (Edith), repeated "no" (Ultron). */
    suspend fun contextual(ctx: Context, sit: Situation): List<Suggestion> {
        val p = Prefs(ctx)
        val out = mutableListOf<Suggestion>()
        val nowMs = System.currentTimeMillis()

        if (p.sensorOn("calendar") && (sit.minutes >= lateStart(p) || sit.minutes < 60)) {
            val ev = ContextEngine.eventAfter(ctx, 3 * 3_600_000L, 14 * 3_600_000L)
            if (ev != null && minuteOfDay(ev.first) <= 11 * 60) {
                var alarm = ev.first - 60 * 60_000L
                alarm -= alarm % (5 * 60_000L)
                if (alarm - nowMs >= 30 * 60_000L) {
                    val am = minuteOfDay(alarm)
                    val acts = JSONArray()
                        .put(JSONObject().put("tool", "set_alarm").put("args", JSONObject().put("hour", am / 60).put("minute", am % 60).put("label", ev.second)))
                        .put(JSONObject().put("tool", "set_dnd").put("args", JSONObject().put("on", true)))
                    out.add(
                        Suggestion(
                            "late_prep", "late_prep:${dayKey(ev.first)}:${ev.second.hashCode()}", "jarvis", "देर रात की तैयारी",
                            "सर, ${hhmm(minuteOfDay(ev.first))} पर ‘${ev.second}’ है और ${sit.time} बज चुके हैं। मैं ${hhmm(am)} का अलार्म लगा दूँ और नोटिफ़िकेशन शांत कर दूँ?",
                            acts.toString(),
                        ),
                    )
                }
            }
        }

        val b = sit.battery
        if (b != null && b <= 20 && sit.charging != true && (sit.place == "outside" || b <= 12)) {
            val acts = JSONArray().put(JSONObject().put("tool", "battery_saver").put("args", JSONObject()))
            out.add(
                Suggestion(
                    "battery", "battery:${dayKey(nowMs)}:${b / 10}", "edith", "बैटरी कम",
                    "बैटरी ${b}% है${if (sit.place == "outside") " और आप घर से बाहर हैं" else ""}। बैटरी सेवर की सेटिंग खोल दूँ?",
                    acts.toString(),
                ),
            )
        }

        val d = dao(ctx)
        for ((type, line) in listOf(
            "late_prep" to "तीसरी बार आपने देर रात की तैयारी का सुझाव ठुकराया। सुबह के अलार्म के प्रति आपकी बेरुख़ी वाक़ई प्रभावशाली है।",
            "battery" to "बैटरी वाला सुझाव तीसरी बार टाल दिया। चार्जर से आपकी दूरी का मैं सम्मान करता हूँ, पर फ़ोन नहीं करेगा।",
        )) {
            val last = d.lastOfType(type, 3)
            val recentRoast = d.lastOfType("roast:$type", 1).firstOrNull()
            if (last.size == 3 && last.all { it.decision == "declined" } && (recentRoast == null || nowMs - recentRoast.ts > 7 * DAY)) {
                out.add(Suggestion("roast:$type", "roast:$type:${dayKey(nowMs)}", "ultron", "Ultron की टिप्पणी", line))
            }
        }
        return out
    }

    /** Habits seen on this phone -> proposed rules. Nothing is created until the user says yes. */
    suspend fun mine(ctx: Context): List<Suggestion> {
        val p = Prefs(ctx)
        if (!p.patternWatch) return emptyList()
        val d = dao(ctx)
        val now = System.currentTimeMillis()
        val ev = d.eventsSince(now - 21 * DAY)
        val out = mutableListOf<Suggestion>()

        suspend fun allowed(key: String): Boolean {
            val st = d.pattern(key) ?: return true
            return when (st.status) {
                "accepted", "muted" -> false
                "declined" -> now - st.ts > 14 * DAY
                else -> now - st.ts > 3 * DAY
            }
        }

        fun rule(name: String, trigger: JSONObject, tool: String, args: JSONObject, summary: String) = JSONObject()
            .put("name", name).put("trigger", trigger).put("conditions", JSONObject())
            .put("actions", JSONArray().put(JSONObject().put("tool", tool).put("args", args)))
            .put("speaker", "jarvis").put("summary", summary)

        // P1: after leaving home, the same app within 10 minutes, on 3+ different days
        val apps = ev.filter { it.type == "app" }
        val after = HashMap<String, MutableSet<String>>()
        val labelOf = HashMap<String, String>()
        ev.filter { it.type == "exit_home" }.forEach { e ->
            apps.firstOrNull { it.ts > e.ts && it.ts - e.ts <= 10 * 60_000L }?.let { a ->
                after.getOrPut(a.key) { HashSet() }.add(dayKey(e.ts))
                labelOf[a.key] = a.extra
            }
        }
        for ((pkg, days) in after) {
            val key = "p1:$pkg"
            if (days.size >= 3 && allowed(key)) {
                val label = labelOf[pkg] ?: pkg
                out.add(
                    Suggestion(
                        "pattern", key, "jarvis", "आदत: घर से निकलते ही $label",
                        "सर, मैंने देखा कि घर से निकलने के कुछ ही देर बाद आप ‘$label’ खोलते हैं (${days.size} अलग-अलग दिन)। इसे नियम बना दूँ? शुरुआत में वह सिर्फ़ सुझाव देगा।",
                        rule("घर से निकलते ही $label", JSONObject().put("type", "exit_home"), "open_app", JSONObject().put("app", label), "आप घर से निकलते हैं तो $label खोलते हैं; मैं यह सुझाव दूँगा।").toString(),
                    ),
                )
            }
        }

        // P2: the same app around the same time on 4+ days;  P3: the same tool (DND, volume ...) around the same time on 3+ days
        fun window(points: List<Pair<String, Int>>, half: Int): Triple<Int, Int, Int>? {
            if (points.isEmpty()) return null
            val total = points.map { it.first }.toSet().size
            var best = -1
            var bestDays = 0
            for (c in points.map { it.second }.distinct()) {
                val n = points.filter { circ(it.second, c) <= half }.map { it.first }.toSet().size
                if (n > bestDays) { bestDays = n; best = c }
            }
            if (best < 0) return null
            val near = points.filter { circ(it.second, best) <= half }.map { it.second }.sorted()
            return Triple(near[near.size / 2], bestDays, total)
        }

        val recent14 = now - 14 * DAY
        for ((pkg, list) in apps.filter { it.ts >= recent14 }.groupBy { it.key }) {
            val w = window(list.map { dayKey(it.ts) to minuteOfDay(it.ts) }, 30) ?: continue
            val key = "p2:$pkg"
            if (w.second >= 4 && w.second.toDouble() / w.third >= 0.7 && allowed(key)) {
                val label = list.first().extra
                val t = hhmm((w.first / 5) * 5)
                out.add(
                    Suggestion(
                        "pattern", key, "jarvis", "आदत: रोज़ ~$t पर $label",
                        "सर, आप रोज़ लगभग $t बजे ‘$label’ खोलते हैं (${w.second} दिन)। इस समय पर मैं सुझाव दिया करूँ? (नियम बना दूँ; शुरुआत में सिर्फ़ सुझाव।)",
                        rule("रोज़ ~$t पर $label", JSONObject().put("type", "time").put("at", t), "open_app", JSONObject().put("app", label), "रोज़ लगभग $t बजे आप $label खोलते हैं।").toString(),
                    ),
                )
            }
        }
        for ((tk, list) in ev.filter { it.type == "tool" }.groupBy { it.key + "|" + it.extra }) {
            val w = window(list.map { dayKey(it.ts) to minuteOfDay(it.ts) }, 45) ?: continue
            val key = "p3:${tk.hashCode()}"
            if (w.second >= 3 && w.second.toDouble() / w.third >= 0.6 && allowed(key)) {
                val name = list.first().key
                val args = try { JSONObject(list.first().extra) } catch (e: Exception) { JSONObject() }
                val t = hhmm((w.first / 5) * 5)
                val desc = RuleModel.describeAction(JSONObject().put("tool", name).put("args", args))
                out.add(
                    Suggestion(
                        "pattern", key, "jarvis", "आदत: रोज़ ~$t पर $desc",
                        "सर, आप रोज़ लगभग $t बजे ‘$desc’ करवाते हैं (${w.second} दिन)। क्या मैं इसे नियम बना दूँ? शुरुआत में वह सिर्फ़ सुझाव देगा।",
                        rule("रोज़ ~$t पर $desc", JSONObject().put("type", "time").put("at", t), name, args, "रोज़ लगभग $t बजे आप यह करवाते हैं: $desc।").toString(),
                    ),
                )
            }
        }
        return out
    }

    // ------------------------------------------------------------------ delivering

    private fun nm(ctx: Context): NotificationManager {
        val n = ctx.getSystemService(NotificationManager::class.java)
        n.createNotificationChannel(NotificationChannel(CH, "सुझाव (किरदारों की पहल)", NotificationManager.IMPORTANCE_DEFAULT))
        return n
    }

    private fun btn(ctx: Context, logId: Long, act: String, idx: Int): PendingIntent = PendingIntent.getBroadcast(
        ctx, (logId * 4 + idx).toInt(), Intent(ctx, ProactiveReceiver::class.java).setAction(act).putExtra("log", logId),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** true if it was shown. force = the "test" button: skips quiet hours/limits, never the kill switch. */
    suspend fun deliver(ctx: Context, s: Suggestion, force: Boolean = false): Boolean {
        val app = ctx.applicationContext
        if (KillSwitch.on(app)) return false
        val p = Prefs(app)
        val d = dao(app)
        val now = System.currentTimeMillis()
        if (!force) {
            if (!p.proactiveOn || p.charMuted(s.character) || s.type in p.mutedTypes) return false
            if (s.key.isNotEmpty() && d.countKey(s.key) > 0) return false
            if (inQuiet(p, minuteOfDay(now)) || OverlayService.taskRunning || inCall(app)) return false
            val cal = Calendar.getInstance()
            cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0)
            val today = d.since(cal.timeInMillis)
            if (today.size >= p.dailyLimit || d.since(now - GAP_MS).isNotEmpty()) return false
        }
        val id = d.insertLog(ProactiveLog(ts = now, type = s.type, key = s.key, character = s.character, decision = "shown", title = s.title, text = s.text, payload = s.payload))
        if (s.type == "pattern") d.putPattern(PatternState(key = s.key, title = s.title, status = "proposed", ts = now))
        val who = Characters.byId(s.character)
        val b = Notification.Builder(app, CH).setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("${who.name} · ${s.title}")
            .setContentText(s.text).setStyle(Notification.BigTextStyle().bigText(s.text))
            .setContentIntent(PendingIntent.getActivity(app, 0, Intent(app, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)).setAutoCancel(true)
        if (!s.type.startsWith("roast")) {
            b.addAction(Notification.Action.Builder(0, if (s.type == "pattern") "✅ नियम बनाओ" else "✅ करो", btn(app, id, A_YES, 1)).build())
            b.addAction(Notification.Action.Builder(0, "❌ अभी नहीं", btn(app, id, A_NO, 2)).build())
            b.addAction(Notification.Action.Builder(0, "🔕 ऐसे सुझाव बंद", btn(app, id, A_MUTE, 3)).build())
        }
        nm(app).notify(40_000 + (id % 5_000).toInt(), b.build())
        return true
    }

    /** What the user tapped on a suggestion. */
    suspend fun onAction(ctx: Context, logId: Long, act: String) {
        val app = ctx.applicationContext
        if (KillSwitch.on(app)) return
        val d = dao(app)
        val l = d.log(logId) ?: return
        nm(app).cancel(40_000 + (logId % 5_000).toInt())
        val now = System.currentTimeMillis()
        when (act) {
            A_YES -> {
                d.updateLog(l.copy(decision = "accepted"))
                if (l.type == "pattern") {
                    try {
                        val o = JSONObject(l.payload)
                        val actions = o.getJSONArray("actions")
                        val rule = Rule(
                            name = o.optString("name"), triggerJson = o.getJSONObject("trigger").toString(),
                            conditionsJson = (o.optJSONObject("conditions") ?: JSONObject()).toString(), actionsJson = actions.toString(),
                            speaker = o.optString("speaker", "jarvis"), summary = o.optString("summary"),
                            teaching = "(देखी हुई आदत) ${l.title}", level = 1, maxLevel = RuleModel.effectiveMax(actions), createdAt = now,
                        )
                        RuleRepo.get(app).save(RuleDraft(rule, rule.teaching, RuleModel.describeRule(rule)))
                        d.putPattern(PatternState(key = l.key, title = l.title, status = "accepted", ts = now))
                        note(app, "नियम बन गया", "‘${rule.name}’ सेव हुआ (शुरुआत सिर्फ़ सुझाव-मोड में)। 🧭 में देखिए।")
                    } catch (e: Exception) {
                        note(app, "नियम नहीं बन पाया", "आदत का रूप सही नहीं था।")
                    }
                } else {
                    val r = Rule(
                        name = l.title, triggerJson = "{}", conditionsJson = "{}", actionsJson = l.payload.ifBlank { "[]" },
                        speaker = l.character, summary = "", createdAt = now,
                    )
                    val res = RuleEngine.runActions(app, r, approved = true)
                    note(app, "${Characters.byId(l.character).name} · हो गया", res.joinToString("\n"))
                }
            }
            A_NO -> {
                d.updateLog(l.copy(decision = "declined"))
                if (l.type == "pattern") d.putPattern(PatternState(key = l.key, title = l.title, status = "declined", ts = now))
            }
            A_MUTE -> {
                d.updateLog(l.copy(decision = "declined"))
                if (l.type == "pattern") d.putPattern(PatternState(key = l.key, title = l.title, status = "muted", ts = now))
                else Prefs(app).mutedTypes = Prefs(app).mutedTypes + l.type
            }
        }
    }

    fun note(ctx: Context, title: String, text: String) {
        val n = Notification.Builder(ctx, CH).setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(title)
            .setContentText(text.lineSequence().firstOrNull().orEmpty()).setStyle(Notification.BigTextStyle().bigText(text)).setAutoCancel(true).build()
        nm(ctx).notify(45_000 + (System.currentTimeMillis() % 4_000).toInt(), n)
    }

    /** Called every ~15 minutes by the background worker: at most one suggestion per round. */
    suspend fun tick(ctx: Context) {
        val app = ctx.applicationContext
        if (KillSwitch.on(app)) return
        val d = dao(app)
        d.trimEvents(System.currentTimeMillis() - 30 * DAY)
        val sit = ContextEngine.snapshot(app)
        val all = contextual(app, sit) + mine(app)
        for (s in all) if (deliver(app, s)) break
    }

    suspend fun testNow(ctx: Context): Boolean {
        val acts = JSONArray().put(JSONObject().put("tool", "set_alarm").put("args", JSONObject().put("hour", 6).put("minute", 30).put("label", "टेस्ट")))
        return deliver(
            ctx, Suggestion("test", "", "jarvis", "टेस्ट सुझाव", "सर, यह एक जाँच-सुझाव है: कल सुबह 06:30 का अलार्म लगा दूँ?", acts.toString()), force = true,
        )
    }

    suspend fun forgetObservations(ctx: Context) = withContext(Dispatchers.IO) {
        val d = dao(ctx)
        d.clearEvents()
        d.clearPatterns()
        lastApp.clear()
    }
}

class ProactiveReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getLongExtra("log", -1L)
        val act = intent.action ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try { Proactive.onAction(ctx.applicationContext, id, act) } catch (_: Exception) { } finally { pending.finish() }
        }
    }
}
