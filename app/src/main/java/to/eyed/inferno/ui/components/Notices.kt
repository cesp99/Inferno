@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package to.eyed.inferno.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Lucide
import to.eyed.inferno.ui.S
import to.eyed.inferno.ui.theme.Eyebrow as EyebrowStyle
import to.eyed.inferno.ui.theme.Ink
import to.eyed.inferno.ui.theme.Radii
import to.eyed.inferno.ui.theme.Typography

/** Quiet inline pill for soft status ("Will send when the model is ready"). Optional tap. */
@Composable
fun QuietNotice(text: String, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    Box(
        modifier
            .surfaceLow(CircleShape)
            .then(
                if (onClick != null) {
                    Modifier
                        .minimumInteractiveComponentSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            role = Role.Button,
                            onClick = onClick,
                        )
                } else Modifier,
            )
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = Typography.labelMedium.copy(fontWeight = FontWeight.Normal), color = Ink.I300)
    }
}

/** Top-centre error card; the whole card dismisses on tap. Announced assertively by TalkBack. */
@Composable
fun ErrorNotice(message: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .surfaceCard(RoundedCornerShape(Radii.row), fill = Ink.Surface)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClick = onDismiss,
            )
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .semantics { liveRegion = LiveRegionMode.Assertive },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            message,
            style = Typography.labelMedium.copy(fontWeight = FontWeight.Normal),
            color = Ink.I100,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        Text(S.dismiss, style = EyebrowStyle, color = Ink.I500)
    }
}

/** Quiet status line: icon, label, a small loading indicator while running, chevron when inspectable. */
@Composable
fun StatusRow(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    running: Boolean = false,
    failed: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val labelColor = when {
        failed -> Ink.Danger
        running -> Ink.I300
        else -> Ink.I500
    }
    Row(
        modifier
            .then(
                if (onClick != null) {
                    Modifier
                        .minimumInteractiveComponentSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            role = Role.Button,
                            onClick = onClick,
                        )
                } else Modifier,
            )
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, null, Modifier.size(14.dp), tint = if (failed) Ink.I300 else Ink.I500)
        Text(label, style = Typography.bodyMedium, color = labelColor)
        if (running) LoadingIndicator(Modifier.size(16.dp), color = Ink.White)
        if (onClick != null) Icon(Lucide.ChevronRight, null, Modifier.size(13.dp), tint = Ink.I500)
    }
}
