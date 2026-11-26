package com.stark.family

import android.Manifest
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import kotlinx.coroutines.delay

/** One permission or special access: what it is for (in plain words), whether it is on, and how to switch it on. */
private class Perm(
    val title: String,
    val why: String,
    val ok: (Context) -> Boolean,
    val runtime: Array<String>? = null,
    val intent: ((Context) -> Intent)? = null,
)

private fun granted(ctx: Context, p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

private fun appDetails(ctx: Context) =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName))

private val ALL_FILES_INTENT: (Context) -> Intent = { c ->
    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + c.packageName))
}

private fun perms(): List<Perm> = listOf(
    Perm(
        "बबल: दूसरे ऐप्स के ऊपर दिखना", "इसके बिना कोई किरदार किसी और ऐप के ऊपर नहीं आ सकता। यह सबसे ज़रूरी है।",
        { Settings.canDrawOverlays(it) },
        intent = { Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + it.packageName)) },
    ),
    Perm(
        "सूचनाएँ भेजने की अनुमति", "सुझाव, बबल की सूचना और kill switch का बटन दिखाने के लिए।",
        { Build.VERSION.SDK_INT < 33 || granted(it, Manifest.permission.POST_NOTIFICATIONS) },
        runtime = arrayOf(Manifest.permission.POST_NOTIFICATIONS),
    ),
    Perm(
        "स्क्रीन एजेंट (Accessibility)", "किरदार स्क्रीन पढ़कर ऐप खोलता, टैप और स्क्रॉल करता है। कुछ बदलने से पहले हमेशा आपसे पूछता है; पासवर्ड और OTP कभी नहीं पढ़ता।",
        { AgentAccessibilityService.enabled() },
        intent = { Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS) },
    ),
    Perm(
        "नोटिफ़िकेशन पढ़ना", "ताकि 'मेरे नोटिफ़िकेशन बताओ' कहने पर वे सार बता सकें। हर बार आपसे पूछकर पढ़े जाते हैं; डिस्क पर सेव नहीं होते।",
        { NotifyListener.enabled(it) },
        intent = { Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) },
    ),
    Perm(
        "ऐप-उपयोग देखना (Usage access)", "ताकि Ultron और Edith बता सकें कि समय किस ऐप में ज़्यादा जा रहा है।",
        { MoreTools.usageAllowed(it) },
        intent = { Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS) },
    ),
    Perm(
        "Do Not Disturb बदलना", "'रात को शांत कर दो' जैसे काम के लिए।",
        { it.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted },
        intent = { Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS) },
    ),
    Perm(
        "स्क्रीन की चमक बदलना", "'चमक कम कर दो' कहने पर।",
        { Settings.System.canWrite(it) },
        intent = { Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + it.packageName)) },
    ),
    Perm(
        "संपर्क पढ़ना", "'Rahul को कॉल करो' में नाम से नंबर ढूँढने के लिए।",
        { granted(it, Manifest.permission.READ_CONTACTS) }, runtime = arrayOf(Manifest.permission.READ_CONTACTS),
    ),
    Perm(
        "कैलेंडर पढ़ना", "ताकि वे जानें कि अगली परीक्षा या मीटिंग कब है।",
        { granted(it, Manifest.permission.READ_CALENDAR) }, runtime = arrayOf(Manifest.permission.READ_CALENDAR),
    ),
    Perm(
        "SMS भेजना", "'Mummy को SMS करो' के लिए। हर संदेश पहले पढ़कर सुनाया जाता है और आपकी हाँ के बाद ही जाता है।",
        { granted(it, Manifest.permission.SEND_SMS) }, runtime = arrayOf(Manifest.permission.SEND_SMS),
    ),
    Perm(
        "जगह (घर / बाहर)", "'घर पहुँचते ही…' जैसे नियम के लिए। हमेशा-वाली जगह की अनुमति ऐप की सेटिंग में 'Allow all the time' से मिलती है।",
        { granted(it, Manifest.permission.ACCESS_FINE_LOCATION) }, runtime = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
    ),
    Perm(
        "चलना / बैठना / गाड़ी में", "ताकि वे समझें कि आप सफ़र में हैं या बैठे हैं।",
        { ActivityTracker.hasPermission(it) }, runtime = arrayOf(Manifest.permission.ACTIVITY_RECOGNITION),
    ),
    Perm(
        "सही समय पर नियम", "'रात 11 बजे…' जैसे नियम ठीक उसी मिनट चलें। इसे चालू न करें तो वे कुछ मिनट आगे-पीछे चल सकते हैं।",
        { Build.VERSION.SDK_INT < 31 || it.getSystemService(android.app.AlarmManager::class.java).canScheduleExactAlarms() },
        intent = { Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + it.packageName)) },
    ),
    Perm(
        "फ़ाइलें: ढूँढना, सजाना, हटाना", "Dum-E और U के लिए: Downloads सजाना, फ़ाइल ढूँढना, हटाना (हटाई फ़ाइल 30 दिन कचरे में रहती है)। सिर्फ़ Download, Documents, Pictures, DCIM, Movies, Music में काम करता है।",
        { FileTools.hasAccess(it) },
        runtime = if (Build.VERSION.SDK_INT < 30) arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE) else null,
        intent = if (Build.VERSION.SDK_INT >= 30) ALL_FILES_INTENT else null,
    ),
    Perm(
        "बैटरी: कोई रोक नहीं", "ताकि फ़ोन बबल और बैकग्राउंड काम को अपने-आप बंद न कर दे।",
        { it.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(it.packageName) },
        intent = { Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + it.packageName)) },
    ),
)

