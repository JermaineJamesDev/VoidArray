package io.github.jermainejamesdev.voidarray.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * A formation array: an outer rune ring, an eight-pointed star of two squares, and an inner ring around a
 * core. When [spinning], the star and rune ring turn in opposite directions; [periodMillis] is one full
 * turn of the star. Stays still when the platform requests reduced motion.
 */
@Composable
internal fun FormationArray(
    modifier: Modifier = Modifier,
    spinning: Boolean = true,
    periodMillis: Int = 24_000,
    lineColor: Color = MaterialTheme.colorScheme.secondary,
    coreColor: Color = MaterialTheme.colorScheme.primary,
) {
    val animate = spinning && !LocalReduceMotion.current
    val angle: State<Float> = if (animate) {
        rememberInfiniteTransition(label = "formation").animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(periodMillis, easing = LinearEasing), RepeatMode.Restart),
            label = "formationAngle",
        )
    } else {
        remember { mutableFloatStateOf(0f) }
    }
    Canvas(modifier) {
        drawFormation(angle.value, lineColor, coreColor)
    }
}

private fun DrawScope.drawFormation(angle: Float, line: Color, core: Color) {
    val radius = min(size.width, size.height) / 2f
    val center = Offset(size.width / 2f, size.height / 2f)
    val stroke = (radius * 0.035f).coerceAtLeast(1f)

    // Outer ring with 24 rune ticks, turning slowly against the star.
    rotate(-angle * 0.5f, center) {
        drawCircle(line, radius = radius * 0.96f, center = center, style = Stroke(stroke))
        drawCircle(line.copy(alpha = line.alpha * 0.5f), radius = radius * 0.86f, center = center, style = Stroke(stroke * 0.6f))
        for (i in 0 until 24) {
            val theta = (i * 15.0) * PI / 180.0
            val long = i % 3 == 0
            val inner = radius * (if (long) 0.80f else 0.86f)
            val outer = radius * 0.92f
            drawLine(
                line,
                start = Offset(center.x + inner * cos(theta).toFloat(), center.y + inner * sin(theta).toFloat()),
                end = Offset(center.x + outer * cos(theta).toFloat(), center.y + outer * sin(theta).toFloat()),
                strokeWidth = if (long) stroke * 1.2f else stroke * 0.7f,
            )
        }
    }

    // Eight-pointed star: two overlapping squares.
    rotate(angle, center) {
        val starRadius = radius * 0.74f
        for (offset in listOf(0.0, 45.0)) {
            val square = Path().apply {
                for (corner in 0 until 4) {
                    val theta = (offset + corner * 90.0) * PI / 180.0
                    val point = Offset(center.x + starRadius * cos(theta).toFloat(), center.y + starRadius * sin(theta).toFloat())
                    if (corner == 0) moveTo(point.x, point.y) else lineTo(point.x, point.y)
                }
                close()
            }
            drawPath(square, line, style = Stroke(stroke))
        }
    }

    drawCircle(core, radius = radius * 0.36f, center = center, style = Stroke(stroke * 1.4f))
    drawCircle(core.copy(alpha = core.alpha * 0.25f), radius = radius * 0.30f, center = center)
    drawCircle(core, radius = radius * 0.11f, center = center)
}

/**
 * The pairing code set apart in jade, large enough to read aloud and compare across two screens, since
 * comparing it is what defeats a first-contact man in the middle. Monospaced so the digits line up when
 * the two screens are held side by side.
 */
@Composable
internal fun PairingCodeToken(code: String, caption: String, modifier: Modifier = Modifier, header: (@Composable () -> Unit)? = null) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, scheme.primary.copy(alpha = 0.55f), MaterialTheme.shapes.large)
            .padding(vertical = 14.dp, horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        header?.invoke()
        Text(
            code,
            fontFamily = VoidArrayTheme.extras.mono,
            fontWeight = FontWeight.Medium,
            fontSize = 34.sp,
            letterSpacing = 0.14.em,
            color = scheme.primary,
        )
        Text(caption, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

/** The pairing code on one line, for a transfer row where space is short. */
@Composable
internal fun CompactPairingCode(code: String, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .border(1.dp, scheme.primary.copy(alpha = 0.55f), MaterialTheme.shapes.small)
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "CONFIRM\nCODE",
            style = MaterialTheme.typography.labelSmall,
            color = scheme.onSurfaceVariant,
            lineHeight = 13.sp,
        )
        Text(
            code,
            fontFamily = VoidArrayTheme.extras.mono,
            fontWeight = FontWeight.Medium,
            fontSize = 24.sp,
            letterSpacing = 0.12.em,
            color = scheme.primary,
        )
    }
}

/** A tilted gold seal, stamped on finished work. Gold rather than red so success never reads as an error. */
@Composable
internal fun SealStamp(text: String = "Sealed", modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.secondary
    Box(
        modifier = modifier
            .rotate(-6f)
            .border(1.5.dp, color)
            .padding(horizontal = 7.dp, vertical = 4.dp),
    ) {
        Text(
            text.uppercase(),
            color = color,
            fontFamily = MaterialTheme.typography.headlineSmall.fontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 10.sp,
            letterSpacing = 0.14.em,
        )
    }
}

/** A tapered brush stroke used as a section divider: thick in the middle, fading to points at both ends. */
@Composable
internal fun InkDivider(modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.secondary) {
    Canvas(modifier.fillMaxWidth().height(6.dp)) {
        val w = size.width
        val h = size.height
        val stroke = Path().apply {
            moveTo(0f, h / 2f)
            quadraticTo(w * 0.35f, h * 0.05f, w * 0.62f, h * 0.30f)
            quadraticTo(w * 0.85f, h * 0.45f, w, h / 2f)
            quadraticTo(w * 0.80f, h * 0.70f, w * 0.55f, h * 0.80f)
            quadraticTo(w * 0.25f, h * 0.95f, 0f, h / 2f)
            close()
        }
        drawPath(stroke, color.copy(alpha = color.alpha * 0.55f))
    }
}
