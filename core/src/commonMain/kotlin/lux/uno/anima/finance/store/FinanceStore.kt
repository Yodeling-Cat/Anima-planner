package lux.uno.anima.finance.store

import lux.uno.anima.finance.FinanceData

/** Somewhere the finance dataset can be kept between launches. */
interface FinanceStore {
    suspend fun load(): FinanceData?
    suspend fun save(data: FinanceData)
}

/** Store used by tests and previews — holds the data for the lifetime of the process only. */
class InMemoryFinanceStore(private var data: FinanceData? = null) : FinanceStore {
    override suspend fun load(): FinanceData? = data
    override suspend fun save(data: FinanceData) {
        this.data = data
    }
}
