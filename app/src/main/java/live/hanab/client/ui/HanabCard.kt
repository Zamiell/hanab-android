package live.hanab.client.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import live.hanab.client.AppSettings
import live.hanab.client.game.Variants
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

// The front of a card as hanab.live draws it (packages/client/src/game/ui/drawCards.ts in the Hanabi-Live
// repository, GPL-3.0): a white card tinted with the suit colour, the rank in the top-left and bottom-right
// corners, and suit symbols laid out by rank. Everything is drawn in the site's own 286 x 406 units and scaled.
private const val CARD_W = 286f
private const val CARD_H = 406f

/** How see-through a card that is no longer needed is (the site's CARD_FADE). */
private const val CARD_FADE = 0.6f

/**
 * How much darker a card is drawn than the site draws it, because our table is darker than the site's green one
 * (a pale card looks brighter against a dark surround). It keeps the contrast ratio between the card and the table
 * what it is on the site, with relative luminance Y and the WCAG ratio (Y + 0.05) / (Ytable + 0.05):
 * the site's table is (13, 43, 17), Y = 0.0185; ours is (15, 18, 29), Y = 0.0062; a pale card has Y of about 0.8.
 * The card's Y must then be (0.8 + 0.05) * (0.0062 + 0.05) / (0.0185 + 0.05) - 0.05 = 0.65, which is 0.81 of its
 * 0.8 and, as sRGB values, 0.81 ^ (1 / 2.2) = 0.91 of them.
 */
private const val TABLE_DARKEN = 0.91f

/** One suit symbol is about this many units across before it is scaled. */
private const val PIP_BOX = 120f

/** Blur of the dark glow behind numbers and symbols, in the site's units. */
private const val GLOW = 4f

private fun fillsOf(suit: String): List<Color> {
    val info = Variants.info(suit)
    return info.fills.map { Color(it) }.ifEmpty { listOf(Color(0xFF616161)) }
}

/** The suit colour as the site paints with it: one colour, or an even gradient for Rainbow and the like. */
private fun brushFor(fills: List<Color>, x0: Float, y0: Float, x1: Float, y1: Float): Brush =
    if (fills.size > 1) Brush.linearGradient(*fills.mapIndexed { i, c -> i / (fills.size - 1f) to c }.toTypedArray(), start = Offset(x0, y0), end = Offset(x1, y1))
    else Brush.linearGradient(listOf(fills[0], fills[0]))

private fun roundedRadius() = CARD_W * 0.08f

/**
 * A card front: [suit] and [rank] are what is known of the card (-1 for unknown). Known suit only gives a faint
 * large symbol, known rank only a white card with the number, and neither the grey blank card. [dull] fades a card
 * that is no longer needed. [wash] is the colour of a prism card (it depends on the rank), or null.
 */
@Composable
fun HanabCardFace(suit: String?, rank: Int, dull: Boolean, modifier: Modifier = Modifier, wash: Color? = null) {
    val measurer = rememberTextMeasurer()
    val pips = PipSettings.enabled
    Canvas(modifier.graphicsLayer { alpha = if (dull) CARD_FADE else 1f; compositingStrategy = CompositingStrategy.Offscreen }) {
        // Fit the site's card shape inside the box (it is a little narrower), centred.
        val s = minOf(size.width / CARD_W, size.height / CARD_H)
        withTransform({ translate((size.width - CARD_W * s) / 2, (size.height - CARD_H * s) / 2); scale(s, s, pivot = Offset.Zero) }) {
            drawCardFace(measurer, suit, rank, pips, wash)
        }
        // Darken only where the card was drawn (its rounded shape and glow), not the table around it.
        drawRect(Color.Black.copy(alpha = 1f - TABLE_DARKEN), blendMode = BlendMode.SrcAtop)
    }
}
    


