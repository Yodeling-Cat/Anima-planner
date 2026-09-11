package lux.uno.anima.finance

import kotlinx.serialization.Serializable
import lux.uno.anima.finance.model.Category
import lux.uno.anima.sync.Hlc
import lux.uno.anima.sync.HybridClock
import lux.uno.anima.sync.RecordKind
import lux.uno.anima.finance.model.PaymentMethod
import lux.uno.anima.finance.model.Subscription

/** The shape currently written to disk. Bumped whenever a migration is needed. */
const val FINANCE_SCHEMA_VERSION: Int = 5

/**
 * The first version whose stamps carry a node id that outlives the launch that wrote them.
 *
 * Anything below this was stamped by a clock whose node id was generated per run, so those
 * readings name devices that no longer exist. See [adoptedBy].
 */
const val STABLE_NODE_SCHEMA_VERSION: Int = 5

/** Preferences that shape how money is shown, rather than what is owed. */
@Serializable
data class FinanceSettings(
    val preferredCurrency: String = "USD",
)

/** What sync needs to know about one record: when it last changed, and whether it still exists. */
@Serializable
data class RecordMeta(val hlc: Hlc, val deleted: Boolean = false)

/** How a record is filed in [FinanceData.meta]. */
fun metaKey(kind: String, id: String): String = "$kind:$id"

/** The whole finance dataset, and the shape written to disk. */
@Serializable
data class FinanceData(
    val subscriptions: List<Subscription> = emptyList(),
    val paymentMethods: List<PaymentMethod> = emptyList(),
    val categories: List<Category> = emptyList(),
    val settings: FinanceSettings = FinanceSettings(),
    /**
     * Sync metadata for every record this device has ever held, keyed by [metaKey].
     *
     * Beside the lists rather than inside the models, because a deleted record has to leave
     * a tombstone behind and this is the only place one can live without every screen having
     * to filter it back out. A record present in a list is live; a record present only here,
     * marked deleted, is gone.
     */
    val meta: Map<String, RecordMeta> = emptyMap(),
    val schemaVersion: Int = FINANCE_SCHEMA_VERSION,
)

/**
 * Brings a loaded file up to the current shape.
 *
 * Version 1 held categories as a fixed enum on each subscription; that field is dropped on
 * read, so those records land uncategorised and the built-in list is seeded for them to be
 * sorted into again. Version 2 to 3 only added fields, which deserialise to their defaults.
 */
fun FinanceData.migrated(): FinanceData {
    if (schemaVersion >= FINANCE_SCHEMA_VERSION && categories.isNotEmpty()) return this
    return copy(
        categories = categories.ifEmpty { Category.defaults() },
        schemaVersion = FINANCE_SCHEMA_VERSION,
    )
}

/**
 * A brand new dataset, with the starting categories already in place.
 *
 * Every record in it is stamped [Hlc.Zero], a reading that loses every comparison it ever
 * takes part in. That is the whole point: none of this is something the user did. It is a
 * starting point this device invented, and it carries the same fixed ids on every device.
 *
 * Without it, installing the app on a second phone would seed eight categories stamped
 * *now* — newer than the ones already on the server — and the first sync would push them
 * over categories that had been renamed or recoloured, everywhere. The settings record is
 * seeded the same way for the same reason: a default of USD must not replace a chosen EUR.
 *
 * A seeded record that the user then edits is stamped properly by [restamped], because at
 * that point it is something they did.
 */
fun emptyFinanceData(): FinanceData {
    val categories = Category.defaults()
    return FinanceData(
        categories = categories,
        meta = buildMap {
            categories.forEach { put(metaKey(RecordKind.CATEGORY, it.id), RecordMeta(Hlc.Zero)) }
            put(metaKey(RecordKind.SETTINGS, RecordKind.SETTINGS_ID), RecordMeta(Hlc.Zero))
        },
    )
}

/**
 * Gives a clock reading to every record that has none.
 *
 * Files written before sync existed arrive unstamped. They are stamped on load rather than
 * left at [Hlc.Zero]: a record that sorts below everything would lose every merge it ever
 * took part in, and for data whose only copy is this file that is the wrong way to be wrong.
 */
fun FinanceData.stamped(clock: HybridClock): FinanceData {
    val stamps = meta.toMutableMap()

    fun stamp(kind: String, id: String) {
        val key = metaKey(kind, id)
        if (key !in stamps) stamps[key] = RecordMeta(clock.tick())
    }

    subscriptions.forEach { stamp(RecordKind.SUBSCRIPTION, it.id) }
    paymentMethods.forEach { stamp(RecordKind.PAYMENT_METHOD, it.id) }
    categories.forEach { stamp(RecordKind.CATEGORY, it.id) }
    stamp(RecordKind.SETTINGS, RecordKind.SETTINGS_ID)

    return if (stamps.size == meta.size) this else copy(meta = stamps)
}

/**
 * Works out what changed since [previous], giving every altered record a fresh reading and
 * leaving a tombstone behind for every removed one.
 *
 * Compared rather than declared. A mutator that forgot to say what it touched would look
 * entirely normal right up until a sync quietly dropped the edit — and some changes are not
 * the caller's to declare anyway: deleting a payment method rewrites every subscription that
 * pointed at it, and those subscriptions have genuinely changed.
 */
fun FinanceData.restamped(previous: FinanceData, clock: HybridClock): FinanceData {
    val stamps = meta.toMutableMap()

    fun <T> reconcile(kind: String, before: List<T>, after: List<T>, id: (T) -> String) {
        val beforeById = before.associateBy(id)
        val afterById = after.associateBy(id)

        afterById.forEach { (recordId, record) ->
            val key = metaKey(kind, recordId)
            val existing = stamps[key]
            // Never seen, edited, or brought back after a delete: all three are changes.
            if (existing == null || existing.deleted || beforeById[recordId] != record) {
                stamps[key] = RecordMeta(clock.tick())
            }
        }

        (beforeById.keys - afterById.keys).forEach { recordId ->
            stamps[metaKey(kind, recordId)] = RecordMeta(clock.tick(), deleted = true)
        }
    }

    reconcile(RecordKind.SUBSCRIPTION, previous.subscriptions, subscriptions) { it.id }
    reconcile(RecordKind.PAYMENT_METHOD, previous.paymentMethods, paymentMethods) { it.id }
    reconcile(RecordKind.CATEGORY, previous.categories, categories) { it.id }

    val settingsKey = metaKey(RecordKind.SETTINGS, RecordKind.SETTINGS_ID)
    if (settingsKey !in stamps || previous.settings != settings) {
        stamps[settingsKey] = RecordMeta(clock.tick())
    }

    return copy(meta = stamps)
}

/**
 * Re-stamps every record under this device's own node id.
 *
 * A record is recognised as this device's to push by the node id inside its reading. Records
 * written before that id was persisted name a device that existed for one launch, so nothing
 * would ever offer them to the server — they would sit here looking synced and never arrive.
 *
 * Safe to do wholesale because it runs once, on a dataset that has never synced: before the
 * node id was stable there was no engine to send anything, so no reading here came from
 * somewhere else and none of them is known to any server.
 */
fun FinanceData.adoptedBy(clock: HybridClock): FinanceData =
    copy(meta = meta.mapValues { (_, existing) -> existing.copy(hlc = clock.tick()) })
