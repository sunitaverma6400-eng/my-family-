package com.stark.family

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
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
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One screen task (goal) and how it ended. */
@Entity(tableName = "runs")
data class AgentRun(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ts: Long,
    val character: String,
    val goal: String,
    val status: String,
    val report: String = "",
    val steps: Int = 0,
)

/** Every single thing the agent did: what, why, and what came of it. */
@Entity(tableName = "agent_steps")
data class AgentStepLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val runId: Long,
    val n: Int,
    val ts: Long,
    val action: String,
    val target: String,
    val reason: String,
    val result: String,
    val confirmed: String = "",
)

@Dao
interface ActivityDao {
    @Insert suspend fun insertRun(r: AgentRun): Long
    @Update suspend fun updateRun(r: AgentRun)
    @Insert suspend fun insertStep(s: AgentStepLog): Long
    @Query("SELECT * FROM runs ORDER BY ts DESC LIMIT 100") suspend fun runs(): List<AgentRun>
    @Query("SELECT * FROM agent_steps WHERE runId = :id ORDER BY n") suspend fun steps(id: Long): List<AgentStepLog>
    @Query("DELETE FROM agent_steps WHERE runId = :id") suspend fun deleteSteps(id: Long)
    @Query("DELETE FROM runs WHERE id = :id") suspend fun deleteRun(id: Long)
}

@Database(entities = [AgentRun::class, AgentStepLog::class], version = 1, exportSchema = false)
abstract class ActivityDb : RoomDatabase() {
    abstract fun dao(): ActivityDao

    companion object {
        @Volatile private var inst: ActivityDb? = null
        fun get(ctx: Context): ActivityDb = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(ctx.applicationContext, ActivityDb::class.java, "activity.db").build().also { inst = it }
        }
    }
}

