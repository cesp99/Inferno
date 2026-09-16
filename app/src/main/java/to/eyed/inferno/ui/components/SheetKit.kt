@file:OptIn(ExperimentalMaterial3Api::class)

package to.eyed.inferno.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Circle
import com.composables.icons.lucide.CircleCheck
import com.composables.icons.lucide.Lucide
import kotlinx.coroutines.delay
import to.eyed.inferno.ui.S
import to.eyed.inferno.ui.theme.Eyebrow as EyebrowStyle
import to.eyed.inferno.ui.theme.Ink
import to.eyed.inferno.ui.theme.Radii
import to.eyed.inferno.ui.theme.RowMeta
import to.eyed.inferno.ui.theme.RowTitle
import to.eyed.inferno.ui.theme.SheetTitle
import to.eyed.inferno.ui.theme.Typography
import to.eyed.inferno.ui.theme.defaultEffectsSpec
import to.eyed.inferno.ui.theme.defaultSpatialSpec
import to.eyed.inferno.ui.theme.whiteA

/** Bottom-sheet scaffold: 28 dp top corners, drag handle at 20 % white, skips the half state. */
@Composable
fun InfernoSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    wide: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
        // Only Hidden and Expanded: the sheet never rests half-open.
        sheetState = rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
        ),
        containerColor = if (wide) Ink.I850 else Ink.SurfaceLow,
        contentColor = Ink.I100,
        shape = RoundedCornerShape(topStart = Radii.sheet, topEnd = Radii.sheet),
        dragHandle = { BottomSheetDefaults.DragHandle(color = whiteA(0.2f)) },
        content = content,
    )
}

/** Centred sheet title with a leading ghost action (X or back arrow) and an optional trailing slot. */
@Composable
fun SheetHeader(
    title: String,
    leadingIcon: ImageVector,
    onLeading: () -> Unit,
    modifier: Modifier = Modifier,
    leadingDescription: String = S.close,
    trailing: (@Composable () -> Unit)? = null,
) {
    Box(modifier.fillMaxWidth().padding(bottom = 14.dp)) {
        GhostIconButton(
            leadingIcon,
            leadingDescription,
            onClick = onLeading,
            size = 36.dp,
            iconSize = 18.dp,
            tint = Ink.I100,
            modifier = Modifier.align(Alignment.CenterStart),
        )
        Text(
            title,
            style = SheetTitle,
            color = Ink.White,
            modifier = Modifier.align(Alignment.Center).semantics { heading() },
        )
        if (trailing != null) Box(Modifier.align(Alignment.CenterEnd)) { trailing() }
    }
}

/**
 * Pages inside one sheet: slide 1/4 width with the spatial spec plus a fade with the effects
 * spec (never a spatial spring on alpha). Navigating back to [root] slides the other way.
 */
@Composable
fun SheetPager(
    page: String,
    modifier: Modifier = Modifier,
    root: String = "main",
    content: @Composable (String) -> Unit,
) {
    val slide = defaultSpatialSpec<IntOffset>()
    val fade = defaultEffectsSpec<Float>()
    AnimatedContent(
        targetState = page,
        modifier = modifier,
        transitionSpec = {
            val backwards = targetState == root
            val enter = slideInHorizontally(slide) { if (backwards) -it / 4 else it / 4 } + fadeIn(fade)
            val exit = slideOutHorizontally(slide) { if (backwards) it / 4 else -it / 4 } + fadeOut(fade)
            enter togetherWith exit
        },
        label = "sheetPage",
        content = { content(it) },
    )
}

/** One visual group of rows: a flat raised card with a hairline border. */
@Composable
fun SettingsCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().surfaceCard(RoundedCornerShape(Radii.row), fill = Ink.I850), content = content)
}

/** Title + optional description on the left, a trailing control on the right. */
@Composable
fun SettingRow(
    title: String,
    description: String? = null,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    titleColor: Color = Ink.White,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = Role.Button,
                        onClick = onClick,
                    )
                } else Modifier,
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = RowTitle, color = titleColor)
            if (description != null) {
                Spacer(Modifier.height(2.dp))
                Text(description, style = RowMeta, color = Ink.I500)
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(16.dp))
            trailing()
        }
    }
}

/** Uppercase eyebrow above a group; a heading for TalkBack. */
@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, color: Color = Ink.I500) {
    Text(
        title.uppercase(),
        style = EyebrowStyle,
        color = color,
        modifier = modifier.padding(start = 8.dp, top = 18.dp, bottom = 8.dp).semantics { heading() },
    )
}

