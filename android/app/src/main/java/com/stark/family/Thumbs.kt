package com.stark.family

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File
import java.io.FileOutputStream

/**
 * A small picture of the screen after each step the agent took, shown in the Activity log (PDF §8: "what was tapped,
 * why, thumbnail"). Kept as tiny JPEG files in the app's private folder (not in the database, so existing data is untouched).
 * Never taken in banking/password apps or while a sensitive app is unlocked, and never sent anywhere. Deleted with the log entry.
 */
object Thumbs {
    private const val WIDTH = 180
    private const val KEEP_MS = 14L * 86_400_000L

    private fun dir(ctx: Context): File = File(ctx.filesDir, "thumbs").apply { mkdirs() }

    fun file(ctx: Context, runId: Long, n: Int): File = File(dir(ctx), "${runId}_$n.jpg")

    /** Captures the screen (overlay hidden for a moment) and stores a small copy. Silent if Android refuses the screenshot. */
    suspend fun capture(ctx: Context, svc: AgentAccessibilityService, ui: AgentUi, runId: Long, n: Int) {
        ui.hideForCapture(true)
        val bmp = try { svc.screenshot() } finally { ui.hideForCapture(false) }
        if (bmp != null) save(ctx, runId, n, bmp)
    }

    fun save(ctx: Context, runId: Long, n: Int, bmp: Bitmap) {
        try {
            val h = (bmp.height * WIDTH.toFloat() / bmp.width).toInt().coerceAtLeast(1)
            val small = Bitmap.createScaledBitmap(bmp, WIDTH, h, true)
            FileOutputStream(file(ctx, runId, n)).use { small.compress(Bitmap.CompressFormat.JPEG, 60, it) }
            if (small !== bmp) small.recycle()
            bmp.recycle()
        } catch (_: Exception) { }
    }

    fun load(ctx: Context, runId: Long, n: Int): ImageBitmap? {
        val f = file(ctx, runId, n)
        if (!f.exists()) return null
        return try { BitmapFactory.decodeFile(f.path)?.asImageBitmap() } catch (_: Exception) { null }
    }

    fun delete(ctx: Context, runId: Long) {
        dir(ctx).listFiles()?.filter { it.name.startsWith("${runId}_") }?.forEach { it.delete() }
    }

    fun deleteAll(ctx: Context) {
        dir(ctx).listFiles()?.forEach { it.delete() }
    }

    fun purgeOld(ctx: Context) {
        val now = System.currentTimeMillis()
        dir(ctx).listFiles()?.filter { now - it.lastModified() > KEEP_MS }?.forEach { it.delete() }
    }
}
