package lux.uno.anima.platform

/** How well the platform underneath a [SecretStore] can actually protect what it holds. */
enum class SecretProtection {
    /**
     * Sealed with a key the OS keeps out of the app's reach — iOS Keychain, Android
     * Keystore. Reading the app's files is not enough to read the secret.
     */
    OS_KEYSTORE,

    /**
     * A file only this user account may open. Plaintext at rest, which is what every
     * desktop CLI that stores a token does, because a desktop app has no secret of its own
     * to encrypt with that an attacker running as the user could not also obtain.
     */
    FILE_PERMISSIONS,
}

/**
 * Somewhere to keep the session token between launches.
 *
 * Deliberately small and deliberately separate from [appDataDirectory]: the dataset is
 * sync state the user can always re-fetch, while this is the credential that fetches it.
 * Nothing else belongs in here.
 *
 * Every read may answer null — a restored device backup invalidates Android's key material,
 * and a Keychain entry does not survive an app being deleted. Callers treat that as "signed
 * out", never as an error, and the local dataset is never touched because of it.
 */
interface SecretStore {
    val protection: SecretProtection

    suspend fun get(key: String): String?
    suspend fun put(key: String, value: String)
    suspend fun remove(key: String)
}

/** The session itself. Cleared the moment someone signs out. */
const val SESSION_TOKEN_KEY: String = "session-token"

/**
 * The address the session belongs to.
 *
 * Kept deliberately past a sign-out, so signing back in means typing a password rather than
 * remembering which address this device uses. It lives in here rather than in the plain
 * device preferences because it identifies a person: on a phone that means the OS keystore,
 * and on a desktop a file only this account can open. Removed only when someone asks for
 * their data to be taken off the device.
 */
const val ACCOUNT_EMAIL_KEY: String = "account-email"

expect fun createSecretStore(): SecretStore
