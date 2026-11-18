package com.stark.family

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class Character(val id: String, val name: String, val accent: Color, val accent2: Color)

object Characters {
    val JARVIS = Character("jarvis", "Jarvis", Color(0xFF2BB8FF), Color(0xFF8FE3FF))
    val FRIDAY = Character("friday", "Friday", Color(0xFFFF9A3C), Color(0xFFFFD7A8))
    val EDITH = Character("edith", "Edith", Color(0xFF7FD0FF), Color(0xFFFF5D5D))
    val VISION = Character("vision", "Vision", Color(0xFFD7344F), Color(0xFFF2C14E))
    val ULTRON = Character("ultron", "Ultron", Color(0xFFC0392B), Color(0xFF9AA3AD))
    val DUME = Character("dume", "Dum-E", Color(0xFFFFC21A), Color(0xFFFF8A00))
    val U = Character("u", "U", Color(0xFFFFB347), Color(0xFFFFE08A))
    val KAREN = Character("karen", "Karen", Color(0xFFE8505B), Color(0xFFFFFFFF))
    val all = listOf(JARVIS, FRIDAY, EDITH, VISION, ULTRON, DUME, U, KAREN)
    fun byId(id: String): Character = all.firstOrNull { it.id == id } ?: JARVIS
}

enum class Kind { USER, AI, NOTE }

data class Msg(val kind: Kind, val text: String, val meta: String = "")

class ChatViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = Prefs(app)
    private val client = RelayClient()
    private val histories = mutableMapOf<String, SnapshotStateList<Msg>>()
    private val repo = MemoryRepo.get(app)

    var character by mutableStateOf(Characters.JARVIS)
    var relayUrl by mutableStateOf(prefs.relayUrl)
    var token by mutableStateOf(prefs.token)
    var busy by mutableStateOf(false)
    var waking by mutableStateOf(false)
    var showSettings by mutableStateOf(prefs.relayUrl.isBlank() || prefs.token.isBlank())
    var brainText by mutableStateOf<String?>(null)
    var showMemory by mutableStateOf(false)
    var showActivity by mutableStateOf(false)
    var showSkills by mutableStateOf(false)
    var showRules by mutableStateOf(false)

    /** A taught rule waiting for the user's yes/no (nothing is saved before that). */
    var ruleDraft by mutableStateOf<RuleDraft?>(null)

    fun saveRuleDraft() {
        val d = ruleDraft ?: return
        ruleDraft = null
        viewModelScope.launch { RuleRepo.get(getApplication<Application>()).save(d) }
    }

    fun dropRuleDraft() { ruleDraft = null }

    private suspend fun proposeRule(teaching: String, who: String, list: SnapshotStateList<Msg>) {
        val (draft, msg) = RuleTeacher.propose(getApplication<Application>(), teaching, who)
        if (draft == null) list.add(Msg(Kind.NOTE, "✗ $msg")) else ruleDraft = draft
    }

    init {
        viewModelScope.launch { repo.maintain(relayUrl, token) }
    }

    fun messages(): SnapshotStateList<Msg> = histories.getOrPut(character.id) { mutableStateListOf() }

    fun select(id: String) { character = Characters.byId(id) }

    fun saveSettings(url: String, tok: String) {
        prefs.relayUrl = url
        prefs.token = tok
        relayUrl = prefs.relayUrl
        token = prefs.token
        showSettings = false
    }

    fun openBrain() {
        if (relayUrl.isBlank() || token.isBlank()) { showSettings = true; return }
        brainText = "लोड हो रहा है…"
        viewModelScope.launch {
            brainText = try {
                formatBrain(client.brainStatus(relayUrl, token) { waking = it })
            } catch (e: Exception) {
                e.message ?: "त्रुटि"
            } finally {
                waking = false
            }
        }
    }

    fun send(text: String) {
        val t = text.trim()
        if (t.isEmpty() || busy) return
        if (relayUrl.isBlank() || token.isBlank()) { showSettings = true; return }
        val ch = character
        val list = histories.getOrPut(ch.id) { mutableStateListOf() }
        val history = list.filter { it.kind != Kind.NOTE }.takeLast(12)
            .map { (if (it.kind == Kind.USER) "user" else "assistant") to it.text }
        list.add(Msg(Kind.USER, t))
        busy = true
        viewModelScope.launch {
            try {
                var tries = 0
                val mem = repo.relevant(t, ch.id)
                while (true) {
                    val r = client.chat(
                        relayUrl, token, ch.id, t, history,
                        tools = Tools.MEMORY_NAMES, context = Tools.context(getApplication<Application>()), memory = mem,
                    ) { waking = it }
                    if (!r.degraded) {
                        var meta = "${r.provider} · ${r.model}"
                        if (!r.languageOk) meta += " · ⚠ जवाब पूरी तरह हिंदी में नहीं"
                        list.add(Msg(Kind.AI, r.speech, meta))
                        repo.addEpisode(ch.id, t, r.speech, r.toolCalls.joinToString(",") { it.name })
                        for (c in r.toolCalls.filter { it.name in Tools.MEMORY_NAMES }.take(3)) {
                            if (c.name == "propose_rule") {
                                proposeRule(c.args.optString("teaching"), ch.id, list)
                                continue
                            }
                            val res = Tools.plan(getApplication<Application>(), c, ch.id).run()
                            list.add(Msg(Kind.NOTE, (if (res.ok) "✓ " else "✗ ") + res.message))
                        }
                        viewModelScope.launch { repo.maintain(relayUrl, token) }
                        break
                    }
                    tries++
                    if (tries == 1) list.add(Msg(Kind.NOTE, r.speech))
                    if (tries >= 3) {
                        list.add(Msg(Kind.NOTE, "अभी जवाब नहीं मिल पाया। थोड़ी देर बाद फिर भेजिए।"))
                        break
                    }
                    delay(maxOf(r.retryAfterS, 8) * 1000L)
                }
            } catch (e: RelayClient.RelayException) {
                list.add(Msg(Kind.NOTE, e.message ?: "त्रुटि"))
            } catch (e: Exception) {
                list.add(Msg(Kind.NOTE, "अनपेक्षित त्रुटि: ${e.javaClass.simpleName}"))
            } finally {
                busy = false
                waking = false
            }
        }
    }
}
