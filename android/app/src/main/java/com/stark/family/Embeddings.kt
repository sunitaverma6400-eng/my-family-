package com.stark.family

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** One meaning-vector (embedding) of one memory item. id = "fact:12", "episode:40", "note:3". Stored in its own small database. */
@Entity(tableName = "vecs")
data class Vec(@PrimaryKey val id: String, val hash: Int, val dims: Int, val data: ByteArray, val ts: Long)

data class KeyHash(val id: String, val hash: Int)

@Dao
interface VecDao {
    @Query("SELECT * FROM vecs") suspend fun all(): List<Vec>
    @Query("SELECT id, hash FROM vecs") suspend fun hashes(): List<KeyHash>
    @Query("SELECT COUNT(*) FROM vecs") suspend fun count(): Int
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(v: Vec)
    @Query("DELETE FROM vecs WHERE id = :id") suspend fun delete(id: String)
    @Query("DELETE FROM vecs") suspend fun wipe()
}

@Database(entities = [Vec::class], version = 1, exportSchema = false)
abstract class EmbedDb : RoomDatabase() {
    abstract fun dao(): VecDao

    companion object {
        @Volatile private var inst: EmbedDb? = null

        fun get(ctx: Context): EmbedDb = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(ctx.applicationContext, EmbedDb::class.java, "embed.db").build().also { inst = it }
        }
    }
}

/**
 * Meaning-based memory search (PDF §11: "keyword + embedding"). The words of memory items are turned into vectors by the
 * user's own relay (Gemini embeddings) and compared with the question by cosine similarity, so "exam kab hai" can find
 * "परीक्षा 9 तारीख़ को". It only ADDS to the existing keyword/recency/importance scoring: if the relay or the key is not
 * available, everything works exactly as before. Sensitive items are never sent. Deleting a memory deletes its vector.
 */
object Embeddings {
    private val mutex = Mutex()
    @Volatile private var lastIndex = 0L
    @Volatile private var failUntil = 0L
    private val qcache = object : LinkedHashMap<String, FloatArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, FloatArray>?): Boolean = size > 20
    }

    fun pack(v: FloatArray): ByteArray {
        val b = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (x in v) b.putFloat(x)
        return b.array()
    }

    fun unpack(b: ByteArray): FloatArray {
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(b.size / 4) { bb.getFloat() }
    }

    fun normalize(v: FloatArray): FloatArray {
        var s = 0.0
        for (x in v) s += x * x
        val n = Math.sqrt(s).toFloat()
        return if (n < 1e-9f) v else FloatArray(v.size) { v[it] / n }
    }

    /** Both vectors are normalised, so the dot product is the cosine similarity (-1..1). */
    fun dot(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        var s = 0f
        for (i in a.indices) s += a[i] * b[i]
        return s
    }

    /** The question as a vector, or null (not indexed yet, switched off, relay busy). One quick attempt; a failure pauses it for 5 minutes. */
    suspend fun queryVector(ctx: Context, text: String): FloatArray? {
        val key = text.trim().take(300)
        if (key.length < 3) return null
        synchronized(qcache) { qcache[key] }?.let { return it }
        val p = Prefs(ctx)
        if (!p.embeddings || p.relayUrl.isBlank() || p.token.isBlank()) return null
        if (System.currentTimeMillis() < failUntil) return null
        if (EmbedDb.get(ctx).dao().count() == 0) return null
        val v = RelayClient().embedQuick(p.relayUrl, p.token, listOf(key), 1)?.firstOrNull()
        if (v == null) {
            failUntil = System.currentTimeMillis() + 5 * 60_000L
            return null
        }
        val n = normalize(v)
        synchronized(qcache) { qcache[key] = n }
        return n
    }

    suspend fun loadAll(ctx: Context): Map<String, FloatArray> =
        EmbedDb.get(ctx).dao().all().associate { it.id to unpack(it.data) }

    suspend fun wipe(ctx: Context) {
        EmbedDb.get(ctx).dao().wipe()
        synchronized(qcache) { qcache.clear() }
    }

    /**
     * Background: vectors for new or changed memory items (at most 40 per run, every 20 minutes at most, lowest priority at
     * the relay), and removal of vectors whose memory is gone or has become sensitive.
     */
    suspend fun indexNew(ctx: Context, base: String, token: String) = withContext(Dispatchers.IO) {
        val p = Prefs(ctx)
        if (!p.embeddings || base.isBlank() || token.isBlank() || KillSwitch.on(ctx)) return@withContext
        val now = System.currentTimeMillis()
        if (now < failUntil || now - lastIndex < 20 * 60_000L) return@withContext
        if (!mutex.tryLock()) return@withContext
        try {
            lastIndex = now
            val mem = MemoryRepo.get(ctx).dao
            val items = LinkedHashMap<String, String>()
            mem.facts().forEach { items["fact:${it.id}"] = it.text }
            mem.notes().forEach { items["note:${it.id}"] = it.text }
            mem.episodesSince(now - 45L * 86_400_000L).forEach { items["episode:${it.id}"] = it.user + " " + it.reply }
            val dao = EmbedDb.get(ctx).dao()
            val have = dao.hashes().associate { it.id to it.hash }
            val sensitive = items.filter { MemoryRepo.isSensitive(it.value) }.keys
            have.keys.filter { it !in items || it in sensitive }.forEach { dao.delete(it) }
            val todo = items.filter { (k, t) -> k !in sensitive && have[k] != t.hashCode() }.entries.take(40)
            for (chunk in todo.chunked(20)) {
                val vecs = RelayClient().embedQuick(base, token, chunk.map { it.value.take(600) }, 4)
                if (vecs == null || vecs.size != chunk.size) {
                    failUntil = System.currentTimeMillis() + 10 * 60_000L
                    break
                }
                chunk.forEachIndexed { i, e ->
                    val n = normalize(vecs[i])
                    dao.put(Vec(e.key, e.value.hashCode(), n.size, pack(n), now))
                }
            }
        } finally {
            mutex.unlock()
        }
        Unit
    }
}
