package lux.uno.anima.server.auth

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Argon2id password hashing.
 *
 * Bouncy Castle rather than one of the JNI-backed Argon2 wrappers: it is pure Java, so the
 * server runs anywhere a JVM does with no native library to match to the host.
 *
 * Parameters follow the OWASP baseline — 64 MiB of memory over three passes. The memory cost
 * is what makes a stolen hash expensive to attack on a GPU, and 64 MiB per login is nothing
 * for a server with a handful of accounts.
 */
object Passwords {

    private const val MEMORY_KIB = 65_536
    private const val ITERATIONS = 3
    private const val PARALLELISM = 1
    private const val SALT_BYTES = 16
    private const val HASH_BYTES = 32

    private val random = SecureRandom()
    private val encoder: Base64.Encoder = Base64.getEncoder().withoutPadding()
    private val decoder: Base64.Decoder = Base64.getDecoder()

    /** Stored in the PHC string format, so the parameters travel with the hash. */
    fun hash(password: String): String {
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val digest = derive(password, salt)
        return "\$argon2id\$v=${Argon2Parameters.ARGON2_VERSION_13}" +
            "\$m=$MEMORY_KIB,t=$ITERATIONS,p=$PARALLELISM" +
            "\$${encoder.encodeToString(salt)}\$${encoder.encodeToString(digest)}"
    }

    /**
     * Re-derives with the parameters recorded in [encoded], so hashes written under older
     * settings keep verifying after the constants above are raised.
     */
    fun verify(password: String, encoded: String): Boolean {
        val parts = encoded.split('$')
        if (parts.size != 6 || parts[1] != "argon2id") return false
        return runCatching {
            val version = parts[2].removePrefix("v=").toInt()
            val options = parts[3].split(',').associate {
                val (name, value) = it.split('=')
                name to value.toInt()
            }
            val salt = decoder.decode(parts[4])
            val expected = decoder.decode(parts[5])
            val actual = derive(
                password = password,
                salt = salt,
                memoryKib = options.getValue("m"),
                iterations = options.getValue("t"),
                parallelism = options.getValue("p"),
                length = expected.size,
                version = version,
            )
            MessageDigest.isEqual(expected, actual)
        }.getOrDefault(false)
    }

    /**
     * Burns the same work as a real verification against a hash that cannot match.
     *
     * Login calls this when the email is unknown, so the response time does not quietly
     * report which addresses have accounts.
     */
    fun burn(password: String) {
        derive(password, ByteArray(SALT_BYTES))
    }

    private fun derive(
        password: String,
        salt: ByteArray,
        memoryKib: Int = MEMORY_KIB,
        iterations: Int = ITERATIONS,
        parallelism: Int = PARALLELISM,
        length: Int = HASH_BYTES,
        version: Int = Argon2Parameters.ARGON2_VERSION_13,
    ): ByteArray {
        val parameters = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(version)
            .withMemoryAsKB(memoryKib)
            .withIterations(iterations)
            .withParallelism(parallelism)
            .withSalt(salt)
            .build()
        val generator = Argon2BytesGenerator().apply { init(parameters) }
        return ByteArray(length).also {
            generator.generateBytes(password.toCharArray(), it)
        }
    }
}
