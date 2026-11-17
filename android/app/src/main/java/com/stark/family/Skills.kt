package com.stark.family

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** A route that worked once (or was written by hand): how to get something done inside an app. */
@Entity(tableName = "skills")
data class Skill(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val app: String,
    val keywords: String,
    val stepsJson: String,
    val source: String = "learned",
    val enabled: Boolean = true,
    val successes: Int = 0,
    val failures: Int = 0,
    val createdAt: Long,
    val lastUsed: Long = 0,
)

/** One step of a route. Labels (not screen numbers) so it survives layout changes. */
class SkillStep(
    val action: String,
    val label: String = "",
    val text: String = "",
    val direction: String = "",
    val app: String = "",
    val expect: String = "",
)

class LoadedSkill(val skill: Skill, val steps: List<SkillStep>)

@Dao
interface SkillDao {
    @Query("SELECT * FROM skills ORDER BY lastUsed DESC, createdAt DESC") suspend fun all(): List<Skill>
    @Insert suspend fun insert(s: Skill): Long
    @Update suspend fun update(s: Skill)
    @Query("DELETE FROM skills WHERE id = :id") suspend fun delete(id: Long)
    @Query("SELECT COUNT(*) FROM skills") suspend fun count(): Int
}

@Database(entities = [Skill::class], version = 1, exportSchema = false)
abstract class SkillDb : RoomDatabase() {
    abstract fun dao(): SkillDao

    companion object {
        @Volatile private var inst: SkillDb? = null
        fun get(ctx: Context): SkillDb = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(ctx.applicationContext, SkillDb::class.java, "skills.db").build().also { inst = it }
        }
    }
}

/**
 * App-skill memory. A skill is used in two ways: as a hint for the model (always), and as a no-model fast path
 * for steps whose labels are on screen (only once the skill has succeeded before). Everything is visible and editable.
 */
class SkillRepo private constructor(ctx: Context) {
    val dao: SkillDao = SkillDb.get(ctx).dao()

    companion object {
        @Volatile private var inst: SkillRepo? = null
        fun get(ctx: Context): SkillRepo = inst ?: synchronized(this) {
            inst ?: SkillRepo(ctx.applicationContext).also { inst = it }
        }

        val ACTIONS = setOf("tap", "long_press", "scroll", "swipe", "type", "enter", "back", "home", "recents", "open_app", "wait", "find")

        fun parse(json: String): List<SkillStep> = try {
            val a = JSONArray(json)
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                SkillStep(o.optString("a"), o.optString("l"), o.optString("t"), o.optString("d"), o.optString("p"), o.optString("x"))
            }
        } catch (e: Exception) {
            emptyList()
        }

        fun toJson(steps: List<SkillStep>): String {
            val a = JSONArray()
            steps.forEach {
                a.put(JSONObject().put("a", it.action).put("l", it.label).put("t", it.text).put("d", it.direction).put("p", it.app).put("x", it.expect))
            }
            return a.toString()
        }

        /** "action | label-or-app | text | direction", one step per line (the editable form). */
        fun toLines(steps: List<SkillStep>): String = steps.joinToString("\n") {
            val l = if (it.action == "open_app") it.app else it.label
            "${it.action} | $l | ${it.text} | ${it.direction}".trimEnd(' ', '|')
        }

        fun fromLines(text: String): List<SkillStep> = text.lines().mapNotNull { line ->
            val p = line.split("|").map { it.trim() }
            val a = p.getOrNull(0)?.lowercase().orEmpty()
            if (a !in ACTIONS) return@mapNotNull null
            val l = p.getOrNull(1).orEmpty()
            SkillStep(a, if (a == "open_app") "" else l, p.getOrNull(2).orEmpty(), p.getOrNull(3).orEmpty(), if (a == "open_app") l else "")
        }

        /** Fraction of keyword groups ("a|b" means either) that appear in the goal. */
        fun score(goal: String, keywords: String): Double {
            val groups = keywords.split(" ").filter { it.isNotBlank() }.map { it.split("|") }
            if (groups.isEmpty()) return 0.0
            val low = goal.lowercase()
            val toks = MemoryRepo.tokens(goal)
            val hits = groups.count { g ->
                g.any { alt ->
                    val a = alt.lowercase()
                    a.isNotEmpty() && (if (a.length >= 3) low.contains(a) else a in toks)
                }
            }
            return hits.toDouble() / groups.size
        }