/** Divider between rows inside a card, inset to align with the text. */
@Composable
fun CardDivider(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(horizontal = 16.dp).height(1.dp).background(whiteA(0.06f)))
}

/** 6 dp bar for RAM / storage / context; [approximate] renders the fill at half strength. */
@Composable
fun UsageBar(
    fraction: Float,
    modifier: Modifier = Modifier,
    color: Color = Ink.White,
    approximate: Boolean = false,
) {
    val clamped = fraction.coerceIn(0f, 1f)
    Box(
        modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(CircleShape)
            .background(whiteA(0.10f))
            .semantics { progressBarRangeInfo = ProgressBarRangeInfo(clamped, 0f..1f) },
    ) {
        Box(
            Modifier
                .fillMaxWidth(clamped)
                .fillMaxHeight()
                .clip(CircleShape)
                .background(if (approximate) color.copy(alpha = color.alpha * 0.5f) else color),
        )
    }
}

/** Selectable option row: selection is signalled by a white border, never a fill. */
@Composable
fun OptionRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    recommended: Boolean = false,
) {
    val shape = RoundedCornerShape(Radii.row)
    Row(
        modifier
            .fillMaxWidth()
            .surfaceLow(shape)
            .then(if (selected) Modifier.border(1.5.dp, Ink.White, shape) else Modifier)
            .selectable(
                selected = selected,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (selected) Lucide.CircleCheck else Lucide.Circle,
            null,
            Modifier.size(16.dp),
            tint = if (selected) Ink.White else Ink.I500,
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = Typography.labelLarge, color = Ink.White)
                if (recommended) {
                    Spacer(Modifier.width(8.dp))
                    Box(
                        Modifier.clip(CircleShape).background(Ink.SurfaceHigh).padding(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Text(S.recommended, style = Typography.labelSmall, color = Ink.I100)
                    }
                }
            }
            if (description != null) {
                Text(description, style = Typography.labelMedium.copy(fontWeight = null), color = Ink.I500)
            }
        }
    }
}

/**
 * Destructive control that needs two taps: first tap arms it ("Confirm", red fill), the second
 * fires [onConfirm]. Auto-disarms after 3 s so a stray tap never lingers.
 */
@Composable
fun ConfirmTwice(
    label: String,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    confirmLabel: String = S.confirm,
) {
    var confirming by remember { mutableStateOf(false) }
    LaunchedEffect(confirming) {
        if (confirming) {
            delay(3_000)
            confirming = false
        }
    }
    Box(
        modifier
            .minimumInteractiveComponentSize()
            .clip(RoundedCornerShape(Radii.control))
            .background(if (confirming) Ink.Danger else Ink.DangerDim)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
            ) {
                if (confirming) {
                    confirming = false
                    onConfirm()
                } else {
                    confirming = true
                }
            }
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            if (confirming) confirmLabel else label,
            style = Typography.labelMedium.copy(fontSize = 11.sp, lineHeight = 15.sp),
            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
            color = if (confirming) Ink.White else Ink.Danger,
        )
    }
}

/** Labelled text input for sheets (system prompt, rename, import name). */
@Composable
fun FormField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    multiline: Boolean = false,
    enabled: Boolean = true,
) {
    Column(modifier.fillMaxWidth()) {
        Text(label, style = RowMeta.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium), color = Ink.I500)
        Spacer(Modifier.height(6.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = !multiline,
            textStyle = Typography.bodyMedium.copy(color = Ink.I100),
            cursorBrush = SolidColor(Ink.White),
            keyboardOptions = KeyboardOptions(imeAction = if (multiline) ImeAction.Default else ImeAction.Done),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Radii.control))
                .background(Ink.SurfaceHigh)
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .then(if (multiline) Modifier.heightIn(min = 96.dp) else Modifier),
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty()) Text(placeholder, style = Typography.bodyMedium, color = Ink.I500)
                    inner()
                }
            },
        )
    }
}

/** Row that navigates to a sub-page: icon circle, title, current value, chevron. */
@Composable
fun NavCard(
    icon: ImageVector,
    title: String,
    value: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radii.row))
            .background(Ink.Surface)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IconCircle(icon)
        Column(Modifier.weight(1f)) {
            Text(title, style = Typography.titleSmall, color = Ink.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (value != null) {
                Text(value, style = RowMeta.copy(fontSize = 13.sp, lineHeight = 18.sp), color = Ink.I500, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Icon(Lucide.ChevronRight, null, Modifier.size(20.dp), tint = Ink.I500)
    }
}

/** Remembers a sheet page name across rotation and process death. */
@Composable
fun rememberSheetPage(initial: String = "main") = rememberSaveable { mutableStateOf(initial) }
