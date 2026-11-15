package com.stark.family

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.temporal.IsoFields
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Stable things about the user (kind = fact) and repeated behaviour (kind = habit). */
@Entity(tableName = "facts")
data class Fact(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    val kind: String = "fact",
    val importance: Int = 3,
    val source: String = "told",
    val character: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

/** Raw time-stamped talk. */
@Entity(tableName = "episodes")
data class Episode(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ts: Long,
    val day: String,
    val character: String,
    val user: String,
    val reply: String,
    val tools: String = "",
)

/** kind = daily | weekly | profile; period = 2026-11-14 | 2026-W46 | profile. */
@Entity(tableName = "summaries", indices = [Index(value = ["kind", "period"], unique = true)])
data class Summary(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String,
    val period: String,
    val text: String,
    val ts: Long,
)

/** A character's own private perspective note about the user. */
@Entity(tableName = "notes")
data class Note(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val character: String,
    val text: String,
    val ts: Long,
)

@Dao
interface MemoryDao {
    @Query("SELECT * FROM facts ORDER BY importance DESC, updatedAt DESC") suspend fun facts(): List<Fact>
    @Insert suspend fun insertFact(f: Fact): Long
    @Update suspend fun updateFact(f: Fact)
    @Delete suspend fun deleteFact(f: Fact)

    @Insert suspend fun insertEpisode(e: Episode): Long
    @Query("SELECT * FROM episodes ORDER BY ts DESC LIMIT :n") suspend fun recentEpisodes(n: Int): List<Episode>
    @Query("SELECT * FROM episodes WHERE ts >= :since ORDER BY ts DESC") suspend fun episodesSince(since: Long): List<Episode>
    @Query("SELECT * FROM episodes WHERE day = :day ORDER BY ts") suspend fun episodesOfDay(day: String): List<Episode>
    @Query("SELECT DISTINCT day FROM episodes ORDER BY day DESC") suspend fun episodeDays(): List<String>
    @Query("SELECT * FROM episodes ORDER BY ts") suspend fun allEpisodes(): List<Episode>
    @Query("DELETE FROM episodes WHERE id = :id") suspend fun deleteEpisode(id: Long)
    @Query("DELETE FROM episodes WHERE day = :day") suspend fun deleteEpisodesOfDay(day: String)

    @Query("SELECT * FROM summaries WHERE kind = :kind ORDER BY period DESC") suspend fun summaries(kind: String): List<Summary>
    @Query("SELECT * FROM summaries WHERE kind = :kind AND period = :period LIMIT 1") suspend fun summary(kind: String, period: String): Summary?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putSummary(s: Summary)
    @Query("DELETE FROM summaries WHERE id = :id") suspend fun deleteSummary(id: Long)
    @Query("DELETE FROM summaries WHERE kind = 'daily' AND period = :day") suspend fun deleteDailySummary(day: String)

    @Query("SELECT * FROM notes ORDER BY ts DESC") suspend fun notes(): List<Note>
    @Insert suspend fun insertNote(n: Note): Long
    @Update suspend fun updateNote(n: Note)
    @Query("DELETE FROM notes WHERE id = :id") suspend fun deleteNote(id: Long)
}

@Database(entities = [Fact::class, Episode::class, Summary::class, Note::class], version = 1, exportSchema = false)
abstract class MemoryDb : RoomDatabase() {
    abstract fun dao(): MemoryDao

    companion object {
        @Volatile private var inst: MemoryDb? = null

        fun get(ctx: Context): MemoryDb = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(ctx.applicationContext, MemoryDb::class.java, "memory.db")
                .addCallback(object : RoomDatabase.Callback() {
                    // Deleted rows are overwritten with zeros, so "delete" really deletes.
                    override fun onOpen(db: SupportSQLiteDatabase) {
                        try { db.query("PRAGMA secure_delete = ON").close() } catch (_: Exception) { }
                    }
                })
                .build().also { inst = it }
        }
    }
}

/**
 * The single door to memory: store, retrieve (keyword + importance + recency), summarise on a daily/weekly/profile
 * ladder through the relay, edit, delete, back up. Everything lives on the phone.
 */
