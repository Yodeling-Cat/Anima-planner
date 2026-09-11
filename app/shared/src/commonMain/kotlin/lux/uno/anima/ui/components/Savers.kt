package lux.uno.anima.ui.components

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import kotlinx.datetime.LocalDate
import lux.uno.anima.finance.model.BillingCycle
import lux.uno.anima.finance.model.BillingPeriod
import lux.uno.anima.finance.model.IconRef

/**
 * Savers for the form values that are not already something saved state can hold.
 *
 * Strings, numbers and booleans go into `rememberSaveable` untouched. Everything below has
 * to be flattened to one of those first, because Android tears the activity down and builds
 * it again on every rotation — and a half-filled subscription editor that forgets what was
 * typed is worse than one that never opened.
 *
 * Every `restore` answers null rather than throwing when it meets something it cannot read.
 * Saved state can outlive the code that wrote it, and losing one field to a fresh default
 * beats the screen refusing to open.
 */

/** Stores an enum by name, so reordering the constants cannot change what was saved. */
inline fun <reified T : Enum<T>> enumSaver(): Saver<T, String> = Saver(
    save = { it.name },
    restore = { name -> enumValues<T>().firstOrNull { it.name == name } },
)

/** ISO-8601, which is also what [LocalDate.toString] produces. */
val LocalDateSaver: Saver<LocalDate, String> = Saver(
    save = { it.toString() },
    restore = { runCatching { LocalDate.parse(it) }.getOrNull() },
)

/**
 * The emoji and the image id, in that order.
 *
 * An absent image is written as an empty string because saved state cannot hold a null in a
 * list — and an emoji is never empty, so the two can never be confused.
 */
val IconRefSaver: Saver<IconRef, Any> = listSaver(
    save = { listOf(it.emoji, it.imageId.orEmpty()) },
    restore = { stored ->
        val emoji = stored.getOrNull(0) ?: return@listSaver null
        IconRef(emoji = emoji, imageId = stored.getOrNull(1)?.takeIf { it.isNotEmpty() })
    },
)

/**
 * The period and the interval, both as text so the stored list has one type.
 *
 * The interval is checked before rebuilding: `BillingCycle` refuses anything below 1 in its
 * own constructor, and a saver that throws would turn stale saved state into a crash on
 * rotate rather than a field back at its default.
 */
val BillingCycleSaver: Saver<BillingCycle, Any> = listSaver(
    save = { listOf(it.period.name, it.interval.toString()) },
    restore = { stored ->
        val period = BillingPeriod.entries.firstOrNull { it.name == stored.getOrNull(0) }
        val interval = stored.getOrNull(1)?.toIntOrNull()?.takeIf { it >= 1 }
        if (period == null || interval == null) null else BillingCycle(period, interval)
    },
)
