package com.stark.family

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val FG = Color(0xFFEDEDED)
private val DIM = Color(0xFF8A8A8A)
private val CARD = Color(0xFF0E0E0E)
private val RED = Color(0xFFFF5D5D)
private val GREEN = Color(0xFF5BE38A)

private fun granted(ctx: Context, perm: String) = ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED

/** नियम (rules + how they behave) | संदर्भ (what the AIs can sense, switches, setup) | लॉग (why did this run). */
@Composable
fun RulesScreen(ch: Character, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val repo = remember { RuleRepo.get(ctx) }
    val prefs = remember { Prefs(ctx) }
    val scope = rememberCoroutineScope()
    var tab by remember { mutableIntStateOf(0) }
    var tick by remember { mutableIntStateOf(0) }
    var rules by remember { mutableStateOf(listOf<Rule>()) }
    var logs by remember { mutableStateOf(listOf<RuleLog>()) }
    var openLogs by remember { mutableLongStateOf(-1L) }
    var ruleLogs by remember { mutableStateOf(listOf<RuleLog>()) }
    var teach by remember { mutableStateOf<Rule?>(null) }
    var teachOpen by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf<RuleDraft?>(null) }
    var replaceId by remember { mutableLongStateOf(0L) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var snap by remember { mutableStateOf(ContextEngine.snapshot(ctx)) }
    var confirmDelete by remember { mutableStateOf<Rule?>(null) }
    var pats by remember { mutableStateOf(listOf<PatternState>()) }
    var plogs by remember { mutableStateOf(listOf<ProactiveLog>()) }

    LaunchedEffect(tick) {
        rules = repo.dao.rules()
        logs = repo.dao.recentLogs(60)
        ruleLogs = if (openLogs >= 0) repo.dao.logsOf(openLogs, 10) else emptyList()
        snap = ContextEngine.snapshot(ctx)
        pats = ProactiveDb.get(ctx).dao().patterns()
        plogs = ProactiveDb.get(ctx).dao().logs(12)
    }
    BackHandler(onBack = onClose)

    val saveHome = {
        Geo.saveHere(ctx) { _, msg -> status = msg; tick++ }
    }
    val bgLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { saveHome() }
    val fineLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (!ok) status = "स्थान की अनुमति नहीं मिली।"
        else if (Build.VERSION.SDK_INT >= 29 && !granted(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
            status = "अब खुलने वाली स्क्रीन में 'हमेशा अनुमति दें' चुनिए, ताकि ऐप बंद होने पर भी घर आना-जाना पहचाना जाए।"
            bgLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        } else saveHome()
    }
    val calLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }

    Column(Modifier.fillMaxSize().background(Color.Black).padding(top = 8.dp)) {
        Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onClose) { Text("← वापस", color = ch.accent2) }
            Text("🧭 नियम और संदर्भ", color = ch.accent, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("नियम", "संदर्भ", "सुझाव", "लॉग").forEachIndexed { i, name ->
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(if (tab == i) ch.accent else CARD)
                        .clickable { tab = i; tick++ }.padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(name, color = if (tab == i) Color.Black else FG, fontSize = 14.sp, fontWeight = FontWeight.Bold) }
            }
        }
        LazyColumn(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (tab) {
                0 -> {
                    item {
                        TextButton(onClick = { teach = null; teachOpen = true; replaceId = 0L }) { Text("＋ बोलकर नया नियम सिखाओ", color = ch.accent) }
                        Text(
                            "जैसे: \"jab main ghar aau toh wifi on kar do aur Friday good evening bole\" या \"raat 11 baje DND on kar do\"। हर नया नियम पहले सिर्फ़ सुझाव देता है (L1)। अपने-आप चलाना (L2) तभी जब आप ख़ुद चुनें।",
                            color = DIM, fontSize = 12.sp,
                        )
                    }
                    if (rules.isEmpty()) item { Text("अभी कोई नियम नहीं। ऊपर के बटन से, या किसी भी किरदार से चैट में सिखाइए।", color = DIM) }
                    items(rules) { r ->
                        val who = Characters.byId(r.speaker)
                        Column(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(CARD)
                                .border(1.dp, if (r.enabled) Color(0xFF222222) else Color(0xFF552222), RoundedCornerShape(14.dp)).padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(r.name, color = FG, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Text("${who.name} · ${RuleModel.levelName(RuleModel.effectiveLevel(r, Autonomy.allowedLevels(ctx, r.speaker)))}${if (r.enabled) "" else " · बंद"}", color = who.accent, fontSize = 12.sp)
                            Text(RuleModel.describeRule(r).substringBefore("\nशुरुआत:"), color = FG, fontSize = 13.sp, lineHeight = 19.sp)
                            Text("हाँ कहा ${r.good} · मना ${r.bad}", color = DIM, fontSize = 11.sp)
                            val high = RuleModel.hasHigh(r)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf(1 to "L1 सुझाव", 2 to "L2 अपने-आप", 3 to "L3 पूछकर").forEach { (l, name) ->
                                    val allowed = l <= r.maxLevel && !(high && l == 2) && l in Autonomy.allowedLevels(ctx, r.speaker)
                                    val on = RuleModel.effectiveLevel(r, Autonomy.allowedLevels(ctx, r.speaker)) == l
                                    Box(
                                        Modifier.clip(RoundedCornerShape(14.dp))
                                            .background(if (on) ch.accent else Color.Transparent)
                                            .border(1.dp, if (allowed) ch.accent else Color(0xFF333333), RoundedCornerShape(14.dp))
                                            .clickable(enabled = allowed) {
                                                scope.launch { repo.dao.updateRule(r.copy(level = l)); repo.afterChange(); tick++ }
                                            }.padding(horizontal = 10.dp, vertical = 5.dp),
                                    ) { Text(name, color = if (on) Color.Black else if (allowed) ch.accent else DIM, fontSize = 12.sp) }
                                }
                            }
                            if (high) Text("कॉल जैसे काम हमेशा पूछकर ही होते हैं (L2 बंद)।", color = DIM, fontSize = 11.sp)
                            Text("${who.name} का स्तर: ${Autonomy.describe(ctx, r.speaker)}", color = DIM, fontSize = 11.sp)
                            Row {
                                TextButton(onClick = { scope.launch { repo.dao.updateRule(r.copy(enabled = !r.enabled)); repo.afterChange(); tick++ } }) {
                                    Text(if (r.enabled) "⏻ बंद" else "⏻ चालू", color = ch.accent2, fontSize = 13.sp)
                                }
                                TextButton(onClick = { teach = r; replaceId = r.id; teachOpen = true }) { Text("✏ फिर सिखाओ", color = ch.accent2, fontSize = 13.sp) }
                                TextButton(onClick = { openLogs = if (openLogs == r.id) -1L else r.id; tick++ }) { Text("❓ क्यों चला", color = ch.accent2, fontSize = 13.sp) }
                                TextButton(onClick = { confirmDelete = r }) { Text("🗑", color = RED, fontSize = 13.sp) }
                            }
                            if (openLogs == r.id) {
                                if (ruleLogs.isEmpty()) Text("अभी तक कुछ नहीं चला।", color = DIM, fontSize = 12.sp)
                                ruleLogs.forEach { l ->
                                    Text(
                                        "${SimpleDateFormat("dd MMM HH:mm", Locale.US).format(Date(l.ts))} · ${l.decision}\n  ${l.why}${if (l.result.isNotBlank()) "\n  → " + l.result else ""}",
                                        color = DIM, fontSize = 12.sp,
                                    )
                                }
                            }
                        }
                    }
                }
                1 -> {
                    item {
                        Text("किरदार क्या महसूस कर सकते हैं (हर एक आप बंद कर सकते हैं):", color = FG, fontWeight = FontWeight.Bold)
                    }
                    items(ContextEngine.SENSORS) { (key, name) ->
                        var on by remember { mutableStateOf(prefs.sensorOn(key)) }
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(CARD).padding(horizontal = 12.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(name, color = FG, fontSize = 14.sp, modifier = Modifier.weight(1f))
                            TextButton(onClick = { on = !on; prefs.setSensor(key, on); tick++ }) {
                                Text(if (on) "चालू ✓" else "बंद ✗", color = if (on) GREEN else RED)
                            }
                        }
                    }
                    item { Text("अभी नहीं बने (आगे): गतिविधि पहचान (चलते/रुके/गाड़ी में) और नोटिफ़िकेशन की हलचल।", color = DIM, fontSize = 12.sp) }
                    item {
                        Column(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(CARD).padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text("अभी का संदर्भ (ऐसा ही किरदारों को दिखता है)", color = ch.accent, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text(snap.toJson().toString(2), color = FG, fontSize = 12.sp)
                            TextButton(onClick = { tick++ }) { Text("↻ ताज़ा करो", color = ch.accent2) }
                        }
                    }
                    item { Text("सेटअप", color = FG, fontWeight = FontWeight.Bold) }
                    item {
                        Column(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(CARD).padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            val bg = Geo.canRunInBackground(ctx)
                            Text("घर: ${if (prefs.hasHome) "तय ✓" else "तय नहीं ✗"} · पहचान (हमेशा-स्थान): ${if (bg) "चालू ✓" else "बंद ✗"}", color = if (prefs.hasHome && bg) GREEN else RED, fontSize = 13.sp)
                            TextButton(onClick = {
                                if (!granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION)) fineLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                                else if (Build.VERSION.SDK_INT >= 29 && !granted(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION)) bgLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                                else saveHome()
                            }) { Text("📍 अभी की जगह को 'घर' बनाओ (घर पर खड़े होकर दबाइए)", color = ch.accent) }
                            val cal = granted(ctx, Manifest.permission.READ_CALENDAR)
                            Text("कैलेंडर: ${if (cal) "अनुमति ✓" else "अनुमति नहीं ✗"}", color = if (cal) GREEN else DIM, fontSize = 13.sp)
                            if (!cal) TextButton(onClick = { calLauncher.launch(Manifest.permission.READ_CALENDAR) }) { Text("📅 कैलेंडर की अनुमति दो", color = ch.accent) }
                            val dnd = ctx.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted
                            Text("DND बदलने की अनुमति: ${if (dnd) "✓" else "✗ (DND वाले नियम के लिए चाहिए)"}", color = if (dnd) GREEN else DIM, fontSize = 13.sp)
                            if (!dnd) TextButton(onClick = { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) { Text("🔕 DND की अनुमति खोलो", color = ch.accent) }
                            val a11y = AgentAccessibilityService.enabled()
                            Text("Accessibility (ऐप खुलने वाले नियम के लिए): ${if (a11y) "चालू ✓" else "बंद ✗"}", color = if (a11y) GREEN else DIM, fontSize = 13.sp)
                            val ignoring = ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)
                            Text("बैटरी ऑप्टिमाइज़ेशन से छूट: ${if (ignoring) "✓" else "✗ (vivo में नियम समय पर चलने के लिए बेहतर)"}", color = if (ignoring) GREEN else DIM, fontSize = 13.sp)
                            if (!ignoring) TextButton(onClick = {
                                try { ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { }
                            }) { Text("🔋 छूट माँगो", color = ch.accent) }
                        }
                    }
                }
                2 -> {
                    item { ProactivePanel(ch, tick, pats, plogs, scope, { tick++ }, { status = it }) }
                }
                else -> {
                    if (logs.isEmpty()) item { Text("अभी कोई लॉग नहीं। जब कोई नियम चलेगा या छूटेगा, यहाँ कारण के साथ दिखेगा।", color = DIM) }
                    items(logs) { l ->
                        val name = rules.firstOrNull { it.id == l.ruleId }?.name ?: "(मिटाया हुआ नियम)"
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(CARD).padding(10.dp)) {
                            Text("${SimpleDateFormat("dd MMM HH:mm", Locale.US).format(Date(l.ts))} · $name · ${l.decision}", color = ch.accent, fontSize = 12.sp)
                            Text(l.why, color = FG, fontSize = 13.sp)
                            if (l.result.isNotBlank()) Text("→ ${l.result}", color = DIM, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
        if (busy) Text("समझ रहा हूँ…", color = ch.accent2, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 14.dp))
        if (status.isNotEmpty()) Text(status, color = ch.accent2, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp))
    }

    if (teachOpen) {
        var text by remember(teach) { mutableStateOf(teach?.teaching.orEmpty()) }
        AlertDialog(
            onDismissRequest = { teachOpen = false },
            title = { Text(if (teach == null) "नियम सिखाइए" else "फिर से सिखाइए", color = FG) },
            text = {
                OutlinedTextField(
                    value = text, onValueChange = { text = it }, minLines = 3, maxLines = 6,
                    placeholder = { Text("जब मैं घर आऊँ तो Wi-Fi चालू कर दो और Friday good evening बोले", color = DIM) },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (text.isNotBlank() && !busy) {
                        teachOpen = false
                        busy = true
                        scope.launch {
                            val (d, msg) = RuleTeacher.propose(ctx, text.trim(), ch.id)
                            busy = false
                            if (d == null) status = msg else draft = d
                        }
                    }
                }) { Text("समझाओ", color = ch.accent) }
            },
            dismissButton = { TextButton(onClick = { teachOpen = false }) { Text("रद्द", color = DIM) } },
        )
    }
    draft?.let { d ->
        AlertDialog(
            onDismissRequest = { draft = null },
            title = { Text("यह नियम सेव करूँ?", color = FG) },
            text = { Text(d.describe, color = FG, fontSize = 14.sp) },
            confirmButton = {
                TextButton(onClick = {
                    draft = null
                    scope.launch { repo.save(d, replaceId); replaceId = 0L; status = "नियम सेव हुआ (सुझाव-मोड में)।"; tick++ }
                }) { Text("सेव करो", color = ch.accent) }
            },
            dismissButton = { TextButton(onClick = { draft = null }) { Text("रद्द", color = DIM) } },
        )
    }
    confirmDelete?.let { r ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("पक्का?", color = FG) },
            text = { Text("‘${r.name}’ और उसका लॉग मिटा दूँ?", color = FG) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = null
                    scope.launch { repo.dao.deleteLogsOf(r.id); repo.dao.deleteRule(r.id); repo.afterChange(); tick++ }
                }) { Text("हाँ", color = RED) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("नहीं", color = DIM) } },
        )
    }
}


