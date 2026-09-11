package lux.uno.anima.sync

/**
 * Somewhere binary attachments live on this device, addressed by the hash of their contents.
 *
 * An interface so the sync engine can move icons without knowing what an icon is — the same
 * reason the server does not know either.
 */
interface BlobStore {
    suspend fun stored(): Set<String>
    suspend fun load(hash: String): ByteArray?
    suspend fun put(hash: String, bytes: ByteArray): Boolean
}

/** What one blob pass did, for reporting rather than for control flow. */
data class BlobExchange(val uploaded: Int, val downloaded: Int)

/**
 * Brings this device's blobs in line with the server's.
 *
 * Far simpler than record sync, and deliberately so: a blob is named after its own bytes, so
 * it can only be present or absent. There is no newer version of one, nothing to merge, and
 * nothing that can be lost by getting the order wrong.
 *
 * Only blobs some record still points at are moved. An icon whose subscription was deleted
 * is left where it is for the pruning pass to deal with, rather than uploaded so the server
 * can store something nothing refers to.
 */
class BlobSync(
    private val blobs: BlobStore,
    private val referenced: suspend () -> Set<String>,
) {
    suspend fun exchange(api: SyncApi, token: String): ApiResult<BlobExchange> {
        val wanted = referenced()
        if (wanted.isEmpty()) return ApiResult.Ok(BlobExchange(0, 0))

        val here = blobs.stored()
        var uploaded = 0
        var downloaded = 0

        val mine = wanted.intersect(here).toList()
        if (mine.isNotEmpty()) {
            when (val missing = api.missingBlobs(token, mine)) {
                is ApiResult.Ok -> for (hash in missing.value) {
                    val bytes = blobs.load(hash) ?: continue
                    when (val put = api.putBlob(token, hash, bytes)) {
                        is ApiResult.Ok -> uploaded += 1
                        // A refusal here means these bytes do not hash to this name, which is
                        // a bug on this side. Skip it rather than retrying it forever.
                        is ApiResult.Refused -> Unit
                        else -> return put as ApiResult<Nothing>
                    }
                }

                else -> return missing as ApiResult<Nothing>
            }
        }

        for (hash in wanted - here) {
            when (val fetched = api.getBlob(token, hash)) {
                is ApiResult.Ok -> if (blobs.put(hash, fetched.value)) downloaded += 1
                // Referenced but not on the server yet: the device that has it has not
                // uploaded it. The next sync will find it.
                is ApiResult.Refused -> Unit
                else -> return fetched as ApiResult<Nothing>
            }
        }

        return ApiResult.Ok(BlobExchange(uploaded, downloaded))
    }
}
