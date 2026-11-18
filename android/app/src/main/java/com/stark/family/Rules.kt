package com.stark.family

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A taught rule: trigger + conditions + actions + who speaks. level: 1 = only suggest, 2 = do small safe things
 * by itself, 3 = always ask first. Every rule starts at 1; only the user moves it up.
 */
@Entity(tableName = "rules")
data class Rule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val triggerJson: String,
    val conditionsJson: String,
    val actionsJson: String,
    val speaker: String,
    val summary: String,
    val teaching: String = "",
    val level: Int = 1,
    val maxLevel: Int = 2,
    val enabled: Boolean = true,
    val good: Int = 0,
    val bad: Int = 0,
    val createdAt: Long,
    val lastFired: Long = 0,
)

/** The "why did this run" record. */
@Entity(tableName = "rule_logs")
data class RuleLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ruleId: Long,
    val ts: Long,
    val event: String,
    val decision: String,
    val why: String,
    val result: String = "",
)

/** The situation library: every taught sentence with the rule it became, reused as examples for similar teachings. */
@Entity(tableName = "examples")
data class Example(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    val ruleJson: String,
    val ts: Long,
)

@Dao
interface RuleDao {
    @Query("SELECT * FROM rules ORDER BY createdAt DESC") suspend fun rules(): List<Rule>
    @Query("SELECT * FROM rules WHERE id = :id") suspend fun rule(id: Long): Rule?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertRule(r: Rule): Long
    @Update suspend fun updateRule(r: Rule)
    @Query("DELETE FROM rules WHERE id = :id") suspend fun deleteRule(id: Long)

    @Insert suspend fun insertLog(l: RuleLog): Long
    @Query("SELECT * FROM rule_logs WHERE ruleId = :id ORDER BY ts DESC LIMIT :n") suspend fun logsOf(id: Long, n: Int): List<RuleLog>
    @Query("SELECT * FROM rule_logs ORDER BY ts DESC LIMIT :n") suspend fun recentLogs(n: Int): List<RuleLog>
    @Query("DELETE FROM rule_logs WHERE ruleId = :id") suspend fun deleteLogsOf(id: Long)
    @Query("DELETE FROM rule_logs WHERE id NOT IN (SELECT id FROM rule_logs ORDER BY ts DESC LIMIT 500)") suspend fun trimLogs()

    @Query("SELECT * FROM examples ORDER BY ts DESC LIMIT 60") suspend fun examples(): List<Example>
    @Insert suspend fun insertExample(e: Example): Long
}

@Database(entities = [Rule::class, RuleLog::class, Example::class], version = 1, exportSchema = false)
abstract class RuleDb : RoomDatabase() {
    abstract fun dao(): RuleDao

    companion object {
        @Volatile private var inst: RuleDb? = null
        fun get(ctx: Context): RuleDb = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(ctx.applicationContext, RuleDb::class.java, "rules.db").build().also { inst = it }
        }
    }
}

class RuleDraft(val rule: Rule, val text: String, val describe: String)

/** Turns a rule's JSON into plain Hindi, so nothing about it is hidden. */
object RuleModel {
    private val DAYS = mapOf(1 to "सोम", 2 to "मंगल", 3 to "बुध", 4 to "गुरु", 5 to "शुक्र", 6 to "शनि", 7 to "रवि")
    val HIGH_TOOLS = setOf("call_contact")

    fun hasHigh(actions: JSONArray): Boolean = (0 until actions.length()).any { actions.optJSONObject(it)?.optString("tool") in HIGH_TOOLS }
    fun hasHigh(r: Rule): Boolean = hasHigh(JSONArray(r.actionsJson))

    /** Highest level this rule may ever reach (decided here, in code, not by the model). */
    fun effectiveMax(actions: JSONArray): Int = if (hasHigh(actions)) 3 else 2

    /** The level actually used when the rule fires: risky actions can never run on their own. */
    fun effectiveLevel(r: Rule): Int {
        val l = r.level.coerceIn(1, 3)
        return if (hasHigh(r) && l == 2) 3 else l
    }

    fun levelName(l: Int) = when (l) {
        1 -> "L1 · सिर्फ़ सुझाव"
        2 -> "L2 · अपने-आप (छोटे सुरक्षित काम)"
        else -> "L3 · हर बार पूछकर"
    }

