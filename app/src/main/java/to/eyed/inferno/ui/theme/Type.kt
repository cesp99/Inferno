package to.eyed.inferno.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// System variable sans everywhere (weights map onto the variable axis on API 33+).
// Deliberate departures from stock Android so it does not read as a default app:
// +0.1 sp tracking on every 11-13 sp label, -0.2 sp on every >= 20 sp title, and
// tabular figures ("tnum") on every numeric style so live counters never change width.
private val Sans = FontFamily.SansSerif

/** OpenType feature: tabular (fixed-width) figures. Apply to anything that shows a live number. */
const val Tabular = "tnum"

private val LabelTracking = 0.1.sp
private val TitleTracking = (-0.2).sp

val Typography = Typography(
    // Hero titles (FirstRun wordmark, empty state)
    headlineSmall = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
        letterSpacing = TitleTracking,
    ),
    // Big figure (bench headline number)
    headlineMedium = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 36.sp,
        lineHeight = 40.sp,
        letterSpacing = TitleTracking,
        fontFeatureSettings = Tabular,
    ),
    headlineSmallEmphasized = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
        letterSpacing = TitleTracking,
    ),
    titleLarge = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Medium,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = TitleTracking,
    ),
    // Expressive emphasized title: sheet titles and the model chip derive from this.
    titleLargeEmphasized = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = TitleTracking,
    ),
    // Modal / card titles
    titleMedium = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    titleMediumEmphasized = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Bold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 20.sp,
    ),
    // Message body / markdown
    bodyLarge = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 24.sp,
    ),
    // Row / list primary
    bodyMedium = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = LabelTracking,
    ),
    // Row secondary / preview
    bodySmall = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        letterSpacing = LabelTracking,
    ),
    // Buttons / prominent labels
    labelLarge = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = LabelTracking,
    ),
    // Meta / captions
    labelSmall = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Normal,
        fontSize = 10.sp,
        lineHeight = 14.sp,
        letterSpacing = LabelTracking,
    ),
)

/** Monospace is reserved for code blocks and the System-info dump only. */
val MonoBody = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 13.sp,
    lineHeight = 19.sp,
    fontFeatureSettings = Tabular,
)

/** Section eyebrow: 10 sp uppercase with wide tracking. */
val Eyebrow = TextStyle(
    fontFamily = Sans,
    fontWeight = FontWeight.SemiBold,
    fontSize = 10.sp,
    lineHeight = 14.sp,
    letterSpacing = 1.5.sp,
)

/** Settings / list row title. */
val RowTitle = TextStyle(
    fontFamily = Sans,
    fontWeight = FontWeight.Medium,
    fontSize = 13.sp,
    lineHeight = 18.sp,
    letterSpacing = LabelTracking,
)

/** Row description / secondary line; tabular so "1.2 GB of 1.9 GB" never wobbles. */
val RowMeta = TextStyle(
    fontFamily = Sans,
    fontWeight = FontWeight.Normal,
    fontSize = 11.sp,
    lineHeight = 15.sp,
    letterSpacing = LabelTracking,
    fontFeatureSettings = Tabular,
)

/** Message meta line ("12.4 tok/s · 843 tokens · 8.1 s"). */
val Meta = RowMeta

/** Bench cells, progress labels, any number that updates live. */
val Numeric = TextStyle(
    fontFamily = Sans,
    fontWeight = FontWeight.Normal,
    fontSize = 13.sp,
    lineHeight = 18.sp,
    letterSpacing = LabelTracking,
    fontFeatureSettings = Tabular,
)

/** Sheet titles: the emphasized title weight at 16 sp. */
val SheetTitle = Typography.titleLargeEmphasized.copy(
    fontSize = 16.sp,
    lineHeight = 22.sp,
    letterSpacing = 0.sp,
)

/** Model chip label: the emphasized title weight scaled to 13 sp. */
val ChipLabel = Typography.titleLargeEmphasized.copy(
    fontSize = 13.sp,
    lineHeight = 18.sp,
    letterSpacing = LabelTracking,
)
