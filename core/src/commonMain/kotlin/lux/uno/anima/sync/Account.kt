package lux.uno.anima.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import lux.uno.anima.platform.ACCOUNT_EMAIL_KEY
import lux.uno.anima.platform.SESSION_TOKEN_KEY
import lux.uno.anima.platform.SecretStore

/** What happened when someone tried to sign in or create an account. */
sealed interface AccountResult {
    data object Success : AccountResult

    /** Wrong password, or no such address — the server will not say which, and nor do we. */
    data object Rejected : AccountResult

    /** This server is not taking new accounts. A setting, not a fault. */
    data object RegistrationClosed : AccountResult

    data object Unreachable : AccountResult

    /** Something the server explained, such as a password that is too short. */
    data class Refused(val reason: String) : AccountResult
}

/**
 * The session, the address it belongs to, and the two ways to get one.
 *
 * The address outlives the session on purpose. A token can go at any time — it expires, or
 * a 401 drops it — and having to remember which address this device signs in with, every
 * time that happens, is friction for no benefit. Only [forget] removes it.
 */
class Account(
    private val secrets: SecretStore,
    private val api: SyncApi?,
    private val tokenKey: String = SESSION_TOKEN_KEY,
    private val emailKey: String = ACCOUNT_EMAIL_KEY,
) {
    private val _signedIn = MutableStateFlow(false)
    val signedIn: StateFlow<Boolean> = _signedIn.asStateFlow()

    private val _email = MutableStateFlow<String?>(null)

    /** The address last signed in with, whether or not there is a session right now. */
    val email: StateFlow<String?> = _email.asStateFlow()

    /** True when this environment has a server at all; the sandbox never signs in. */
    val canSignIn: Boolean get() = api != null

    suspend fun refresh() {
        _signedIn.value = secrets.get(tokenKey) != null
        _email.value = secrets.get(emailKey)
    }

    suspend fun signIn(email: String, password: String): AccountResult =
        attempt(email) { it.logIn(email.trim(), password) }

    suspend fun register(email: String, password: String): AccountResult =
        attempt(email) { it.register(email.trim(), password) }

    /**
     * Ends the session.
     *
     * The token is dropped locally whatever the server says. A logout that failed because
     * the network was down still has to sign the person out of this device — leaving them
     * holding a session they asked to end would be the wrong way to fail.
     *
     * The address stays. Signing back in should ask for a password, not for both.
     */
    suspend fun signOut() {
        val token = secrets.get(tokenKey)
        if (token != null) api?.logOut(token)
        secrets.remove(tokenKey)
        _signedIn.value = false
    }

    /** Forgets this device ever knew who the user was. Only the "remove data" path calls it. */
    suspend fun forget() {
        signOut()
        secrets.remove(emailKey)
        _email.value = null
    }

    private suspend fun attempt(
        email: String,
        call: suspend (SyncApi) -> ApiResult<Session>,
    ): AccountResult {
        val api = api ?: return AccountResult.Unreachable
        return when (val result = call(api)) {
            is ApiResult.Ok -> {
                val normalised = email.trim()
                secrets.put(tokenKey, result.value.token)
                // Stored only once the server has agreed it is a real account, so a typo
                // never becomes the address this device offers back next time.
                secrets.put(emailKey, normalised)
                _signedIn.value = true
                _email.value = normalised
                AccountResult.Success
            }

            ApiResult.Unauthorized -> AccountResult.Rejected
            is ApiResult.Unreachable -> AccountResult.Unreachable
            is ApiResult.Refused -> when (result.status) {
                403 -> AccountResult.RegistrationClosed
                else -> AccountResult.Refused(result.detail.ifBlank { "the server refused that" })
            }
        }
    }
}
