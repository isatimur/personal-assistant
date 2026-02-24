package com.assistant.memory

import com.assistant.domain.*
import com.assistant.ports.EmbeddingPort
import com.assistant.ports.MemoryPort
import com.assistant.ports.MemoryStats
import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.PrintWriter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.sql.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.logging.Logger
import javax.sql.DataSource
import kotlin.math.*

/**
 * A DataSource that always returns the same underlying connection and prevents it from
 * being closed. This is required for SQLite in-memory databases, which are scoped to a
 * single JDBC connection — closing the connection drops all in-memory data.
 */
private class SingleConnectionDataSource(url: String) : DataSource {
    private val realConnection: Connection = DriverManager.getConnection(url)

    /** Proxy connection that ignores close() so Exposed cannot destroy the in-memory DB. */
    private val proxy: Connection = object : Connection by realConnection {
        override fun close() { /* intentionally empty */ }
        override fun isClosed(): Boolean = realConnection.isClosed
    }

    override fun getConnection(): Connection = proxy
    override fun getConnection(u: String?, p: String?): Connection = proxy
    override fun getLogWriter(): PrintWriter? = null
    override fun setLogWriter(out: PrintWriter?) {}
    override fun setLoginTimeout(seconds: Int) {}
    override fun getLoginTimeout(): Int = 0
    override fun getParentLogger(): Logger = Logger.getLogger(Logger.GLOBAL_LOGGER_NAME)
    override fun <T : Any?> unwrap(iface: Class<T>?): T = iface!!.cast(this)
    override fun isWrapperFor(iface: Class<*>?): Boolean = iface?.isInstance(this) ?: false
}

