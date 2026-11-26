package com.stark.family

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One web source of a research answer. */
data class Source(val title: String, val url: String)

data class ResearchResult(
    val degraded: Boolean,
    val retryAfterS: Int,
    val refused: String,
    val answer: String,
    val known: String,
    val guessed: String,
    val confidence: Double,
    val timeSensitive: Boolean,
    val sources: List<Source>,
    val reused: Boolean = false,
    val errors: String = "",
)

/** One visible step of the ask-the-family protocol (nothing happens in a hidden channel). */
data class FamilyStep(val kind: String, val who: String, val to: String, val text: String)

data class FamilyResult(
    val degraded: Boolean,
    val speech: String,
    val who: String,
    val sources: List<Source>,
    val confidence: Double,
    val steps: List<FamilyStep>,
    val honestGap: Boolean,
    val hops: Int,
    val report: String = "",
)

/**
 * Research notes live in the normal Memory notes table (character = "research"), so they show up in the Memory screen,
 * can be edited/deleted there, are included in backups and are really wiped with "सब मिटाओ".
 * The note text is human-readable AND parseable: the retrieval time is Note.ts, and "ताज़ा-ज़रूरी: हाँ" marks fast-changing facts.
 */
object ResearchNotes {
    const val OWNER = "research"
    private const val TS_FRESH_MS = 3L * 3600_000L          // time-sensitive notes are re-checked after 3 hours
    private const val STABLE_MS = 30L * 86_400_000L          // other notes stay reusable for 30 days
    private val ID_FORMAT get() = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

    fun encode(query: String, r: ResearchResult, nowMs: Long): String {
        val src = r.sources.take(4).joinToString("; ") { s -> if (s.title.isBlank()) s.url else "${s.title} (${s.url})" }
        return buildString {
            append("🔎 खोज: ").append(query.take(160)).append('\n')
            append(r.answer.take(700)).append('\n')
            if (r.known.isNotBlank()) append("पक्का: ").append(r.known.take(300)).append('\n')
            if (r.guessed.isNotBlank()) append("अंदाज़ा/अधूरा: ").append(r.guessed.take(300)).append('\n')
            if (src.isNotBlank()) append("स्रोत: ").append(src).append('\n')
            append("भरोसा: ").append((r.confidence * 100).toInt()).append("% · तारीख़: ").append(ID_FORMAT.format(Date(nowMs)))
            append(" · ताज़ा-ज़रूरी: ").append(if (r.timeSensitive) "हाँ" else "नहीं")
        }
    }

    fun isTimeSensitive(text: String) = text.contains("ताज़ा-ज़रूरी: हाँ")
    fun queryOf(text: String): String = text.lineSequence().firstOrNull()?.removePrefix("🔎 खोज:")?.trim().orEmpty()
    fun answerOf(text: String): String = text.lineSequence().drop(1).firstOrNull().orEmpty()

    /** Usable without a new search? Time-sensitive notes must be recent; others may be a month old. */
    fun usable(text: String, ts: Long, nowMs: Long): Boolean {
        val age = nowMs - ts
        return if (isTimeSensitive(text)) age <= TS_FRESH_MS else age <= STABLE_MS
    }

    private fun confidenceOf(text: String): Double =
        Regex("भरोसा: (\\d{1,3})%").find(text)?.groupValues?.get(1)?.toDoubleOrNull()?.div(100.0) ?: 0.5

    suspend fun save(ctx: Context, query: String, r: ResearchResult) = withContext(Dispatchers.IO) {
        if (r.degraded || r.refused.isNotBlank() || r.confidence < 0.3 || MemoryRepo.isSensitive(r.answer)) return@withContext
        val now = System.currentTimeMillis()
        MemoryRepo.get(ctx).dao.insertNote(Note(character = OWNER, text = encode(query, r, now), ts = now))
        Unit
    }

    /** Best matching note that may be reused instead of searching again (needs most of the query words to match). */
    suspend fun findReusable(ctx: Context, query: String): ResearchResult? = withContext(Dispatchers.IO) {
        val q = MemoryRepo.tokens(query)
        if (q.isEmpty()) return@withContext null
        val now = System.currentTimeMillis()
        val best = MemoryRepo.get(ctx).dao.notes().filter { it.character == OWNER && usable(it.text, it.ts, now) }
            .map { n ->
                val t = MemoryRepo.tokens(queryOf(n.text))
                n to (if (t.isEmpty()) 0.0 else q.count { it in t }.toDouble() / q.size)
            }.filter { it.second >= 0.7 }.maxByOrNull { it.second }?.first ?: return@withContext null
        ResearchResult(
            degraded = false, retryAfterS = 0, refused = "", answer = answerOf(best.text), known = "", guessed = "",
            confidence = confidenceOf(best.text), timeSensitive = isTimeSensitive(best.text),
            sources = emptyList(), reused = true,
        )
    }

