package lux.uno.anima.ui.components

import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import lux.uno.anima.resources.*
import lux.uno.anima.ui.text.formatLong
import lux.uno.anima.ui.theme.AnimaIcons
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import org.jetbrains.compose.resources.stringResource

/**
 * Dates are held as calendar days, so both conversions run in UTC — the picker hands back a
 * midnight-UTC instant, and reading it back in a local zone would shift the day.
 */
@OptIn(ExperimentalTime::class)
fun LocalDate.toPickerMillis(): Long = atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()

@OptIn(ExperimentalTime::class)
fun Long.toPickerDate(): LocalDate =
    Instant.fromEpochMilliseconds(this).toLocalDateTime(TimeZone.UTC).date

/**
 * A row showing the chosen day that opens the calendar when tapped.
 *
 * [earliest] greys out everything before it and trims the year list to match, so a day the
 * field would refuse is never offered in the first place. The caller is responsible for
 * passing a [date] that is not itself before it — the picker would open on a day it cannot
 * select, and Choose would have nothing to hand back.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateField(
    label: String,
    date: LocalDate,
    onDateChange: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
    earliest: LocalDate? = null,
) {
    var open by remember { mutableStateOf(false) }

    PickerRow(
        label = label,
        value = date.formatLong(),
        onClick = { open = true },
        modifier = modifier,
        icon = AnimaIcons.Calendar,
    )

    if (open) {
        val selectable = remember(earliest) {
            val floor = earliest?.toPickerMillis()
            object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                    floor == null || utcTimeMillis >= floor

                override fun isSelectableYear(year: Int): Boolean =
                    earliest == null || year >= earliest.year
            }
        }
        val state = rememberDatePickerState(
            initialSelectedDateMillis = date.toPickerMillis(),
            yearRange = IntRange(
                earliest?.year ?: DatePickerDefaults.YearRange.first,
                DatePickerDefaults.YearRange.last,
            ),
            selectableDates = selectable,
        )
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(
                    // Null while a refused day is showing — typing one into the text field
                    // leaves nothing selected, and closing on that would look like it took.
                    enabled = state.selectedDateMillis != null,
                    onClick = {
                        state.selectedDateMillis?.let { onDateChange(it.toPickerDate()) }
                        open = false
                    },
                ) { Text(stringResource(Res.string.date_field_choose)) }
            },
            dismissButton = {
                TextButton(onClick = { open = false }) { Text(stringResource(Res.string.common_cancel)) }
            },
        ) {
            DatePicker(state = state, showModeToggle = true)
        }
    }
}
