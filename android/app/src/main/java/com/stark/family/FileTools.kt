package com.stark.family

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Find, tidy and remove files in the user's own folders (Download, Documents, Pictures, DCIM, Movies, Music) once the user
 * has given "all files access". Nothing outside those folders is ever touched. Nothing is deleted outright: "delete" moves
 * a file into a trash folder for 30 days, and "organise" keeps a journal so it can be undone.
 */
object FileTools {
    private val CATS = linkedMapOf(
        "PDF" to setOf("pdf"),
        "Images" to setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "bmp"),
        "Videos" to setOf("mp4", "mkv", "mov", "avi", "3gp", "webm"),
        "Audio" to setOf("mp3", "m4a", "wav", "ogg", "aac", "flac", "opus"),
        "Documents" to setOf("doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "csv", "odt", "rtf"),
        "Archives" to setOf("zip", "rar", "7z", "tar", "gz"),
        "APKs" to setOf("apk", "xapk", "apks"),
    )
    private val PARTIAL = setOf("crdownload", "part", "tmp", "download", "partial")
    private const val TRASH_MS = 30L * 86_400_000L
    const val NO_ACCESS = "फ़ाइलों की पहुँच की अनुमति नहीं है। ⚙ → 'अनुमतियाँ और फ़ोन की गाइड' में 'फ़ाइलें' चालू कीजिए।"

