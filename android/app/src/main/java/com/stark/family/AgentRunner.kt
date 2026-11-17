package com.stark.family

import android.content.Context
import android.graphics.Rect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** What the runner needs from the overlay: speak, highlight, ask the user, honour pause. */
interface AgentUi {
    fun narrate(text: String)
    fun status(step: Int, max: Int)
    fun highlight(r: Rect?)
    fun markGesture()
    fun sending(on: Boolean)
    suspend fun hideForCapture(hide: Boolean)
    suspend fun confirm(text: String): Boolean
    suspend fun awaitIfPaused()
}

data class AgentOutcome(val status: String, val report: String, val steps: Int)

private class ExecRes(val ok: Boolean, val note: String = "")

/**
 * Observe -> Think -> Act -> Verify, one step at a time.
 *  - Think happens on the relay (one strict-JSON action per call); everything else is decided here, in code.
 *  - Screen text is untrusted data. Sensitive apps are blocked until the user unlocks them for this task.
 *  - Anything that changes state needs the user's "हाँ". Ultron (Observer) may not change anything at all.
 *  - Limits: step budget, time budget, loop detection, honest failure.
 */
class AgentRunner(
    private val ctx: Context,
    private val client: RelayClient,
    private val ui: AgentUi,
    private val maxSteps: Int = MAX_STEPS,
    private val blocked: Set<String> = emptySet(),
) {
    companion object {
        const val MAX_STEPS = 30
        const val MAX_MS = 5 * 60_000L
        const val MAX_VISION = 12

        private val SENSITIVE_PKG = listOf(
            "bank", "upi", "paytm", "phonepe", "paisa", "bhim", "wallet", "authenticator", "authy", "2fa", "otp",
            "password", "keepass", "bitwarden", "lastpass", "onepassword", "1password", "dashlane", "nordpass",
            "mpay", "freecharge", "mobikwik", "cred.club", "zerodha", "groww", "hdfc", "icici", "axisbank", "sbi", "kotak",
        )

        private val CHANGE_WORDS = listOf(
            "send", "भेज", "delete", "हटा", "मिटा", "remove", "pay", "भुगतान", "buy", "खरीद", "order", "ऑर्डर",
            "install", "इंस्टॉल", "uninstall", "अनइंस्टॉल", "restart", "reboot", "deploy", "publish", "post ", "submit",
            "confirm", "सबमिट", "reset", "रीसेट", "clear", "साफ़", "साफ", "format", "factory", "logout", "log out",
            "sign out", "लॉग आउट", "turn on", "turn off", "enable", "disable", "चालू", "बंद", "allow", "अनुमति",
            "grant", "accept", "स्वीकार", "forget", "भूल", "unpair", "disconnect", "cancel subscription",
            "share", "शेयर", "forward", "फॉरवर्ड", "फ़ॉरवर्ड", "upload", "अपलोड",
        )

        fun isSensitivePkg(pkg: String): Boolean {
            val p = pkg.lowercase()
            return SENSITIVE_PKG.any { p.contains(it) }
        }

        fun looksStateChanging(action: String, node: UiNode?, label: String): Boolean {
            if (action != "tap" && action != "long_press") return false
            if (node != null && (node.checkable || node.cls.contains("Switch") || node.cls.contains("CheckBox") || node.cls.contains("Toggle"))) return true
            val l = label.lowercase()
            return CHANGE_WORDS.any { l.contains(it) }
        }
    }

    private val dao = ActivityDb.get(ctx).dao()

    private var visionCalls = 0

    // ---- app-skill memory (a route that worked before) ----
    private val skills = SkillRepo.get(ctx)
    private var matched: LoadedSkill? = null
    private var activeSkill: LoadedSkill? = null
    private var sPtr = 0
    private var skillMiss = 0
    private var skillUsed = false
    private var learnable = true
    private var goalText = ""
    private val trail = mutableListOf<SkillStep>()

    private fun fastStep(s: SkillStep, snap: Snapshot): AgentStep? {
        fun mk(action: String, target: Int? = null, app: String = "", text: String = "", dir: String = "down") = AgentStep(
            action = action, target = target, app = app, text = text, direction = dir, say = "", reason = "सीखा हुआ रास्ता",
            expect = "", changesState = false, injection = false, report = "",
        )
        return when (s.action) {
            "tap", "long_press" -> {
                val l = s.label.trim()
                if (l.isEmpty()) null else snap.nodes.firstOrNull { !it.vision && it.label.equals(l, ignoreCase = true) }?.let { mk(s.action, it.idx) }
            }
            "open_app" -> if (s.app.isBlank()) null else mk("open_app", app = s.app)
            "scroll" -> mk("scroll", dir = s.direction.ifBlank { "down" })
            "find" -> if (s.text.isBlank()) null else mk("find", text = s.text, dir = s.direction.ifBlank { "down" })
            "back", "home", "recents", "enter" -> mk(s.action)
            else -> null  // type: the words usually change, so the model decides
        }
    }

    private fun advanceSkill(action: String, label: String, app: String, fromSkill: Boolean) {
        val sk = activeSkill ?: return
        val exp = sk.steps.getOrNull(sPtr)
        val same = exp != null && exp.action == action && when (action) {
            "tap", "long_press" -> exp.label.startsWith("{") || exp.label.equals(label, ignoreCase = true)
            "open_app" -> exp.app.equals(app, ignoreCase = true)
            else -> true
        }
        if (same) {
            sPtr++
            skillUsed = true
            skillMiss = 0
        } else if (!fromSkill && ++skillMiss >= 2) {
            activeSkill = null
        }
        if (sPtr >= sk.steps.size) activeSkill = null
    }

    /** Screenshot -> relay vision tier. Overlay is hidden while capturing; the picture lives only in memory. */
    private suspend fun look(svc: AgentAccessibilityService, character: String, question: String, base: String, token: String): Pair<VisionResult?, String> {
        if (visionCalls >= MAX_VISION) return null to "स्क्रीनशॉट पढ़ने की सीमा ($MAX_VISION बार) पूरी हो गई।"
        visionCalls++
        ui.hideForCapture(true)
        val bmp = try { svc.screenshot() } finally { ui.hideForCapture(false) }
            ?: return null to "स्क्रीनशॉट नहीं मिला (Android 11+ चाहिए, या यह स्क्रीन सुरक्षित/FLAG_SECURE है)।"
        val m = ctx.resources.displayMetrics
        val b64 = withContext(Dispatchers.Default) { svc.encodeForVision(bmp) }
            ?: return null to "स्क्रीन काली/सुरक्षित है, मैं उसे पढ़ नहीं सकता।"
        ui.sending(true)
        val v = try {
            client.agentLook(base, token, character, question, b64, m.widthPixels, m.heightPixels)
        } catch (e: RelayClient.RelayException) {
            return null to (e.message ?: "relay से जवाब नहीं मिला।")
        } finally {
            ui.sending(false)
        }
        return if (v == null) null to "दृश्य-मॉडल अभी उपलब्ध नहीं है (relay में Gemini key या vision model जाँचिए)।" else v to ""
    }

    private suspend fun finishRun(run: AgentRun, status: String, report: String, steps: Int): AgentOutcome {
        withContext(NonCancellable) {
            try { skills.finish(goalText, matched?.skill, skillUsed, status, trail, learnable) } catch (_: Exception) { }
            dao.updateRun(run.copy(status = status, report = report.take(1500), steps = steps))
        }
        return AgentOutcome(status, report, steps)
    }

    suspend fun run(goal: String, character: String, base: String, token: String): AgentOutcome {
        if (AgentAccessibilityService.instance == null) {
            return AgentOutcome("failed", "स्क्रीन-एजेंट (Accessibility) चालू नहीं है।", 0)
        }
        val run0 = AgentRun(ts = System.currentTimeMillis(), character = character, goal = goal.take(300), status = "running")
        val run = run0.copy(id = dao.insertRun(run0))
        goalText = goal
        try {
            skills.seedIfEmpty()
            matched = skills.match(goal)
        } catch (_: Exception) { }
        activeSkill = matched
        if (matched != null) ui.narrate("पुराना रास्ता मिला: ${matched?.skill?.title?.take(40)}")
        val started = System.currentTimeMillis()
        val history = mutableListOf<StepRec>()
        val unlocked = mutableSetOf<String>()
        val repeats = mutableMapOf<String, Int>()
        var n = 0
        var failStreak = 0
        var emptyTries = 0
        var relayFails = 0
        var hint = ""
        var visionCache: Pair<Int, VisionResult>? = null
        try {
            while (true) {
                coroutineContext.ensureActive()
                ui.awaitIfPaused()
                if (n >= maxSteps) return finishRun(run, "limit", "$maxSteps क़दम की सीमा पूरी हो गई, काम पूरा नहीं हुआ।\nअब तक: ${history.takeLast(4).joinToString("; ") { it.action + " " + it.target }}", n)
                if (System.currentTimeMillis() - started > MAX_MS) return finishRun(run, "limit", "समय सीमा (${MAX_MS / 60_000} मिनट) पूरी हो गई, काम पूरा नहीं हुआ।", n)
                val svc = AgentAccessibilityService.instance ?: return finishRun(run, "failed", "Accessibility बंद हो गई, इसलिए रुक गया।", n)

                val tree = withContext(Dispatchers.Default) { svc.snapshot() }
                if (tree.pkg.isNotEmpty() && tree.pkg in blocked) {
                    return finishRun(run, "stopped", "इस ऐप (${tree.pkg}) पर आपने एजेंट बंद कर रखा है, इसलिए रुक गया।", n)
                }
                if (tree.pkg.isNotEmpty() && isSensitivePkg(tree.pkg) && tree.pkg !in unlocked) {
                    val ok = ui.confirm("यह संवेदनशील ऐप लगता है (${tree.pkg}). इस काम में इसमें चलने दूँ? पासवर्ड, OTP और कार्ड नंबर मैं कभी नहीं पढ़ूँगा या लिखूँगा।")
                    if (!ok) return finishRun(run, "stopped", "संवेदनशील ऐप में काम नहीं किया।", n)
                    unlocked.add(tree.pkg)
                }
                if (tree.empty || unlocked.isNotEmpty()) learnable = false
                var snap = tree
                val m = ctx.resources.displayMetrics
                if (tree.empty) {
                    if (emptyTries++ < 1) { delay(900); continue }
                    val (v, why) = look(svc, character, "इस स्क्रीन पर क्या-क्या दिख रहा है? दबाने लायक चीज़ें कहाँ हैं?", base, token)
                    if (v == null) return finishRun(run, "failed", "इस स्क्रीन से कुछ पढ़ नहीं पाया: $why झूठ नहीं बोलूँगा, यहाँ मैं अंधा हूँ।", n)
                    snap = tree.withVision(v, m.widthPixels, m.heightPixels)
                } else {
                    emptyTries = 0
                    visionCache?.takeIf { it.first == tree.hash }?.let { snap = tree.withVision(it.second, m.widthPixels, m.heightPixels) }
                }

                var fromSkill = false
                var got: AgentStep? = null
                val sk = activeSkill
                if (sk != null && sk.skill.successes >= 1 && sPtr < sk.steps.size) {
                    got = fastStep(sk.steps[sPtr], snap)
                    if (got != null) {
                        fromSkill = true
                        ui.narrate("पहले का रास्ता दोहरा रहा हूँ (${sPtr + 1}/${sk.steps.size})")
                    }
                }
                if (got == null) {
                    ui.sending(true)
                    got = try {
                        client.agentStep(
                            base, token, character, goal, history.takeLast(8), snap.pkg, snap.text, hint,
                            skillHint = activeSkill?.let { SkillRepo.hintText(it.steps, sPtr) }.orEmpty(),
                        )
                    } catch (e: RelayClient.RelayException) {
                        return finishRun(run, "failed", e.message ?: "relay से जवाब नहीं मिला।", n)
                    } finally {
                        ui.sending(false)
                    }
                }
                val st = got
                if (st == null) {
                    if (++relayFails >= 3) return finishRun(run, "failed", "अभी दिमाग़ (relay) व्यस्त है, थोड़ी देर बाद फिर कहिए।", n)
                    delay(4000)
                    continue
                }
                relayFails = 0
                if (st.say.isNotBlank()) ui.narrate(st.say)
                if (st.injection) ui.narrate("⚠ स्क्रीन पर कोई निर्देश दिखा, मैंने उसे नहीं माना।")

                if (st.action == "finish") {
                    n++
                    dao.insertStep(AgentStepLog(runId = run.id, n = n, ts = System.currentTimeMillis(), action = "finish", target = "", reason = st.reason, result = "रिपोर्ट दी", confirmed = if (st.injection) "स्क्रीन पर निर्देश मिला, नहीं माना" else ""))
                    return finishRun(run, "ok", st.report, n)
                }
                if (st.action == "ask") return finishRun(run, "ask", st.say.ifBlank { st.report }, n)

                n++
                ui.status(n, maxSteps)

                if (st.action == "calc") {
                    val v = Calc.eval(st.text)
                    val res = if (v == null) "हिसाब नहीं हो पाया" else "= ${Calc.fmt(v)}"
                    history.add(StepRec(n, "calc", st.text.take(60), res))
                    dao.insertStep(AgentStepLog(runId = run.id, n = n, ts = System.currentTimeMillis(), action = "calc", target = st.text.take(60), reason = st.reason, result = res))
                    hint = if (v == null) "calc की अभिव्यक्ति सही नहीं थी; सिर्फ़ अंक और + - * / ( ) % ." else ""
                    continue
                }
                if (st.action == "look") {
                    val (v, why) = look(svc, character, st.text, base, token)
                    val res = if (v == null) "विफल: $why" else v.summary.take(200)
                    history.add(StepRec(n, "look", st.text.take(40), res))
                    dao.insertStep(AgentStepLog(runId = run.id, n = n, ts = System.currentTimeMillis(), action = "look", target = st.text.take(60), reason = st.reason, result = res))
                    if (v != null && !tree.empty) visionCache = tree.hash to v
                    if (v == null && ++failStreak >= 2) return finishRun(run, "ask", "स्क्रीनशॉट पढ़ नहीं पा रहा: $why आप बताइए आगे क्या करूँ।", n)
                    continue
                }

                if (st.action == "find") {
                    val want = st.text.trim()
                    var found: UiNode? = null
                    var scrolls = 0
                    var cur = tree
                    var same = 0
                    var lastHash = tree.hash
                    while (want.isNotEmpty()) {
                        coroutineContext.ensureActive()
                        found = cur.nodes.firstOrNull { !it.vision && (it.text.contains(want, ignoreCase = true) || it.desc.contains(want, ignoreCase = true)) }
                        if (found != null || scrolls >= 12 || same >= 2) break
                        ui.markGesture()
                        svc.scroll(null, cur, st.direction != "up")
                        delay(800)
                        scrolls++
                        cur = withContext(Dispatchers.Default) { svc.snapshot() }
                        if (cur.hash == lastHash) same++ else { same = 0; lastHash = cur.hash }
                    }
                    val ok = found != null
                    val res = if (ok) "मिला: '${found?.label}' ($scrolls स्क्रॉल के बाद)" else "'$want' नहीं मिला ($scrolls स्क्रॉल; सूची का अंत या वह यहाँ नहीं है)"
                    history.add(StepRec(n, "find", want.take(40), res))
                    dao.insertStep(AgentStepLog(runId = run.id, n = n, ts = System.currentTimeMillis(), action = "find", target = want.take(60), reason = st.reason, result = res))
                    if (ok) {
                        failStreak = 0
                        hint = ""
                        trail.add(SkillStep("find", text = want, direction = st.direction))
                        advanceSkill("find", "", "", fromSkill)
                    } else {
                        failStreak++
                        hint = "'$want' नहीं मिला। दूसरा तरीक़ा आज़माओ: ऐप का खोज-बॉक्स, दूसरा टैब, या दूसरा नाम/शब्द।"
                        if (fromSkill) activeSkill = null
                        if (failStreak >= 3) return finishRun(run, "ask", "मैं '$want' ढूँढ नहीं पाया ($res)। आप बताइए आगे क्या करूँ?", n)
                    }
                    continue
                }

                val node = st.target?.let { snap.nodes.getOrNull(it) }
                val label = (node?.label ?: st.app).take(60)
                if (node?.vision == true) learnable = false
                if ((st.action == "tap" || st.action == "long_press" || st.action == "type") && node == null) {
                    history.add(StepRec(n, st.action, "?", "विफल: स्क्रीन पर वह क्रमांक नहीं है"))
                    hint = "तुमने ऐसा क्रमांक चुना जो स्क्रीन पर नहीं है। सिर्फ़ दिखती सूची से चुनो।"
                    if (++failStreak >= 2) return finishRun(run, "ask", "मैं सही चीज़ पहचान नहीं पा रहा। आप बताइए आगे क्या करूँ?", n)
                    continue
                }
                val key = "${st.action}|${st.target}|${st.text}|${st.app}|${snap.hash}"
                val times = (repeats[key] ?: 0) + 1
                repeats[key] = times
                if (times >= 3) return finishRun(run, "failed", "एक ही क़दम बार-बार दोहरा रहा हूँ, इसलिए रुक गया ($label)।", n)

                var confirmedNote = ""
                if (st.changesState || looksStateChanging(st.action, node, label)) {
                    val desc = "${st.action} → ‘$label’ — ${st.reason}"
                    if (character == "ultron") {
                        return finishRun(run, "stopped", "मैं सिर्फ़ देख सकता हूँ (Observer)। यह बदलाव मैं नहीं करूँगा: $desc", n)
                    }
                    if (!ui.confirm("कुछ बदलने वाला क़दम: $desc\nकरूँ?")) {
                        dao.insertStep(AgentStepLog(runId = run.id, n = n, ts = System.currentTimeMillis(), action = st.action, target = label, reason = st.reason, result = "आपने मना किया", confirmed = "मना"))
                        return finishRun(run, "stopped", "ठीक है, वह बदलाव नहीं किया।", n)
                    }
                    confirmedNote = "आपने हाँ कहा"
                }

                ui.highlight(node?.bounds)
                delay(500)
                ui.markGesture()
                val res = execute(svc, st, snap, node)
                ui.highlight(null)
                delay(900)

                val snap2 = withContext(Dispatchers.Default) { svc.snapshot() }
                var result = when {
                    !res.ok -> "विफल: ${res.note.ifBlank { "क़दम नहीं चला" }}"
                    st.action == "wait" || st.action == "open_app" || tree.empty -> "ठीक"
                    snap2.hash == tree.hash && st.action == "scroll" -> "कोई असर नहीं दिखा (सूची का अंत हो सकता है)"
                    snap2.hash == tree.hash -> "कोई असर नहीं दिखा"
                    else -> "ठीक"
                }
                // 'expect' is only a hint from the model (often a description, not a word on screen), so a miss is
                // reported to the model but never counted as a failed step.
                if (result == "ठीक" && st.expect.isNotBlank() && !snap2.text.contains(st.expect, ignoreCase = true)) {
                    result = "ठीक (स्क्रीन बदली; '${st.expect}' शब्द नहीं दिखा, अगले क़दम में ख़ुद देखकर तय करो)"
                }
                history.add(StepRec(n, st.action, label, result))
                dao.insertStep(AgentStepLog(runId = run.id, n = n, ts = System.currentTimeMillis(), action = st.action, target = label, reason = st.reason, result = result, confirmed = confirmedNote))

                if (result.startsWith("ठीक")) {
                    failStreak = 0
                    hint = ""
                    if (st.action != "wait") {
                        trail.add(
                            SkillStep(
                                st.action, if (st.action == "tap" || st.action == "long_press") label else "",
                                if (st.action == "type") st.text else "", st.direction, st.app,
                            ),
                        )
                    }
                    advanceSkill(st.action, label, st.app, fromSkill)
                } else {
                    if (fromSkill) activeSkill = null  // a replayed step failed: the model takes over
                    failStreak++
                    hint = "पिछला क़दम ($result)। वही तरीक़ा मत दोहराओ; दूसरा रास्ता आज़माओ।"
                    if (failStreak >= 3) {
                        return finishRun(run, "ask", "मैं अटक गया हूँ: $result। आप बताइए आगे क्या करूँ, या ख़ुद वहाँ तक पहुँच जाइए।", n)
                    }
                }
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) { dao.updateRun(run.copy(status = "stopped", report = "आपने रोक दिया।", steps = n)) }
            throw e
        } catch (e: Exception) {
            return finishRun(run, "failed", "अचानक गड़बड़ हुई: ${e.javaClass.simpleName}", n)
        }
    }

    private suspend fun execute(svc: AgentAccessibilityService, st: AgentStep, snap: Snapshot, node: UiNode?): ExecRes = when (st.action) {
        "tap" -> ExecRes(node != null && svc.click(node), "टैप नहीं हुआ")
        "long_press" -> ExecRes(node != null && svc.longClick(node), "लंबा दबाना नहीं हुआ")
        "scroll" -> ExecRes(svc.scroll(node, snap, st.direction != "up"), "स्क्रॉल नहीं हुआ")
        "swipe" -> ExecRes(svc.swipeDir(st.direction), "स्वाइप नहीं हुआ")
        "type" -> when {
            node == null -> ExecRes(false, "फ़ील्ड नहीं मिली")
            node.password -> ExecRes(false, "पासवर्ड-फ़ील्ड में मैं नहीं लिखता")
            MemoryRepo.isSensitive(st.text) -> ExecRes(false, "संवेदनशील जानकारी मैं नहीं लिखता")
            else -> ExecRes(svc.typeText(node, st.text), "लिखा नहीं जा सका")
        }
        "enter" -> ExecRes(svc.pressEnter(node), "Enter नहीं दब सका")
        "back" -> ExecRes(svc.back())
        "home" -> ExecRes(svc.home())
        "recents" -> ExecRes(svc.recents())
        "open_app" -> Tools.openApp(ctx, st.app).let { ExecRes(it.ok, it.message) }
        "wait" -> { delay(1500); ExecRes(true) }
        else -> ExecRes(false, "अनजान क़दम")
    }
}