        fun hintText(steps: List<SkillStep>, done: Int): String = steps.mapIndexed { i, s ->
            val what = when (s.action) {
                "open_app" -> "open_app '${s.app}'"
                "type" -> "type '${s.text}'"
                "find" -> "find '${s.text}'"
                "tap", "long_press" -> "${s.action} '${s.label}'"
                else -> s.action + (if (s.direction.isNotBlank()) " ${s.direction}" else "")
            }
            "${i + 1}. $what${if (i < done) "  (हो चुका)" else ""}"
        }.joinToString("\n")
    }

    private val seeds = listOf(
        Skill(
            title = "WhatsApp में किसी संपर्क का आख़िरी PDF/दस्तावेज़ ढूँढना", app = "WhatsApp",
            keywords = "whatsapp|व्हाट्सएप|वॉट्सऐप pdf|document|दस्तावेज़|docs|file|फ़ाइल",
            stepsJson = toJson(
                listOf(
                    SkillStep("open_app", app = "WhatsApp"),
                    SkillStep("tap", label = "Search"),
                    SkillStep("type", text = "{संपर्क का नाम}"),
                    SkillStep("tap", label = "{संपर्क का नाम}"),
                    SkillStep("tap", label = "{संपर्क का नाम}"),
                    SkillStep("tap", label = "Media, links, and docs"),
                    SkillStep("tap", label = "Docs"),
                ),
            ),
            source = "seed", createdAt = 0,
        ),
        Skill(
            title = "Settings में बैटरी usage देखना", app = "Settings",
            keywords = "settings|सेटिंग battery|बैटरी usage|उपयोग|खपत|खा",
            stepsJson = toJson(
                listOf(
                    SkillStep("open_app", app = "Settings"),
                    SkillStep("tap", label = "Battery"),
                    SkillStep("tap", label = "Battery usage"),
                ),
            ),
            source = "seed", createdAt = 0,
        ),
        Skill(
            title = "Chrome में किसी वेबसाइट (जैसे Render) का पन्ना खोलना", app = "Chrome",
            keywords = "chrome|क्रोम render|रेंडर|website|वेबसाइट|dashboard|डैशबोर्ड",
            stepsJson = toJson(
                listOf(
                    SkillStep("open_app", app = "Chrome"),
                    SkillStep("tap", label = "Search or type web address"),
                    SkillStep("type", text = "https://dashboard.render.com"),
                    SkillStep("enter"),
                ),
            ),
            source = "seed", createdAt = 0,
        ),
    )

    suspend fun seedIfEmpty() = withContext(Dispatchers.IO) {
        if (dao.count() == 0) seeds.forEach { dao.insert(it.copy(createdAt = System.currentTimeMillis())) }
    }

    suspend fun match(goal: String): LoadedSkill? = withContext(Dispatchers.IO) {
        val best = dao.all().filter { it.enabled }
            .map { it to score(goal, it.keywords) }
            .filter { it.second >= 0.5 }
            .maxWithOrNull(compareBy({ it.second }, { it.first.successes }))?.first
            ?: return@withContext null
        val steps = parse(best.stepsJson)
        if (steps.isEmpty()) null else LoadedSkill(best, steps)
    }

    /** Called once at the end of a screen task: update the used skill's record, and learn a new route if it worked. */
    suspend fun finish(goal: String, matched: Skill?, used: Boolean, status: String, trail: List<SkillStep>, learnable: Boolean) =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            if (matched != null && used) {
                val cur = dao.all().firstOrNull { it.id == matched.id }
                if (cur != null) {
                    if (status == "ok") {
                        dao.update(cur.copy(successes = cur.successes + 1, lastUsed = now))
                    } else if (status == "failed" || status == "limit") {
                        val f = cur.failures + 1
                        dao.update(cur.copy(failures = f, lastUsed = now, enabled = !(cur.source == "learned" && f >= cur.successes + 3)))
                    }
                }
            }
            if (status == "ok" && learnable && trail.size >= 2 && trail.none { (it.action == "tap" || it.action == "long_press") && it.label.isBlank() }) {
                learn(goal, trail.take(25), now)
            }
            Unit
        }

    private suspend fun learn(goal: String, trail: List<SkillStep>, now: Long) {
        val same = dao.all().map { it to score(goal, it.keywords) }.filter { it.second >= 0.8 }.maxByOrNull { it.second }?.first
        if (same != null) {
            if (same.source == "manual") return
            val old = parse(same.stepsJson)
            if (same.source == "seed" || trail.size <= old.size) {
                dao.update(same.copy(stepsJson = toJson(trail), source = "learned", lastUsed = now))
            }
            return
        }
        val app = trail.firstOrNull { it.action == "open_app" }?.app.orEmpty()
        val kw = MemoryRepo.tokens(goal).take(8).joinToString(" ")
        if (kw.isBlank()) return
        dao.insert(Skill(title = goal.take(80), app = app, keywords = kw, stepsJson = toJson(trail), source = "learned", successes = 1, createdAt = now, lastUsed = now))
    }
}

private val FG = Color(0xFFEDEDED)
private val DIM = Color(0xFF8A8A8A)
private val CARD = Color(0xFF0E0E0E)
private val RED = Color(0xFFFF5D5D)

private class SkillEdit(val skill: Skill?)