fun openAccessibilitySettings(ctx: Context) {
    ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

private val FG = Color(0xFFEDEDED)
private val DIM = Color(0xFF8A8A8A)
private val CARD = Color(0xFF0E0E0E)
private val RED = Color(0xFFFF5D5D)

@Composable
fun ActivityScreen(ch: Character, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val dao = remember { ActivityDb.get(ctx).dao() }
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    var runs by remember { mutableStateOf(listOf<AgentRun>()) }
    var open by remember { mutableLongStateOf(-1L) }
    var steps by remember { mutableStateOf(listOf<AgentStepLog>()) }
    var enabled by remember { mutableStateOf(AgentAccessibilityService.enabled()) }
    var wipe by remember { mutableStateOf(false) }

    LaunchedEffect(tick) {
        runs = dao.runs()
        enabled = AgentAccessibilityService.enabled()
        steps = if (open >= 0) dao.steps(open) else emptyList()
    }
    BackHandler(onBack = onClose)

    Column(Modifier.fillMaxSize().background(Color.Black).padding(top = 8.dp)) {
        Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onClose) { Text("← वापस", color = ch.accent2) }
            Text("📋 गतिविधि लॉग", color = ch.accent, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = { tick++ }) { Text("↻", color = ch.accent2, fontSize = 20.sp) }
        }
        LazyColumn(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(CARD)
                        .border(1.dp, if (enabled) Color(0xFF2E7D4F) else RED, RoundedCornerShape(14.dp)).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        if (enabled) "✓ स्क्रीन-एजेंट (Accessibility) चालू है" else "✗ स्क्रीन-एजेंट बंद है",
                        color = if (enabled) Color(0xFF5BE38A) else RED, fontWeight = FontWeight.Bold,
                    )
                    if (!enabled) {
                        Text(
                            "चालू करने के लिए: नीचे का बटन दबाइए → 'Stark Family एजेंट' चुनिए → चालू कीजिए।\n" +
                                "अगर वह धूसर (बंद) दिखे: Settings → Apps → Stark Family → ऊपर दाएँ ⋮ → 'Restricted settings की अनुमति दें' " +
                                "(Android 13+ पर बाहर से install किए ऐप के लिए ज़रूरी), फिर दोबारा कोशिश कीजिए।",
                            color = DIM, fontSize = 12.sp,
                        )
                        TextButton(onClick = { openAccessibilitySettings(ctx) }) { Text("♿ Accessibility सेटिंग खोलो", color = ch.accent) }
                    } else {
                        Text("यह सिर्फ़ तब स्क्रीन पढ़ता है जब आप किसी किरदार को स्क्रीन पर काम देते हैं। पासवर्ड/OTP/कार्ड कभी नहीं।", color = DIM, fontSize = 12.sp)
                    }
                }
            }
            item { AgentSettings(ch) }
            if (runs.isEmpty()) item { Text("अभी कोई स्क्रीन-काम नहीं हुआ। बबल में Jarvis से कहिए: 'Settings खोलकर बैटरी usage दिखाओ'।", color = DIM) }
            items(runs) { r ->
                val who = Characters.byId(r.character)
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(CARD)
                        .border(1.dp, Color(0xFF222222), RoundedCornerShape(14.dp))
                        .clickable { open = if (open == r.id) -1L else r.id; tick++ }.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    val st = when (r.status) {
                        "ok" -> "✓ पूरा"; "stopped" -> "⏹ रोका गया"; "ask" -> "❓ आपसे पूछा"
                        "limit" -> "⏱ सीमा पूरी"; "running" -> "… चल रहा था"; else -> "✗ नहीं हो पाया"
                    }
                    Text("${SimpleDateFormat("dd MMM HH:mm", Locale.US).format(Date(r.ts))} · ${who.name} · $st · ${r.steps} क़दम", color = who.accent, fontSize = 12.sp)
                    Text(r.goal, color = FG, fontWeight = FontWeight.Bold)
                    if (r.report.isNotBlank()) Text(r.report, color = FG, fontSize = 14.sp, maxLines = if (open == r.id) 40 else 3)
                    if (open == r.id) {
                        steps.forEach { s ->
                            Text(
                                "${s.n}. ${s.action} ${s.target} — ${s.reason}\n   → ${s.result}${if (s.confirmed.isNotEmpty()) "  [" + s.confirmed + "]" else ""}",
                                color = DIM, fontSize = 12.sp,
                            )
                            val thumb = remember(s.id) { Thumbs.load(ctx, s.runId, s.n) }
                            if (thumb != null) Image(bitmap = thumb, contentDescription = null, modifier = Modifier.fillMaxWidth(0.4f).padding(vertical = 2.dp))
                        }
                        TextButton(onClick = { scope.launch { dao.deleteSteps(r.id); dao.deleteRun(r.id); Thumbs.delete(ctx, r.id); open = -1L; tick++ } }) {
                            Text("🗑 यह रिकॉर्ड मिटाओ", color = RED, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
        if (runs.isNotEmpty()) {
            TextButton(onClick = { wipe = true }, modifier = Modifier.padding(horizontal = 8.dp)) { Text("🗑 पूरा लॉग मिटाओ", color = RED, fontSize = 13.sp) }
        }
    }
    if (wipe) {
        AlertDialog(
            onDismissRequest = { wipe = false },
            title = { Text("पक्का?", color = FG) },
            text = { Text("गतिविधि लॉग का सारा रिकॉर्ड मिटा दूँ?", color = FG) },
            confirmButton = {
                TextButton(onClick = {
                    wipe = false
                    scope.launch { dao.runs().forEach { dao.deleteSteps(it.id); dao.deleteRun(it.id) }; Thumbs.deleteAll(ctx); open = -1L; tick++ }
                }) { Text("हाँ", color = RED) }
            },
            dismissButton = { TextButton(onClick = { wipe = false }) { Text("नहीं", color = DIM) } },
        )
    }
}

private fun appLabel(ctx: Context, pkg: String): String = try {
    val pm = ctx.packageManager
    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
} catch (e: Exception) {
    pkg
}

private fun launchableApps(ctx: Context): List<Pair<String, String>> {
    val pm = ctx.packageManager
    return pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
        .distinctBy { it.second }
        .sortedBy { it.first.lowercase() }
}

/** The agent's limits: how many steps per task, and apps where it must never run. */
@Composable
private fun AgentSettings(ch: Character) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    var max by remember { mutableIntStateOf(prefs.agentMaxSteps) }
    var blocked by remember { mutableStateOf(prefs.blockedPkgs) }
    var pick by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(CARD)
            .border(1.dp, Color(0xFF222222), RoundedCornerShape(14.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("⚙ एजेंट की सीमाएँ", color = ch.accent, fontWeight = FontWeight.Bold)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("एक काम में ज़्यादा से ज़्यादा क़दम: $max", color = FG, fontSize = 14.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = { max = (max - 5).coerceAtLeast(10); prefs.agentMaxSteps = max }) { Text("−", color = ch.accent, fontSize = 22.sp) }
            TextButton(onClick = { max = (max + 5).coerceAtMost(80); prefs.agentMaxSteps = max }) { Text("＋", color = ch.accent, fontSize = 22.sp) }
        }
        Text("इन ऐप्स में एजेंट कभी नहीं चलेगा (बैंक/पेमेंट/पासवर्ड ऐप वैसे भी बिना आपकी हाँ के नहीं चलते):", color = DIM, fontSize = 12.sp)
        if (blocked.isEmpty()) Text("— कोई नहीं —", color = DIM, fontSize = 12.sp)
        blocked.sorted().forEach { pkg ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(appLabel(ctx, pkg), color = FG, fontSize = 14.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { blocked = blocked - pkg; prefs.blockedPkgs = blocked }) { Text("✕ हटाओ", color = RED, fontSize = 12.sp) }
            }
        }
        TextButton(onClick = { pick = true }) { Text("＋ ऐप जोड़ो", color = ch.accent) }
    }
    if (pick) {
        val apps = remember { launchableApps(ctx) }
        var q by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { pick = false },
            title = { Text("किस ऐप में एजेंट बंद करें?", color = FG) },
            text = {
                Column {
                    OutlinedTextField(value = q, onValueChange = { q = it }, singleLine = true, placeholder = { Text("खोजिए…", color = DIM) })
                    LazyColumn(Modifier.heightIn(max = 320.dp)) {
                        items(apps.filter { q.isBlank() || it.first.contains(q, ignoreCase = true) }) { a ->
                            Text(
                                a.first, color = if (a.second in blocked) DIM else FG, fontSize = 15.sp,
                                modifier = Modifier.fillMaxWidth().clickable {
                                    blocked = blocked + a.second
                                    prefs.blockedPkgs = blocked
                                    pick = false
                                }.padding(vertical = 10.dp),
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { pick = false }) { Text("बंद", color = DIM) } },
        )
    }
}
