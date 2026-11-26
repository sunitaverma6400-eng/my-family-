package com.stark.family

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

data class ChatReply(
    val speech: String,
    val degraded: Boolean,
    val retryAfterS: Int,
    val languageOk: Boolean,
    val provider: String,
    val model: String,
    val toolCalls: List<ToolCall> = emptyList(),
)

class RelayClient {
    class RelayException(message: String) : Exception(message)

    /** The live (streamed) answer could not start or broke off: the caller falls back to the normal request. */
    class StreamUnavailable(message: String) : Exception(message)

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(75, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    /** For memory search: one short attempt, no waiting for a sleeping server. */
    private val quick by lazy { http.newBuilder().callTimeout(6, TimeUnit.SECONDS).build() }

    /** Vectors for texts via the relay, or null on any problem (the phone then simply searches by keywords). */
    suspend fun embedQuick(base: String, token: String, texts: List<String>, priority: Int): List<FloatArray>? {
        val body = JSONObject().put("texts", JSONArray(texts)).put("priority", priority)
        val req = try {
            Request.Builder().url(base.trim().trimEnd('/') + "/embed").header("X-App-Token", token)
                .post(body.toString().toRequestBody(jsonType)).build()
        } catch (e: IllegalArgumentException) {
            return null
        }
        return withContext(Dispatchers.IO) {
            try {
                quick.newCall(req).execute().use { resp ->
                    if (resp.code != 200) return@use null
                    val j = JSONObject(resp.body?.string() ?: return@use null)
                    if (j.optBoolean("degraded", true)) return@use null
                    val arr = j.optJSONArray("vectors") ?: return@use null
                    (0 until arr.length()).map { i ->
                        val a = arr.getJSONArray(i)
                        FloatArray(a.length()) { k -> a.getDouble(k).toFloat() }
                    }
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    suspend fun chat(
        base: String, token: String, character: String, message: String,
        history: List<Pair<String, String>>,
        tools: List<String> = emptyList(), context: JSONObject? = null, memory: List<String> = emptyList(),
        language: String = "hi",
        onWaking: (Boolean) -> Unit,
    ): ChatReply {
        val hist = JSONArray()
        history.forEach { (role, content) -> hist.put(JSONObject().put("role", role).put("content", content)) }
        val body = JSONObject().put("character", character).put("message", message).put("history", hist)
        if (tools.isNotEmpty()) body.put("tools", JSONArray(tools))
        if (context != null) body.put("context", context)
        if (memory.isNotEmpty()) body.put("memory", JSONArray(memory))
        if (language != "hi") body.put("language", language)
        val j = request(base, "/chat", token, body.toString(), onWaking)
        return toReply(j)
    }

    private fun toReply(j: JSONObject) = ChatReply(
        speech = j.optString("speech", ""),
        degraded = j.optBoolean("degraded", false),
        retryAfterS = j.optInt("retry_after_s", 15),
        languageOk = j.optBoolean("language_ok", true),
        provider = j.optString("provider", ""),
        model = j.optString("model", ""),
        toolCalls = parseCalls(j.optJSONArray("tool_calls")),
    )

    /**
     * Same answer as chat(), but words arrive while they are being written (server-sent events). [onDelta] runs on the main
     * thread. Throws StreamUnavailable if it cannot start or breaks off; the caller then uses chat() instead.
     */
    suspend fun chatStream(
        base: String, token: String, character: String, message: String,
        history: List<Pair<String, String>>,
        tools: List<String> = emptyList(), context: JSONObject? = null, memory: List<String> = emptyList(),
        language: String = "hi",
        onDelta: (String) -> Unit,
    ): ChatReply {
        val hist = JSONArray()
        history.forEach { (role, content) -> hist.put(JSONObject().put("role", role).put("content", content)) }
        val body = JSONObject().put("character", character).put("message", message).put("history", hist)
        if (tools.isNotEmpty()) body.put("tools", JSONArray(tools))
        if (context != null) body.put("context", context)
        if (memory.isNotEmpty()) body.put("memory", JSONArray(memory))
        if (language != "hi") body.put("language", language)
        val req = try {
            Request.Builder().url(base.trim().trimEnd('/') + "/chat/stream").header("X-App-Token", token)
                .post(body.toString().toRequestBody(jsonType)).build()
        } catch (e: IllegalArgumentException) {
            throw RelayException("Relay URL सही नहीं लग रहा। सेटिंग में पूरा https://… पता डालिए।")
        }
        return withContext(Dispatchers.IO) {
            try {
                http.newCall(req).execute().use { resp ->
                    if (resp.code == 401) throw RelayException("टोकन ग़लत है — सेटिंग में APP_TOKEN जाँचिए।")
                    if (resp.code != 200) throw StreamUnavailable("code ${resp.code}")
                    val src = resp.body?.source() ?: throw StreamUnavailable("no body")
                    var last: JSONObject? = null
                    while (last == null) {
                        val line = src.readUtf8Line() ?: break
                        if (!line.startsWith("data: ")) continue
                        val j = try { JSONObject(line.substring(6)) } catch (e: Exception) { continue }
                        val type = j.optString("type")
                        if (type == "delta") {
                            val d = j.optString("text")
                            if (d.isNotEmpty()) withContext(Dispatchers.Main) { onDelta(d) }
                        } else if (type == "final") {
                            last = j
                        } else if (type == "error") {
                            throw StreamUnavailable("cut")
                        }
                    }
                    toReply(last ?: throw StreamUnavailable("no final"))
                }
            } catch (e: IOException) {
                throw StreamUnavailable(e.javaClass.simpleName)
            }
        }
    }

    private fun parseCalls(arr: JSONArray?): List<ToolCall> {
        val out = mutableListOf<ToolCall>()
        if (arr == null) return out
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val name = o.optString("name", "")
            if (name.isEmpty()) continue
            // Missing risk/confirm fields default to the safe side (ask first).
            out.add(ToolCall(o.optString("id", "c$i"), name, o.optJSONObject("args") ?: JSONObject(),
                o.optString("risk", "high"), o.optBoolean("confirm", true)))
        }
        return out
    }

    /** Agent Engine: ask the relay for ONE next screen action. Returns null when the relay is busy. */
    suspend fun agentStep(
        base: String, token: String, character: String, goal: String, steps: List<StepRec>,
        pkg: String, screen: String, hint: String, skillHint: String = "",
    ): AgentStep? {
        val arr = JSONArray()
        steps.forEach { arr.put(JSONObject().put("n", it.n).put("action", it.action).put("target", it.target).put("result", it.result)) }
        val body = JSONObject().put("character", character).put("goal", goal).put("steps", arr)
            .put("screen", JSONObject().put("package", pkg).put("text", screen)).put("hint", hint).put("skill_hint", skillHint.take(900))
        val j = request(base, "/agent/step", token, body.toString()) { }
        if (j.optBoolean("degraded", false)) return null
        val o = j.optJSONObject("step") ?: return null
        return AgentStep(
            action = o.optString("action"), target = if (o.isNull("target")) null else o.optInt("target", -1).takeIf { it >= 0 },
            app = o.optString("app"), text = o.optString("text"), direction = o.optString("direction", "down"),
            say = o.optString("say"), reason = o.optString("reason"), expect = o.optString("expect"),
            changesState = o.optBoolean("changes_state", false), injection = o.optBoolean("injection", false),
            report = o.optString("report"),
        )
    }

    /** Reads one screenshot through the relay's vision tier. null = vision model busy/unavailable. */
    suspend fun agentLook(
        base: String, token: String, character: String, question: String, imageB64: String, w: Int, h: Int,
    ): VisionResult? {
        val body = JSONObject().put("character", character).put("question", question)
            .put("image_b64", imageB64).put("width", w).put("height", h)
        val j = request(base, "/agent/look", token, body.toString()) { }
        if (j.optBoolean("degraded", false)) return null
        val els = mutableListOf<VisionEl>()
        val arr = j.optJSONArray("elements")
        if (arr != null) for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            els.add(VisionEl(o.optString("label"), o.optInt("x"), o.optInt("y")))
        }
        return VisionResult(j.optString("summary"), els)
    }

    /** Teach by talking: Hinglish sentence -> one validated rule (or a question back). null = relay busy. */
    suspend fun ruleFromText(base: String, token: String, text: String, now: String, examples: JSONArray): RuleResp? {
        val body = JSONObject().put("text", text).put("now", now).put("examples", examples)
        val j = request(base, "/rule_from_text", token, body.toString()) { }
        if (j.optBoolean("degraded", false)) return null
        return RuleResp(j.optBoolean("understood", false), j.optJSONObject("rule"), j.optString("question"))
    }

    /** Research Engine: web search + Hindi summary with sources and confidence. Never throws for "busy": check degraded. */
    suspend fun research(
        base: String, token: String, character: String, query: String, redact: JSONArray, proactive: Boolean,
    ): JSONObject {
        val body = JSONObject().put("character", character).put("query", query).put("redact", redact).put("proactive", proactive)
        return request(base, "/research", token, body.toString()) { }
    }

    /** Camera photo -> Hindi answer (read the text on it, identify it, or answer a question). The photo is not stored by the relay. */
    suspend fun visionDescribe(
        base: String, token: String, character: String, mode: String, question: String, imageB64: String,
    ): JSONObject {
        val body = JSONObject().put("character", character).put("mode", mode).put("question", question).put("image_b64", imageB64)
        return request(base, "/vision/describe", token, body.toString()) { }
    }

    /** Quiet helper: is there something in what the user just said worth preparing research for? */
    suspend fun proactiveTopic(base: String, token: String, text: String): JSONObject {
        val body = JSONObject().put("text", text)
        return request(base, "/proactive/topic", token, body.toString()) { }
    }

    /** Ask-the-family protocol (memory -> best peer -> research; the relay enforces max 2 hops and a budget). */
    suspend fun familyAsk(
        base: String, token: String, asker: String, question: String, memory: List<String>,
        registry: JSONObject, redact: JSONArray, ultronReview: Boolean = false,
    ): JSONObject {
        val body = JSONObject().put("asker", asker).put("question", question).put("memory", JSONArray(memory))
            .put("registry", registry).put("redact", redact).put("ultron_review", ultronReview)
        return request(base, "/family/ask", token, body.toString()) { }
    }

    /** Memory ladder helper. Returns null when the relay is busy (try again later). */
    suspend fun summarize(base: String, token: String, kind: String, items: JSONArray, profilePrev: String? = null): JSONObject? {
        val body = JSONObject().put("kind", kind).put("items", items)
        if (profilePrev != null) body.put("profile_prev", profilePrev)
        val j = request(base, "/summarize", token, body.toString()) { }
        return if (j.optBoolean("degraded", false)) null else j
    }

    suspend fun brainStatus(base: String, token: String, onWaking: (Boolean) -> Unit): JSONObject =
        request(base, "/brain_status", token, null, onWaking)

    /** Render's free tier sleeps: retry with backoff and tell the UI we are waking it up. */
    private suspend fun request(
        base: String, path: String, token: String, body: String?, onWaking: (Boolean) -> Unit,
    ): JSONObject {
        val url = base.trim().trimEnd('/') + path
        val req = try {
            val b = Request.Builder().url(url).header("X-App-Token", token)
            if (body != null) b.post(body.toRequestBody(jsonType)) else b.get()
            b.build()
        } catch (e: IllegalArgumentException) {
            throw RelayException("Relay URL सही नहीं लग रहा। सेटिंग में पूरा https://… पता डालिए।")
        }
        var wait = 3000L
        for (attempt in 1..5) {
            try {
                val (code, text) = withContext(Dispatchers.IO) {
                    http.newCall(req).execute().use { it.code to (it.body?.string() ?: "") }
                }
                when {
                    code in 200..299 -> { onWaking(false); return JSONObject(text) }
                    code == 401 -> throw RelayException("टोकन ग़लत है — सेटिंग में APP_TOKEN जाँचिए।")
                    code == 503 && text.contains("server_not_configured") ->
                        throw RelayException("Relay पर APP_TOKEN सेट नहीं है (Render के environment में डालिए)।")
                    code == 502 || code == 503 || code == 504 -> { /* waking up: retry */ }
                    else -> throw RelayException("Relay ने त्रुटि दी ($code)।")
                }
            } catch (e: IOException) {
                /* network hiccup or cold start: retry */
            }
            if (attempt == 5) break
            onWaking(true)
            delay(wait)
            wait = minOf(wait * 2, 20000L)
        }
        onWaking(false)
        throw RelayException("Relay जाग नहीं पाया। इंटरनेट और Render की स्थिति जाँचिए, फिर कोशिश कीजिए।")
    }
}

fun formatBrain(j: JSONObject): String {
    val sb = StringBuilder()
    val provs = j.getJSONObject("providers")
    provs.keys().forEach { name ->
        val p = provs.getJSONObject(name)
        sb.append("${name.uppercase()}  keys:${p.getInt("configured_keys")}  breaker:${p.getString("breaker")}\n")
        val ks = p.getJSONArray("keys")
        for (i in 0 until ks.length()) {
            val k = ks.getJSONObject(i)
            val st = if (k.getString("state") == "cooling") "cooling ${k.getInt("cooling_for_s")}s" else "healthy"
            sb.append("  ${k.getString("key")}  $st  req:${k.getInt("req_today")} ok:${k.getInt("ok_today")}")
            val le = k.optString("last_error", "")
            if (le.isNotEmpty()) sb.append("  ($le)")
            sb.append("\n")
        }
        sb.append("\n")
    }
    sb.append("अभी कौन-सा मॉडल:\n")
    val chars = j.getJSONObject("characters")
    chars.keys().forEach { c ->
        val o = chars.getJSONObject(c)
        sb.append("  $c → ${o.optString("model_now", "—").ifEmpty { "—" }}  (${o.getString("tier")})\n")
    }
    return sb.toString()
}

data class StepRec(val n: Int, val action: String, val target: String, val result: String)

data class AgentStep(
    val action: String, val target: Int?, val app: String, val text: String, val direction: String,
    val say: String, val reason: String, val expect: String, val changesState: Boolean, val injection: Boolean,
    val report: String,
)

class RuleResp(val understood: Boolean, val rule: JSONObject?, val question: String)
