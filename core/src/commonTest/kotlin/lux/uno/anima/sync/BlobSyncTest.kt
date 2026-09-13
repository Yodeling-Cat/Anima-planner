package lux.uno.anima.sync

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A blob is named after its own bytes, so the only questions here are which ones move and
 * what a refusal does. Nothing merges and nothing can be stale.
 */
class BlobSyncTest {

    private val token = "a-token"

    private class Blobs(initial: Map<String, ByteArray> = emptyMap()) : BlobStore {
        val held = initial.toMutableMap()
        var loads = 0

        override suspend fun stored(): Set<String> = held.keys.toSet()

        override suspend fun load(hash: String): ByteArray? {
            loads++
            return held[hash]
        }

        override suspend fun put(hash: String, bytes: ByteArray): Boolean {
            held[hash] = bytes
            return true
        }
    }

    /** Answers the blob calls and refuses everything else, which a blob pass never reaches. */
    private open class Api : SyncApi {
        val held = mutableMapOf<String, ByteArray>()
        val asked = mutableListOf<String>()
        val pushed = mutableListOf<String>()
        val pulled = mutableListOf<String>()

        override suspend fun missingBlobs(token: String, hashes: List<String>): ApiResult<List<String>> {
            asked += hashes
            return ApiResult.Ok(hashes.filterNot { it in held })
        }

        override suspend fun putBlob(token: String, hash: String, bytes: ByteArray): ApiResult<Unit> {
            pushed += hash
            held[hash] = bytes
            return ApiResult.Ok(Unit)
        }

        override suspend fun getBlob(token: String, hash: String): ApiResult<ByteArray> {
            pulled += hash
            return held[hash]?.let { ApiResult.Ok(it) } ?: ApiResult.Refused(404, "no such blob")
        }

        override suspend fun sync(token: String, request: SyncRequest): ApiResult<SyncResponse> =
            error("record sync is not part of a blob pass")
        override suspend fun register(email: String, password: String, joinPassword: String?) =
            ApiResult.Ok(Session("t", 0))
        override suspend fun logIn(email: String, password: String) = ApiResult.Ok(Session("t", 0))
        override suspend fun logOut(token: String) = ApiResult.Ok(Unit)
    }

    private fun sync(blobs: BlobStore, referenced: Set<String>) = BlobSync(blobs) { referenced }

    @Test
    fun aDeviceWithNothingReferencedTalksToNobody() = runTest {
        val api = Api()

        val result = sync(Blobs(mapOf("a" to byteArrayOf(1))), referenced = emptySet())
            .exchange(api, token)

        assertEquals(ApiResult.Ok(BlobExchange(0, 0)), result)
        assertTrue(api.asked.isEmpty(), "asked about blobs nothing points at")
    }

    @Test
    fun onlyBlobsTheServerIsMissingAreUploaded() = runTest {
        val api = Api().apply { held["known"] = byteArrayOf(9) }
        val blobs = Blobs(mapOf("known" to byteArrayOf(9), "fresh" to byteArrayOf(7)))

        val result = sync(blobs, referenced = setOf("known", "fresh")).exchange(api, token)

        assertEquals(ApiResult.Ok(BlobExchange(uploaded = 1, downloaded = 0)), result)
        assertContentEquals(listOf("fresh"), api.pushed)
        assertContentEquals(byteArrayOf(7), api.held["fresh"])
    }

    @Test
    fun aBlobThisDeviceLacksIsFetched() = runTest {
        val api = Api().apply { held["theirs"] = byteArrayOf(4, 2) }
        val blobs = Blobs()

        val result = sync(blobs, referenced = setOf("theirs")).exchange(api, token)

        assertEquals(ApiResult.Ok(BlobExchange(uploaded = 0, downloaded = 1)), result)
        assertContentEquals(byteArrayOf(4, 2), blobs.held["theirs"])
    }

    @Test
    fun anUnreferencedBlobIsNeitherSentNorAskedAbout() = runTest {
        // An icon whose subscription was deleted stays put for the pruning pass; uploading it
        // would only have the server hold something no record points at.
        val api = Api()
        val blobs = Blobs(mapOf("orphan" to byteArrayOf(1), "live" to byteArrayOf(2)))

        sync(blobs, referenced = setOf("live")).exchange(api, token)

        assertContentEquals(listOf("live"), api.asked)
        assertContentEquals(listOf("live"), api.pushed)
    }