    /** Lines for the LLM prompt. Stale time-sensitive notes are left out (they must be re-checked first). */
    suspend fun relevant(ctx: Context, userText: String, limit: Int = 2): List<String> = withContext(Dispatchers.IO) {
        val q = MemoryRepo.tokens(userText)
        if (q.isEmpty()) return@withContext emptyList()
        val now = System.currentTimeMillis()
        MemoryRepo.get(ctx).dao.notes().filter { it.character == OWNER && usable(it.text, it.ts, now) }
            .map { n -> n to q.count { it in MemoryRepo.tokens(n.text) } }
            .filter { it.second >= 2 }.sortedByDescending { it.second }.take(limit)
            .map { (n, _) -> "पुरानी खोज (${ID_FORMAT.format(Date(n.ts))}, डेटा): " + n.text.lineSequence().take(3).joinToString(" ").take(380) }
    }
}

/** Talent registry adjustments learned on this phone (+ when a peer answered, - when "नहीं पता"). Sent with each request. */
object Talent {
    fun adjustments(ctx: Context): JSONObject = try { JSONObject(Prefs(ctx).talentAdj) } catch (_: Exception) { JSONObject() }

    fun apply(ctx: Context, updates: JSONArray?) {
        if (updates == null || updates.length() == 0) return
        val cur = adjustments(ctx)
        for (i in 0 until updates.length()) {
            val u = updates.optJSONObject(i) ?: continue
            val c = u.optString("character")
            val d = u.optString("domain")
            if (c.isBlank() || d.isBlank() || Characters.all.none { it.id == c }) continue
            val row = cur.optJSONObject(c) ?: JSONObject().also { cur.put(c, it) }
            row.put(d, (row.optDouble(d, 0.0) + u.optDouble("delta", 0.0)).coerceIn(-0.4, 0.2))
        }
        Prefs(ctx).talentAdj = cur.toString()
    }
}

object Research {
    /** Personal terms that must never reach a search query (the relay also strips numbers, e-mails and links). */
    private suspend fun redactTerms(ctx: Context): JSONArray = withContext(Dispatchers.IO) {
        val out = LinkedHashSet<String>()
        Prefs(ctx).redactTerms.split(',').map { it.trim() }.filter { it.length >= 2 }.forEach { out.add(it) }
        val nameRx = Regex("(?:नाम|name is|name)\\s*(?:है|:)?\\s*([\\p{L}\\p{M}]{2,20})", RegexOption.IGNORE_CASE)
        MemoryRepo.get(ctx).dao.facts().take(60).forEach { f ->
            nameRx.find(f.text)?.groupValues?.get(1)?.let { n -> if (n !in setOf("है", "मेरा", "my")) out.add(n) }
        }
        JSONArray(out.toList().take(15))
    }

    private fun sources(a: JSONArray?): List<Source> {
        val out = mutableListOf<Source>()
        if (a != null) for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val u = o.optString("url")
            if (u.startsWith("http")) out.add(Source(o.optString("title"), u))
        }
        return out
    }

    suspend fun search(ctx: Context, character: String, query: String, proactive: Boolean = false): ResearchResult {
        if (KillSwitch.on(ctx)) return ResearchResult(true, 0, "kill", "⛔ kill switch चालू है।", "", "", 0.0, false, emptyList())
        ResearchNotes.findReusable(ctx, query)?.let { return it }
        val p = Prefs(ctx)
        val j = RelayClient().research(p.relayUrl, p.token, character, query, redactTerms(ctx), proactive)
        val r = ResearchResult(
            degraded = j.optBoolean("degraded", false), retryAfterS = j.optInt("retry_after_s", 30),
            refused = j.optString("refused"), answer = j.optString("answer"), known = j.optString("known"),
            guessed = j.optString("guessed"), confidence = j.optDouble("confidence", 0.0),
            timeSensitive = j.optBoolean("time_sensitive", false), sources = sources(j.optJSONArray("sources")),
            errors = j.optJSONArray("errors")?.let { a -> (0 until a.length()).map { a.optString(it) }.take(3).joinToString("; ") } ?: "",
        )
        ResearchNotes.save(ctx, query, r)
        return r
    }

    /** What the user sees: answer, what is sure vs guessed, sources, confidence. */
    fun format(r: ResearchResult): String {
        if (r.degraded) return "अभी खोज नहीं हो पाई। थोड़ी देर बाद फिर कहिए।" + if (r.errors.isBlank()) "" else "\n[कारण: ${r.errors.take(200)}]"
        if (r.refused.isNotBlank()) return r.answer
        return buildString {
            append(r.answer)
            if (r.reused) append("\n(पहले की खोज से; नई खोज की ज़रूरत नहीं पड़ी)")
            if (r.known.isNotBlank()) append("\nपक्का: ").append(r.known)
            if (r.guessed.isNotBlank()) append("\nअंदाज़ा/अधूरा: ").append(r.guessed)
            if (r.sources.isNotEmpty()) append("\nस्रोत: ").append(r.sources.take(3).joinToString("; ") { it.title.ifBlank { it.url } + " (" + it.url + ")" })
            append("\nभरोसा: ").append((r.confidence * 100).toInt()).append('%')
            if (r.timeSensitive) append(" · यह जल्दी बदल सकती है")
        }
    }
}

