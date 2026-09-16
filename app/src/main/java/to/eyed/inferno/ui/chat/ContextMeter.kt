package to.eyed.inferno.ui.chat

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import to.eyed.inferno.ui.S
import to.eyed.inferno.ui.components.GlassButton
import to.eyed.inferno.ui.components.PrimaryButton
import to.eyed.inferno.ui.components.QuietNotice
import to.eyed.inferno.ui.components.surfaceCard
import to.eyed.inferno.ui.theme.Ink
import to.eyed.inferno.ui.theme.Radii
import to.eyed.inferno.ui.theme.Typography
import to.eyed.inferno.ui.theme.defaultSpatialSpec
import to.eyed.inferno.ui.theme.whiteA
import java.util.Locale

/**
 * 2 dp ring showing kvUsed / nCtx. The track is dashed when the figure is an estimate (no exact
 * kvUsed for this chat with this model yet). Draw-phase only; the fill animates with the spatial spec.
 */
@Composable
fun ContextRing(
    fraction: Float,
    size: Dp,
    modifier: Modifier = Modifier,
    approximate: Boolean = false,
    color: Color = Ink.White,
    stroke: Dp = 2.dp,
) {
    val clamped = fraction.coerceIn(0f, 1f)
    val animated by animateFloatAsState(clamped, defaultSpatialSpec(), label = "ctxRing")
    val percent = (clamped * 100).toInt()
    Canvas(
        modifier
            .size(size)
            .semantics {
                contentDescription = S.contextUsed(percent)
                progressBarRangeInfo = ProgressBarRangeInfo(clamped, 0f..1f)
            },
    ) {
        val w = stroke.toPx()
        val inset = w / 2
        val arcSize = Size(this.size.width - w, this.size.height - w)
        val topLeft = Offset(inset, inset)
        val track = if (approximate) {
            Stroke(width = w, cap = StrokeCap.Butt, pathEffect = PathEffect.dashPathEffect(floatArrayOf(w * 1.2f, w * 1.2f)))
        } else Stroke(width = w, cap = StrokeCap.Round)
        drawArc(whiteA(0.15f), 0f, 360f, useCenter = false, topLeft = topLeft, size = arcSize, style = track)
        if (animated > 0f) {
            drawArc(
                color.copy(alpha = if (approximate) 0.7f else 1f), -90f, 360f * animated, useCenter = false,
                topLeft = topLeft, size = arcSize, style = Stroke(width = w, cap = StrokeCap.Round),
            )
        }
    }
}

/**
 * STOP policy (ContextPolicy.STOP): replaces the composer once the next prompt no longer fits or usage passed
 * ContextManager.STOP_FRACTION. Nothing is dropped; the user picks a fresh chat (optionally seeded with one
 * summary of this one) or flips the policy to rolling. "Carry over" needs the model loaded.
 */
@Composable
fun ContextFullPanel(
    used: Int, nCtx: Int, canCarryOver: Boolean,
    onNewChat: () -> Unit, onCarryOver: () -> Unit, onSwitchToRolling: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .surfaceCard(RoundedCornerShape(Radii.card), fill = Ink.Surface)
            .padding(20.dp),
    ) {
        Text(S.contextFullTitle, style = Typography.titleMedium, color = Ink.White)
        Spacer(Modifier.height(6.dp))
        Text(
            S.contextFullBody(String.format(Locale.US, "%,d", used), String.format(Locale.US, "%,d", nCtx)),
            style = Typography.bodyMedium, color = Ink.I500,
        )
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            PrimaryButton(S.newChat, onClick = onNewChat, modifier = Modifier.weight(1f))
            GlassButton(S.carryOverSummary, onClick = onCarryOver, enabled = canCarryOver)
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { QuietNotice(S.switchToRolling, onClick = onSwitchToRolling) }
    }
}