    @Test
    fun aReferencedBlobNobodyHoldsYetIsLeftForTheNextPass() = runTest {
        // The device holding it has not uploaded it. Ordinary, not an error.
        val api = Api()

        val result = sync(Blobs(), referenced = setOf("elsewhere")).exchange(api, token)

        assertEquals(ApiResult.Ok(BlobExchange(0, 0)), result)
    }

    @Test
    fun aRefusedUploadIsSkippedRatherThanFailingThePass() = runTest {
        // A refusal means these bytes do not hash to this name, which is a bug on this side.
        // Retrying it forever would stall every later blob behind it.
        val api = object : Api() {
            override suspend fun putBlob(token: String, hash: String, bytes: ByteArray): ApiResult<Unit> {
                pushed += hash
                return if (hash == "bad") ApiResult.Refused(400, "hash mismatch")
                else super.putBlob(token, hash, bytes)
            }
        }
        val blobs = Blobs(mapOf("bad" to byteArrayOf(1), "good" to byteArrayOf(2)))

        val result = sync(blobs, referenced = setOf("bad", "good")).exchange(api, token)

        assertEquals(ApiResult.Ok(BlobExchange(uploaded = 1, downloaded = 0)), result)
        assertTrue("good" in api.pushed)
    }

    @Test
    fun aBlobThatVanishedLocallyIsSkipped() = runTest {
        // `stored` said it was there and `load` disagreed: a file deleted underneath the app.
        val api = Api()
        val blobs = object : BlobStore {
            override suspend fun stored() = setOf("gone")
            override suspend fun load(hash: String): ByteArray? = null
            override suspend fun put(hash: String, bytes: ByteArray) = true
        }

        val result = sync(blobs, referenced = setOf("gone")).exchange(api, token)

        assertEquals(ApiResult.Ok(BlobExchange(0, 0)), result)
        assertTrue(api.pushed.isEmpty())
    }

    @Test
    fun aStoreThatRejectsTheBytesIsNotCountedAsADownload() = runTest {
        val api = Api().apply { held["theirs"] = byteArrayOf(4, 2) }
        val blobs = object : BlobStore {
            override suspend fun stored() = emptySet<String>()
            override suspend fun load(hash: String): ByteArray? = null
            override suspend fun put(hash: String, bytes: ByteArray) = false
        }

        val result = sync(blobs, referenced = setOf("theirs")).exchange(api, token)

        assertEquals(ApiResult.Ok(BlobExchange(0, 0)), result)
    }

    @Test
    fun anExpiredTokenStopsThePassRatherThanTouchingAnything() = runTest {
        val api = object : Api() {
            override suspend fun missingBlobs(token: String, hashes: List<String>) =
                ApiResult.Unauthorized
        }
        val blobs = Blobs(mapOf("a" to byteArrayOf(1)))

        assertEquals(ApiResult.Unauthorized, sync(blobs, setOf("a")).exchange(api, token))
    }

    @Test
    fun aDroppedConnectionOnTheWayDownStopsThePass() = runTest {
        val failure = IllegalStateException("socket closed")
        val api = object : Api() {
            override suspend fun getBlob(token: String, hash: String): ApiResult<ByteArray> =
                ApiResult.Unreachable(failure)
        }

        val result = sync(Blobs(), referenced = setOf("theirs")).exchange(api, token)

        assertEquals(ApiResult.Unreachable(failure), result)
    }

    @Test
    fun blobsAlreadyOnBothSidesAreNeverRead() = runTest {
        val api = Api().apply { held["settled"] = byteArrayOf(1) }
        val blobs = Blobs(mapOf("settled" to byteArrayOf(1)))

        val result = sync(blobs, referenced = setOf("settled")).exchange(api, token)

        assertEquals(ApiResult.Ok(BlobExchange(0, 0)), result)
        assertEquals(0, blobs.loads, "read bytes the server already holds")
    }
}