private fun DrawScope.drawCardFace(measurer: TextMeasurer, suit: String?, rank: Int, pips: Boolean, wash: Color?) {
    val alpha = 1f
    val radius = CornerRadius(roundedRadius())
    val inset = 4f
    val inner = Size(CARD_W - 2 * inset, CARD_H - 2 * inset)
    // The white card.
    drawRoundRect(Color.White, Offset(inset, inset), inner, radius)

    if (suit == null && rank < 0) {
        // Nothing known: the blank grey card.
        drawRoundRect(Color.Black, Offset(inset, inset), inner, radius, alpha = 0.5f)
        drawRoundRect(Color.Black, Offset(inset, inset), inner, radius, alpha = 0.6f, style = Stroke(8f))
        return
    }

    val shown = suit ?: "Unknown"
    val fills = fillsOf(shown)
    // A prism card is washed with the colour of its rank; its symbols keep the suit's own grey.
    val washFills = if (wash != null) listOf(wash) else fills
    val tint = brushFor(washFills, 0f, 0f, CARD_W, CARD_H)
    // The two-colour corner triangles of suits that more than one colour clue touches go under the tint.
    val clueColors = Variants.info(shown).colors
    if (suit != null && clueColors.size > 1 && !Variants.info(shown).allColors) drawDualColorTriangles(clueColors)
    // A faint wash of the suit colour with a thin edge of it, as the site draws.
    drawRoundRect(tint, Offset(inset, inset), inner, radius, alpha = 0.3f * alpha)
    drawRoundRect(tint, Offset(inset, inset), inner, radius, alpha = 0.7f * alpha, style = Stroke(8f))

    val numberBrush = brushFor(washFills, 0f, 14f, 0f, 110f)
    if (rank in 1..5 || rank == live.hanab.client.game.START_RANK) {
        val label = live.hanab.client.game.cardRankText(rank)
        drawRank(measurer, label, numberBrush, alpha)
        withTransform({ translate(CARD_W, CARD_H); rotate(180f, Offset.Zero) }) {
            drawRank(measurer, label, numberBrush, alpha)
        }
    }
    if (suit != null && pips) drawSuitPips(suit, rank, fills, alpha)
}

private fun DrawScope.drawRank(measurer: TextMeasurer, label: String, brush: Brush, alpha: Float) {
    // 96pt bold in the site's units (1 unit = 1 sp here); the baseline sits 110 units down and the text starts 19 in.
    val layout = measurer.measure(
        label,
        TextStyle(fontSize = 128.sp, fontWeight = FontWeight.Bold),
        density = Density(1f, 1f),
    )
    val at = Offset(19f, 110f - layout.firstBaseline)
    // The site gives numbers and symbols a soft dark glow (shadowBlur 10, black at 90%), which takes the edge off
    // their colour. The layout is shared between draws and remembers its last style, so each draw sets its own.
    val glow = Shadow(Color.Black.copy(alpha = 0.9f), Offset.Zero, blurRadius = GLOW)
    drawText(layout, color = Color.Black, topLeft = at, alpha = alpha, shadow = glow, drawStyle = Fill)
    // The outline goes under the fill: this font's digits are built from overlapping shapes, and a stroke on top of
    // the fill would draw the inner edges (a line through the 4).
    drawText(layout, color = Color.Black, topLeft = at, alpha = alpha, shadow = null, drawStyle = Stroke(3.5f, join = StrokeJoin.Round))
    drawText(layout, brush = brush, topLeft = at, alpha = alpha, shadow = null, drawStyle = Fill)
}

private fun DrawScope.drawDualColorTriangles(clueColors: List<String>) {
    val count = clueColors.size
    val size = 50f
    val border = 8f
    val slice = (Math.PI / 2 / count).toFloat()
    fun corner(cx: Float, cy: Float, start: Float, direction: Int) {
        clueColors.forEachIndexed { i, name ->
            val t1 = start + i * slice * direction
            val t2 = start + (i + 1) * slice * direction
            fun r(t: Float) = size / (abs(cos(t)) + abs(sin(t)))
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(cx, cy)
                lineTo(cx + cos(t1) * r(t1), cy + sin(t1) * r(t1))
                lineTo(cx + cos(t2) * r(t2), cy + sin(t2) * r(t2))
                close()
            }
            drawPath(path, fillsOf(name).first())
            drawPath(path, Color.Black, style = Stroke(1f))
        }
    }
    val pi = Math.PI.toFloat()
    corner(CARD_W - border, border, pi, -1) // top-right
    corner(border, CARD_H - border, 1.5f * pi, 1) // bottom-left
}