    private fun days(a: JSONArray?): String =
        if (a == null || a.length() == 0) "रोज़" else (0 until a.length()).joinToString(", ") { DAYS[a.optInt(it)] ?: "?" }

    fun describeTrigger(t: JSONObject): String = when (t.optString("type")) {
        "time" -> "${days(t.optJSONArray("days"))} ${t.optString("at")} बजे"
        "enter_home" -> "जब आप घर पहुँचें"
        "exit_home" -> "जब आप घर से निकलें"
        "app_open" -> "जब ‘${t.optString("app")}’ खुले"
        "battery_below" -> "जब बैटरी ${t.optInt("level")}% से कम हो (चार्जिंग में न हो)"
        else -> "?"
    }

    fun describeConditions(c: JSONObject): String {
        val out = mutableListOf<String>()
        if (c.has("after")) out.add("${c.optString("after")} के बाद")
        if (c.has("before")) out.add("${c.optString("before")} से पहले")
        if (c.has("days")) out.add("दिन: " + days(c.optJSONArray("days")))
        if (c.has("place")) out.add(if (c.optString("place") == "home") "घर पर" else "घर से बाहर")
        if (c.has("charging")) out.add(if (c.optBoolean("charging")) "चार्जिंग में" else "चार्जिंग में नहीं")
        if (c.has("headphones")) out.add(if (c.optBoolean("headphones")) "हेडफ़ोन लगे" else "हेडफ़ोन नहीं")
        if (c.has("network")) out.add("नेटवर्क: " + c.optString("network"))
        if (c.has("screen_on")) out.add(if (c.optBoolean("screen_on")) "स्क्रीन चालू" else "स्क्रीन बंद")
        if (c.has("battery_below")) out.add("बैटरी ${c.optInt("battery_below")}% से कम")
        if (c.has("battery_above")) out.add("बैटरी ${c.optInt("battery_above")}% से ज़्यादा")
        return out.joinToString("; ")
    }

    fun describeAction(a: JSONObject): String {
        val g = a.optJSONObject("args") ?: JSONObject()
        return when (a.optString("tool")) {
            "set_dnd" -> if (g.optBoolean("on")) "Do Not Disturb चालू" else "Do Not Disturb बंद"
            "set_volume" -> "आवाज़ (${g.optString("stream")}) ${g.optInt("percent")}%"
            "wifi_panel" -> "Wi-Fi का पैनल खोलना (स्विच आप दबाएँगे: Android अपने-आप नहीं करने देता)"
            "open_app" -> "ऐप खोलना: ${g.optString("app")}"
            "torch" -> if (g.optBoolean("on")) "टॉर्च चालू" else "टॉर्च बंद"
            "set_alarm" -> "अलार्म ${String.format(Locale.US, "%02d:%02d", g.optInt("hour"), g.optInt("minute"))}"
            "set_reminder" -> "याद दिलाना: ${g.optString("text")}"
            "set_timer" -> "टाइमर ${g.optInt("seconds")} सेकंड"
            "remember_fact" -> "याद रखना: ${g.optString("fact")}"
            "call_contact" -> "कॉल: ${g.optString("contact")} (हर बार पूछकर)"
            "say" -> "${Characters.byId(g.optString("character")).name} कहेगी/कहेगा: ‘${g.optString("text")}’"
            else -> a.optString("tool")
        }
    }

    fun describeActions(arr: JSONArray): List<String> = (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let { a -> describeAction(a) } }

    fun describeRule(r: Rule): String {
        val cond = describeConditions(JSONObject(r.conditionsJson))
        return buildString {
            append("कब: ").append(describeTrigger(JSONObject(r.triggerJson)))
            if (cond.isNotBlank()) append("\nशर्तें: ").append(cond)
            append("\nतब:")
            describeActions(JSONArray(r.actionsJson)).forEach { append("\n • ").append(it) }
            append("\nशुरुआत: ").append(levelName(1))
            if (r.summary.isNotBlank()) append("\n\n").append(r.summary)
        }
    }
}

class RuleRepo private constructor(ctx: Context) {
    private val app = ctx.applicationContext
    val dao: RuleDao = RuleDb.get(app).dao()

