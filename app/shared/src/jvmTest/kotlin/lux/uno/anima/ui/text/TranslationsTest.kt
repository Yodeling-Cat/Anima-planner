package lux.uno.anima.ui.text

import kotlinx.coroutines.test.runTest
import lux.uno.anima.resources.Res
import lux.uno.anima.resources.common_save
import lux.uno.anima.resources.months_long
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.getStringArray
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * That the translations are *found*, rather than what they say.
 *
 * Compose Resources falls back to the default locale in silence, so a misnamed folder or a
 * key that only exists in English looks exactly like a working app until somebody reads it
 * in Slovak. Asserting a particular wording would be a test of the translator; asserting
 * that the wording changes with the locale is a test of the wiring.
 */
class TranslationsTest {

    private val original = Locale.getDefault()

    @AfterTest
    fun restoreLocale() = Locale.setDefault(original)

    @Test
    fun everyLocaleAnswersWithItsOwnWords() = runTest {
        Locale.setDefault(Locale.ENGLISH)
        val english = getString(Res.string.common_save)

        Locale.setDefault(Locale.forLanguageTag("sk"))
        assertNotEquals(english, getString(Res.string.common_save))

        Locale.setDefault(Locale.forLanguageTag("cs"))
        assertNotEquals(english, getString(Res.string.common_save))
    }

    @Test
    fun theMonthNamesAreTranslatedAsAWholeYear() = runTest {
        listOf(Locale.ENGLISH, Locale.forLanguageTag("sk"), Locale.forLanguageTag("cs"))
            .forEach { locale ->
                Locale.setDefault(locale)
                assertEquals(12, getStringArray(Res.array.months_long).size, locale.language)
            }
    }
}
