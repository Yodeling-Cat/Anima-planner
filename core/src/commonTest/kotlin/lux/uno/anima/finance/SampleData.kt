package lux.uno.anima.finance

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import lux.uno.anima.finance.model.BillingCycle
import lux.uno.anima.finance.model.Category
import lux.uno.anima.finance.model.IconRef
import lux.uno.anima.finance.model.Money
import lux.uno.anima.finance.model.PaymentMethod
import lux.uno.anima.finance.model.PaymentMethodKind
import lux.uno.anima.finance.model.PricePoint
import lux.uno.anima.finance.model.Subscription
import lux.uno.anima.finance.model.Trial

/**
 * A furnished dataset that touches every field of every record.
 *
 * It used to be offered from the empty state as "fill it with sample data", which was
 * removed: a button that writes a dozen fabricated subscriptions into a real person's
 * finances is one mis-tap from a mess they have to unpick by hand, and on a synced install
 * that mess reaches their other devices before they notice.
 *
 * It lives in `commonTest` now so it cannot be shipped by accident. The breadth is what
 * makes it worth keeping: a round trip through it is the widest serialization check there
 * is, and narrowing it would quietly narrow that.
 */
object SampleData {

    fun build(today: LocalDate = today(), currency: String = "USD"): FinanceData {
        val personalCard = PaymentMethod(
            id = "pm-personal-visa",
            name = "Personal Visa",
            kind = PaymentMethodKind.CARD,
            last4 = "4417",
            colorArgb = 0xFF6C7BFF,
        )
        val revolut = PaymentMethod(
            id = "pm-revolut",
            name = "Revolut",
            kind = PaymentMethodKind.WALLET,
            last4 = "9082",
            colorArgb = 0xFF3ECFB2,
        )
        val bank = PaymentMethod(
            id = "pm-bank",
            name = "Main account",
            kind = PaymentMethodKind.BANK,
            colorArgb = 0xFFE0A458,
        )

        fun money(major: Double) = Money.of(major, currency)

        val subscriptions = listOf(
            Subscription(
                id = "sub-netflix",
                name = "Netflix",
                icon = IconRef("🎬"),
                colorArgb = 0xFFE0504F,
                cycle = BillingCycle.Monthly,
                anchorDate = today.minus(11, DateTimeUnit.MONTH).plus(4, DateTimeUnit.DAY),
                priceHistory = listOf(
                    PricePoint(today.minus(26, DateTimeUnit.MONTH), money(13.99), "Standard plan"),
                    PricePoint(today.minus(14, DateTimeUnit.MONTH), money(15.49), "Annual increase"),
                    PricePoint(today.minus(3, DateTimeUnit.MONTH), money(17.99), "Ads-free tier rise"),
                ),
                paymentMethodId = personalCard.id,
                categoryId = "cat-entertainment",
                notes = "Shared with family — worth reviewing in the new year.",
            ),
            Subscription(
                id = "sub-spotify",
                name = "Spotify",
                icon = IconRef("🎧"),
                colorArgb = 0xFF3ECF7E,
                cycle = BillingCycle.Monthly,
                anchorDate = today.minus(2, DateTimeUnit.MONTH).plus(18, DateTimeUnit.DAY),
                priceHistory = listOf(
                    PricePoint(today.minus(30, DateTimeUnit.MONTH), money(9.99)),
                    PricePoint(today.minus(8, DateTimeUnit.MONTH), money(11.99), "Duo plan"),
                ),
                paymentMethodId = revolut.id,
                categoryId = "cat-music",
            ),
            Subscription(
                id = "sub-icloud",
                name = "iCloud+ 2TB",
                icon = IconRef("☁️"),
                colorArgb = 0xFF5AA9E6,
                cycle = BillingCycle.Monthly,
                anchorDate = today.minus(1, DateTimeUnit.MONTH).plus(2, DateTimeUnit.DAY),
                priceHistory = listOf(PricePoint(today.minus(19, DateTimeUnit.MONTH), money(9.99))),
                paymentMethodId = personalCard.id,
                categoryId = "cat-software",
            ),
            Subscription(
                id = "sub-jetbrains",
                name = "JetBrains All Products",
                icon = IconRef("🛠"),
                colorArgb = 0xFFB07CF5,
                cycle = BillingCycle.Yearly,
                anchorDate = today.minus(1, DateTimeUnit.YEAR).plus(21, DateTimeUnit.DAY),
                priceHistory = listOf(
                    PricePoint(today.minus(3, DateTimeUnit.YEAR), money(289.00)),
                    PricePoint(today.minus(1, DateTimeUnit.YEAR), money(249.00), "Loyalty discount"),
                ),
                paymentMethodId = bank.id,
                categoryId = "cat-software",
            ),
            Subscription(
                id = "sub-gym",
                name = "Climbing gym",
                icon = IconRef("🧗"),
                colorArgb = 0xFFE0A458,
                cycle = BillingCycle.Monthly,
                anchorDate = today.plus(6, DateTimeUnit.DAY),
                priceHistory = listOf(PricePoint(today.minus(7, DateTimeUnit.MONTH), money(62.00))),
                paymentMethodId = bank.id,
                categoryId = "cat-health",
            ),
            Subscription(
                id = "sub-nyt",
                name = "NYT Cooking",
                icon = IconRef("🍳"),
                colorArgb = 0xFFE07A5F,
                cycle = BillingCycle(period = lux.uno.anima.finance.model.BillingPeriod.WEEK, interval = 1),
                anchorDate = today.plus(2, DateTimeUnit.DAY),
                priceHistory = listOf(PricePoint(today.minus(20, DateTimeUnit.DAY), money(1.25))),
                paymentMethodId = revolut.id,
                categoryId = "cat-learning",
            ),
            Subscription(
                id = "sub-notion-ai",
                name = "Notion AI",
                icon = IconRef("✨"),
                colorArgb = 0xFF8C8C8C,
                cycle = BillingCycle.Monthly,
                anchorDate = today.plus(4, DateTimeUnit.DAY),
                priceHistory = listOf(PricePoint(today.minus(10, DateTimeUnit.DAY), money(10.00))),
                trial = Trial(endsOn = today.plus(4, DateTimeUnit.DAY), remindDaysBefore = 5),
                paymentMethodId = personalCard.id,
                categoryId = "cat-software",
                notes = "Decide before the trial converts.",
            ),
        )

        return FinanceData(
            subscriptions = subscriptions,
            paymentMethods = listOf(personalCard, revolut, bank),
            categories = Category.defaults(),
            settings = FinanceSettings(preferredCurrency = currency),
        )
    }
}
