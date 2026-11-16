package com.stark.family

import android.content.Context

/** Only the relay address and shared token live here. API keys never touch the app. */
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
}
