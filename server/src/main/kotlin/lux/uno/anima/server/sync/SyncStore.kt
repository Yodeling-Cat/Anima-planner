package lux.uno.anima.server.sync

import lux.uno.anima.Limits
import lux.uno.anima.server.db.Database
import lux.uno.anima.server.db.map
import lux.uno.anima.sync.Hlc
import lux.uno.anima.sync.SyncRecord
import lux.uno.anima.sync.SyncRequest
import lux.uno.anima.sync.RejectedRecord
import lux.uno.anima.sync.SyncResponse
import java.sql.Connection
import java.sql.ResultSet

private val SAFE_ID = Regex("[A-Za-z0-9._:-]{1,${Limits.MAX_ID_LENGTH}}")

/**
 * Structural checks only — shape, size and clock, never content. Refusing a record because
 * of what is inside it would mean having looked.
 */
private fun check(record: SyncRecord): RejectedRecord? {
    fun problem(reason: String) = RejectedRecord(record.kind, record.id, reason)
    return when {
        !SAFE_ID.matches(record.kind) -> problem("unusable kind")
        !SAFE_ID.matches(record.id) -> problem("unusable id")
        Hlc.parseOrNull(record.hlc.encoded) == null -> problem("unreadable clock reading")
        record.deleted && record.payload != null -> problem("a tombstone cannot carry a payload")
        !record.deleted && record.payload == null -> problem("a live record needs a payload")
        (record.payload?.length ?: 0) > Limits.MAX_PAYLOAD_BYTES ->
            problem("payload over ${Limits.MAX_PAYLOAD_BYTES} bytes")
        else -> null
    }
}

/**
 * The whole of what the server does with user data: take records, order them, hand them back.
 *
 * Conflicts are settled by comparing [Hlc] strings, which needs no idea of what a
 * subscription is. That is the point of the shape — the spine carries everything required to
 * pick a winner, so the payload never has to be opened.
 */
class SyncStore(private val database: Database) {

    suspend fun sync(userId: String, request: SyncRequest): SyncResponse =
        database.transaction { connection ->
            val rejected = mutableListOf<RejectedRecord>()
            val touched = mutableSetOf<Pair<String, String>>()
            var nextSeq = readNextSeq(connection, userId)

            for (incoming in request.changes) {
                val problem = check(incoming)
                if (problem != null) {
                    rejected += problem
                    continue
                }
                touched += incoming.kind to incoming.id

                val existing = readOne(connection, userId, incoming.kind, incoming.id)
                // The device that wrote last wins, exactly as it does on the client. Running
                // the same rule in both places is what keeps the two from ever disagreeing
                // about which version is current.
                if (existing != null && existing.hlc >= incoming.hlc) continue

                write(connection, userId, incoming.copy(seq = nextSeq))
                nextSeq += 1
            }
            writeNextSeq(connection, userId, nextSeq)

            val page = readFeed(connection, userId, request.since, Limits.FEED_PAGE_SIZE + 1)
            val more = page.size > Limits.FEED_PAGE_SIZE
            val changes = page.take(Limits.FEED_PAGE_SIZE).toMutableList()

            // A push that lost keeps its older seq, so the query above may not include it and
            // the device would never learn its edit was superseded. Add back anything it
            // touched that did not come through on its own.
            val returned = changes.mapTo(mutableSetOf()) { it.kind to it.id }
            for ((kind, id) in touched - returned) {
                readOne(connection, userId, kind, id)?.let { changes += it }
            }

            SyncResponse(
                // Never below what the device already had: the records added back above can
                // be older than its cursor, and moving it backwards would make the next sync
                // replay the feed from there.
                cursor = maxOf(request.since, changes.maxOfOrNull { it.seq } ?: 0L),
                changes = changes,
                more = more,
                rejected = rejected,
            )
        }

    private fun readNextSeq(connection: Connection, userId: String): Long =
        connection.prepareStatement("SELECT next_seq FROM user_seq WHERE user_id = ?").use {
            it.setString(1, userId)
            it.executeQuery().map { rows -> rows.getLong(1) }
        }.firstOrNull() ?: 1L

    private fun writeNextSeq(connection: Connection, userId: String, next: Long) {
        connection.prepareStatement(
            "INSERT INTO user_seq (user_id, next_seq) VALUES (?, ?) " +
                "ON CONFLICT(user_id) DO UPDATE SET next_seq = excluded.next_seq",
        ).use {
            it.setString(1, userId)
            it.setLong(2, next)
            it.executeUpdate()
        }
    }

    private fun readOne(
        connection: Connection,
        userId: String,
        kind: String,
        id: String,
    ): SyncRecord? = connection.prepareStatement(
        "SELECT kind, id, hlc, deleted, seq, payload FROM records " +
            "WHERE user_id = ? AND kind = ? AND id = ?",
    ).use {
        it.setString(1, userId)
        it.setString(2, kind)
        it.setString(3, id)
        it.executeQuery().map(::toRecord)
    }.firstOrNull()

    private fun readFeed(
        connection: Connection,
        userId: String,
        since: Long,
        limit: Int,
    ): List<SyncRecord> = connection.prepareStatement(
        "SELECT kind, id, hlc, deleted, seq, payload FROM records " +
            "WHERE user_id = ? AND seq > ? ORDER BY seq LIMIT ?",
    ).use {
        it.setString(1, userId)
        it.setLong(2, since)
        it.setInt(3, limit)
        it.executeQuery().map(::toRecord)
    }

    private fun write(connection: Connection, userId: String, record: SyncRecord) {
        connection.prepareStatement(
            "INSERT INTO records (user_id, kind, id, hlc, deleted, seq, payload) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?) " +
                "ON CONFLICT(user_id, kind, id) DO UPDATE SET " +
                "hlc = excluded.hlc, deleted = excluded.deleted, " +
                "seq = excluded.seq, payload = excluded.payload",
        ).use {
            it.setString(1, userId)
            it.setString(2, record.kind)
            it.setString(3, record.id)
            it.setString(4, record.hlc.encoded)
            it.setInt(5, if (record.deleted) 1 else 0)
            it.setLong(6, record.seq)
            // Held as bytes and never decoded here. A BLOB column means the day this arrives
            // encrypted, nothing in this file has to change.
            it.setBytes(7, record.payload?.toByteArray())
            it.executeUpdate()
        }
    }

    private fun toRecord(rows: ResultSet) = SyncRecord(
        kind = rows.getString(1),
        id = rows.getString(2),
        // A row we cannot read the clock of has to lose every future merge rather than win
        // them all, which is what Zero arranges.
        hlc = Hlc.parseOrNull(rows.getString(3)) ?: Hlc.Zero,
        deleted = rows.getInt(4) == 1,
        seq = rows.getLong(5),
        payload = rows.getBytes(6)?.decodeToString(),
    )
}
