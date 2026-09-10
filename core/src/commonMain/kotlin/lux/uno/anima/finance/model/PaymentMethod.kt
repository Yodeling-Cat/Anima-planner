package lux.uno.anima.finance.model

import kotlinx.serialization.Serializable

enum class PaymentMethodKind(val label: String, val glyph: String) {
    CARD("Card", "💳"),
    BANK("Bank account", "🏦"),
    WALLET("Wallet", "📱"),
    CASH("Cash", "💵"),
    OTHER("Other", "🔗"),
}

/** A card, account or wallet that subscriptions are charged to. */
@Serializable
data class PaymentMethod(
    val id: String,
    val name: String,
    val kind: PaymentMethodKind = PaymentMethodKind.CARD,
    /** Last four digits, kept purely as a visual hint. Never a full number. */
    val last4: String? = null,
    /** ARGB colour used for the chip; picked in the editor. */
    val colorArgb: Long = 0xFF6C7BFFL,
) {
    val displayName: String get() = if (last4.isNullOrBlank()) name else "$name ·· $last4"
}
