package lux.uno.anima.sync

import kotlinx.coroutines.test.runTest
import lux.uno.anima.platform.ACCOUNT_EMAIL_KEY
import lux.uno.anima.platform.SecretProtection
import lux.uno.anima.platform.SecretStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AccountTest {

    private val tokenKey = "session-token"

    private class Secrets : SecretStore {
        val values = mutableMapOf<String, String>()
        override val protection = SecretProtection.FILE_PERMISSIONS
        override suspend fun get(key: String): String? = values[key]
        override suspend fun put(key: String, value: String) {
            values[key] = value
        }

        override suspend fun remove(key: String) {
            values.remove(key)
        }
    }

    private class StubApi(
        private val answer: ApiResult<Session>,
        val logOutResult: ApiResult<Unit> = ApiResult.Ok(Unit),
    ) : SyncApi {
        var loggedOutWith: String? = null

        override suspend fun sync(token: String, request: SyncRequest) =
            ApiResult.Ok(SyncResponse(cursor = 0))

        override suspend fun register(email: String, password: String) = answer
        override suspend fun logIn(email: String, password: String) = answer
        override suspend fun logOut(token: String): ApiResult<Unit> {
            loggedOutWith = token
            return logOutResult
        }

        // Not exercised here; the account never touches attachments.
        override suspend fun missingBlobs(token: String, hashes: List<String>) =
            ApiResult.Ok(emptyList<String>())

        override suspend fun putBlob(token: String, hash: String, bytes: ByteArray) =
            ApiResult.Ok(Unit)

        override suspend fun getBlob(token: String, hash: String): ApiResult<ByteArray> =
            ApiResult.Refused(404, "no such blob")
    }

    private fun account(api: SyncApi?, secrets: Secrets = Secrets()) =
        Account(secrets, api, tokenKey) to secrets

    @Test
    fun `signing in keeps the token`() = runTest {
        val (account, secrets) = account(StubApi(ApiResult.Ok(Session("a-token", 0))))

        assertEquals(AccountResult.Success, account.signIn(" someone@example.com ", "a password"))

        assertEquals("a-token", secrets.values[tokenKey])
        assertTrue(account.signedIn.value)
    }

    @Test
    fun `a wrong password says nothing about whether the account exists`() = runTest {
        val (account, secrets) = account(StubApi(ApiResult.Unauthorized))

        assertEquals(AccountResult.Rejected, account.signIn("someone@example.com", "wrong"))

        assertNull(secrets.values[tokenKey])
        assertFalse(account.signedIn.value)
    }

    @Test
    fun `a closed server is its own answer rather than a generic failure`() = runTest {
        // The server returns 403 when ANIMA_REGISTRATION_OPEN is unset, which is the normal
        // state of a running server rather than something going wrong.
        val (account, _) = account(StubApi(ApiResult.Refused(403, "registration is closed")))

        assertEquals(
            AccountResult.RegistrationClosed,
            account.register("someone@example.com", "correct horse battery"),
        )
    }

    @Test
    fun `the server's own explanation is passed through`() = runTest {
        val (account, _) = account(
            StubApi(ApiResult.Refused(400, "password must be at least 12 characters")),
        )

        assertEquals(
            AccountResult.Refused("password must be at least 12 characters"),
            account.register("someone@example.com", "short"),
        )
    }

    @Test
    fun `being offline is not being rejected`() = runTest {
        val (account, _) = account(StubApi(ApiResult.Unreachable(RuntimeException("no network"))))

        assertEquals(AccountResult.Unreachable, account.signIn("someone@example.com", "password"))
    }

    @Test
    fun `an environment with no server cannot sign in`() = runTest {
        val (account, _) = account(api = null)

        assertFalse(account.canSignIn)
        assertEquals(AccountResult.Unreachable, account.signIn("someone@example.com", "password"))
    }

    @Test
    fun `signing out tells the server and drops the token`() = runTest {
        val api = StubApi(ApiResult.Ok(Session("a-token", 0)))
        val (account, secrets) = account(api)
        account.signIn("someone@example.com", "password")

        account.signOut()

        assertEquals("a-token", api.loggedOutWith, "the session should be revoked, not orphaned")
        assertNull(secrets.values[tokenKey])
        assertFalse(account.signedIn.value)
    }

    @Test
    fun `signing out works even with the server unreachable`() = runTest {
        // Leaving someone holding a session they asked to end is the wrong way to fail.
        val api = StubApi(
            answer = ApiResult.Ok(Session("a-token", 0)),
            logOutResult = ApiResult.Unreachable(RuntimeException("no network")),
        )
        val (account, secrets) = account(api)
        account.signIn("someone@example.com", "password")

        account.signOut()

        assertNull(secrets.values[tokenKey])
        assertFalse(account.signedIn.value)
    }

    @Test
    fun `the address is remembered and survives signing out`() = runTest {
        // The point of keeping it: a token can go at any time, and having to remember which
        // address this device uses every time that happens is friction for nothing.
        val (account, secrets) = account(StubApi(ApiResult.Ok(Session("a-token", 0))))
        account.signIn("  Someone@example.com  ", "password")

        assertEquals("Someone@example.com", account.email.value, "trimmed, not otherwise altered")
        account.signOut()

        assertEquals("Someone@example.com", account.email.value)
        assertEquals("Someone@example.com", secrets.values[ACCOUNT_EMAIL_KEY])
    }

    @Test
    fun `a failed sign-in does not leave the address behind`() = runTest {
        // Otherwise a typo becomes the address this device offers back for good.
        val (account, secrets) = account(StubApi(ApiResult.Unauthorized))

        account.signIn("typo@example.con", "password")

        assertNull(account.email.value)
        assertNull(secrets.values[ACCOUNT_EMAIL_KEY])
    }

    @Test
    fun `forgetting takes the address as well as the session`() = runTest {
        val (account, secrets) = account(StubApi(ApiResult.Ok(Session("a-token", 0))))
        account.signIn("someone@example.com", "password")

        account.forget()

        assertNull(secrets.values[tokenKey])
        assertNull(secrets.values[ACCOUNT_EMAIL_KEY])
        assertNull(account.email.value)
    }

    @Test
    fun `an address from a previous launch comes back`() = runTest {
        val secrets = Secrets().apply { values[ACCOUNT_EMAIL_KEY] = "someone@example.com" }
        val (account, _) = account(StubApi(ApiResult.Unauthorized), secrets)

        account.refresh()

        assertEquals("someone@example.com", account.email.value)
        assertFalse(account.signedIn.value, "an address is not a session")
    }

    @Test
    fun `a token already on the device counts as signed in`() = runTest {
        val secrets = Secrets().apply { values[tokenKey] = "from-a-previous-launch" }
        val (account, _) = account(StubApi(ApiResult.Unauthorized), secrets)

        account.refresh()

        assertTrue(account.signedIn.value)
    }
}