    companion object {
        @Volatile private var inst: RuleRepo? = null
        fun get(ctx: Context): RuleRepo = inst ?: synchronized(this) { inst ?: RuleRepo(ctx.applicationContext).also { inst = it } }
    }

    /** Saves an approved draft (always at Level 1). replaceId > 0 re-teaches an existing rule. */
    suspend fun save(d: RuleDraft, replaceId: Long = 0): Long = withContext(Dispatchers.IO) {
        val id = dao.insertRule(d.rule.copy(id = replaceId, level = 1, good = 0, bad = 0, enabled = true))
        val ex = JSONObject().put("name", d.rule.name).put("trigger", JSONObject(d.rule.triggerJson))
            .put("conditions", JSONObject(d.rule.conditionsJson)).put("actions", JSONArray(d.rule.actionsJson))
        dao.insertExample(Example(text = d.text.take(300), ruleJson = ex.toString(), ts = System.currentTimeMillis()))
        afterChange()
        id
    }

    suspend fun log(ruleId: Long, event: String, decision: String, why: String, result: String = "") = withContext(Dispatchers.IO) {
        dao.insertLog(RuleLog(ruleId = ruleId, ts = System.currentTimeMillis(), event = event, decision = decision, why = why.take(300), result = result.take(400)))
        dao.trimLogs()
    }

    /** Up to 3 earlier taught sentences that look like this one, so the model can generalise from them. */
    suspend fun examplesFor(text: String): JSONArray = withContext(Dispatchers.IO) {
        val q = MemoryRepo.tokens(text)
        val a = JSONArray()
        dao.examples().map { it to MemoryRepo.tokens(it.text).count { t -> t in q } }.filter { it.second > 0 }
            .sortedByDescending { it.second }.take(3).forEach {
                try { a.put(JSONObject().put("text", it.first.text).put("rule", JSONObject(it.first.ruleJson))) } catch (_: Exception) { }
            }
        a
    }

    suspend fun refreshCache() = withContext(Dispatchers.IO) {
        RuleEngine.appKeys = dao.rules().filter { it.enabled }.mapNotNull {
            val t = JSONObject(it.triggerJson)
            if (t.optString("type") == "app_open") t.optString("app").lowercase().takeIf { s -> s.isNotBlank() } else null
        }.toSet()
    }

    suspend fun afterChange() {
        refreshCache()
        RuleScheduler.reschedule(app)
    }
}

/** Teach by talking: the words go to the relay's /rule_from_text; the answer comes back as a draft to approve. */
object RuleTeacher {
    suspend fun propose(ctx: Context, teaching: String, character: String): Pair<RuleDraft?, String> {
        val p = Prefs(ctx)
        if (p.relayUrl.isBlank() || p.token.isBlank()) return null to "पहले ऐप की सेटिंग (⚙) में Relay URL और टोकन डालिए।"
        val now = System.currentTimeMillis()
        val examples = RuleRepo.get(ctx).examplesFor(teaching)
        val resp = try {
            RelayClient().ruleFromText(p.relayUrl, p.token, teaching, SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(now)), examples)
        } catch (e: RelayClient.RelayException) {
            return null to (e.message ?: "relay से जवाब नहीं मिला।")
        } ?: return null to "अभी दिमाग़ व्यस्त है, थोड़ी देर बाद फिर सिखाइए।"
        val o = resp.rule
        if (!resp.understood || o == null) return null to resp.question.ifBlank { "इसे नियम में नहीं बदल पाया। कब और क्या होना चाहिए, एक बार और साफ़ बताइए।" }
        return try {
            val actions = o.getJSONArray("actions")
            val rule = Rule(
                name = o.optString("name", "नया नियम").take(60), triggerJson = o.getJSONObject("trigger").toString(),
                conditionsJson = (o.optJSONObject("conditions") ?: JSONObject()).toString(), actionsJson = actions.toString(),
                speaker = o.optString("speaker", character), summary = o.optString("summary"), teaching = teaching.take(300),
                level = 1, maxLevel = RuleModel.effectiveMax(actions), createdAt = now,
            )
            RuleDraft(rule, teaching, RuleModel.describeRule(rule)) to ""
        } catch (e: Exception) {
            null to "नियम का रूप सही नहीं आया, एक बार और सिखाइए।"
        }
    }
}
