package lux.uno.anima.server.auth

import lux.uno.anima.server.db.Database
import lux.uno.anima.server.db.map
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.Connection
import java.util.Base64
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.ExperimentalTime

/** Who a request turned out to be. The only identity the rest of the server ever sees. */
data class Principal(val userId: String)

data class IssuedSession(val token: String, val expiresAtMillis: Long)

sealed interface RegisterResult {
    data class Success(val session: IssuedSession) : RegisterResult
    data object EmailTaken : RegisterResult
    data class Invalid(val reason: String) : RegisterResult
}

/**
 * Accounts and sessions.
 *
 * The user row holds an email and a password hash and nothing else — no name, no locale, no
 * record of where anyone signed in from. Anything more would be data the server has no job
 * that needs it for.
 */
@OptIn(ExperimentalTime::class)
class Accounts(private val database: Database) {

    /**
     * Long by design. The app is offline-first and may not reach the server for weeks, and a
     * session that quietly expired in the meantime turns a flight into a forced login. Every
     * authenticated request slides the window forward, and deleting the row revokes it at
     * once — revocation, not a short expiry, is what limits a leaked token.
     */
    private val sessionLifetime = 365.days

    /** How stale the stored expiry must get before a request is worth a write to refresh it. */
    private val slideAfter = 24.hours

    suspend fun register(email: String, password: String): RegisterResult {
        val normalised = email.trim().lowercase()
        validate(normalised, password)?.let { return RegisterResult.Invalid(it) }

        // Hashing before the transaction keeps a 64 MiB derivation outside the database lock.
        val hash = Passwords.hash(password)
        val userId = "user-" + UUID.randomUUID().toString().replace("-", "")

        return database.transaction { connection ->
            val taken = connection.prepareStatement("SELECT 1 FROM users WHERE email = ?").use {
                it.setString(1, normalised)
                it.executeQuery().use { rows -> rows.next() }
            }
            if (taken) return@transaction RegisterResult.EmailTaken

            connection.prepareStatement(
                "INSERT INTO users (id, email, password_hash, created_at) VALUES (?, ?, ?, ?)",
            ).use {
                it.setString(1, userId)
                it.setString(2, normalised)
                it.setString(3, hash)
                it.setLong(4, Clock.System.now().toEpochMilliseconds())
                it.executeUpdate()
            }
            connection.prepareStatement(
                "INSERT INTO user_seq (user_id, next_seq) VALUES (?, 1)",
            ).use {
                it.setString(1, userId)
                it.executeUpdate()
            }
            RegisterResult.Success(issue(connection, userId))
        }
    }

    /** Null for both an unknown address and a wrong password; the caller must not learn which. */
    suspend fun signIn(email: String, password: String): IssuedSession? {
        val normalised = email.trim().lowercase()
        val found = database.transaction { connection ->
            connection.prepareStatement("SELECT id, password_hash FROM users WHERE email = ?").use {
                it.setString(1, normalised)
                it.executeQuery().map { rows -> rows.getString(1) to rows.getString(2) }
            }.firstOrNull()
        }

        if (found == null) {
            // The same work as a real check, so response time does not quietly report which
            // addresses have accounts.
            Passwords.burn(password)
            return null
        }
        val (userId, hash) = found
        if (!Passwords.verify(password, hash)) return null

        return database.transaction { connection -> issue(connection, userId) }
    }

    /** Resolves a bearer token, sliding its expiry, or null when it is unknown or expired. */
    suspend fun authenticate(token: String): Principal? {
        val hash = tokenHash(token)
        val now = Clock.System.now().toEpochMilliseconds()

        return database.transaction { connection ->
            val row = connection.prepareStatement(
                "SELECT user_id, expires_at FROM sessions WHERE token_hash = ?",
            ).use {
                it.setString(1, hash)
                it.executeQuery().map { rows -> rows.getString(1) to rows.getLong(2) }
            }.firstOrNull() ?: return@transaction null

            val (userId, expiresAt) = row
            if (expiresAt <= now) {
                connection.prepareStatement("DELETE FROM sessions WHERE token_hash = ?").use {
                    it.setString(1, hash)
                    it.executeUpdate()
                }
                return@transaction null
            }

            val renewed = now + sessionLifetime.inWholeMilliseconds
            if (renewed - expiresAt > slideAfter.inWholeMilliseconds) {
                connection.prepareStatement(
                    "UPDATE sessions SET expires_at = ?, last_seen = ? WHERE token_hash = ?",
                ).use {
                    it.setLong(1, renewed)
                    it.setLong(2, now)
                    it.setString(3, hash)
                    it.executeUpdate()
                }
            }
            Principal(userId)
        }
    }

    suspend fun signOut(token: String) {
        database.transaction { connection ->
            connection.prepareStatement("DELETE FROM sessions WHERE token_hash = ?").use {
                it.setString(1, tokenHash(token))
                it.executeUpdate()
            }
        }
    }

    private fun issue(connection: Connection, userId: String): IssuedSession {
        val token = newToken()
        val now = Clock.System.now().toEpochMilliseconds()
        val expiresAt = now + sessionLifetime.inWholeMilliseconds
        connection.prepareStatement(
            "INSERT INTO sessions (token_hash, user_id, created_at, expires_at, last_seen) " +
                "VALUES (?, ?, ?, ?, ?)",
        ).use {
            it.setString(1, tokenHash(token))
            it.setString(2, userId)
            it.setLong(3, now)
            it.setLong(4, expiresAt)
            it.setLong(5, now)
            it.executeUpdate()
        }
        return IssuedSession(token, expiresAt)
    }

    private fun validate(email: String, password: String): String? = when {
        !EMAIL.matches(email) -> "that does not look like an email address"
        // Length rather than composition rules: a long passphrase beats a short one with a
        // symbol in it, and rules only push people towards predictable substitutions.
        password.length < 12 -> "password must be at least 12 characters"
        password.length > 1024 -> "password must be under 1024 characters"
        else -> null
    }

    private companion object {
        val EMAIL = Regex("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")
        val random = SecureRandom()
        val encoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()

        /** 256 bits of randomness, so guessing is not an attack worth modelling. */
        fun newToken(): String = encoder.encodeToString(ByteArray(32).also(random::nextBytes))

        /**
         * Only the hash is stored, so a copy of the database does not hand over live
         * sessions. A plain SHA-256 is right here where a password needs Argon2: the token
         * is already full-entropy random, so there is no guessing space to slow down.
         */
        fun tokenHash(token: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(token.toByteArray())
                .joinToString("") { byte -> "%02x".format(byte) }
    }
}