@Composable
private fun Sect(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(CARD).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) { content() }
}

/** The "सुझाव" tab: kill switch, when/how often the AIs may speak up, who is muted, what they have learned about habits. */
@Composable
private fun ProactivePanel(
    ch: Character, version: Int, pats: List<PatternState>, plogs: List<ProactiveLog>,
    scope: kotlinx.coroutines.CoroutineScope, onChange: () -> Unit, onStatus: (String) -> Unit,
) {
    val ctx = LocalContext.current
    val p = remember { Prefs(ctx) }
    fun hh(min: Int) = String.format(Locale.US, "%02d:%02d", min / 60, min % 60)
    val killed = KillSwitch.state
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Sect {
            Text("⛔ Kill switch", color = ch.accent, fontWeight = FontWeight.Bold)
            Text(
                if (killed) "अभी सब बंद है: कोई नियम, सुझाव, स्क्रीन-काम या क्रिया नहीं चल रही। बबल भी बंद है।"
                else "एक दबाव में सब रुक जाता है: नियम, सुझाव, स्क्रीन-काम, बबल और सारे किरदारों की क्रियाएँ। यही बटन नोटिफ़िकेशन में, बबल पर और मुख्य स्क्रीन पर भी है।",
                color = DIM, fontSize = 12.sp,
            )
            TextButton(onClick = { if (killed) KillSwitch.resume(ctx) else KillSwitch.activate(ctx); onChange() }) {
                Text(if (killed) "▶ फिर चालू करो" else "⛔ सब रोको", color = if (killed) GREEN else RED, fontWeight = FontWeight.Bold)
            }
        }
        Sect {
            Text("🔔 पहल (proactive सुझाव)", color = ch.accent, fontWeight = FontWeight.Bold)
            Text("किरदार सिर्फ़ सुझाव देते हैं (आप हाँ/नहीं दबाते हैं)। शांत घंटों में, किसी स्क्रीन-काम या कॉल के दौरान, या सीमा पूरी होने पर चुप रहते हैं।", color = DIM, fontSize = 12.sp)
            TextButton(onClick = { p.proactiveOn = !p.proactiveOn; onChange() }) { Text(if (p.proactiveOn) "चालू ✓ (बंद करने के लिए दबाइए)" else "बंद ✗ (चालू करने के लिए दबाइए)", color = if (p.proactiveOn) GREEN else RED) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("शांत घंटे: ${hh(p.quietStart)} – ${hh(p.quietEnd)}", color = FG, fontSize = 14.sp, modifier = Modifier.weight(1f))
            }
            Row {
                TextButton(onClick = { p.quietStart = p.quietStart - 30; onChange() }) { Text("शुरू −", color = ch.accent2, fontSize = 12.sp) }
                TextButton(onClick = { p.quietStart = p.quietStart + 30; onChange() }) { Text("शुरू +", color = ch.accent2, fontSize = 12.sp) }
                TextButton(onClick = { p.quietEnd = p.quietEnd - 30; onChange() }) { Text("अंत −", color = ch.accent2, fontSize = 12.sp) }
                TextButton(onClick = { p.quietEnd = p.quietEnd + 30; onChange() }) { Text("अंत +", color = ch.accent2, fontSize = 12.sp) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("रोज़ ज़्यादा से ज़्यादा सुझाव: ${p.dailyLimit} (दो सुझावों के बीच कम से कम 90 मिनट)", color = FG, fontSize = 13.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { p.dailyLimit = p.dailyLimit - 1; onChange() }) { Text("−", color = ch.accent, fontSize = 22.sp) }
                TextButton(onClick = { p.dailyLimit = p.dailyLimit + 1; onChange() }) { Text("＋", color = ch.accent, fontSize = 22.sp) }
            }
            TextButton(onClick = {
                scope.launch { onStatus(if (Proactive.testNow(ctx)) "टेस्ट सुझाव नोटिफ़िकेशन में भेजा।" else "नहीं भेजा (kill switch चालू है?)।"); onChange() }
            }) { Text("▶ टेस्ट: अभी एक सुझाव दिखाओ", color = ch.accent) }
        }
        Sect {
            Text("किरदार: स्तर और म्यूट", color = ch.accent, fontWeight = FontWeight.Bold)
            Characters.all.forEach { c ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(c.name, color = c.accent, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Text(Autonomy.describe(ctx, c.id), color = DIM, fontSize = 11.sp)
                    }
                    TextButton(onClick = { p.setCharMuted(c.id, !p.charMuted(c.id)); onChange() }) {
                        Text(if (p.charMuted(c.id)) "🔇 म्यूट" else "🔔 चालू", color = if (p.charMuted(c.id)) RED else GREEN, fontSize = 13.sp)
                    }
                }
            }
            Text("Ultron अभी सिर्फ़ देखने/सुझाने वाला (Observer) है। ऊपर उठाने पर भी हर बदलाव आपकी हाँ के बाद ही होगा।", color = DIM, fontSize = 11.sp)
            TextButton(onClick = { p.ultronRaised = !p.ultronRaised; onChange() }) {
                Text(if (p.ultronRaised) "Ultron: सुझाव + मंज़ूरी के साथ करना (अभी चालू) — वापस Observer करें" else "Ultron को 'मंज़ूरी के साथ करने' तक उठाओ", color = ch.accent)
            }
        }
        Sect {
            Text("🧠 देखी हुई आदतें (सिर्फ़ इसी फ़ोन पर)", color = ch.accent, fontWeight = FontWeight.Bold)
            Text("जब आप किसी ऐप को रोज़ एक ही समय खोलते हैं, या घर से निकलकर हर बार एक ही ऐप खोलते हैं, तब Jarvis पूछता है कि क्या इसे नियम बना दे। सिर्फ़ ऐप का नाम और समय सहेजा जाता है, स्क्रीन की कोई सामग्री नहीं। बैंक/पासवर्ड ऐप कभी नहीं।", color = DIM, fontSize = 12.sp)
            TextButton(onClick = { p.patternWatch = !p.patternWatch; onChange() }) { Text(if (p.patternWatch) "आदतें देखना: चालू ✓" else "आदतें देखना: बंद ✗", color = if (p.patternWatch) GREEN else RED) }
            if (pats.isEmpty()) Text("अभी कोई आदत नहीं मिली।", color = DIM, fontSize = 12.sp)
            pats.forEach { Text("• ${it.title} · ${when (it.status) { "accepted" -> "नियम बना"; "declined" -> "मना किया"; "muted" -> "कभी नहीं"; else -> "पूछा गया" }}", color = FG, fontSize = 13.sp) }
            TextButton(onClick = { scope.launch { Proactive.forgetObservations(ctx); onStatus("देखी हुई सारी आदतें और घटनाएँ मिटा दीं।"); onChange() } }) { Text("🗑 देखी हुई आदतें मिटाओ", color = RED, fontSize = 13.sp) }
        }
        Sect {
            Text("हाल के सुझाव", color = ch.accent, fontWeight = FontWeight.Bold)
            if (plogs.isEmpty()) Text("अभी कोई सुझाव नहीं गया।", color = DIM, fontSize = 12.sp)
            plogs.forEach { l ->
                Text(
                    "${SimpleDateFormat("dd MMM HH:mm", Locale.US).format(Date(l.ts))} · ${Characters.byId(l.character).name} · ${l.title} · ${when (l.decision) { "accepted" -> "✓ हाँ"; "declined" -> "✗ नहीं"; else -> "… पूछा" }}",
                    color = DIM, fontSize = 12.sp,
                )
            }
        }
    }
}
