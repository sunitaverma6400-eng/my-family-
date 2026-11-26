package com.stark.family

import android.net.Uri
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val FG = Color(0xFFEDEDED)
private val DIM = Color(0xFF8A8A8A)
private val CARD = Color(0xFF0E0E0E)
private val RED = Color(0xFFFF5D5D)

private class EditTarget(val title: String, val initial: String, val importance: Int?, val onSave: (String, Int) -> Unit)
private class Confirm(val text: String, val onYes: () -> Unit)

private fun fmt(ts: Long, pattern: String) = SimpleDateFormat(pattern, Locale.US).format(Date(ts))

@Composable
fun MemoryScreen(ch: Character, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val repo = remember { MemoryRepo.get(ctx) }
    val scope = rememberCoroutineScope()
    var tab by remember { mutableIntStateOf(0) }
    var tick by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var facts by remember { mutableStateOf(listOf<Fact>()) }
    var sums by remember { mutableStateOf(listOf<Summary>()) }
    var eps by remember { mutableStateOf(listOf<Episode>()) }
    var notes by remember { mutableStateOf(listOf<Note>()) }
    var edit by remember { mutableStateOf<EditTarget?>(null) }
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    var status by remember { mutableStateOf("") }
    var passAsk by remember { mutableStateOf<PassAsk?>(null) }
    var pendingPass by remember { mutableStateOf("") }

    LaunchedEffect(tick) {
        facts = repo.dao.facts()
        sums = listOf("profile", "weekly", "daily").flatMap { repo.dao.summaries(it) }
        eps = repo.dao.recentEpisodes(300)
        notes = repo.dao.notes()
    }
    BackHandler(onBack = onClose)

    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri: Uri? ->
        if (uri != null) scope.launch {
            status = try {
                ctx.contentResolver.openOutputStream(uri)?.use { repo.backupZip(it, pendingPass) }
                if (pendingPass.isEmpty()) "बैकअप बन गया (बिना लॉक)।" else "बैकअप बन गया और पासफ़्रेज़ से लॉक है। पासफ़्रेज़ भूले तो खुल नहीं पाएगा।"
            } catch (e: Exception) {
                "बैकअप नहीं बन पाया।"
            } finally {
                pendingPass = ""
            }
        }
    }
    val opener = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) passAsk = PassAsk("अगर बैकअप लॉक था तो पासफ़्रेज़ डालिए (नहीं तो ख़ाली छोड़िए)") { pass ->
            confirm = Confirm("मौजूदा याददाश्त हटाकर इस बैकअप से बदल दूँ?") {
                scope.launch {
                    status = try {
                        val n = ctx.contentResolver.openInputStream(uri)?.use { repo.restoreZip(it, pass) } ?: -1
                        when {
                            n >= 0 -> "रीस्टोर हो गया ($n प्रविष्टियाँ)।"
                            n == -2 -> "बैकअप लॉक है: पासफ़्रेज़ नहीं दिया या ग़लत है।"
                            else -> "यह फ़ाइल हमारे बैकअप जैसी नहीं लगी।"
                        }
                    } catch (e: Exception) {
                        "रीस्टोर नहीं हो पाया।"
                    }
                    tick++
                }
            }
        }
    }

    Column(Modifier.fillMaxSize().background(Color.Black).padding(top = 8.dp)) {
        Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onClose) { Text("← वापस", color = ch.accent2) }
            Text("📚 याददाश्त", color = ch.accent, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("तथ्य", "सार", "बातचीत", "नोट्स").forEachIndexed { i, name ->
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                        .background(if (tab == i) ch.accent else CARD)
                        .clickable { tab = i }.padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(name, color = if (tab == i) Color.Black else FG, fontSize = 14.sp, fontWeight = FontWeight.Bold) }
            }
        }
        LazyColumn(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (tab) {
                0 -> {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = query, onValueChange = { query = it }, singleLine = true,
                                placeholder = { Text("खोजिए…", color = DIM) }, modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = {
                                edit = EditTarget("नई बात", "", 3) { t, imp ->
                                    scope.launch {
                                        status = if (repo.addFact(t, imp, "manual")) "जोड़ दिया।" else "यह बात नहीं जुड़ी (बहुत छोटी या संवेदनशील)।"
                                        tick++
                                    }
                                }
                            }) { Text("＋", color = ch.accent, fontSize = 24.sp) }
                        }
                    }
                    val shown = facts.filter { query.isBlank() || it.text.contains(query, ignoreCase = true) }
                    if (shown.isEmpty()) item { Text("अभी कोई तथ्य नहीं। बातचीत में 'याद रखो…' कहिए, या ＋ दबाइए।", color = DIM) }
                    items(shown) { f ->
                        MemCard {
                            Text(f.text, color = FG, fontSize = 15.sp)
                            val src = when (f.source) { "told" -> "आपने बताया"; "auto" -> "अपने-आप"; else -> "हाथ से" }
                            Text("${if (f.kind == "habit") "आदत" else "तथ्य"} · ${"★".repeat(f.importance)} · $src · ${fmt(f.updatedAt, "dd MMM")}", color = DIM, fontSize = 12.sp)
                            Row {
                                TextButton(onClick = {
                                    edit = EditTarget("बदलिए", f.text, f.importance) { t, imp ->
                                        scope.launch {
                                            if (t.length >= 2 && !MemoryRepo.isSensitive(t)) repo.dao.updateFact(f.copy(text = t, importance = imp, updatedAt = System.currentTimeMillis()))
                                            else status = "यह बात सहेजी नहीं जा सकती।"
                                            tick++
                                        }
                                    }
                                }) { Text("✏ बदलो", color = ch.accent2) }
                                TextButton(onClick = { scope.launch { repo.dao.deleteFact(f); tick++ } }) { Text("🗑 मिटाओ", color = RED) }
                            }
                        }
                    }
                }
                1 -> {
                    if (sums.isEmpty()) item { Text("सार अपने-आप बनते हैं: पिछले दिनों की बातचीत से रोज़ का, फिर हफ़्ते का, फिर आपका प्रोफ़ाइल।", color = DIM) }
                    items(sums) { s ->
                        MemCard {
                            Text(
                                when (s.kind) { "profile" -> "प्रोफ़ाइल"; "weekly" -> "हफ़्ता ${s.period}"; else -> "दिन ${s.period}" },
                                color = ch.accent, fontWeight = FontWeight.Bold, fontSize = 13.sp,
                            )
                            Text(s.text, color = FG, fontSize = 15.sp)
                            Row {
                                TextButton(onClick = {
                                    edit = EditTarget("सार बदलिए", s.text, null) { t, _ ->
                                        scope.launch { if (t.isNotBlank()) repo.dao.putSummary(s.copy(text = t)); tick++ }
                                    }
                                }) { Text("✏ बदलो", color = ch.accent2) }
                                TextButton(onClick = { scope.launch { repo.dao.deleteSummary(s.id); tick++ } }) { Text("🗑 मिटाओ", color = RED) }
                            }
                        }
                    }
                }
                2 -> {
                    if (eps.isEmpty()) item { Text("अभी कोई बातचीत दर्ज नहीं है।", color = DIM) }
                    eps.groupBy { it.day }.forEach { (day, list) ->
                        item {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("📅 $day", color = ch.accent, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                TextButton(onClick = {
                                    confirm = Confirm("$day की सारी बातचीत और उस दिन का सार मिटा दूँ?") {
                                        scope.launch { repo.deleteDay(day); tick++ }
                                    }
                                }) { Text("🗑 पूरा दिन", color = RED, fontSize = 13.sp) }
                            }
                        }
                        items(list) { e ->
                            MemCard {
                                Text("${fmt(e.ts, "HH:mm")} · ${Characters.byId(e.character).name}", color = DIM, fontSize = 12.sp)
                                Text("आप: ${e.user}", color = FG, fontSize = 14.sp, maxLines = 4)
                                if (e.reply.isNotBlank()) Text(e.reply, color = ch.accent2, fontSize = 14.sp, maxLines = 4)
                                TextButton(onClick = { scope.launch { repo.dao.deleteEpisode(e.id); tick++ } }) { Text("🗑 मिटाओ", color = RED) }
                            }
                        }
                    }
                    if (eps.isNotEmpty()) item { Text("ध्यान: बातचीत मिटाने पर उससे बने हफ़्ते/प्रोफ़ाइल के सार अपने-आप नहीं मिटते। 'सार' टैब में उन्हें भी मिटा सकते हैं।", color = DIM, fontSize = 12.sp) }
                }
                else -> {
                    item {
                        TextButton(onClick = {
                            edit = EditTarget("${ch.name} की नई टिप्पणी", "", null) { t, _ ->
                                scope.launch {
                                    if (t.isNotBlank() && !MemoryRepo.isSensitive(t)) repo.dao.insertNote(Note(character = ch.id, text = t, ts = System.currentTimeMillis()))
                                    tick++
                                }
                            }
                        }) { Text("＋ ${ch.name} के लिए नोट जोड़ो", color = ch.accent) }
                    }
                    if (notes.isEmpty()) item { Text("हर किरदार की अपनी निजी टिप्पणियाँ यहाँ दिखेंगी (जैसे Ultron को आपके बहाने याद रहते हैं)।", color = DIM) }
                    items(notes) { n ->
                        MemCard {
                            Text(Characters.byId(n.character).name, color = Characters.byId(n.character).accent, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text(n.text, color = FG, fontSize = 15.sp)
                            Row {
                                TextButton(onClick = {
                                    edit = EditTarget("नोट बदलिए", n.text, null) { t, _ ->
                                        scope.launch { if (t.isNotBlank()) repo.dao.updateNote(n.copy(text = t)); tick++ }
                                    }
                                }) { Text("✏ बदलो", color = ch.accent2) }
                                TextButton(onClick = { scope.launch { repo.dao.deleteNote(n.id); tick++ } }) { Text("🗑 मिटाओ", color = RED) }
                            }
                        }
                    }
                }
            }
        }
        if (status.isNotEmpty()) Text(status, color = ch.accent2, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 14.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = {
                passAsk = PassAsk("बैकअप को पासफ़्रेज़ से लॉक करें? (ख़ाली छोड़ेंगे तो बिना लॉक बनेगा)") { pass ->
                    pendingPass = pass
                    saver.launch("stark-memory-${fmt(System.currentTimeMillis(), "yyyyMMdd")}.zip")
                }
            }) { Text("💾 बैकअप", color = ch.accent2, fontSize = 13.sp) }
            TextButton(onClick = { opener.launch(arrayOf("*/*")) }) { Text("📥 रीस्टोर", color = ch.accent2, fontSize = 13.sp) }
            TextButton(onClick = {
                confirm = Confirm("सब कुछ (तथ्य, बातचीत, सार, नोट्स) हमेशा के लिए मिटा दूँ? यह वापस नहीं आएगा।") {
                    scope.launch { repo.wipeAll(); status = "सब मिटा दिया।"; tick++ }
                }
            }) { Text("🗑 सब मिटाओ", color = RED, fontSize = 13.sp) }
        }
    }

    passAsk?.let { a -> PassDialog(a) { passAsk = null } }
    edit?.let { t -> EditDialog(t, ch.accent) { edit = null } }
    confirm?.let { c ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("पक्का?", color = FG) },
            text = { Text(c.text, color = FG) },
            confirmButton = { TextButton(onClick = { c.onYes(); confirm = null }) { Text("हाँ", color = RED) } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("नहीं", color = DIM) } },
        )
    }
}