class SqliteMemoryStore(
    dbPath: String,
    private val embeddingPort: EmbeddingPort? = null,
    private val memoryDir: File = File(System.getProperty("user.home"), ".assistant/memory")
) : MemoryPort {

    private val db: Database = if (dbPath == ":memory:") {
        Database.connect(SingleConnectionDataSource("jdbc:sqlite::memory:"))
    } else {
        Database.connect("jdbc:sqlite:$dbPath", "org.sqlite.JDBC")
    }

    private val memoryFile = File(memoryDir, "MEMORY.md")
    private val fileMutex = Mutex()

    object Messages : LongIdTable("messages") {
        val sessionId = varchar("session_id", 128)
        val userId    = varchar("user_id", 128)
        val text      = text("text")
        val channel   = varchar("channel", 32)
        val createdAt = long("created_at")
    }

    object Chunks : LongIdTable("chunks") {
        val sessionId = varchar("session_id", 128)
        val userId    = varchar("user_id", 128)
        val text      = text("text")
        val embedding = binary("embedding").nullable()
        val createdAt = long("created_at")
    }

    fun init() {
        transaction(db) {
            SchemaUtils.create(Messages, Chunks)
            execInBatch(listOf(
                """CREATE VIRTUAL TABLE IF NOT EXISTS chunks_fts USING fts5(text, content='chunks', content_rowid='id')""",
                """CREATE TRIGGER IF NOT EXISTS chunks_ai AFTER INSERT ON chunks BEGIN INSERT INTO chunks_fts(rowid, text) VALUES (new.id, new.text); END""",
                """CREATE TRIGGER IF NOT EXISTS chunks_ad AFTER DELETE ON chunks BEGIN INSERT INTO chunks_fts(chunks_fts, rowid, text) VALUES('delete', old.id, old.text); END""",
                """CREATE TRIGGER IF NOT EXISTS chunks_au AFTER UPDATE ON chunks BEGIN INSERT INTO chunks_fts(chunks_fts, rowid, text) VALUES('delete', old.id, old.text); INSERT INTO chunks_fts(rowid, text) VALUES (new.id, new.text); END"""
            ))
        }
        memoryDir.mkdirs()
    }

    override suspend fun append(sessionId: String, message: Message) {
        val now = System.currentTimeMillis()
        val chunks = chunk(message.text)
        // Pre-collect embeddings (suspend calls) before entering the transaction
        val chunkEmbeddings: List<ByteArray?> = chunks.map { chunkText ->
            embeddingPort?.embed(chunkText)?.toBytes()
        }
        withContext(Dispatchers.IO) {
            transaction(db) {
                Messages.insert {
                    it[Messages.sessionId] = sessionId
                    it[userId] = message.sender
                    it[text] = message.text
                    it[channel] = message.channel.name
                    it[createdAt] = now
                }
                chunks.forEachIndexed { i, chunkText ->
                    Chunks.insert {
                        it[Chunks.sessionId] = sessionId
                        it[Chunks.userId] = message.sender
                        it[Chunks.text] = chunkText
                        it[Chunks.embedding] = chunkEmbeddings[i]
                        it[Chunks.createdAt] = now
                    }
                }
            }
        }

        if (memoryDir.exists()) {
            val date = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
            fileMutex.withLock {
                withContext(Dispatchers.IO) {
                    File(memoryDir, "$date.md").appendText("${message.sender}: ${message.text}\n")
                }
            }
        }
    }

    override suspend fun history(sessionId: String, limit: Int): List<Message> =
        withContext(Dispatchers.IO) {
            transaction(db) {
                Messages.selectAll()
                    .where { Messages.sessionId eq sessionId }
                    .orderBy(Messages.createdAt, SortOrder.DESC)
                    .limit(limit)
                    .map { row ->
                        Message(
                            sender = row[Messages.userId],
                            text = row[Messages.text],
                            channel = Channel.valueOf(row[Messages.channel])
                        )
                    }
                    .reversed()
            }
        }

    override suspend fun facts(userId: String): List<String> =
        withContext(Dispatchers.IO) {
            if (!memoryFile.exists()) return@withContext emptyList()
            memoryFile.readLines().filter { it.isNotBlank() }.map { it.trimStart('-', ' ') }
        }

    override suspend fun saveFact(userId: String, fact: String) {
        fileMutex.withLock {
            withContext(Dispatchers.IO) {
                memoryDir.mkdirs()
                memoryFile.appendText("- $fact\n")
            }
        }
    }

    override suspend fun deleteFact(userId: String, fact: String) {
        fileMutex.withLock {
            withContext(Dispatchers.IO) {
                if (!memoryFile.exists()) return@withContext
                val lines = memoryFile.readLines()
                val updated = lines.filter { line -> line.trimStart('-', ' ') != fact }
                memoryFile.writeText(updated.joinToString("\n") + if (updated.isNotEmpty()) "\n" else "")
            }
        }
    }

    override suspend fun clearHistory(sessionId: String) {
        withContext(Dispatchers.IO) {
            transaction(db) {
                Messages.deleteWhere { with(it) { Messages.sessionId eq sessionId } }
                Chunks.deleteWhere { with(it) { Chunks.sessionId eq sessionId } }
            }
        }
    }

    override suspend fun trimHistory(sessionId: String, deleteCount: Int) {
        if (deleteCount <= 0) return
        withContext(Dispatchers.IO) {
            transaction(db) {
                val oldest = Messages
                    .selectAll()
                    .where { Messages.sessionId eq sessionId }
                    .orderBy(Messages.createdAt, SortOrder.ASC)
                    .limit(deleteCount)
                    .toList()
                if (oldest.isEmpty()) return@transaction
                val maxCreatedAt = oldest.maxOf { it[Messages.createdAt] }
                Messages.deleteWhere { with(it) { Messages.id inList oldest.map { row -> row[Messages.id].value } } }
                Chunks.deleteWhere { with(it) {
                    (Chunks.sessionId eq sessionId) and (Chunks.createdAt lessEq maxCreatedAt)
                } }
            }
        }
    }

    override suspend fun stats(userId: String): MemoryStats {
        val factsCount = facts(userId).size
        return withContext(Dispatchers.IO) {
            transaction(db) {
                val messageCount = Messages.selectAll()
                    .where { Messages.userId eq userId }
                    .count().toInt()
                val chunkCount = Chunks.selectAll()
                    .where { Chunks.userId eq userId }
                    .count().toInt()
                MemoryStats(factsCount = factsCount, chunkCount = chunkCount, messageCount = messageCount)
            }
        }
    }

    override suspend fun search(userId: String, query: String, limit: Int): List<String> {
        if (query.isBlank()) return emptyList()

        data class Candidate(
            val text: String,
            val bm25: Double,
            val embeddingBytes: ByteArray?,
            val createdAt: Long
        )

        val sanitized = sanitizeFtsQuery(query)
        val candidates = mutableListOf<Candidate>()

        withContext(Dispatchers.IO) {
            transaction(db) {
                try {
                    val sql = """
                        SELECT c.text, bm25(chunks_fts) AS bm25_score, c.embedding, c.created_at
                        FROM chunks_fts
                        JOIN chunks c ON c.id = chunks_fts.rowid
                        WHERE chunks_fts MATCH ? AND c.user_id = ?
                        ORDER BY bm25(chunks_fts)
                        LIMIT 20
                    """.trimIndent()
                    exec(sql, listOf(TextColumnType() to sanitized, VarCharColumnType(128) to userId), null) { rs ->
                        while (rs.next()) {
                            candidates.add(Candidate(
                                text = rs.getString("text"),
                                bm25 = rs.getDouble("bm25_score"),
                                embeddingBytes = rs.getBytes("embedding"),
                                createdAt = rs.getLong("created_at")
                            ))
                        }
                    }
                } catch (_: Exception) {
                    // FTS5 query syntax error or no data — return empty
                }
            }
        }

        if (candidates.isEmpty()) return emptyList()

        val minBm25 = candidates.minOf { it.bm25 }  // most negative = best match
        val maxBm25 = candidates.maxOf { it.bm25 }
        val bm25Range = maxBm25 - minBm25

        val queryEmbedding = embeddingPort?.embed(query)

        return candidates.map { cand ->
            val bm25Normalized = if (bm25Range == 0.0) 1.0f
                                 else ((maxBm25 - cand.bm25) / bm25Range).toFloat()
            val vectorScore = if (queryEmbedding != null && cand.embeddingBytes != null)
                cosine(queryEmbedding, cand.embeddingBytes.toFloatArray())
            else 0.0f
            val hybrid = if (queryEmbedding != null)
                0.3f * vectorScore + 0.7f * bm25Normalized
            else bm25Normalized
            val score = temporalDecay(hybrid, cand.createdAt)
            cand.text to score
        }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
    }
}