class MemoryRepo private constructor(app: Context) {
    private val db = MemoryDb.get(app)
    val dao: MemoryDao = db.dao()
    private val client = RelayClient()
    private val lock = Mutex()
    private var lastMaintain = 0L

    companion object {
        @Volatile private var inst: MemoryRepo? = null
        fun get(ctx: Context): MemoryRepo = inst ?: synchronized(this) {
            inst ?: MemoryRepo(ctx.applicationContext).also { inst = it }
        }

        private val SENSITIVE = Regex(
            "(?:\\d[ -]?){13,19}|password|passcode|\\botp\\b|\\bcvv\\b|पासवर्ड|ओटीपी|पासकोड",
            RegexOption.IGNORE_CASE,
        )
        private val TOKEN = Regex("[\\p{L}\\p{M}\\p{N}]+")
        private val PUNCT = Regex("[\\s\\p{P}\\p{S}]+")
        private val STOP = setOf(
            "है", "हैं", "का", "की", "के", "में", "को", "से", "और", "मैं", "मेरा", "मेरी", "मेरे", "तो", "भी", "पर",
            "कर", "क्या", "ये", "वो", "यह", "था", "थी", "हो", "ho", "hai", "hain", "ka", "ki", "ke", "ko", "se", "me",
            "mein", "the", "is", "to", "of", "and", "my", "you", "me",
        )

        fun isSensitive(t: String) = SENSITIVE.containsMatchIn(t)
        fun tokens(s: String): Set<String> =
            TOKEN.findAll(s.lowercase()).map { it.value }.filter { it.length >= 2 && it !in STOP }.toSet()
        fun dayKey(ts: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(ts))
        fun weekKey(day: String): String {
            val d = LocalDate.parse(day)
            return String.format(Locale.US, "%04d-W%02d", d.get(IsoFields.WEEK_BASED_YEAR), d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR))
        }
        private fun norm(s: String) = s.lowercase().replace(PUNCT, "")
    }

    // ---------- writing ----------

    /** Returns false (and stores nothing) for sensitive or too-short text. */
    suspend fun addFact(
        text: String, importance: Int = 3, source: String = "told", character: String = "", kind: String = "fact",
    ): Boolean = withContext(Dispatchers.IO) {
        val t = text.trim().take(200)
        val n = norm(t)
        if (t.length < 2 || n.isEmpty() || isSensitive(t)) return@withContext false
        val imp = importance.coerceIn(1, 5)
        val now = System.currentTimeMillis()
        val same = dao.facts().firstOrNull {
            val e = norm(it.text)
            e.isNotEmpty() && (e == n || e.contains(n) || n.contains(e))
        }
        if (same != null) {
            dao.updateFact(same.copy(importance = maxOf(same.importance, imp), updatedAt = now))
        } else {
            dao.insertFact(Fact(text = t, kind = kind, importance = imp, source = source, character = character, createdAt = now, updatedAt = now))
        }
        true
    }

    suspend fun addEpisode(character: String, user: String, reply: String, tools: String = "") = withContext(Dispatchers.IO) {
        if (reply.isBlank()) return@withContext
        val redact = isSensitive(user) || isSensitive(reply)
        val now = System.currentTimeMillis()
        dao.insertEpisode(
            Episode(
                ts = now, day = dayKey(now), character = character,
                user = if (redact) "[संवेदनशील जानकारी हटाई गई]" else user.take(500),
                reply = if (redact) "" else reply.take(800),
                tools = tools,
            ),
        )
        Unit
    }

    // ---------- retrieval ----------

    /** Short, relevant lines for the next LLM call: profile, matching facts, recent summaries, own notes, old talk. */
    suspend fun relevant(userText: String, character: String): List<String> = withContext(Dispatchers.IO) {
        val out = mutableListOf<String>()
        val q = tokens(userText)
        val nowMs = System.currentTimeMillis()
        fun overlap(t: String): Int {
            val low = t.lowercase()
            return q.count { low.contains(it) }
        }
        dao.summary("profile", "profile")?.let { out.add("प्रोफ़ाइल: " + it.text.take(700)) }
        val facts = dao.facts()
        val matched = facts.map { f ->
            val ageDays = (nowMs - f.updatedAt) / 86_400_000.0
            f to (overlap(f.text) * 3.0 + f.importance * 0.8 + 2.0 * Math.exp(-ageDays / 30.0))
        }.filter { overlap(it.first.text) > 0 }.sortedByDescending { it.second }.take(5).map { it.first }
        val important = facts.filter { it.importance >= 4 && it !in matched }.take(3)
        (matched + important).forEach { out.add((if (it.kind == "habit") "आदत: " else "तथ्य: ") + it.text) }
        dao.summaries("daily").take(2).forEach { out.add("सार (${it.period}): " + it.text.take(300)) }
        dao.notes().filter { it.character == character }.take(3).forEach { out.add("तुम्हारी अपनी टिप्पणी: " + it.text) }
        if (q.isNotEmpty()) {
            val today = dayKey(nowMs)
            dao.episodesSince(nowMs - 45L * 86_400_000L).filter { it.day != today }
                .map { it to overlap(it.user + " " + it.reply) }
                .filter { it.second > 0 }.sortedByDescending { it.second }.take(2)
                .forEach { (e, _) ->
                    out.add("पुरानी बातचीत (${e.day}): उपयोगकर्ता: ${e.user.take(100)} | ${Characters.byId(e.character).name}: ${e.reply.take(100)}")
                }
        }
        var total = 0
        out.takeWhile { total += it.length; total <= 1700 }
    }

    // ---------- summaries ladder: raw -> daily -> weekly -> profile ----------

    suspend fun maintain(base: String, token: String) = withContext(Dispatchers.IO) {
        if (base.isBlank() || token.isBlank()) return@withContext
        val nowMs = System.currentTimeMillis()
        if (nowMs - lastMaintain < 30 * 60_000L) return@withContext
        if (!lock.tryLock()) return@withContext
        try {
            lastMaintain = nowMs
            val today = dayKey(nowMs)
            var calls = 0

            for (day in dao.episodeDays()) {
                if (calls >= 3) break
                if (day >= today || dao.summary("daily", day) != null) continue
                val eps = dao.episodesOfDay(day).filter { it.reply.isNotBlank() }
                if (eps.sumOf { it.user.length } < 15) continue
                val items = JSONArray()
                eps.take(40).forEach {
                    items.put(JSONObject().put("character", it.character).put("user", it.user).put("reply", it.reply))
                }
                val r = try { client.summarize(base, token, "daily", items) } catch (e: Exception) { null } ?: break
                calls++
                dao.putSummary(Summary(kind = "daily", period = day, text = r.getString("summary"), ts = nowMs))
                r.optJSONArray("facts")?.let { a ->
                    for (i in 0 until a.length()) {
                        val o = a.optJSONObject(i) ?: continue
                        addFact(o.optString("text"), o.optInt("importance", 3), "auto")
                    }
                }
                r.optJSONArray("habits")?.let { a ->
                    for (i in 0 until a.length()) addFact(a.optString(i), 3, "auto", kind = "habit")
                }
                r.optJSONArray("notes")?.let { a ->
                    for (i in 0 until a.length()) {
                        val o = a.optJSONObject(i) ?: continue
                        val t = o.optString("text")
                        if (t.isNotBlank() && !isSensitive(t)) dao.insertNote(Note(character = o.optString("character"), text = t, ts = nowMs))
                    }
                }
            }

            val newWeekly = mutableListOf<Summary>()
            val thisWeek = weekKey(today)
            for ((wk, list) in dao.summaries("daily").groupBy { weekKey(it.period) }) {
                if (calls >= 3) break
                if (wk == thisWeek || list.size < 2 || dao.summary("weekly", wk) != null) continue
                val items = JSONArray()
                list.sortedBy { it.period }.forEach { items.put(JSONObject().put("period", it.period).put("text", it.text)) }
                val r = try { client.summarize(base, token, "weekly", items) } catch (e: Exception) { null } ?: break
                calls++
                val s = Summary(kind = "weekly", period = wk, text = r.getString("summary"), ts = nowMs)
                dao.putSummary(s)
                newWeekly.add(s)
            }

            if (newWeekly.isNotEmpty()) {
                val items = JSONArray()
                newWeekly.forEach { items.put(JSONObject().put("period", it.period).put("text", it.text)) }
                dao.facts().filter { it.importance >= 4 }.take(8).forEach {
                    items.put(JSONObject().put("period", "तथ्य").put("text", it.text))
                }
                val prev = dao.summary("profile", "profile")?.text
                val r = try { client.summarize(base, token, "profile", items, prev) } catch (e: Exception) { null }
                if (r != null) dao.putSummary(Summary(kind = "profile", period = "profile", text = r.getString("summary"), ts = nowMs))
            }
            Unit
        } finally {
            lock.unlock()
        }
    }

    // ---------- deleting (really) ----------

    suspend fun deleteDay(day: String) = withContext(Dispatchers.IO) {
        dao.deleteEpisodesOfDay(day)
        dao.deleteDailySummary(day)
        Unit
    }

    suspend fun wipeAll() = withContext(Dispatchers.IO) {
        db.clearAllTables()
        try { db.openHelper.writableDatabase.execSQL("VACUUM") } catch (_: Exception) { }
        Unit
    }

    // ---------- backup / restore (zip with one JSON file) ----------

    private suspend fun toJson(): JSONObject {
        val facts = JSONArray()
        dao.facts().forEach {
            facts.put(JSONObject().put("text", it.text).put("kind", it.kind).put("importance", it.importance)
                .put("source", it.source).put("character", it.character).put("createdAt", it.createdAt).put("updatedAt", it.updatedAt))
        }
        val eps = JSONArray()
        dao.allEpisodes().forEach {
            eps.put(JSONObject().put("ts", it.ts).put("day", it.day).put("character", it.character)
                .put("user", it.user).put("reply", it.reply).put("tools", it.tools))
        }
        val sums = JSONArray()
        for (k in listOf("profile", "weekly", "daily")) dao.summaries(k).forEach {
            sums.put(JSONObject().put("kind", it.kind).put("period", it.period).put("text", it.text).put("ts", it.ts))
        }
        val notes = JSONArray()
        dao.notes().forEach { notes.put(JSONObject().put("character", it.character).put("text", it.text).put("ts", it.ts)) }
        return JSONObject().put("app", "stark-family-memory").put("version", 1)
            .put("facts", facts).put("episodes", eps).put("summaries", sums).put("notes", notes)
    }

    suspend fun backupZip(out: OutputStream) = withContext(Dispatchers.IO) {
        val json = toJson().toString()
        ZipOutputStream(out).use { z ->
            z.putNextEntry(ZipEntry("memory.json"))
            z.write(json.toByteArray(Charsets.UTF_8))
            z.closeEntry()
        }
    }

    /** Replaces everything with the backup. Returns the number of entries restored, or -1 if the file is not ours. */
    suspend fun restoreZip(input: InputStream): Int = withContext(Dispatchers.IO) {
        var text: String? = null
        ZipInputStream(input).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (e.name == "memory.json") { text = z.readBytes().toString(Charsets.UTF_8); break }
            }
        }
        val j = try { JSONObject(text ?: return@withContext -1) } catch (e: Exception) { return@withContext -1 }
        if (j.optString("app") != "stark-family-memory") return@withContext -1
        wipeAll()
        var n = 0
        j.optJSONArray("facts")?.let { a ->
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                if (isSensitive(o.getString("text"))) continue
                dao.insertFact(Fact(text = o.getString("text"), kind = o.optString("kind", "fact"), importance = o.optInt("importance", 3),
                    source = o.optString("source", "told"), character = o.optString("character"),
                    createdAt = o.optLong("createdAt"), updatedAt = o.optLong("updatedAt")))
                n++
            }
        }
        j.optJSONArray("episodes")?.let { a ->
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                dao.insertEpisode(Episode(ts = o.getLong("ts"), day = o.getString("day"), character = o.optString("character"),
                    user = o.optString("user"), reply = o.optString("reply"), tools = o.optString("tools")))
                n++
            }
        }
        j.optJSONArray("summaries")?.let { a ->
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                dao.putSummary(Summary(kind = o.getString("kind"), period = o.getString("period"), text = o.getString("text"), ts = o.optLong("ts")))
                n++
            }
        }
        j.optJSONArray("notes")?.let { a ->
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                dao.insertNote(Note(character = o.optString("character"), text = o.getString("text"), ts = o.optLong("ts")))
                n++
            }
        }
        n
    }
}
