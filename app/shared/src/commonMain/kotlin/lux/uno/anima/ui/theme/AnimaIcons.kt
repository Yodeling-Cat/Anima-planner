package lux.uno.anima.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * A small hand-drawn line-icon set. Defining them here keeps the whole app on one stroke
 * weight and avoids pulling in the full Material icon font for a dozen glyphs.
 */
object AnimaIcons {

    val ArrowLeft = stroked("ArrowLeft") {
        moveTo(20f, 12f); lineTo(4f, 12f)
        moveTo(10.5f, 5.5f); lineTo(4f, 12f); lineTo(10.5f, 18.5f)
    }

    val Plus = stroked("Plus") {
        moveTo(12f, 5f); lineTo(12f, 19f)
        moveTo(5f, 12f); lineTo(19f, 12f)
    }

    val Check = stroked("Check") {
        moveTo(5f, 12.5f); lineTo(9.8f, 17.5f); lineTo(19f, 6.5f)
    }

    val Close = stroked("Close") {
        moveTo(6.5f, 6.5f); lineTo(17.5f, 17.5f)
        moveTo(17.5f, 6.5f); lineTo(6.5f, 17.5f)
    }

    val ChevronRight = stroked("ChevronRight") {
        moveTo(9.5f, 5f); lineTo(16.5f, 12f); lineTo(9.5f, 19f)
    }

    val ChevronDown = stroked("ChevronDown") {
        moveTo(5f, 9.5f); lineTo(12f, 16.5f); lineTo(19f, 9.5f)
    }

    val Trash = stroked("Trash") {
        moveTo(4f, 7f); lineTo(20f, 7f)
        moveTo(9.5f, 7f); lineTo(9.5f, 4f); lineTo(14.5f, 4f); lineTo(14.5f, 7f)
        moveTo(6.5f, 7f); lineTo(7.5f, 20f); lineTo(16.5f, 20f); lineTo(17.5f, 7f)
        moveTo(10.5f, 10.5f); lineTo(10.5f, 16.5f)
        moveTo(13.5f, 10.5f); lineTo(13.5f, 16.5f)
    }

    val Pencil = stroked("Pencil") {
        moveTo(4f, 20f); lineTo(4.8f, 15.6f); lineTo(15.8f, 4.6f); lineTo(19.4f, 8.2f); lineTo(8.4f, 19.2f); close()
        moveTo(13.6f, 6.8f); lineTo(17.2f, 10.4f)
    }

    val Calendar = stroked("Calendar") {
        moveTo(4f, 6.5f); lineTo(20f, 6.5f); lineTo(20f, 20f); lineTo(4f, 20f); close()
        moveTo(4f, 10.5f); lineTo(20f, 10.5f)
        moveTo(8.5f, 3.5f); lineTo(8.5f, 8f)
        moveTo(15.5f, 3.5f); lineTo(15.5f, 8f)
    }

    val Card = stroked("Card") {
        moveTo(3f, 6.5f); lineTo(21f, 6.5f); lineTo(21f, 17.5f); lineTo(3f, 17.5f); close()
        moveTo(3f, 10.5f); lineTo(21f, 10.5f)
        moveTo(6.5f, 14.5f); lineTo(10f, 14.5f)
    }

    val TrendUp = stroked("TrendUp") {
        moveTo(3.5f, 16.5f); lineTo(9.5f, 10.5f); lineTo(13.5f, 14.5f); lineTo(20.5f, 7.5f)
        moveTo(15f, 7.5f); lineTo(20.5f, 7.5f); lineTo(20.5f, 13f)
    }

    val Bell = stroked("Bell") {
        moveTo(6f, 17f); lineTo(18f, 17f)
        moveTo(7f, 17f); lineTo(7f, 10.5f)
        arcTo(5f, 5f, 0f, isMoreThanHalf = true, isPositiveArc = true, 17f, 10.5f)
        lineTo(17f, 17f)
        moveTo(10.2f, 20f); lineTo(13.8f, 20f)
    }

    val Sparkline = stroked("Sparkline") {
        moveTo(3.5f, 19f); lineTo(3.5f, 5f)
        moveTo(3.5f, 19f); lineTo(20.5f, 19f)
        moveTo(6.5f, 15.5f); lineTo(11f, 11f); lineTo(14f, 13.5f); lineTo(19.5f, 7.5f)
    }

    val Sliders = stroked("Sliders") {
        moveTo(4f, 7f); lineTo(20f, 7f)
        moveTo(4f, 12f); lineTo(20f, 12f)
        moveTo(4f, 17f); lineTo(20f, 17f)
        circle(9f, 7f, 2.1f)
        circle(15.5f, 12f, 2.1f)
        circle(8f, 17f, 2.1f)
    }

    val Pause = stroked("Pause") {
        moveTo(9f, 5f); lineTo(9f, 19f)
        moveTo(15f, 5f); lineTo(15f, 19f)
    }

    val Play = stroked("Play") {
        moveTo(7.5f, 4.5f); lineTo(19f, 12f); lineTo(7.5f, 19.5f); close()
    }

    val Person = stroked("Person") {
        // A head and the top of a pair of shoulders, drawn as arcs so it stays a line icon
        // at the same weight as the rest.
        moveTo(12f, 4.5f)
        arcToRelative(3.6f, 3.6f, 0f, true, true, -0.01f, 0f)
        moveTo(4.8f, 19.5f)
        arcToRelative(7.2f, 7.2f, 0f, false, true, 14.4f, 0f)
    }

    val Home = stroked("Home") {
        moveTo(3.5f, 10.5f); lineTo(12f, 3.5f); lineTo(20.5f, 10.5f)
        moveTo(5.5f, 9f); lineTo(5.5f, 20f); lineTo(18.5f, 20f); lineTo(18.5f, 9f)
    }

    val Wallet = stroked("Wallet") {
        moveTo(3.5f, 7f); lineTo(3.5f, 18.5f); lineTo(20.5f, 18.5f); lineTo(20.5f, 9.5f); lineTo(3.5f, 9.5f)
        moveTo(3.5f, 7f); lineTo(16f, 5f); lineTo(16f, 9.5f)
        circle(16.5f, 14f, 1.2f)
    }
}

/** Draws a full circle, used inside the icon path DSL. */
private fun PathBuilder.circle(centerX: Float, centerY: Float, radius: Float) {
    moveTo(centerX + radius, centerY)
    arcTo(radius, radius, 0f, isMoreThanHalf = true, isPositiveArc = true, centerX - radius, centerY)
    arcTo(radius, radius, 0f, isMoreThanHalf = true, isPositiveArc = true, centerX + radius, centerY)
}

/**
 * Icons are stroked rather than filled, and drawn in black so `Icon`'s tint takes over
 * completely at the call site.
 */
private fun stroked(name: String, block: PathBuilder.() -> Unit): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.7f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathBuilder = block,
        )
    }.build()
