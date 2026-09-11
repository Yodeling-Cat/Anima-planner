package lux.uno.anima.finance.sync

import kotlinx.datetime.LocalDate
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import lux.uno.anima.finance.FinanceData
import lux.uno.anima.finance.FinanceSettings
import lux.uno.anima.finance.RecordMeta
import lux.uno.anima.finance.metaKey
import lux.uno.anima.finance.model.Category
import lux.uno.anima.finance.model.PaymentMethod
import lux.uno.anima.finance.model.PricePoint
import lux.uno.anima.finance.model.Subscription
import lux.uno.anima.sync.Hlc
import lux.uno.anima.sync.HybridClock
import lux.uno.anima.sync.RecordKind
import lux.uno.anima.sync.SyncRecord

/**
 * Turns the dataset into records the server can hold, and folds returned records back in.
 *
 * All of it lives here on the client, because the server is not allowed to know what a
 * subscription is — see AGENTS.md. Everything below the spine is text as far as it is
 * concerned.
 */
private val payloadFormat = Json {
    // A device on a newer build may add fields; they are kept out of the way rather than
    // treated as corruption.
    ignoreUnknownKeys = true
    // Defaults are written out, so a payload never depends on what this build's defaults
    // happen to be when another build reads it.
    encodeDefaults = true
}

// region outbound

/**
 * Every record this device holds, tombstones included.
 *
 * Driven by [FinanceData.meta] rather than the typed lists, because a deleted record exists
 * only there — and a delete that never reached the server would be undone by the next pull.
 */
fun FinanceData.toRecords(): List<SyncRecord> = meta.mapNotNull { (key, recordMeta) ->
    val (kind, id) = splitMetaKey(key) ?: return@mapNotNull null
    if (recordMeta.deleted) {
        SyncRecord(kind = kind, id = id, hlc = recordMeta.hlc, deleted = true)
    } else {
        val payload = encodePayload(kind, id) ?: return@mapNotNull null
        SyncRecord(kind = kind, id = id, hlc = recordMeta.hlc, payload = payload)
    }
}

/**
 * The records worth pushing: the ones this device stamped itself, after [after].
 *
 * Identifying them by the node id inside the reading means no separate list of pending
 * changes has to be kept in step with the data. A record that arrived from elsewhere still
 * carries that device's node id, so it is not sent straight back; one that arrived and was
 * then altered here — the price history union below — carries this device's, so it is.
 */
fun FinanceData.recordsToPush(nodeId: String, after: Hlc = Hlc.Zero): List<SyncRecord> =
    toRecords().filter { it.hlc.nodeId == nodeId && it.hlc > after }

// endregion

// region inbound

/**
 * Folds [incoming] in, keeping whichever version of each record is newer.
 *
 * [clock] is advanced past every reading seen, whether or not the record wins. That happens
 * here rather than in the caller because applying a record without acknowledging its clock
 * is never correct, and this is the one place it cannot be forgotten.
 *
 * A payload that will not decode is skipped and left unrecorded. It is almost certainly a
 * record written by a newer build; dropping the update keeps this one running, where
 * throwing would take the whole sync down.
 */