@Composable
private fun MemCard(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(CARD)
            .border(1.dp, Color(0xFF222222), RoundedCornerShape(14.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) { content() }
}

@Composable
private fun EditDialog(t: EditTarget, accent: Color, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(t.initial) }
    var imp by remember { mutableIntStateOf(t.importance ?: 3) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t.title, color = FG) },
        text = {
            Column {
                OutlinedTextField(value = text, onValueChange = { text = it }, minLines = 2, maxLines = 6)
                if (t.importance != null) {
                    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("अहमियत:", color = DIM, fontSize = 13.sp)
                        (1..5).forEach { n ->
                            Text(
                                if (n <= imp) "★" else "☆", color = accent, fontSize = 24.sp,
                                modifier = Modifier.clickable { imp = n }.padding(horizontal = 4.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { t.onSave(text.trim(), imp); onDismiss() }) { Text("सहेजो", color = accent) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("रद्द", color = DIM) } },
    )
}


private class PassAsk(val title: String, val onOk: (String) -> Unit)

@Composable
private fun PassDialog(ask: PassAsk, onDismiss: () -> Unit) {
    var pass by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(ask.title, fontSize = 15.sp) },
        text = {
            OutlinedTextField(
                value = pass, onValueChange = { pass = it }, singleLine = true,
                label = { Text("पासफ़्रेज़") }, visualTransformation = PasswordVisualTransformation(),
            )
        },
        confirmButton = { TextButton(onClick = { onDismiss(); ask.onOk(pass) }) { Text("आगे") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("रद्द") } },
    )
}
