package lux.uno.anima.finance

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import lux.uno.anima.finance.model.CardExpiry
import lux.uno.anima.finance.model.PaymentMethod
import lux.uno.anima.finance.model.PaymentMethodKind
import lux.uno.anima.finance.store.InMemoryFinanceStore
import lux.uno.anima.sync.HybridClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The sample dataset exercises every field on every record, so round-tripping it is the
 * cheapest guard against a serializer that silently stops matching the model.
 */
class FinanceDataSerializationTest {

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }
    private val today = LocalDate(2026, 9, 11)

    @Test
    fun sampleDataSurvivesARoundTrip() {
        val original = SampleData.build(today)
        val text = json.encodeToString(FinanceData.serializer(), original)
        val restored = json.decodeFromString(FinanceData.serializer(), text)

        assertEquals(original, restored)
        assertTrue(restored.subscriptions.isNotEmpty())
        assertTrue(restored.categories.isNotEmpty())
    }

    @Test
    fun cardExpirySurvivesARoundTrip() {
        val data = FinanceData(
            paymentMethods = listOf(
                PaymentMethod(
                    id = "pm-visa",
                    name = "Personal Visa",
                    kind = PaymentMethodKind.CARD,
                    last4 = "4417",
                    expiry = CardExpiry(year = 2026, month = 9),
                ),
                PaymentMethod(id = "pm-bank", name = "Main account", kind = PaymentMethodKind.BANK),
            ),
        )

        val restored = json.decodeFromString(
            FinanceData.serializer(),
            json.encodeToString(FinanceData.serializer(), data),
        )

        assertEquals(data, restored)
        assertEquals(CardExpiry(2026, 9), restored.paymentMethods.first().expiry)
        assertEquals(null, restored.paymentMethods[1].expiry)
    }

    @Test
    fun loadingSampleDataThroughTheRepositoryPersistsIt() = runTest {
        val store = InMemoryFinanceStore()
        val repository = FinanceRepository(store, this, clock = HybridClock("test-device"))
        repository.load()

        repository.replaceAll(SampleData.build(today))
        testScheduler.runCurrent()

        val saved = assertNotNull(store.load())
        assertEquals(FINANCE_SCHEMA_VERSION, saved.schemaVersion)
        assertTrue(saved.subscriptions.isNotEmpty())
    }
}