fun FinanceData.applying(incoming: List<SyncRecord>, clock: HybridClock): FinanceData {
    var subscriptions = this.subscriptions
    var paymentMethods = this.paymentMethods
    var categories = this.categories
    var settings = this.settings
    val stamps = meta.toMutableMap()

    for (record in incoming) {
        clock.observe(record.hlc)

        val key = metaKey(record.kind, record.id)
        val held = stamps[key]
        // Ties keep what is already here: only the same device in the same millisecond can
        // tie, so the two are the same write and there is nothing to do.
        if (held != null && held.hlc >= record.hlc) continue

        when (record.kind) {
            RecordKind.SUBSCRIPTION -> {
                if (record.deleted) {
                    subscriptions = subscriptions.filterNot { it.id == record.id }
                    stamps[key] = RecordMeta(record.hlc, deleted = true)
                } else {
                    val arrived = decode(Subscription.serializer(), record.payload) ?: continue
                    val merged = arrived.withPriceHistoryFrom(
                        subscriptions.firstOrNull { it.id == record.id },
                    )
                    subscriptions = subscriptions.upsert(merged) { it.id }
                    // When the union kept a point the incoming record did not carry, this
                    // device now holds a version nothing else has. A reading of its own is
                    // what sends it back rather than leaving it stranded here.
                    stamps[key] = if (merged.priceHistory == arrived.priceHistory) {
                        RecordMeta(record.hlc)
                    } else {
                        RecordMeta(clock.tick())
                    }
                }
            }

            RecordKind.PAYMENT_METHOD -> {
                if (record.deleted) {
                    paymentMethods = paymentMethods.filterNot { it.id == record.id }
                    stamps[key] = RecordMeta(record.hlc, deleted = true)
                } else {
                    val arrived = decode(PaymentMethod.serializer(), record.payload) ?: continue
                    paymentMethods = paymentMethods.upsert(arrived) { it.id }
                    stamps[key] = RecordMeta(record.hlc)
                }
            }

            RecordKind.CATEGORY -> {
                if (record.deleted) {
                    categories = categories.filterNot { it.id == record.id }
                    stamps[key] = RecordMeta(record.hlc, deleted = true)
                } else {
                    val arrived = decode(Category.serializer(), record.payload) ?: continue
                    categories = categories.upsert(arrived) { it.id }
                    stamps[key] = RecordMeta(record.hlc)
                }
            }

            RecordKind.SETTINGS -> {
                // There is no such thing as having no settings, so a tombstone for them is
                // meaningless and is ignored rather than obeyed.
                if (record.deleted) continue
                settings = decode(FinanceSettings.serializer(), record.payload) ?: continue
                stamps[key] = RecordMeta(record.hlc)
            }

            // A kind this build has never heard of, from a device running a newer one.
            // Left alone so the next sync can offer it again once this build understands it.
            else -> continue
        }
    }

    return copy(
        subscriptions = subscriptions,
        paymentMethods = paymentMethods,
        categories = categories,
        settings = settings,
        meta = stamps,
    )
}

// endregion

/**
 * Keeps every price point both versions know about.
 *
 * The one place record-level last-write-wins would throw away something typed: a price
 * recorded on a phone would vanish the moment a desktop edit of the same subscription won.
 * Prices only ever accumulate, so a union is always right, and on a collision the winning
 * record's own version of that day stands.
 *
 * Nothing else on a subscription works this way — for a name or a colour the newest version
 * really is the one that was meant.
 */
private fun Subscription.withPriceHistoryFrom(local: Subscription?): Subscription {
    if (local == null || local.priceHistory.isEmpty()) return this

    val byDate = LinkedHashMap<LocalDate, PricePoint>()
    local.priceHistory.forEach { byDate[it.effectiveFrom] = it }
    priceHistory.forEach { byDate[it.effectiveFrom] = it }

    val union = byDate.values.sortedBy { it.effectiveFrom }
    return if (union == priceHistory) this else copy(priceHistory = union)
}

private fun <T> List<T>.upsert(item: T, id: (T) -> String): List<T> {
    val index = indexOfFirst { id(it) == id(item) }
    // Replaced in place rather than appended, so an update does not quietly reorder the list.
    return if (index >= 0) toMutableList().apply { this[index] = item } else this + item
}

private fun FinanceData.encodePayload(kind: String, id: String): String? = when (kind) {
    RecordKind.SUBSCRIPTION -> subscriptions.firstOrNull { it.id == id }
        ?.let { payloadFormat.encodeToString(Subscription.serializer(), it) }

    RecordKind.PAYMENT_METHOD -> paymentMethods.firstOrNull { it.id == id }
        ?.let { payloadFormat.encodeToString(PaymentMethod.serializer(), it) }

    RecordKind.CATEGORY -> categories.firstOrNull { it.id == id }
        ?.let { payloadFormat.encodeToString(Category.serializer(), it) }

    RecordKind.SETTINGS -> payloadFormat.encodeToString(FinanceSettings.serializer(), settings)

    else -> null
}

private fun <T> decode(serializer: KSerializer<T>, payload: String?): T? =
    payload?.let { runCatching { payloadFormat.decodeFromString(serializer, it) }.getOrNull() }

/**
 * Splits on the first colon only: the kind never contains one, but an id is allowed to.
 */
private fun splitMetaKey(key: String): Pair<String, String>? {
    val separator = key.indexOf(':')
    if (separator <= 0 || separator == key.lastIndex) return null
    return key.substring(0, separator) to key.substring(separator + 1)
}
