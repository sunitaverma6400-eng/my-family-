package com.stark.family

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityRecognitionResult
import com.google.android.gms.location.DetectedActivity

/**
 * "Still / walking / in a vehicle" for the context engine, from Google's low-power Activity Recognition.
 * Needs the ACTIVITY_RECOGNITION permission; without it (or with the sensor switched off) nothing is read.
 * Only the last label and its time are kept, nothing else.
 */
object ActivityTracker {
    private const val SP = "stark_activity"
    private const val FRESH_MS = 15 * 60_000L

    fun hasPermission(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 29 ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

    private fun pending(ctx: Context): PendingIntent {
        val mutable = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getBroadcast(ctx, 71, Intent(ctx, ActivityReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or mutable)
    }

    /** Safe to call often: re-registering the same PendingIntent just replaces the old request. */
    fun start(ctx: Context) {
        val app = ctx.applicationContext
        if (KillSwitch.on(app) || !Prefs(app).sensorOn("activity") || !hasPermission(app)) return
        try {
            ActivityRecognition.getClient(app).requestActivityUpdates(120_000L, pending(app))
        } catch (_: SecurityException) {
        } catch (_: Exception) {
        }
    }

    fun stop(ctx: Context) {
        val app = ctx.applicationContext
        try {
            ActivityRecognition.getClient(app).removeActivityUpdates(pending(app))
        } catch (_: Exception) {
        }
    }

    fun save(ctx: Context, type: Int) {
        val label = when (type) {
            DetectedActivity.STILL -> "still"
            DetectedActivity.WALKING, DetectedActivity.ON_FOOT -> "walking"
            DetectedActivity.RUNNING -> "running"
            DetectedActivity.ON_BICYCLE -> "cycling"
            DetectedActivity.IN_VEHICLE -> "vehicle"
            else -> "unknown"
        }
        ctx.getSharedPreferences(SP, Context.MODE_PRIVATE).edit().putString("type", label).putLong("ts", System.currentTimeMillis()).apply()
    }

    /** The last label if it is recent enough to still be true, otherwise null (unknown). */
    fun current(ctx: Context): String? {
        val sp = ctx.getSharedPreferences(SP, Context.MODE_PRIVATE)
        val t = sp.getString("type", null) ?: return null
        if (System.currentTimeMillis() - sp.getLong("ts", 0L) > FRESH_MS) return null
        return if (t == "unknown") null else t
    }
}

class ActivityReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (!ActivityRecognitionResult.hasResult(intent)) return
        val r = ActivityRecognitionResult.extractResult(intent) ?: return
        ActivityTracker.save(ctx, r.mostProbableActivity.type)
    }
}
