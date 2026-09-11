package lux.uno.anima

import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Who may create an account here.
 *
 * One rule with three inputs, and the combination that must never drift is the last one:
 * a private server nobody set a join password on admits nobody at all.
 */
class AdmissionTest {

    private fun config(joinPassword: String?, publicInstance: Boolean) = ServerConfig(
        port = 0,
        host = "localhost",
        dataDirectory = Paths.get("unused"),
        joinPassword = joinPassword,
        publicInstance = publicInstance,
    )

    @Test
    fun `a private server without a join password admits nobody`() {
        val server = config(joinPassword = null, publicInstance = false)
        assertEquals(Admission.CLOSED, server.admits(null))
        assertEquals(Admission.CLOSED, server.admits("anything"))
    }

    @Test
    fun `a join password admits whoever presents it`() {
        val server = config(joinPassword = "open-sesame", publicInstance = false)
        assertEquals(Admission.ALLOWED, server.admits("open-sesame"))
    }

    @Test
    fun `a wrong or missing join password is told apart from a closed server`() {
        val server = config(joinPassword = "open-sesame", publicInstance = false)
        assertEquals(Admission.WRONG_JOIN_PASSWORD, server.admits("sesame"))
        assertEquals(Admission.WRONG_JOIN_PASSWORD, server.admits(null))
        assertEquals(Admission.WRONG_JOIN_PASSWORD, server.admits(""))
    }

    @Test
    fun `a public instance admits anyone when no join password is set`() {
        val server = config(joinPassword = null, publicInstance = true)
        assertEquals(Admission.ALLOWED, server.admits(null))
    }

    @Test
    fun `a join password still applies on a public instance`() {
        // A soft launch: the service is public in the sense that matters legally, and still
        // only lets in people who were handed the secret.
        val server = config(joinPassword = "not-yet", publicInstance = true)
        assertEquals(Admission.ALLOWED, server.admits("not-yet"))
        assertEquals(Admission.WRONG_JOIN_PASSWORD, server.admits(null))
    }

    @Test
    fun `a blank join password in the environment counts as none`() {
        // Otherwise `ANIMA_JOIN_PASSWORD=` in a .env file would admit anyone who sent an
        // empty field, which is the opposite of setting one.
        val read = ServerConfig.fromEnvironment { name ->
            when (name) {
                "ANIMA_JOIN_PASSWORD" -> "   "
                else -> null
            }
        }
        assertEquals(null, read.joinPassword)
        assertEquals(Admission.CLOSED, read.admits(""))
    }
}
