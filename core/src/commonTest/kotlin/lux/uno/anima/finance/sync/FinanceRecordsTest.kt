package lux.uno.anima.finance.sync

import kotlinx.datetime.LocalDate
import lux.uno.anima.finance.FinanceData
import lux.uno.anima.finance.FinanceSettings
import lux.uno.anima.finance.RecordMeta
import lux.uno.anima.finance.metaKey
import lux.uno.anima.finance.model.Category
import lux.uno.anima.finance.model.Money
import lux.uno.anima.finance.model.PaymentMethod
import lux.uno.anima.finance.model.PricePoint
import lux.uno.anima.finance.model.Subscription
import lux.uno.anima.sync.Hlc
import lux.uno.anima.sync.HybridClock
import lux.uno.anima.sync.RecordKind
import lux.uno.anima.sync.SyncRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FinanceRecordsTest {

    private val today = LocalDate(2026, 9, 11)
    private val earlier = Hlc.of(1_757_000_001_000, 0, "phone")
    private val later = Hlc.of(1_757_000_002_000, 0, "desktop")

    private fun clock(node: String = "here", millis: Long = 1_757_000_003_000) =
        HybridClock(node, wallClockMillis = { millis })

    private fun subscription(
        id: String = "sub-1",
        name: String = "Netflix",
        prices: List<PricePoint> = listOf(PricePoint(today, Money.of(9.99))),
    ) = Subscription(id = id, name = name, anchorDate = today, priceHistory = prices)

    private fun dataWith(
        subscription: Subscription? = null,
        hlc: Hlc = earlier,
    ): FinanceData = FinanceData(
        subscriptions = listOfNotNull(subscription),
        meta = buildMap {
            subscription?.let { put(metaKey(RecordKind.SUBSCRIPTION, it.id), RecordMeta(hlc)) }
        },
    )

    private fun record(
        subscription: Subscription,
        hlc: Hlc,
    ): SyncRecord = dataWith(subscription, hlc).toRecords().single()

    // region outbound

    @Test
    fun `every kind becomes a record`() {
        val data = FinanceData(
            subscriptions = listOf(subscription()),
            paymentMethods = listOf(PaymentMethod(id = "pm-1", name = "Visa")),
            categories = listOf(Category("cat-1", "Software")),
            meta = mapOf(
                metaKey(RecordKind.SUBSCRIPTION, "sub-1") to RecordMeta(earlier),
                metaKey(RecordKind.PAYMENT_METHOD, "pm-1") to RecordMeta(earlier),
                metaKey(RecordKind.CATEGORY, "cat-1") to RecordMeta(earlier),
                metaKey(RecordKind.SETTINGS, RecordKind.SETTINGS_ID) to RecordMeta(earlier),
            ),
        )

        val kinds = data.toRecords().map { it.kind }.toSet()

        assertEquals(
            setOf(
                RecordKind.SUBSCRIPTION,
                RecordKind.PAYMENT_METHOD,
                RecordKind.CATEGORY,
                RecordKind.SETTINGS,
            ),
            kinds,
        )
        assertTrue(data.toRecords().all { it.payload != null })
    }

    @Test
    fun `a tombstone travels with no payload`() {
        val data = FinanceData(
            meta = mapOf(
                metaKey(RecordKind.SUBSCRIPTION, "sub-gone") to RecordMeta(earlier, deleted = true),
            ),
        )

        val record = data.toRecords().single()

        assertTrue(record.deleted)
        assertNull(record.payload)
        assertEquals("sub-gone", record.id)
    }

    @Test
    fun `the push set is what this device stamped and nothing that came back`() {
        // Without this, every record pulled from the server would be pushed straight back.
        val data = FinanceData(
            subscriptions = listOf(subscription("sub-mine"), subscription("sub-theirs")),
            meta = mapOf(
                metaKey(RecordKind.SUBSCRIPTION, "sub-mine") to RecordMeta(Hlc.of(1, 0, "here")),
                metaKey(RecordKind.SUBSCRIPTION, "sub-theirs") to RecordMeta(Hlc.of(2, 0, "elsewhere")),
            ),
        )

        assertEquals(listOf("sub-mine"), data.recordsToPush("here").map { it.id })
    }

    @Test
    fun `already-pushed records are not sent again`() {
        val data = FinanceData(
            subscriptions = listOf(subscription("sub-old"), subscription("sub-new")),
            meta = mapOf(
                metaKey(RecordKind.SUBSCRIPTION, "sub-old") to RecordMeta(Hlc.of(1_000, 0, "here")),
                metaKey(RecordKind.SUBSCRIPTION, "sub-new") to RecordMeta(Hlc.of(3_000, 0, "here")),
            ),
        )

        val pending = data.recordsToPush("here", after = Hlc.of(2_000, 0, "here"))

        assertEquals(listOf("sub-new"), pending.map { it.id })
    }

    // endregion

    // region inbound

    @Test
    fun `a device with nothing takes everything offered`() {
        val source = FinanceData(
            subscriptions = listOf(subscription()),
            paymentMethods = listOf(PaymentMethod(id = "pm-1", name = "Visa", last4 = "4417")),
            categories = listOf(Category("cat-1", "Software", "🛠", 0xFFB07CF5)),
            settings = FinanceSettings(preferredCurrency = "EUR"),
            meta = mapOf(
                metaKey(RecordKind.SUBSCRIPTION, "sub-1") to RecordMeta(earlier),
                metaKey(RecordKind.PAYMENT_METHOD, "pm-1") to RecordMeta(earlier),
                metaKey(RecordKind.CATEGORY, "cat-1") to RecordMeta(earlier),
                metaKey(RecordKind.SETTINGS, RecordKind.SETTINGS_ID) to RecordMeta(earlier),
            ),
        )

        val restored = FinanceData().applying(source.toRecords(), clock())

        assertEquals(source.subscriptions, restored.subscriptions)
        assertEquals(source.paymentMethods, restored.paymentMethods)
        assertEquals(source.categories, restored.categories)
        assertEquals(source.settings, restored.settings)
        assertEquals(source.meta, restored.meta)
    }

    @Test
    fun `a newer version replaces what is held`() {
        val local = dataWith(subscription(name = "Netflix"), earlier)

        val applied = local.applying(
            listOf(record(subscription(name = "Netflix Premium"), later)),
            clock(),
        )

        assertEquals("Netflix Premium", applied.subscriptions.single().name)
    }

    @Test
    fun `an older version is ignored`() {
        val local = dataWith(subscription(name = "edited at the desk"), later)

        val applied = local.applying(
            listOf(record(subscription(name = "edited on the plane"), earlier)),
            clock(),
        )

        assertEquals("edited at the desk", applied.subscriptions.single().name)
        assertEquals(later, applied.meta.getValue(metaKey(RecordKind.SUBSCRIPTION, "sub-1")).hlc)
    }

    @Test
    fun `an incoming tombstone removes the record`() {
        val local = dataWith(subscription(), earlier)

        val applied = local.applying(
            listOf(SyncRecord(RecordKind.SUBSCRIPTION, "sub-1", later, deleted = true)),
            clock(),
        )

        assertTrue(applied.subscriptions.isEmpty())
        assertTrue(applied.meta.getValue(metaKey(RecordKind.SUBSCRIPTION, "sub-1")).deleted)
    }

    @Test
    fun `an edit newer than a delete brings the record back`() {
        val local = FinanceData(
            meta = mapOf(
                metaKey(RecordKind.SUBSCRIPTION, "sub-1") to RecordMeta(earlier, deleted = true),
            ),
        )

        val applied = local.applying(listOf(record(subscription(), later)), clock())

        assertEquals("sub-1", applied.subscriptions.single().id)
        assertTrue(!applied.meta.getValue(metaKey(RecordKind.SUBSCRIPTION, "sub-1")).deleted)
    }

    @Test
    fun `applying advances the clock past what it was shown`() {
        val clock = clock(millis = 1_000)
        val ahead = Hlc.of(1_757_000_900_000, 0, "elsewhere")

        FinanceData().applying(listOf(record(subscription(), ahead)), clock)

        assertTrue(clock.tick() > ahead, "later local edits must sort above what arrived")
    }

    @Test
    fun `a record that will not decode is skipped rather than fatal`() {
        val local = dataWith(subscription(name = "kept"), earlier)

        val applied = local.applying(
            listOf(SyncRecord(RecordKind.SUBSCRIPTION, "sub-1", later, payload = "{ not json")),
            clock(),
        )

        assertEquals("kept", applied.subscriptions.single().name)
        // Left unrecorded, so a later sync can offer it again.
        assertEquals(earlier, applied.meta.getValue(metaKey(RecordKind.SUBSCRIPTION, "sub-1")).hlc)
    }

    @Test
    fun `a kind this build does not know is left alone`() {
        val applied = FinanceData().applying(
            listOf(SyncRecord("budget", "budget-1", later, payload = """{"limit":100}""")),
            clock(),
        )

        assertEquals(emptyMap(), applied.meta)
    }

    @Test
    fun `settings cannot be deleted`() {
        val local = FinanceData(settings = FinanceSettings(preferredCurrency = "GBP"))

        val applied = local.applying(
            listOf(SyncRecord(RecordKind.SETTINGS, RecordKind.SETTINGS_ID, later, deleted = true)),
            clock(),
        )

        assertEquals("GBP", applied.settings.preferredCurrency)
    }

    // endregion

    // region the price history exception

    @Test
    fun `a price recorded on the other device survives a losing merge`() {
        // Both offline. The phone records a rise, the desktop records a different one and
        // also renames. The desktop edit is newer, so plain last-write-wins would keep the
        // rename and silently drop the phone's price point.
        val onPhone = subscription(
            prices = listOf(
                PricePoint(today, Money.of(9.99)),
                PricePoint(LocalDate(2026, 10, 1), Money.of(11.99)),
            ),
        )
        val onDesktop = subscription(
            name = "Netflix Premium",
            prices = listOf(
                PricePoint(today, Money.of(9.99)),
                PricePoint(LocalDate(2026, 11, 1), Money.of(13.99)),
            ),
        )

        val applied = dataWith(onPhone, earlier).applying(listOf(record(onDesktop, later)), clock())

        val merged = applied.subscriptions.single()
        assertEquals("Netflix Premium", merged.name, "the newer edit still wins the record")
        assertEquals(
            listOf(LocalDate(2026, 9, 11), LocalDate(2026, 10, 1), LocalDate(2026, 11, 1)),
            merged.priceHistory.map { it.effectiveFrom },
            "both devices' price points have to survive",
        )
    }

    @Test
    fun `a union this device alone holds is stamped so it goes back out`() {
        val onPhone = subscription(
            prices = listOf(PricePoint(LocalDate(2026, 10, 1), Money.of(11.99))),
        )
        val onDesktop = subscription(
            name = "Renamed",
            prices = listOf(PricePoint(LocalDate(2026, 11, 1), Money.of(13.99))),
        )
        val clock = clock(node = "here")

        val applied = dataWith(onPhone, earlier).applying(listOf(record(onDesktop, later)), clock)

        val stamp = applied.meta.getValue(metaKey(RecordKind.SUBSCRIPTION, "sub-1"))
        assertTrue(stamp.hlc > later, "the merged version is newer than either side")
        assertEquals("here", stamp.hlc.nodeId, "and is this device's to push")
        assertTrue(applied.recordsToPush("here").any { it.id == "sub-1" })
    }

    @Test
    fun `a plain edit is not restamped`() {
        // Only a union that actually added something needs pushing back; otherwise every
        // pull would bounce straight back to the server.
        val same = listOf(PricePoint(today, Money.of(9.99)))
        val clock = clock(node = "here")

        val applied = dataWith(subscription(prices = same), earlier)
            .applying(listOf(record(subscription(name = "Renamed", prices = same), later)), clock)

        assertEquals(later, applied.meta.getValue(metaKey(RecordKind.SUBSCRIPTION, "sub-1")).hlc)
        assertTrue(applied.recordsToPush("here").isEmpty())
    }

    @Test
    fun `the winning record decides a same-day collision`() {
        val day = LocalDate(2026, 10, 1)
        val onPhone = subscription(prices = listOf(PricePoint(day, Money.of(11.99), "phone")))
        val onDesktop = subscription(prices = listOf(PricePoint(day, Money.of(12.99), "desktop")))

        val applied = dataWith(onPhone, earlier).applying(listOf(record(onDesktop, later)), clock())

        val point = applied.subscriptions.single().priceHistory.single()
        assertEquals("desktop", point.note)
        assertEquals(Money.of(12.99), point.price)
    }

    @Test
    fun `both devices end up holding the same history`() {
        // The property that matters: after exchanging records, neither device is missing
        // anything the other had.
        val phoneOnly = PricePoint(LocalDate(2026, 10, 1), Money.of(11.99))
        val desktopOnly = PricePoint(LocalDate(2026, 11, 1), Money.of(13.99))
        val base = PricePoint(today, Money.of(9.99))

        val phone = dataWith(subscription(prices = listOf(base, phoneOnly)), earlier)
        val desktop = dataWith(subscription(name = "Renamed", prices = listOf(base, desktopOnly)), later)

        val phoneAfter = phone.applying(desktop.toRecords(), clock("phone"))
        val desktopAfter = desktop.applying(phoneAfter.toRecords(), clock("desktop"))

        val expected = listOf(base, phoneOnly, desktopOnly).map { it.effectiveFrom }
        assertEquals(expected, phoneAfter.subscriptions.single().priceHistory.map { it.effectiveFrom })
        assertEquals(expected, desktopAfter.subscriptions.single().priceHistory.map { it.effectiveFrom })
    }

    // endregion

    @Test
    fun `an id containing a colon still round trips`() {
        // Record ids are allowed one, and the key is split on the first colon only.
        val data = FinanceData(
            categories = listOf(Category("cat:odd", "Odd")),
            meta = mapOf(metaKey(RecordKind.CATEGORY, "cat:odd") to RecordMeta(earlier)),
        )

        val record = data.toRecords().single()

        assertEquals(RecordKind.CATEGORY, record.kind)
        assertEquals("cat:odd", record.id)
        assertEquals(
            "Odd",
            assertNotNull(FinanceData().applying(listOf(record), clock()).categories.singleOrNull()).label,
        )
    }
}
