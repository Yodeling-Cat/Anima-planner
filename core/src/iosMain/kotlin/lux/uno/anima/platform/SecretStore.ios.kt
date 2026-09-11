package lux.uno.anima.platform

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFAllocatorDefault
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlock
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData

actual fun createSecretStore(): SecretStore = KeychainSecretStore()

private const val SERVICE = "lux.uno.anima"

/**
 * Keeps the token in the Keychain as a generic password.
 *
 * Accessibility is `AfterFirstUnlock` rather than `WhenUnlocked`: background sync has to be
 * able to read the token while the phone sits locked in a pocket, and the entry still stays
 * unreadable between a cold boot and the first passcode entry. It is not marked
 * `ThisDeviceOnly`, so the token rides along to a restored device — the server can revoke a
 * session, which is the better place to draw that line.
 *
 * Writes delete before adding rather than using `SecItemUpdate`, because an update against a
 * missing entry and an add against an existing one both fail, and one path is easier to keep
 * correct than two.
 */
@OptIn(ExperimentalForeignApi::class)
private class KeychainSecretStore : SecretStore {

    override val protection = SecretProtection.OS_KEYSTORE

    override suspend fun get(key: String): String? = withContext(Dispatchers.Default) {
        val scope = CfScope()
        try {
            val query = scope.dictionary(
                kSecClass to kSecClassGenericPassword,
                kSecAttrService to scope.string(SERVICE),
                kSecAttrAccount to scope.string(key),
                kSecReturnData to kCFBooleanTrue,
                kSecMatchLimit to kSecMatchLimitOne,
            )
            memScoped {
                val found = alloc<CFTypeRefVar>()
                if (SecItemCopyMatching(query, found.ptr) != errSecSuccess) return@withContext null
                val data = CFBridgingRelease(found.value) as? NSData ?: return@withContext null
                data.decodeToString()
            }
        } finally {
            scope.release()
        }
    }

    override suspend fun put(key: String, value: String) = withContext(Dispatchers.Default) {
        remove(key)
        val scope = CfScope()
        try {
            val data = value.toNSData() ?: return@withContext
            val entry = scope.dictionary(
                kSecClass to kSecClassGenericPassword,
                kSecAttrService to scope.string(SERVICE),
                kSecAttrAccount to scope.string(key),
                kSecValueData to scope.own(CFBridgingRetain(data)),
                kSecAttrAccessible to kSecAttrAccessibleAfterFirstUnlock,
            )
            SecItemAdd(entry, null)
            Unit
        } finally {
            scope.release()
        }
    }

    override suspend fun remove(key: String) = withContext(Dispatchers.Default) {
        val scope = CfScope()
        try {
            SecItemDelete(
                scope.dictionary(
                    kSecClass to kSecClassGenericPassword,
                    kSecAttrService to scope.string(SERVICE),
                    kSecAttrAccount to scope.string(key),
                ),
            )
            Unit
        } finally {
            scope.release()
        }
    }
}

/**
 * Core Foundation objects created here are owned by us, and ARC is not going to release
 * them. Collecting them in one place means a single [release] at the end of each call
 * instead of a `CFRelease` on every early return.
 */
@OptIn(ExperimentalForeignApi::class)
private class CfScope {
    private val owned = mutableListOf<platform.CoreFoundation.CFTypeRef>()

    fun own(reference: platform.CoreFoundation.CFTypeRef?): platform.CoreFoundation.CFTypeRef? =
        reference?.also { owned += it }

    // Kotlin's String bridges to NSString on its own at the interop boundary; an `as`
    // cast between them is a different thing entirely and never succeeds.
    fun string(value: String): platform.CoreFoundation.CFTypeRef? =
        own(CFBridgingRetain(value.toNSString()))

    fun dictionary(
        vararg entries: Pair<platform.CoreFoundation.CFTypeRef?, platform.CoreFoundation.CFTypeRef?>,
    ): CFDictionaryRef {
        val dictionary = CFDictionaryCreateMutable(
            kCFAllocatorDefault,
            entries.size.toLong(),
            kCFTypeDictionaryKeyCallBacks.ptr,
            kCFTypeDictionaryValueCallBacks.ptr,
        )!!
        owned += dictionary
        entries.forEach { (key, value) -> CFDictionaryAddValue(dictionary, key, value) }
        return dictionary
    }

    fun release() {
        owned.forEach { CFRelease(it) }
        owned.clear()
    }
}

/**
 * Kotlin `String` and `NSString` are bridged, not interchangeable: the compiler will accept
 * an `as` cast between them and it will never succeed at runtime. These go through the
 * documented conversions instead.
 */
@OptIn(BetaInteropApi::class)
private fun String.toNSString(): NSString = NSString.create(string = this)

@OptIn(BetaInteropApi::class)
private fun String.toNSData(): NSData? =
    toNSString().dataUsingEncoding(NSUTF8StringEncoding)

@OptIn(BetaInteropApi::class)
private fun NSData.decodeToString(): String? =
    NSString.create(data = this, encoding = NSUTF8StringEncoding)?.toString()
