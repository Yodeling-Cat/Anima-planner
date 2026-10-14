package lux.uno.anima.ui.text

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import lux.uno.anima.finance.model.Money
import lux.uno.anima.finance.model.format
import lux.uno.anima.finance.rates.ExchangeRates
import lux.uno.anima.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * Decides what currency an amount is shown in.
 *
 * Carried through composition rather than passed down, because money is drawn in twenty
 * places across five screens and threading a formatter through all of them would put a
 * parameter on every component that happens to contain a price. The alternative — each site
 * reaching for the settings itself — is how one of them ends up forgetting.
 *
 * Conversion is display only. The stored price is always what the card is actually charged,
 * which is why the editor never asks this: a price round-tripped through a daily reference
 * rate would drift a little every time someone opened the form.
 */
@Immutable
data class MoneyDisplay(
    val preferredCurrency: String,
    val convertAll: Boolean,
    val rates: ExchangeRates?,
) {
    /** [money] in the currency it should be shown in — the original when nothing applies. */
    fun asShown(money: Money): Money {
        if (!convertAll || money.currency == preferredCurrency) return money
        return rates?.convert(money, preferredCurrency) ?: money
    }

    /** True when [asShown] actually moved the amount, so the UI can mark it approximate. */
    fun isConverted(money: Money): Boolean =
        convertAll && money.currency != preferredCurrency && rates?.covers(money.currency) == true

    companion object {
        /** What the app shows before anything is loaded: every amount in its own currency. */
        val AsBilled = MoneyDisplay(preferredCurrency = "", convertAll = false, rates = null)
    }
}

val LocalMoneyDisplay = staticCompositionLocalOf { MoneyDisplay.AsBilled }

/**
 * An amount, ready to draw, marked approximate when it was converted.
 *
 * The marker is not decoration. A converted figure is an estimate against a rate published
 * once a day, and a reader comparing it against a bank statement has to be able to tell the
 * two apart at a glance.
 */
@Composable
fun Money.shown(): String {
    val display = LocalMoneyDisplay.current
    val text = display.asShown(this).format()
    return if (display.isConverted(this)) {
        stringResource(Res.string.money_approximate, text)
    } else {
        text
    }
}

/**
 * The same amount without the marker, for the few places whose surrounding wording already
 * says the figure is an approximation — the monthly equivalent of a yearly plan was never
 * an exact charge either.
 */
@Composable
fun Money.shownBare(): String = LocalMoneyDisplay.current.asShown(this).format()