    fun hasAccess(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    private fun base(): File = Environment.getExternalStorageDirectory()
    private fun downloads(): File = File(base(), "Download")
    private fun roots(): List<File> = listOf("Download", "Documents", "Pictures", "DCIM", "Movies", "Music").map { File(base(), it) }.filter { it.isDirectory }
    private fun trashDir(): File = File(downloads(), ".StarkTrash")

    private fun inRoots(f: File): Boolean {
        val c = f.canonicalPath
        return roots().any { c.startsWith(it.canonicalPath + File.separator) }
    }

    private fun size(b: Long): String = if (b >= 1_048_576L) String.format(Locale.US, "%.1f MB", b / 1_048_576.0) else (b / 1024).toString() + " KB"
    private fun day(ms: Long): String = SimpleDateFormat("d MMM yyyy", Locale.US).format(Date(ms))
    private fun shortPath(f: File): String = f.path.removePrefix(base().path + File.separator)

    // ---------------- find ----------------

    class Hit(val file: File, val size: Long, val modified: Long)

    fun find(query: String, limit: Int = 10): List<Hit> {
        val tokens = query.lowercase().split(' ', '_', '-', '.').filter { it.length >= 2 }
        if (tokens.isEmpty()) return emptyList()
        val deadline = System.currentTimeMillis() + 5_000L
        var seen = 0
        val hits = ArrayList<Hit>()
        for (r in roots()) {
            r.walkTopDown().onEnter { d -> !d.name.startsWith(".") && d.name != "Android" }.forEach { f ->
                if (++seen > 30_000 || System.currentTimeMillis() > deadline) return hits.sortedByDescending { it.modified }.take(limit)
                if (f.isFile) {
                    val n = f.name.lowercase()
                    if (tokens.all { it in n }) hits.add(Hit(f, f.length(), f.lastModified()))
                }
            }
        }
        return hits.sortedByDescending { it.modified }.take(limit)
    }

    fun findText(ctx: Context, name: String): Pair<Boolean, String> {
        if (!hasAccess(ctx)) return false to NO_ACCESS
        val hits = find(name)
        if (hits.isEmpty()) return false to "'$name' नाम की कोई फ़ाइल नहीं मिली (Download, Documents, Pictures, DCIM, Movies, Music में देखा)।"
        return true to hits.joinToString("\n") { "• ${it.file.name} (${size(it.size)}, ${day(it.modified)}): ${shortPath(it.file)}" }
    }

    // ---------------- organise Downloads (with undo) ----------------

    private fun categoryOf(f: File): String? {
        val ext = f.extension.lowercase()
        if (ext in PARTIAL) return null
        return CATS.entries.firstOrNull { ext in it.value }?.key
    }

    private fun planOrganise(): List<Pair<File, File>> {
        val dl = downloads()
        val now = System.currentTimeMillis()
        val files = dl.listFiles() ?: return emptyList()
        return files.filter { it.isFile && !it.name.startsWith(".") && now - it.lastModified() > 120_000L }
            .mapNotNull { f -> categoryOf(f)?.let { cat -> f to File(File(dl, cat), f.name) } }
    }

    fun previewOrganise(): String {
        val m = planOrganise()
        if (m.isEmpty()) return "Downloads में सजाने लायक़ कुछ नहीं दिख रहा"
        val counts = m.groupingBy { it.second.parentFile?.name ?: "" }.eachCount()
        return "Downloads की ${m.size} फ़ाइलें फ़ोल्डरों में रखना: " + counts.entries.joinToString(", ") { "${it.key} ${it.value}" } + " (कुछ मिटेगा नहीं; पलट सकते हैं)"
    }

    fun organise(ctx: Context): Pair<Boolean, String> {
        if (!hasAccess(ctx)) return false to NO_ACCESS
        val moves = planOrganise()
        if (moves.isEmpty()) return true to "Downloads में सजाने लायक़ नई फ़ाइल नहीं मिली।"
        val done = JSONArray()
        var n = 0
        for ((from, wanted) in moves) {
            try {
                wanted.parentFile?.mkdirs()
                val to = unique(wanted)
                if (moveFile(from, to)) {
                    done.put(JSONObject().put("from", from.path).put("to", to.path))
                    n++
                }
            } catch (_: Exception) { }
        }
        journal(ctx, "organise_undo.json").writeText(done.toString())
        return true to "$n फ़ाइलें सजा दीं। 'वापस करो' कहिए तो सब पहले जैसी हो जाएँगी।"
    }

    fun undoOrganise(ctx: Context): Pair<Boolean, String> {
        if (!hasAccess(ctx)) return false to NO_ACCESS
        val jf = journal(ctx, "organise_undo.json")
        if (!jf.exists()) return false to "पलटने के लिए कोई पिछली 'सजाना' कार्रवाई नहीं है।"
        val arr = try { JSONArray(jf.readText()) } catch (_: Exception) { JSONArray() }
        var n = 0
        var skipped = 0
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val from = File(o.optString("from"))
            val to = File(o.optString("to"))
            if (!to.exists() || !inRoots(to)) { skipped++; continue }
            if (moveFile(to, unique(from))) n++ else skipped++
        }
        CATS.keys.forEach { cat ->
            val d = File(downloads(), cat)
            if (d.isDirectory && d.list()?.isEmpty() == true) d.delete()
        }
        jf.delete()
        return true to "$n फ़ाइलें अपनी पुरानी जगह लौटा दीं" + if (skipped > 0) " ($skipped नहीं मिलीं: शायद आपने ख़ुद हटा या हिला दीं)।" else "।"
    }

    // ---------------- delete = move to a trash folder; restore ----------------

    private fun resolveOne(name: String): Pair<File?, List<File>> {
        val direct = File(name)
        if (direct.isAbsolute && direct.isFile && inRoots(direct)) return direct to emptyList()
        val hits = find(name, 8).map { it.file }
        val exact = hits.filter { it.name.equals(name, ignoreCase = true) }
        return when {
            exact.size == 1 -> exact[0] to emptyList()
            hits.size == 1 -> hits[0] to emptyList()
            else -> null to hits
        }
    }

    fun previewDelete(name: String): String {
        val (f, cands) = resolveOne(name)
        return when {
            f != null -> "फ़ाइल हटाना (कचरे में 30 दिन रहेगी): ${f.name} (${size(f.length())}), ${shortPath(f)}"
            cands.isEmpty() -> "'$name' नाम की कोई फ़ाइल नहीं मिली"
            else -> "'$name' से ${cands.size} फ़ाइलें मेल खाती हैं; पूरा नाम बताना होगा"
        }
    }