private val CARD_BORDER = Color(0xFF2A2A2A)

@Composable
fun SetupScreen(ch: Character, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1500)
            tick++
        }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }
    val list = perms()
    val state = list.map { it to (tick >= 0 && try { it.ok(ctx) } catch (_: Exception) { false }) }
    val done = state.count { it.second }

    Column(Modifier.fillMaxSize().background(Color.Black).verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("अनुमतियाँ: एक-एक करके", color = ch.accent, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
            "$done / ${list.size} चालू। कोई भी अनुमति न दें तो ऐप चलता रहेगा, बस वह एक सुविधा बंद रहेगी। 'चालू करो' दबाइए, सेटिंग में जाकर चालू कीजिए, फिर वापस आइए: यहाँ अपने-आप ✅ हो जाएगा।",
            color = Color(0xFFBBBBBB), fontSize = 13.sp,
        )
        Spacer(Modifier.height(12.dp))
        state.forEach { (p, on) ->
            val shape = RoundedCornerShape(12.dp)
            Column(
                Modifier.fillMaxWidth().padding(vertical = 5.dp).clip(shape).background(Color(0xFF0E0E0E))
                    .border(1.dp, if (on) ch.accent.copy(alpha = 0.5f) else CARD_BORDER, shape).padding(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (on) "✅" else "⬜", fontSize = 16.sp)
                    Spacer(Modifier.width(8.dp))
                    Text(p.title, color = Color(0xFFEDEDED), fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
                Spacer(Modifier.height(4.dp))
                Text(p.why, color = Color(0xFFAAAAAA), fontSize = 12.sp)
                if (!on) {
                    TextButton(
                        onClick = {
                            val rt = p.runtime
                            val mk = p.intent
                            if (rt != null) launcher.launch(rt)
                            else if (mk != null) {
                                try { ctx.startActivity(mk(ctx).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { ctx.startActivity(appDetails(ctx).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                            }
                        },
                    ) { Text("चालू करो", color = ch.accent) }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        OemGuide(ch)
        Spacer(Modifier.height(16.dp))
        Button(onClick = {
            Prefs(ctx).onboarded = true
            Background.start(ctx)
            ActivityTracker.start(ctx)
            onClose()
        }) { Text("हो गया") }
        Spacer(Modifier.height(24.dp))
    }
}

/** Phone makers (Xiaomi, Samsung, Vivo, Oppo, Realme...) stop background apps on their own. This tells the user exactly what to switch off. */
@Composable
private fun OemGuide(ch: Character) {
    val ctx = LocalContext.current
    val shape = RoundedCornerShape(12.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(Color(0xFF0E0E0E)).border(1.dp, CARD_BORDER, shape).padding(12.dp)) {
        Text("फ़ोन अपने-आप बबल बंद कर दे तो", color = Color(0xFFEDEDED), fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Spacer(Modifier.height(4.dp))
        Text(
            "कई फ़ोन (Xiaomi, Redmi, Realme, Oppo, Vivo, Samsung) बैकग्राउंड ऐप चुपचाप मार देते हैं। ऐप की सेटिंग में जाकर:\n" +
                "• बैटरी → 'Unrestricted' / 'No restrictions' / 'कोई रोक नहीं'\n" +
                "• Autostart / 'अपने-आप शुरू' → चालू\n" +
                "• Recent ऐप्स में Stark Family को 🔒 लॉक कर दीजिए\n" +
                "Samsung: Settings → Battery → Background usage limits → 'Never sleeping apps' में Stark जोड़िए।",
            color = Color(0xFFAAAAAA), fontSize = 12.sp, lineHeight = 17.sp,
        )
        Row {
            TextButton(onClick = { try { ctx.startActivity(appDetails(ctx).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { } }) {
                Text("ऐप की सेटिंग खोलो", color = ch.accent)
            }
            TextButton(onClick = { openAutostart(ctx) }) { Text("Autostart खोलो", color = ch.accent) }
        }
    }
}

private fun openAutostart(ctx: Context) {
    val tries = listOf(
        ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
        ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
        ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
        ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
        ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
    )
    for (c in tries) {
        try {
            ctx.startActivity(Intent().setComponent(c).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (_: Exception) { }
    }
    try { ctx.startActivity(appDetails(ctx).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(shape).background(Color(0xFF0E0E0E)).border(1.dp, CARD_BORDER, shape).padding(12.dp)) {
        Text(title, color = Color(0xFFEDEDED), fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Spacer(Modifier.height(6.dp))
        content()
    }
}

@Composable
private fun ToggleRow(label: String, sub: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, color = Color(0xFFEDEDED), fontSize = 14.sp)
            if (sub.isNotBlank()) Text(sub, color = Color(0xFF999999), fontSize = 12.sp)
        }
        Switch(checked = on, onCheckedChange = onChange)
    }
}

/** Ultron's access level, quiet research, the Ultron-review option, words kept out of web searches, and what the family learned. */
@Composable
fun AssistSettingsScreen(ch: Character, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val p = remember { Prefs(ctx) }
    var level by remember { mutableIntStateOf(p.ultronLevel) }
    var review by remember { mutableStateOf(p.ultronReview) }
    var proactive by remember { mutableStateOf(p.proactiveResearch) }
    var budget by remember { mutableIntStateOf(p.researchBudget) }
    var redact by remember { mutableStateOf(p.redactTerms) }
    var talent by remember { mutableStateOf(Talent.adjustments(ctx).toString()) }

    Column(Modifier.fillMaxSize().background(Color.Black).verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("परिवार की सेटिंग", color = ch.accent, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))

        Section("Ultron कितना कर सकता है") {
            val opts = listOf(
                1 to "सिर्फ़ बोलना: कोई औज़ार नहीं",
                2 to "Observer (डिफ़ॉल्ट): पढ़ना, खोजना, विश्लेषण; कुछ बदलता नहीं",
                3 to "Analyst + मंज़ूरी: योजना बनाना, काम सौंपना; हर बदलाव पर आपकी हाँ",
            )
            opts.forEach { (v, text) ->
                val sel = level == v
                Row(
                    Modifier.fillMaxWidth().clickable { level = v; p.ultronLevel = v }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(if (sel) "🔘" else "⚪", fontSize = 16.sp)
                    Spacer(Modifier.width(8.dp))
                    Text(text, color = if (sel) ch.accent else Color(0xFFCCCCCC), fontSize = 13.sp)
                }
            }
            Text("यह नियम ऐप के कोड में लागू है, Ultron के अंदाज़ पर नहीं। kill switch उसे भी रोकता है।", color = Color(0xFF888888), fontSize = 11.sp)
        }

        Section("याददाश्त में अर्थ से खोज") {
            var emb by remember { mutableStateOf(p.embeddings) }
            ToggleRow(
                "बातों के मतलब से याद करना (embeddings)",
                "आपके relay से याददाश्त के छोटे टुकड़ों के 'अर्थ-अंक' बनते हैं, ताकि 'exam कब' पूछने पर 'परीक्षा 9 तारीख़' भी मिल जाए। संवेदनशील बातें कभी नहीं भेजी जातीं। बंद करने पर सिर्फ़ शब्दों से खोज होती है।",
                emb,
            ) { emb = it; p.embeddings = it }
        }

        Section("गतिविधि लॉग") {
            var th by remember { mutableStateOf(p.stepThumbs) }
            ToggleRow(
                "हर क़दम के बाद स्क्रीन की छोटी तस्वीर रखो",
                "स्क्रीन-एजेंट के लॉग में दिखती है कि हर क़दम पर स्क्रीन कैसी थी। बैंकिंग/पासवर्ड ऐप में कभी नहीं ली जाती, कहीं भेजी नहीं जाती, 14 दिन बाद या लॉग मिटाने पर हट जाती है।",
                th,
            ) { th = it; p.stepThumbs = it }
        }

        Section("योजना की जाँच") {
            ToggleRow("Ultron हर जवाब/योजना की कमी देखे", "परिवार से पूछने पर जवाब के बाद Ultron एक तीखी टिप्पणी जोड़ेगा (एक कॉल ज़्यादा लगता है)", review) { review = it; p.ultronReview = it }
        }

        Section("जवाब की भाषा") {
            var glob by remember { mutableStateOf(p.language) }
            var tick by remember { mutableIntStateOf(0) }
            val langs = listOf("hi" to "हिंदी (देवनागरी): डिफ़ॉल्ट", "en" to "English", "hinglish" to "Hinglish (रोमन अक्षरों में)", "auto" to "जिस भाषा में मैं लिखूँ, उसी में")
            langs.forEach { (v, text) ->
                val sel = glob == v
                Row(Modifier.fillMaxWidth().clickable { glob = v; p.language = v }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (sel) "🔘" else "⚪", fontSize = 16.sp)
                    Spacer(Modifier.width(8.dp))
                    Text(text, color = if (sel) ch.accent else Color(0xFFCCCCCC), fontSize = 13.sp)
                }
            }
            Text("हर किरदार के लिए अलग भाषा (नाम दबाकर बदलिए):", color = Color(0xFF999999), fontSize = 12.sp)
            val order = listOf("", "hi", "en", "hinglish", "auto")
            val names = mapOf("" to "सबके जैसा", "hi" to "हिंदी", "en" to "English", "hinglish" to "Hinglish", "auto" to "मेरी भाषा में")
            Characters.all.forEach { c ->
                val cur = if (tick >= 0) p.languageOverride(c.id) else ""
                TextButton(onClick = { p.setLanguageFor(c.id, order[(order.indexOf(cur) + 1) % order.size]); tick++ }) {
                    Text("${c.name}: ${names[cur]}", color = ch.accent, fontSize = 13.sp)
                }
            }
        }

        Section("चुपचाप तैयारी (proactive research)") {
            ToggleRow(
                "अगले काम की जानकारी पहले से खोज रखें", "आप कहें कि 'कल जयपुर जाना है' तो सही किरदार चुपचाप ज़रूरी जानकारी खोजकर बाद में बताएगा। निजी बातों (सेहत, पैसे, रिश्ते) पर कभी नहीं।",
                proactive,
            ) { proactive = it; p.proactiveResearch = it }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("रोज़ का बजट: $budget खोज", color = Color(0xFFCCCCCC), fontSize = 13.sp)
                TextButton(onClick = { budget = (budget - 1).coerceAtLeast(1); p.researchBudget = budget }) { Text("−", color = ch.accent, fontSize = 18.sp) }
                TextButton(onClick = { budget = (budget + 1).coerceAtMost(10); p.researchBudget = budget }) { Text("+", color = ch.accent, fontSize = 18.sp) }
            }
            Text("आज इस्तेमाल: ${p.researchUsedToday()}। आपके सीधे सवालों का कोटा इससे कभी नहीं घटता।", color = Color(0xFF888888), fontSize = 11.sp)
        }

        Section("खोज में कभी न जाने वाले शब्द") {
            Text("आपका नाम, मोहल्ला वग़ैरह (कॉमा से अलग)। फ़ोन नंबर, ईमेल और लिंक वैसे भी हटा दिए जाते हैं।", color = Color(0xFF999999), fontSize = 12.sp)
            OutlinedTextField(
                value = redact, onValueChange = { redact = it; p.redactTerms = it },
                singleLine = true, label = { Text("जैसे: सुनीता, Sunita") },
            )
        }

        Section("परिवार ने क्या सीखा (किसे किस विषय में भरोसा)") {
            val readable = try {
                val o = Talent.adjustments(ctx)
                val rows = mutableListOf<String>()
                o.keys().forEach { c ->
                    val r = o.optJSONObject(c) ?: return@forEach
                    r.keys().forEach { d -> rows.add("${Characters.byId(c).name} / $d: " + String.format(java.util.Locale.US, "%+.2f", r.optDouble(d))) }
                }
                if (rows.isEmpty()) "अभी कोई समायोजन नहीं।" else rows.joinToString("\n")
            } catch (_: Exception) { "अभी कोई समायोजन नहीं।" }
            Text(readable, color = Color(0xFFCCCCCC), fontSize = 12.sp)
            if (talent != "{}") TextButton(onClick = { p.talentAdj = "{}"; talent = "{}" }) { Text("सब भुलाओ (रीसेट)", color = ch.accent) }
            Text("पुरानी खोजें: 📚 → नोट्स में (वहीं बदल या मिटा सकते हैं)।", color = Color(0xFF888888), fontSize = 11.sp)
        }

        Spacer(Modifier.height(12.dp))
        Button(onClick = onClose) { Text("बंद करो") }
        Spacer(Modifier.height(24.dp))
    }
}
