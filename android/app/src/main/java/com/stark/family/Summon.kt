package com.stark.family

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.widget.Toast
import androidx.core.content.ContextCompat

/** One way to bring the family on screen, used by the Quick Settings tile, the app shortcut and the assistant gesture. */
object Summon {
    fun start(ctx: Context, character: String?) {
        val app = ctx.applicationContext
        if (KillSwitch.on(app)) {
            Toast.makeText(app, "⛔ kill switch चालू है। ऐप खोलकर पहले ▶ फिर चालू करो दबाइए।", Toast.LENGTH_LONG).show()
            return
        }
        if (!Settings.canDrawOverlays(app)) {
            Toast.makeText(app, "\"दूसरे ऐप्स के ऊपर दिखाएँ\" की अनुमति दीजिए, फिर दोबारा बुलाइए।", Toast.LENGTH_LONG).show()
            try {
                app.startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + app.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            } catch (_: Exception) { }
            return
        }
        Prefs(app).bubbleWanted = true
        val i = Intent(app, OverlayService::class.java).putExtra(OverlayService.EXTRA_OPEN, true)
        if (!character.isNullOrBlank()) i.putExtra(OverlayService.EXTRA_CHARACTER, character)
        try {
            ContextCompat.startForegroundService(app, i)
        } catch (e: Exception) {
            Toast.makeText(app, "बबल शुरू नहीं हो पाया। ऐप खोलकर 🫧 दबाइए।", Toast.LENGTH_LONG).show()
        }
    }
}

/** Invisible activity: summons the family and closes at once (shortcut and assistant entry point). */
class SummonActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Summon.start(this, intent?.getStringExtra(OverlayService.EXTRA_CHARACTER))
        finish()
    }
}

/** Quick Settings tile "Stark परिवार": tap to bring the family on screen. */
class SummonTileService : TileService() {
    override fun onStartListening() {
        val t = qsTile ?: return
        t.state = if (OverlayService.running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.updateTile()
    }

    override fun onClick() {
        val i = Intent(this, SummonActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        collapseAndStart(i)
    }

    @Suppress("DEPRECATION")
    private fun collapseAndStart(i: Intent) {
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE))
        } else {
            startActivityAndCollapse(i)
        }
    }
}

/**
 * Lets the phone offer "Stark Family" as the digital assistant (power-button / corner swipe, where the maker's Android
 * allows it). When the system asks the assistant to show, we just bring the family on screen.
 */
class StarkVoiceService : VoiceInteractionService()

class StarkSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = StarkSession(this)
}

class StarkSession(ctx: Context) : VoiceInteractionSession(ctx) {
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        Summon.start(context, null)
        hide()
    }
}

/** Every 15 minutes (from the background worker): if the bubble should be running but the system killed it, bring it back. */
object Watchdog {
    private const val CH = "stark_watchdog"

    fun check(ctx: Context) {
        val app = ctx.applicationContext
        val p = Prefs(app)
        if (!p.bubbleWanted || OverlayService.running || KillSwitch.on(app) || !Settings.canDrawOverlays(app)) return
        try {
            ContextCompat.startForegroundService(app, Intent(app, OverlayService::class.java))
        } catch (e: Exception) {
            // Android 12+ may refuse to start it from the background: say so, one tap brings it back
            try {
                val nm = app.getSystemService(NotificationManager::class.java)
                nm.createNotificationChannel(NotificationChannel(CH, "Stark बबल की याद", NotificationManager.IMPORTANCE_LOW))
                val tap = PendingIntent.getActivity(app, 5, Intent(app, SummonActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
                nm.notify(
                    41_001,
                    android.app.Notification.Builder(app, CH).setSmallIcon(android.R.drawable.ic_dialog_info)
                        .setContentTitle("बबल बंद हो गया")
                        .setContentText("फ़ोन ने इसे बंद कर दिया। दबाकर फिर चालू कीजिए (और बैटरी-सेटिंग में Stark को 'Unrestricted' कर दीजिए)।")
                        .setContentIntent(tap).setAutoCancel(true).build(),
                )
            } catch (_: Exception) { }
        }
    }
}
