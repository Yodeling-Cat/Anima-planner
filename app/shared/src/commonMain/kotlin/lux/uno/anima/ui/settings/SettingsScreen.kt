package lux.uno.anima.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import lux.uno.anima.finance.model.Currency
import lux.uno.anima.finance.rates.RatesProblem
import lux.uno.anima.finance.rates.RatesStatus
import lux.uno.anima.resources.*
import lux.uno.anima.ui.components.AnimaTopBar
import lux.uno.anima.ui.components.ChoiceChip
import lux.uno.anima.ui.components.FormSection
import lux.uno.anima.ui.components.GhostButton
import lux.uno.anima.ui.components.ScreenSurface
import lux.uno.anima.ui.components.ToggleRow
import lux.uno.anima.ui.text.formatLong
import lux.uno.anima.ui.theme.AnimaTheme
import org.jetbrains.compose.resources.stringResource

/** Everything the settings screen needs, so it stays free of wiring. */
data class SettingsState(
    val preferredCurrency: String,
    val convertAllAmounts: Boolean,
    /** The currencies the pickers offer, in [Currency] order. Never empty. */
    val enabledCurrencies: List<Currency>,
    val rates: RatesStatus,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    state: SettingsState,
    onPreferredCurrency: (String) -> Unit,
    onCurrencyEnabled: (String, Boolean) -> Unit,
    onConvertAllAmounts: (Boolean) -> Unit,
    onRefreshRates: () -> Unit,
    /** Null where a rail is showing, which is the way back to the dashboard instead. */
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    ScreenSurface(modifier) { contentModifier ->
        Column(contentModifier.safeDrawingPadding().fillMaxSize()) {
            AnimaTopBar(title = stringResource(Res.string.settings_title), onBack = onBack)

            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(26.dp),
            ) {
                FormSection(stringResource(Res.string.settings_currencies)) {
                    Text(
                        stringResource(Res.string.settings_currencies_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = AnimaTheme.palette.muted,
                    )
                    CurrencyChips {
                        Currency.entries.forEach { currency ->
                            val enabled = currency in state.enabledCurrencies
                            ChoiceChip(
                                label = currency.code,
                                leading = currency.symbol,
                                selected = enabled,
                                onClick = { onCurrencyEnabled(currency.code, !enabled) },
                            )
                        }
                    }
                    // Said only where it bites. Explaining the rule while ten chips are on
                    // is a warning about something nobody is trying to do.
                    if (state.enabledCurrencies.size == 1) {
                        Text(
                            stringResource(Res.string.settings_currencies_last),
                            style = MaterialTheme.typography.bodySmall,
                            color = AnimaTheme.palette.faint,
                        )
                    }
                }

                FormSection(stringResource(Res.string.settings_currency)) {
                    Text(
                        stringResource(Res.string.settings_currency_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = AnimaTheme.palette.muted,
                    )
                    CurrencyChips {
                        state.enabledCurrencies.forEach { currency ->
                            ChoiceChip(
                                label = currency.code,
                                leading = currency.symbol,
                                selected = currency.code == state.preferredCurrency,
                                onClick = { onPreferredCurrency(currency.code) },
                            )
                        }
                    }
                }

                FormSection(stringResource(Res.string.settings_conversion)) {
                    ToggleRow(
                        title = stringResource(
                            Res.string.settings_convert_all,
                            state.preferredCurrency,
                        ),
                        subtitle = stringResource(Res.string.settings_convert_all_body),
                        checked = state.convertAllAmounts,
                        onCheckedChange = onConvertAllAmounts,
                    )
                    RatesLine(state.rates)
                    if (state.rates.problem != RatesProblem.NOT_CONFIGURED) {
                        GhostButton(
                            label = stringResource(Res.string.settings_rates_refresh),
                            onClick = onRefreshRates,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Chips wrap into rows rather than scrolling sideways: a list you are choosing from should
 * show every option at once.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CurrencyChips(content: @Composable FlowRowScope.() -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        content = content,
    )
}

/**
 * Where the numbers come from and how old they are.
 *
 * Shown even when everything is working, because a converted figure is an estimate and the
 * date is what lets someone judge it. A failure says so without hiding the rates it still
 * holds — those are what the app is converting with right now.
 */
@Composable
private fun RatesLine(status: RatesStatus) {
    val held = status.rates
    val text = when {
        status.refreshing -> stringResource(Res.string.settings_rates_refreshing)
        status.problem == RatesProblem.NOT_CONFIGURED ->
            stringResource(Res.string.settings_rates_unavailable)
        held == null -> stringResource(Res.string.settings_rates_none)
        status.problem != null -> stringResource(
            Res.string.settings_rates_stale,
            held.date.formatLong(),
        )
        else -> stringResource(
            Res.string.settings_rates_from,
            held.date.formatLong(),
        )
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = if (status.problem != null && status.problem != RatesProblem.NOT_CONFIGURED) {
            AnimaTheme.palette.warning
        } else {
            AnimaTheme.palette.muted
        },
    )
}
