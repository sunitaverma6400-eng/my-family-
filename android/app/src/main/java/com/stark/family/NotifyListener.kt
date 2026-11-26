package com.stark.family

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** One notification as the family sees it: already redacted (OTP, card-like numbers hidden), kept in RAM only. */
data class NoteItem(val key: String, val pkg: String, val app: String, val title: String, val text: String, val time: Long)

/**
 * Reads notifications only after the user switched this on in Android settings ("Notification access").
 * Nothing is written to disk: a small in-memory list that disappears when the app process ends.
 */
class NotifyListener : NotificationListenerService() {
    companion object {
        @Volatile var instance: NotifyListener? = null
        private val ring = ArrayList<NoteItem>()
        private const val MAX = 40

        fun enabled(ctx: Context): Boolean {
            val flat = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: return false
            return flat.split(':').any { ComponentName.unflattenFromString(it)?.packageName == ctx.packageName }
        }

        /** Newest first. */
        fun recent(limit: Int): List<NoteItem> = synchronized(ring) { ring.takeLast(limit).reversed() }

        /** How many notifications arrived in the last [minutes] minutes ("notification activity" for the context engine). */
        fun activity(minutes: Int): Int {
            val since = System.currentTimeMillis() - minutes * 60_000L
            return synchronized(ring) { ring.count { it.time >= since } }
        }

        private fun add(n: NoteItem) {
            synchronized(ring) {
                ring.removeAll { it.key == n.key }
                ring.add(n)
                while (ring.size > MAX) ring.removeAt(0)
            }
        }

        /** Clears every notification of apps whose name contains [query]. Returns how many were cleared. */
        fun dismiss(query: String): Int {
            val svc = instance ?: return 0
            val q = query.trim().lowercase()
            if (q.isEmpty()) return 0
            var n = 0
            try {
                for (sbn in svc.activeNotifications) {
                    if (sbn.isOngoing) continue
                    val label = appLabel(svc, sbn.packageName).lowercase()
                    if (label.contains(q) || sbn.packageName.lowercase().contains(q)) {
                        svc.cancelNotification(sbn.key)
                        n++
                    }
                }
            } catch (_: Exception) { }
            return n
        }

        /** Sends [text] through the "reply" box of the newest matching notification (WhatsApp, SMS, Telegram...). null = sent, else the reason it was not. */
        fun reply(ctx: Context, query: String, text: String): String? {
            val svc = instance ?: return "नोटिफ़िकेशन-अनुमति चालू नहीं है।"
            val q = query.trim().lowercase()
            val list = try { svc.activeNotifications.toList() } catch (_: Exception) { emptyList() }
            val cands = list.filter { !it.isOngoing && (appLabel(svc, it.packageName).lowercase().contains(q) || it.packageName.lowercase().contains(q)) }
                .sortedByDescending { it.postTime }
            for (sbn in cands) {
                val actions = sbn.notification.actions ?: continue
                for (a in actions) {
                    val ri = a.remoteInputs ?: continue
                    if (ri.isEmpty()) continue
                    val intent = Intent()
                    val bundle = Bundle()
                    for (r in ri) bundle.putCharSequence(r.resultKey, text)
                    RemoteInput.addResultsToIntent(ri, intent, bundle)
                    return try {
                        a.actionIntent.send(ctx, 0, intent)
                        null
                    } catch (e: PendingIntent.CanceledException) {
                        "यह सूचना अब जवाब नहीं ले रही।"
                    }
                }
            }
            return "'$query' की ऐसी कोई सूचना नहीं मिली जिसका सीधा जवाब दिया जा सके (सूचना हट चुकी होगी या उसमें जवाब का डिब्बा नहीं है)।"
        }

        private fun appLabel(ctx: Context, pkg: String): String = try {
            val pm = ctx.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Exception) {
            pkg
        }
    }

    override fun onListenerConnected() {
        instance = this
    }

    override fun onListenerDisconnected() {
        instance = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null || sbn.packageName == packageName || sbn.isOngoing) return
        val n = sbn.notification ?: return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val extras = n.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return
        add(
            NoteItem(
                key = sbn.key, pkg = sbn.packageName, app = appLabel(this, sbn.packageName),
                title = AgentAccessibilityService.redact(title).take(80),
                text = AgentAccessibilityService.redact(text).take(160),
                time = sbn.postTime,
            ),
        )
    }
}
