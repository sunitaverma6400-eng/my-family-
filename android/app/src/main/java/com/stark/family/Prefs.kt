package com.stark.family

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Only the relay address and shared token live here. API keys never touch the app. */
private val LANGS = setOf("hi", "en", "hinglish", "auto")

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("stark_prefs", Context.MODE_PRIVATE)

    var relayUrl: String
        get() = sp.getString("relay_url", "") ?: ""
        set(v) { sp.edit().putString("relay_url", v.trim()).apply() }

    var token: String
        get() = sp.getString("token", "") ?: ""
        set(v) { sp.edit().putString("token", v.trim()).apply() }

    /** How many screen-task steps are allowed before the agent must stop (default 30). */
    var agentMaxSteps: Int
        get() = sp.getInt("agent_max", 30).coerceIn(10, 80)
        set(v) { sp.edit().putInt("agent_max", v.coerceIn(10, 80)).apply() }

    /** Apps where the agent never runs (per-app off switch). */
    var blockedPkgs: Set<String>
        get() = HashSet(sp.getStringSet("agent_blocked", emptySet()) ?: emptySet())
        set(v) { sp.edit().putStringSet("agent_blocked", HashSet(v)).apply() }

    // ---- context engine: each sensor can be switched on/off ----
    fun sensorOn(key: String): Boolean = sp.getBoolean("sensor_$key", true)
    fun setSensor(key: String, on: Boolean) { sp.edit().putBoolean("sensor_$key", on).apply() }

    /** "home" / "outside" / "" (unknown). Updated by the geofence. */
    var place: String
        get() = sp.getString("place", "") ?: ""
        set(v) { sp.edit().putString("place", v).apply() }

    val hasHome: Boolean get() = sp.getBoolean("has_home", false)
    val homeLat: Double get() = java.lang.Double.longBitsToDouble(sp.getLong("home_lat", 0L))
    val homeLon: Double get() = java.lang.Double.longBitsToDouble(sp.getLong("home_lon", 0L))
    fun setHome(lat: Double, lon: Double) {
        sp.edit().putBoolean("has_home", true)
            .putLong("home_lat", java.lang.Double.doubleToRawLongBits(lat))
            .putLong("home_lon", java.lang.Double.doubleToRawLongBits(lon)).apply()
    }

    /** "seen,off|seen,off|..." for 24 hours: how often the screen was off at each hour (sleep-window guess). */
    var hourStats: String
        get() = sp.getString("hour_stats", "") ?: ""
        set(v) { sp.edit().putString("hour_stats", v).apply() }

    // ---- proactive behaviour + kill switch ----
    /** Kill switch: while true, no rule, suggestion, agent task or tool runs. Survives restarts. */
    var killed: Boolean
        get() = sp.getBoolean("killed", false)
        set(v) { sp.edit().putBoolean("killed", v).apply() }

    var proactiveOn: Boolean
        get() = sp.getBoolean("proactive_on", true)
        set(v) { sp.edit().putBoolean("proactive_on", v).apply() }

    /** Watch app/place habits (stored only on this phone) so the AIs can propose rules. */
    var patternWatch: Boolean
        get() = sp.getBoolean("pattern_watch", true)
        set(v) { sp.edit().putBoolean("pattern_watch", v).apply() }

    /** Quiet hours in minutes after midnight; default 00:00 to 07:00. */
    var quietStart: Int
        get() = sp.getInt("quiet_start", 0)
        set(v) { sp.edit().putInt("quiet_start", ((v % 1440) + 1440) % 1440).apply() }
    var quietEnd: Int
        get() = sp.getInt("quiet_end", 420)
        set(v) { sp.edit().putInt("quiet_end", ((v % 1440) + 1440) % 1440).apply() }

    var dailyLimit: Int
        get() = sp.getInt("daily_limit", 4).coerceIn(1, 12)
        set(v) { sp.edit().putInt("daily_limit", v.coerceIn(1, 12)).apply() }

    /** Ultron starts as an Observer (suggest only). Raising lets him act only with a "yes" each time. */
    var ultronRaised: Boolean
        get() = ultronLevel == 3
        set(v) { ultronLevel = if (v) 3 else 2 }

    /** Ultron's access: 1 = speech only, 2 = Observer (read, search, analyse: the default), 3 = Analyst + approval (plans and delegates; every change needs a yes). */
    var ultronLevel: Int
        get() {
            val v = sp.getInt("ultron_level", -1)
            return if (v in 1..3) v else if (sp.getBoolean("ultron_raised", false)) 3 else 2
        }
        set(v) { sp.edit().putInt("ultron_level", v.coerceIn(1, 3)).putBoolean("ultron_raised", v == 3).apply() }

    /** A tiny screen picture after each agent step, for the Activity log. Never in sensitive apps. */
    var stepThumbs: Boolean
        get() = sp.getBoolean("step_thumbs", true)
        set(v) { sp.edit().putBoolean("step_thumbs", v).apply() }

    /** Meaning-based memory search (vectors made by your own relay). Off = keyword search only. */
    var embeddings: Boolean
        get() = sp.getBoolean("embeddings", true)
        set(v) { sp.edit().putBoolean("embeddings", v).apply() }

    /** Optional: Ultron reviews an answer or fix plan for flaws before it is shown. */
    var ultronReview: Boolean
        get() = sp.getBoolean("ultron_review", false)
        set(v) { sp.edit().putBoolean("ultron_review", v).apply() }

    /** Quiet research on something the user said they will do. Off by default; small daily budget. */
    var proactiveResearch: Boolean
        get() = sp.getBoolean("proactive_research", false)
        set(v) { sp.edit().putBoolean("proactive_research", v).apply() }

    var researchBudget: Int
        get() = sp.getInt("research_budget", 3).coerceIn(1, 10)
        set(v) { sp.edit().putInt("research_budget", v.coerceIn(1, 10)).apply() }

    private fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    fun researchUsedToday(): Int = if (sp.getString("research_day", "") == today()) sp.getInt("research_used", 0) else 0

    fun noteResearchUsed() {
        sp.edit().putString("research_day", today()).putInt("research_used", researchUsedToday() + 1).apply()
    }

    /** Reply language: hi (Devanagari Hindi, default), en, hinglish (Roman), auto (mirror the user). A character can override it. */
    var language: String
        get() = sp.getString("lang", "hi").let { if (it in LANGS) it!! else "hi" }
        set(v) { sp.edit().putString("lang", if (v in LANGS) v else "hi").apply() }

    /** "" means: follow the global setting. */
    fun languageOverride(id: String): String = sp.getString("lang_$id", "").let { if (it in LANGS) it!! else "" }

    fun setLanguageFor(id: String, v: String) { sp.edit().putString("lang_$id", if (v in LANGS) v else "").apply() }

    fun languageFor(id: String): String = languageOverride(id).ifEmpty { language }

    /** True while the user wants the bubble running; the watchdog restarts it if the system killed it. */
    var bubbleWanted: Boolean
        get() = sp.getBoolean("bubble_wanted", false)
        set(v) { sp.edit().putBoolean("bubble_wanted", v).apply() }

    var onboarded: Boolean
        get() = sp.getBoolean("onboarded", false)
        set(v) { sp.edit().putBoolean("onboarded", v).apply() }

    fun charMuted(id: String): Boolean = sp.getBoolean("muted_$id", false)
    fun setCharMuted(id: String, v: Boolean) { sp.edit().putBoolean("muted_$id", v).apply() }

    var mutedTypes: Set<String>
        get() = HashSet(sp.getStringSet("muted_types", emptySet()) ?: emptySet())
        set(v) { sp.edit().putStringSet("muted_types", HashSet(v)).apply() }

    // ---- research / family (M10) ----
    /** JSON {character: {domain: delta}} learned from who answered well; sent with every family question. */
    var talentAdj: String
        get() = sp.getString("talent_adj", "{}") ?: "{}"
        set(v) { sp.edit().putString("talent_adj", v).apply() }

    /** Extra words (comma separated) that must never appear in a web search, e.g. your name. */
    var redactTerms: String
        get() = sp.getString("redact_terms", "") ?: ""
        set(v) { sp.edit().putString("redact_terms", v.take(300)).apply() }
}