object Family {
    suspend fun ask(ctx: Context, asker: String, question: String, memory: List<String>): FamilyResult {
        val p = Prefs(ctx)
        val redact = JSONArray(p.redactTerms.split(',').map { it.trim() }.filter { it.length >= 2 })
        val j = RelayClient().familyAsk(p.relayUrl, p.token, asker, question, memory, Talent.adjustments(ctx), redact, p.ultronReview)
        val fin = j.optJSONObject("final") ?: JSONObject()
        val steps = mutableListOf<FamilyStep>()
        j.optJSONArray("steps")?.let { a ->
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                steps.add(FamilyStep(o.optString("kind"), o.optString("who"), o.optString("to"), o.optString("text")))
            }
        }
        Talent.apply(ctx, j.optJSONArray("talent_updates"))
        val note = j.optJSONObject("research_note")
        if (note != null) {
            val rr = ResearchResult(
                false, 0, "", note.optString("answer"), note.optString("known"), note.optString("guessed"),
                note.optDouble("confidence", 0.0), note.optBoolean("time_sensitive", false), parseSources(note.optJSONArray("sources")),
            )
            ResearchNotes.save(ctx, note.optString("query"), rr)
        }
        return FamilyResult(
            degraded = j.optBoolean("degraded", false), speech = fin.optString("speech"), who = fin.optString("who", asker),
            sources = parseSources(fin.optJSONArray("sources")), confidence = fin.optDouble("confidence", 0.0), steps = steps,
            honestGap = fin.optBoolean("honest_gap", false), hops = j.optInt("hops", 0),
            report = reportText(j.optJSONObject("report")),
        )
    }

    /** The self-solving report: problem / what was done / result / what is left / options. */
    private fun reportText(o: JSONObject?): String {
        if (o == null) return ""
        val did = o.optJSONArray("did")?.let { a -> (0 until a.length()).joinToString(", ") { a.optString(it) } }.orEmpty()
        val opts = o.optJSONArray("options")?.let { a -> (0 until a.length()).joinToString(" · ") { a.optString(it) } }.orEmpty()
        return buildString {
            append("समस्या: ").append(o.optString("problem")).append('\n')
            append("क्या किया: ").append(did).append('\n')
            append("नतीजा: ").append(o.optString("result")).append('\n')
            append("बाक़ी: ").append(o.optString("left"))
            if (opts.isNotBlank()) append('\n').append("आगे के विकल्प: ").append(opts)
        }
    }

    private fun parseSources(a: JSONArray?): List<Source> {
        val out = mutableListOf<Source>()
        if (a != null) for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val u = o.optString("url")
            if (u.startsWith("http")) out.add(Source(o.optString("title"), u))
        }
        return out
    }

    /** The steps as readable lines: "Jarvis → Edith: …". The final answer is shown separately by the caller. */
    fun lines(r: FamilyResult): List<String> = r.steps.filter { it.kind != "final" }.map { s ->
        val from = Characters.byId(s.who).name
        val to = if (s.to == "user" || s.to == "family") "" else " → " + Characters.byId(s.to).name
        "👥 $from$to: ${s.text}"
    }
}

/**
 * Quiet preparation: when the user says what they will do next, the right character may look it up in the background
 * and tell them later. Off by default, a small daily budget, never for personal topics, and the relay gives it the
 * lowest priority so it can never use the quota kept for direct requests.
 */
object ProactiveResearch {
    suspend fun maybe(ctx: Context, userText: String, speaker: String) {
        val app = ctx.applicationContext
        val p = Prefs(app)
        if (!p.proactiveResearch || KillSwitch.on(app) || userText.length < 12) return
        if (p.researchUsedToday() >= p.researchBudget) return
        try {
            val t = RelayClient().proactiveTopic(p.relayUrl, p.token, userText)
            if (!t.optBoolean("worth", false)) return
            val q = t.optString("query")
            if (q.isBlank()) return
            val who = t.optString("character", speaker).ifBlank { speaker }
            p.noteResearchUsed()
            val r = Research.search(app, who, q, proactive = true)
            if (r.degraded || r.refused.isNotBlank() || r.confidence < 0.5) return
            Proactive.deliver(app, Suggestion("info", "", who, "आपके अगले काम के लिए खोजकर रखा", Research.format(r).take(400)))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) { }
    }
}