    fun delete(ctx: Context, name: String): Pair<Boolean, String> {
        if (!hasAccess(ctx)) return false to NO_ACCESS
        val (f, cands) = resolveOne(name)
        if (f == null) {
            return if (cands.isEmpty()) false to "'$name' नाम की कोई फ़ाइल नहीं मिली।"
            else false to "कई फ़ाइलें मिलीं, कुछ हटाया नहीं। पूरा नाम बताइए:\n" + cands.take(5).joinToString("\n") { "• " + shortPath(it) }
        }
        if (!inRoots(f)) return false to "यह फ़ाइल मेरे दायरे (Download, Documents, Pictures…) से बाहर है; नहीं छुई।"
        trashDir().mkdirs()
        val dest = unique(File(trashDir(), System.currentTimeMillis().toString() + "_" + f.name))
        val orig = f.path
        if (!moveFile(f, dest)) return false to "फ़ाइल हटा नहीं पाया।"
        val arr = readJournal(ctx, "trash.json")
        arr.put(JSONObject().put("trash", dest.path).put("orig", orig).put("ts", System.currentTimeMillis()))
        journal(ctx, "trash.json").writeText(arr.toString())
        return true to "'${f.name}' कचरे में रख दी (30 दिन तक)। 'वापस लाओ' कहिए तो लौट आएगी।"
    }

    fun restore(ctx: Context, name: String): Pair<Boolean, String> {
        if (!hasAccess(ctx)) return false to NO_ACCESS
        val arr = readJournal(ctx, "trash.json")
        val q = name.trim().lowercase()
        var best = -1
        var bestTs = -1L
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (File(o.optString("orig")).name.lowercase().contains(q) && o.optLong("ts") > bestTs) { best = i; bestTs = o.optLong("ts") }
        }
        if (best < 0) return false to "कचरे में '$name' नाम की कोई फ़ाइल नहीं मिली।"
        val o = arr.getJSONObject(best)
        val trashed = File(o.optString("trash"))
        val orig = File(o.optString("orig"))
        arr.remove(best)
        journal(ctx, "trash.json").writeText(arr.toString())
        if (!trashed.exists()) return false to "वह फ़ाइल कचरे से जा चुकी है (30 दिन पूरे हो गए होंगे)।"
        orig.parentFile?.mkdirs()
        val back = unique(orig)
        return if (moveFile(trashed, back)) true to "'${back.name}' अपनी जगह (${shortPath(back.parentFile ?: back)}) लौटा दी।"
        else false to "फ़ाइल वापस नहीं रख पाया।"
    }

    /** Called by the 15-minute worker: trash older than 30 days is really deleted. */
    fun purgeTrash(ctx: Context) {
        try {
            val arr = readJournal(ctx, "trash.json")
            if (arr.length() == 0) return
            val keep = JSONArray()
            val now = System.currentTimeMillis()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (now - o.optLong("ts") > TRASH_MS) File(o.optString("trash")).delete() else keep.put(o)
            }
            if (keep.length() != arr.length()) journal(ctx, "trash.json").writeText(keep.toString())
        } catch (_: Exception) { }
    }

    // ---------------- helpers ----------------

    private fun journal(ctx: Context, name: String) = File(ctx.filesDir, name)

    private fun readJournal(ctx: Context, name: String): JSONArray = try {
        val f = journal(ctx, name)
        if (f.exists()) JSONArray(f.readText()) else JSONArray()
    } catch (_: Exception) {
        JSONArray()
    }

    private fun unique(f: File): File {
        if (!f.exists()) return f
        val stem = f.nameWithoutExtension
        val ext = if (f.extension.isEmpty()) "" else "." + f.extension
        var i = 1
        while (true) {
            val c = File(f.parentFile, "$stem ($i)$ext")
            if (!c.exists()) return c
            i++
        }
    }

    private fun moveFile(from: File, to: File): Boolean {
        if (from.renameTo(to)) return true
        return try {
            from.copyTo(to, overwrite = false)
            from.delete()
            true
        } catch (_: Exception) {
            false
        }
    }
}
