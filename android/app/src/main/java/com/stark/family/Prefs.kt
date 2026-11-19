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
        get() = sp.getBoolean("ultron_raised", false)
        set(v) { sp.edit().putBoolean("ultron_raised", v).apply() }

    fun charMuted(id: String): Boolean = sp.getBoolean("muted_$id", false)
    fun setCharMuted(id: String, v: Boolean) { sp.edit().putBoolean("muted_$id", v).apply() }

    var mutedTypes: Set<String>
        get() = HashSet(sp.getStringSet("muted_types", emptySet()) ?: emptySet())
        set(v) { sp.edit().putStringSet("muted_types", HashSet(v)).apply() }
}