/** The suit symbols, laid out by rank as on the site. */
private fun DrawScope.drawSuitPips(suit: String, rank: Int, fills: List<Color>, alpha: Float) {
    val brush = brushFor(fills, 0f, -50f, 15f, 50f)
    val cx = CARD_W / 2
    val cy = CARD_H / 2
    fun pip(dx: Float, dy: Float, factor: Float, upsideDown: Boolean = false, a: Float = alpha) =
        drawPip(suit, cx + dx, cy + dy, PIP_BOX * 0.4f * factor, if (upsideDown) 180f else 0f, brush, a, fills.size > 1)
    when (rank) {
        1 -> pip(0f, 0f, 1.8f)
        2 -> {
            pip(0f, -90f, 1.4f)
            pip(0f, 90f, 1.4f, upsideDown = true)
        }
        3, 4, 5 -> {
            pip(0f, -120f, 1f)
            pip(0f, 120f, 1f, upsideDown = true)
            if (rank >= 4) {
                pip(-90f, 0f, 1f)
                pip(90f, 0f, 1f, upsideDown = true)
            }
            if (rank != 4) pip(0f, 0f, 1.2f)
        }
        // Rank not known yet: one large, faint symbol.
        else -> pip(0f, 0f, 3f, a = 0.1f * alpha)
    }
}

/** A suit symbol of [size] units centred on (cx, cy), filled with [brush] (laid out around the symbol's centre) and outlined in black. */
private fun DrawScope.drawPip(suit: String, cx: Float, cy: Float, size: Float, degrees: Float, brush: Brush, alpha: Float, ownColours: Boolean) {
    val bmp = PipStore.get(suit) ?: return
    val dst = IntSize(size.toInt().coerceAtLeast(1), size.toInt().coerceAtLeast(1))
    val outline = (size * 0.045f).coerceAtLeast(1f)
    withTransform({ translate(cx, cy); rotate(degrees, Offset.Zero) }) {
        val topLeft = Offset(-size / 2, -size / 2)
        val black = androidx.compose.ui.graphics.ColorFilter.tint(Color.Black, BlendMode.SrcIn)
        // The dark glow: a blurred black copy of the symbol.
        drawIntoCanvas { canvas ->
            val paint = android.graphics.Paint().apply {
                this.alpha = (255 * 0.9f * alpha).toInt()
                maskFilter = android.graphics.BlurMaskFilter(GLOW, android.graphics.BlurMaskFilter.Blur.NORMAL)
                colorFilter = android.graphics.PorterDuffColorFilter(android.graphics.Color.BLACK, android.graphics.PorterDuff.Mode.SRC_IN)
            }
            val left = topLeft.x.toInt()
            val top = topLeft.y.toInt()
            canvas.nativeCanvas.drawBitmap(bmp.asAndroidBitmap(), null, android.graphics.Rect(left, top, left + dst.width, top + dst.height), paint)
        }
        for (dx in -1..1) for (dy in -1..1) {
            if (dx == 0 && dy == 0) continue
            drawImage(
                bmp, dstOffset = androidx.compose.ui.unit.IntOffset((topLeft.x + dx * outline).toInt(), (topLeft.y + dy * outline).toInt()),
                dstSize = dst, alpha = alpha, colorFilter = black,
            )
        }
        if (ownColours) {
            // Rainbow and the like: the symbol's own colours (the pinwheel).
            drawImage(bmp, dstOffset = androidx.compose.ui.unit.IntOffset(topLeft.x.toInt(), topLeft.y.toInt()), dstSize = dst, alpha = alpha)
        } else {
            // The symbol's shape filled with the suit colour.
            drawIntoCanvas { canvas ->
                val bounds = androidx.compose.ui.geometry.Rect(topLeft, Size(size, size))
                canvas.saveLayer(bounds, Paint().apply { this.alpha = alpha })
                drawImage(bmp, dstOffset = androidx.compose.ui.unit.IntOffset(topLeft.x.toInt(), topLeft.y.toInt()), dstSize = dst)
                drawRect(brush, topLeft, Size(size, size), blendMode = BlendMode.SrcIn)
                canvas.restore()
            }
        }
    }
}