@Composable
fun SkillsScreen(ch: Character, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val repo = remember { SkillRepo.get(ctx) }
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    var list by remember { mutableStateOf(listOf<Skill>()) }
    var edit by remember { mutableStateOf<SkillEdit?>(null) }
    var status by remember { mutableStateOf("") }
    LaunchedEffect(tick) {
        repo.seedIfEmpty()
        list = repo.dao.all()
    }
    BackHandler(onBack = onClose)

    Column(Modifier.fillMaxSize().background(Color.Black).padding(top = 8.dp)) {
        Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onClose) { Text("← वापस", color = ch.accent2) }
            Text("🧩 स्किल्स", color = ch.accent, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = { edit = SkillEdit(null) }) { Text("＋ नई", color = ch.accent) }
        }
        Text(
            "स्किल = किसी ऐप में किसी काम का आज़माया हुआ रास्ता। सफल काम के बाद वह अपने-आप सीखा जाता है। अगली बार मॉडल को वही रास्ता सुझाया जाता है, और जो क़दम सफल रह चुके हैं उन्हें बिना मॉडल पूछे सीधे दोहराया जाता है (तेज़ और सस्ता)। 'शुरुआती' स्किल सिर्फ़ सुझाव हैं।",
            color = DIM, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
        )
        LazyColumn(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(list) { s ->
                val steps = SkillRepo.parse(s.stepsJson)
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(CARD)
                        .border(1.dp, if (s.enabled) Color(0xFF222222) else Color(0xFF552222), RoundedCornerShape(14.dp)).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(s.title, color = FG, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    val src = when (s.source) { "seed" -> "शुरुआती (सुझाव)"; "manual" -> "हाथ से"; else -> "सीखा हुआ" }
                    Text("ऐप: ${s.app.ifBlank { "—" }} · $src · सफल ${s.successes} · असफल ${s.failures}${if (s.enabled) "" else " · बंद"}", color = ch.accent, fontSize = 12.sp)
                    Text(SkillRepo.hintText(steps, 0), color = DIM, fontSize = 12.sp)
                    Row {
                        TextButton(onClick = { edit = SkillEdit(s) }) { Text("✏ बदलो", color = ch.accent2, fontSize = 13.sp) }
                        TextButton(onClick = { scope.launch { repo.dao.update(s.copy(enabled = !s.enabled)); tick++ } }) {
                            Text(if (s.enabled) "⏻ बंद करो" else "⏻ चालू करो", color = ch.accent2, fontSize = 13.sp)
                        }
                        TextButton(onClick = { scope.launch { repo.dao.delete(s.id); tick++ } }) { Text("🗑", color = RED, fontSize = 13.sp) }
                    }
                }
            }
        }
        if (status.isNotEmpty()) Text(status, color = ch.accent2, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp))
    }

    edit?.let { e ->
        var title by remember { mutableStateOf(e.skill?.title.orEmpty()) }
        var lines by remember { mutableStateOf(e.skill?.let { SkillRepo.toLines(SkillRepo.parse(it.stepsJson)) }.orEmpty()) }
        AlertDialog(
            onDismissRequest = { edit = null },
            title = { Text(if (e.skill == null) "नई स्किल" else "स्किल बदलिए", color = FG) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("किस काम की (जैसे: WhatsApp में आख़िरी PDF)") })
                    OutlinedTextField(
                        value = lines, onValueChange = { lines = it }, minLines = 5, maxLines = 10,
                        label = { Text("क़दम (हर पंक्ति: action | नाम | text | दिशा)") },
                    )
                    Text("action: tap, long_press, scroll, swipe, type, enter, back, home, recents, open_app, wait, find", color = DIM, fontSize = 11.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val steps = SkillRepo.fromLines(lines)
                    if (title.isBlank() || steps.isEmpty()) {
                        status = "शीर्षक और कम से कम एक सही क़दम चाहिए।"
                    } else {
                        scope.launch {
                            val kw = MemoryRepo.tokens(title).take(8).joinToString(" ")
                            val app = steps.firstOrNull { it.action == "open_app" }?.app.orEmpty()
                            val cur = e.skill
                            if (cur == null) repo.dao.insert(Skill(title = title.trim(), app = app, keywords = kw, stepsJson = SkillRepo.toJson(steps), source = "manual", createdAt = System.currentTimeMillis()))
                            else repo.dao.update(cur.copy(title = title.trim(), app = app, keywords = if (cur.source == "seed") cur.keywords else kw, stepsJson = SkillRepo.toJson(steps), source = "manual"))
                            status = "सहेज लिया।"
                            tick++
                        }
                        edit = null
                    }
                }) { Text("सहेजो", color = ch.accent) }
            },
            dismissButton = { TextButton(onClick = { edit = null }) { Text("रद्द", color = DIM) } },
        )
    }
}
