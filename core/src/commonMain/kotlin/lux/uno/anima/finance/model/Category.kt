package lux.uno.anima.finance.model

import kotlinx.serialization.Serializable

/**
 * A spending bucket. Categories are data rather than an enum so the list belongs to whoever
 * is using the app — the built-in set below is only a starting point, and every one of them
 * can be renamed, recoloured or deleted.
 */
@Serializable
data class Category(
    val id: String,
    val label: String,
    val glyph: String = "✦",
    val colorArgb: Long = 0xFF6C7BFF,
) {
    companion object {
        /**
         * Shown wherever a subscription has no category, and never stored on a record.
         *
         * Its empty id is what marks it as the stand-in, and what the UI keys off to show the
         * word in the reader's language. The label here is only what a log line would print.
         */
        val Uncategorised = Category(
            id = "",
            label = "Uncategorised",
            glyph = "·",
            colorArgb = 0xFF9C948C,
        )

        /**
         * Seeded on first launch. Nothing depends on these ids afterwards.
         *
         * The labels are English because core has no language. A device seeds the list through
         * `FinanceRepository(seedCategories = ...)`, which is where the UI hands over the same
         * ids, glyphs and colours with the labels translated.
         */
        fun defaults(): List<Category> = listOf(
            Category("cat-entertainment", "Entertainment", "🎬", 0xFFE0504F),
            Category("cat-software", "Software", "🛠", 0xFFB07CF5),
            Category("cat-music", "Music", "🎧", 0xFF3ECF7E),
            Category("cat-health", "Health", "🌿", 0xFF2E6F5E),
            Category("cat-home", "Home", "🏠", 0xFFE0A458),
            Category("cat-finance", "Finance", "📈", 0xFF5AA9E6),
            Category("cat-learning", "Learning", "📚", 0xFF8C4A6B),
            Category("cat-transport", "Transport", "🚗", 0xFF6C7BFF),
        )
    }
}

/** Resolves a stored id, falling back to [Category.Uncategorised] for null or deleted ids. */
fun List<Category>.resolve(id: String?): Category =
    firstOrNull { it.id == id } ?: Category.Uncategorised
