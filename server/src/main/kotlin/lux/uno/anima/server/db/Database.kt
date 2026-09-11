package lux.uno.anima.server.db

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

/**
 * One SQLite connection, used one caller at a time.
 *
 * A pool would be the reflex, and would be wrong here: Anima's write volume is a person
 * occasionally editing a subscription, and serialising access removes `SQLITE_BUSY`,
 * retry loops and interleaved-transaction bugs outright. If that ever becomes the
 * bottleneck it will be visible long before it is painful.
 */
class Database(file: Path) : AutoCloseable {

    private val connection: Connection

    private val lock = Mutex()

    init {
        Files.createDirectories(file.parent)
        connection = DriverManager.getConnection("jdbc:sqlite:${file.toAbsolutePath()}")
        connection.createStatement().use { statement ->
            // Pragmas and DDL run while the connection still auto-commits: `journal_mode`
            // cannot change inside a transaction, and SQLite's driver executes one statement
            // per call, so the schema is fed to it a statement at a time.
            //
            // WAL survives the process dying mid-write. NORMAL trades a theoretical loss of
            // the last commit on power failure for far fewer fsyncs, which is the right trade
            // for data every client can simply push again.
            statement.execute("PRAGMA journal_mode=WAL")
            statement.execute("PRAGMA synchronous=NORMAL")
            statement.execute("PRAGMA foreign_keys=ON")
            statement.execute("PRAGMA busy_timeout=5000")
            SCHEMA.split(";").map { it.trim() }.filter { it.isNotEmpty() }
                .forEach { statement.execute(it) }
        }
        connection.autoCommit = false
    }

    /** Runs [body] in a transaction, rolling back if it throws. */
    suspend fun <T> transaction(body: (Connection) -> T): T = withContext(Dispatchers.IO) {
        lock.withLock {
            try {
                val result = body(connection)
                connection.commit()
                result
            } catch (failure: Throwable) {
                runCatching { connection.rollback() }
                throw failure
            }
        }
    }

    override fun close() {
        runCatching { connection.close() }
    }
}

/** Reads every row of [this] through [row] and closes the result. */
fun <T> ResultSet.map(row: (ResultSet) -> T): List<T> = use {
    buildList { while (next()) add(row(this@map)) }
}

/**
 * The whole schema, written so re-running it is a no-op.
 *
 * `records` is the shape the privacy rule produces: typed columns for the things the server
 * genuinely acts on — who owns the row, how to order it against a rival version, whether it
 * is a tombstone, where it falls in the change feed — and one opaque blob for the rest. The
 * server never parses `payload`, which is what leaves room to encrypt it end-to-end later.
 * Domain relationships such as a subscription's category stay inside it, because the server
 * neither joins on them nor validates them, and hoisting them would only teach it more about
 * the user than the job requires.
 */
private val SCHEMA = """
    CREATE TABLE IF NOT EXISTS users (
        id            TEXT PRIMARY KEY,
        email         TEXT NOT NULL UNIQUE,
        password_hash TEXT NOT NULL,
        created_at    INTEGER NOT NULL
    );

    CREATE TABLE IF NOT EXISTS sessions (
        token_hash TEXT PRIMARY KEY,
        user_id    TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
        created_at INTEGER NOT NULL,
        expires_at INTEGER NOT NULL,
        last_seen  INTEGER NOT NULL
    );
    CREATE INDEX IF NOT EXISTS sessions_user ON sessions(user_id);

    CREATE TABLE IF NOT EXISTS records (
        user_id TEXT    NOT NULL REFERENCES users(id) ON DELETE CASCADE,
        kind    TEXT    NOT NULL,
        id      TEXT    NOT NULL,
        hlc     TEXT    NOT NULL,
        deleted INTEGER NOT NULL DEFAULT 0,
        seq     INTEGER NOT NULL,
        payload BLOB,
        PRIMARY KEY (user_id, kind, id)
    );
    CREATE INDEX IF NOT EXISTS records_feed ON records(user_id, seq);

    CREATE TABLE IF NOT EXISTS user_seq (
        user_id  TEXT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
        next_seq INTEGER NOT NULL
    );

    CREATE TABLE IF NOT EXISTS blobs (
        user_id    TEXT    NOT NULL REFERENCES users(id) ON DELETE CASCADE,
        hash       TEXT    NOT NULL,
        size       INTEGER NOT NULL,
        created_at INTEGER NOT NULL,
        PRIMARY KEY (user_id, hash)
    );
""".trimIndent()