internal fun sanitizeFtsQuery(query: String): String =
    query.split(Regex("\\s+"))
        .filter { it.isNotBlank() }
        .joinToString(" ") { "\"${it.replace("\"", "")}\"" }

internal fun chunk(text: String, maxLen: Int = 512, overlap: Int = 80): List<String> {
    if (text.length <= maxLen) return listOf(text)
    val chunks = mutableListOf<String>()
    var start = 0
    while (start < text.length) {
        val end = minOf(start + maxLen, text.length)
        chunks.add(text.substring(start, end))
        if (end == text.length) break
        start += maxLen - overlap
    }
    return chunks
}

internal fun cosine(a: FloatArray, b: FloatArray): Float {
    if (a.size != b.size) return 0f
    var dot = 0f; var normA = 0f; var normB = 0f
    for (i in a.indices) {
        dot += a[i] * b[i]
        normA += a[i] * a[i]
        normB += b[i] * b[i]
    }
    val denom = sqrt(normA) * sqrt(normB)
    return if (denom == 0f) 0f else dot / denom
}

internal fun temporalDecay(score: Float, createdAtMs: Long): Float {
    val ageDays = (System.currentTimeMillis() - createdAtMs) / (1000.0 * 60 * 60 * 24)
    return (score * exp(-ln(2.0) * ageDays / 30.0)).toFloat()
}

internal fun FloatArray.toBytes(): ByteArray {
    val buf = ByteBuffer.allocate(size * 4).order(ByteOrder.LITTLE_ENDIAN)
    forEach { buf.putFloat(it) }
    return buf.array()
}

internal fun ByteArray.toFloatArray(): FloatArray {
    val buf = ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN)
    return FloatArray(size / 4) { buf.getFloat() }
}
