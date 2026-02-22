package com.assistant.memory

import com.assistant.domain.*
import com.assistant.ports.MemoryPort
import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.PrintWriter
import java.sql.*
import java.util.logging.Logger
import javax.sql.DataSource

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

class SqliteMemoryStore(dbPath: String) : MemoryPort {
    private val db: Database = if (dbPath == ":memory:") {
        Database.connect(SingleConnectionDataSource("jdbc:sqlite::memory:"))
    } else {
        Database.connect("jdbc:sqlite:$dbPath", "org.sqlite.JDBC")
    }

    object Messages : LongIdTable("messages") {
        val sessionId = varchar("session_id", 128)
        val userId = varchar("user_id", 128)
        val text = text("text")
        val channel = varchar("channel", 32)
        val createdAt = long("created_at")
    }

    object Facts : LongIdTable("facts") {
        val userId = varchar("user_id", 128)
        val fact = text("fact")
    }

    fun init() {
        transaction(db) { SchemaUtils.create(Messages, Facts) }
    }

    override suspend fun append(sessionId: String, message: Message) {
        transaction(db) {
            Messages.insert {
                it[Messages.sessionId] = sessionId
                it[userId] = message.sender
                it[text] = message.text
                it[channel] = message.channel.name
                it[createdAt] = System.currentTimeMillis()
            }
        }
    }

    override suspend fun history(sessionId: String, limit: Int): List<Message> =
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

    override suspend fun facts(userId: String): List<String> =
        transaction(db) {
            Facts.selectAll()
                .where { Facts.userId eq userId }
                .map { it[Facts.fact] }
        }

    override suspend fun saveFact(userId: String, fact: String) {
        transaction(db) {
            Facts.insert {
                it[Facts.userId] = userId
                it[Facts.fact] = fact
            }
        }
    }
}
