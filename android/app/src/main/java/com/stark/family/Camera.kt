package com.stark.family

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.os.Bundle
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * One photo from the system camera app, for Edith's "read this / what is this". The picture lives in the app's private
 * cache only until it has been sent to the user's own relay for reading, then it is deleted. Nothing is kept.
 */
object CameraBridge {
    @Volatile private var pending: CompletableDeferred<File?>? = null

    /** Opens the camera, waits (up to 2 minutes) for the shot. null = cancelled, failed or timed out. */
    suspend fun capture(ctx: Context, timeoutMs: Long = 120_000L): File? {
        val d = CompletableDeferred<File?>()
        pending = d
        try {
            ctx.startActivity(Intent(ctx, CameraActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            pending = null
            return null
        }
        return try {
            withTimeoutOrNull(timeoutMs) { d.await() }
        } finally {
            if (pending === d) pending = null
        }
    }

    fun deliver(f: File?) {
        pending?.complete(f)
    }

    /** Scales the photo down (max 1280 px, rotated upright) and returns base64 JPEG small enough for the relay, or null. */
    fun encode(file: File, maxSide: Int = 1280): String? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSide * 2) sample *= 2
        val raw = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val degrees = try {
            when (ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } catch (_: Exception) {
            0f
        }
        val scale = minOf(1f, maxSide.toFloat() / maxOf(raw.width, raw.height))
        val m = Matrix().apply { postRotate(degrees); postScale(scale, scale) }
        val bmp = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
        for (q in intArrayOf(80, 65, 50)) {
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, q, out)
            val b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
            if (b64.length <= 1_700_000) return b64
        }
        return null
    }
}

/** Invisible activity that asks the system camera for one picture and hands it to [CameraBridge]. */
class CameraActivity : ComponentActivity() {
    private lateinit var out: File
    private val launcher = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        CameraBridge.deliver(if (ok && out.exists() && out.length() > 0) out else null)
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dir = File(cacheDir, "camera").apply { mkdirs() }
        dir.listFiles()?.forEach { if (System.currentTimeMillis() - it.lastModified() > 3_600_000L) it.delete() }
        out = File(dir, "shot_" + System.currentTimeMillis() + ".jpg")
        try {
            launcher.launch(FileProvider.getUriForFile(this, "$packageName.files", out))
        } catch (e: Exception) {
            CameraBridge.deliver(null)
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) CameraBridge.deliver(null) // no effect if a picture was already delivered
    }
}
