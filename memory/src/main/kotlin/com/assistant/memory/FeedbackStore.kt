package com.assistant.memory

import com.assistant.ports.FeedbackPort
import com.assistant.ports.FeedbackStats
import com.assistant.ports.Signal
import com.assistant.ports.SignalType
import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class FeedbackStore(dbPath: String) : FeedbackPort {

    private val db: Database = if (dbPath == ":memory:") {
        Database.connect(SingleConnectionDataSource("jdbc:sqlite::memory:"))
    } else {
        Database.connect("jdbc:sqlite:$dbPath", "org.sqlite.JDBC")
    }

    object Signals : LongIdTable("signals") {
        val sessionId = varchar("session_id", 128)
        val userId    = varchar("user_id", 128)
        val type      = varchar("type", 32)
        val context   = text("context")
        val createdAt = long("created_at")
    }

    object ReflectedSessions : Table("reflected_sessions") {
        val sessionId   = varchar("session_id", 128)
        val reflectedAt = long("reflected_at")
        override val primaryKey = PrimaryKey(sessionId)
    }

    fun init() {
        transaction(db) {
            SchemaUtils.createMissingTablesAndColumns(Signals, ReflectedSessions)
            exec("CREATE INDEX IF NOT EXISTS idx_signals_user_time ON signals(user_id, created_at)")
        }
    }

    override suspend fun recordSignal(signal: Signal) = withContext(Dispatchers.IO) {
        transaction(db) {
            Signals.insert {
                it[sessionId] = signal.sessionId
                it[userId]    = signal.userId
                it[type]      = signal.type.name
                it[context]   = signal.context
                it[createdAt] = signal.createdAt
            }
        }
        Unit
    }

    override suspend fun signalsFor(userId: String, sinceMs: Long): List<Signal> =
        withContext(Dispatchers.IO) {
            transaction(db) {
                Signals.selectAll()
                    .where { (Signals.userId eq userId) and (Signals.createdAt greaterEq sinceMs) }
                    .map { row ->
                        Signal(
                            id        = row[Signals.id].value,
                            sessionId = row[Signals.sessionId],
                            userId    = row[Signals.userId],
                            type      = SignalType.valueOf(row[Signals.type]),
                            context   = row[Signals.context],
                            createdAt = row[Signals.createdAt]
                        )
                    }
            }
        }

    override suspend fun markReflected(sessionIds: List<String>) {
        if (sessionIds.isEmpty()) return
        val now = System.currentTimeMillis()
        withContext(Dispatchers.IO) {
            transaction(db) {
                sessionIds.forEach { id ->
                    exec(
                        "INSERT OR IGNORE INTO reflected_sessions (session_id, reflected_at) VALUES (?, ?)",
                        listOf(VarCharColumnType(128) to id, LongColumnType() to now)
                    )
                }
            }
        }
    }

    override suspend fun unreflectedSessions(userId: String, sinceMs: Long): List<String> =
        withContext(Dispatchers.IO) {
            transaction(db) {
                val reflected = ReflectedSessions.selectAll()
                    .map { it[ReflectedSessions.sessionId] }
                    .toSet()
                Signals.selectAll()
                    .where { (Signals.userId eq userId) and (Signals.createdAt greaterEq sinceMs) }
                    .map { it[Signals.sessionId] }
                    .distinct()
                    .filter { it !in reflected }
            }
        }

    override suspend fun stats(userId: String, sinceMs: Long): FeedbackStats =
        withContext(Dispatchers.IO) {
            transaction(db) {
                val rows = Signals.selectAll()
                    .where { (Signals.userId eq userId) and (Signals.createdAt greaterEq sinceMs) }
                    .toList()
                FeedbackStats(
                    totalSessions = rows.map { it[Signals.sessionId] }.distinct().size,
                    corrections   = rows.count { it[Signals.type] == SignalType.CORRECTION.name },
                    approvals     = rows.count { it[Signals.type] == SignalType.APPROVAL.name },
                    toolErrors    = rows.count { it[Signals.type] == SignalType.TOOL_ERROR.name },
                    highSteps     = rows.count { it[Signals.type] == SignalType.HIGH_STEPS.name }
                )
            }
        }
}
