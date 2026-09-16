package to.eyed.inferno.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.Cpu
import com.composables.icons.lucide.Gauge
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Settings
import com.composables.icons.lucide.SquarePen
import com.composables.icons.lucide.X
import com.composables.icons.lucide.Zap
import to.eyed.inferno.ui.S
import to.eyed.inferno.ui.theme.InfernoTheme
import to.eyed.inferno.ui.theme.Ink
import to.eyed.inferno.ui.theme.Meta
import to.eyed.inferno.ui.theme.Numeric
import to.eyed.inferno.ui.theme.Typography

// Component gallery: one @Preview per component (spec 10 WP6) plus a scrolling screen that
// MainActivity shows until WP5 wires InfernoRoot. Everything renders on pure black.

private const val PreviewBg = 0xFF000000L

@Preview(name = "Controls", showBackground = true, backgroundColor = PreviewBg)
@Composable
fun ControlsPreview() = InfernoTheme {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            GhostIconButton(Lucide.SquarePen, S.newChat, onClick = {}, size = 40.dp, iconSize = 20.dp)
            GhostIconButton(Lucide.X, S.close, onClick = {})
            GhostIconButton(Lucide.Settings, S.settings, onClick = {}, enabled = false)
            IconCircle(Lucide.Cpu)
            ChipPill("Qwen3.5 2B", onClick = {}, icon = Lucide.Zap)
        }
        PrimaryButton(S.downloadAndStart, onClick = {}, modifier = Modifier.fillMaxWidth())
        PrimaryButton(S.apply, onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassButton(S.resume, onClick = {})
            GlassButton(S.jumpToLatest, onClick = {}, icon = Lucide.Check)
            GlassButton(S.cancel, onClick = {}, textColor = Ink.Danger)
        }
        Eyebrow("Eyebrow label")
        Hairline()
    }
}

@Preview(name = "Toggle + segmented", showBackground = true, backgroundColor = PreviewBg)
@Composable
fun TogglePreview() = InfernoTheme {
    var on by rememberSaveable { mutableStateOf(true) }
    var seg by rememberSaveable { mutableIntStateOf(1) }
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            GlassToggle(on, { on = it })
            GlassToggle(!on, { on = !it })
            GlassToggle(true, {}, enabled = false)
        }
        GlassToggle(S.alwaysAllow, on, { on = it })
        SegmentedPill(listOf("Auto", "Max", "Cool"), seg, { seg = it }, Modifier.fillMaxWidth())
    }
}

@Preview(name = "Marks", showBackground = true, backgroundColor = PreviewBg)
@Composable
fun MarkPreview() = InfernoTheme {
    Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        OwlMark(20.dp)
        OwlMark(48.dp, color = Ink.I500)
        MorphingMark(28.dp, active = false)
        MorphingMark(72.dp, active = true)
    }
}

@Preview(name = "Sheet kit", showBackground = true, backgroundColor = PreviewBg)
@Composable
fun SheetKitPreview() = InfernoTheme {
    var value by rememberSaveable { mutableStateOf("") }
    Column(Modifier.padding(16.dp)) {
        SheetHeader(S.settings, Lucide.X, onLeading = {})
        SectionHeader(S.inference)
        SettingsCard {
            SettingRow("Keep model loaded", "Stays in memory while the app is in the background") { GlassToggle(true, {}) }
            CardDivider()
            SettingRow("Threads", "4 big cores detected. More threads is slower on this phone.")
            CardDivider()
            SettingRow(S.deleteAllChats, S.chatsStoredLocally, titleColor = Ink.Danger) { ConfirmTwice(S.delete, onConfirm = {}) }
        }
        Spacer(Modifier.height(12.dp))
        NavCard(Lucide.Gauge, S.contextLength, "Auto · 65,536 tokens · 2.9 GB", onClick = {})
        Spacer(Modifier.height(8.dp))
        OptionRow("Balanced", selected = true, onClick = {}, description = "About 512 image tokens", recommended = true)
        Spacer(Modifier.height(4.dp))
        OptionRow("High", selected = false, onClick = {}, description = "Model maximum")
        Spacer(Modifier.height(12.dp))
        UsageBar(0.42f)
        Spacer(Modifier.height(6.dp))
        UsageBar(0.42f, approximate = true)
        Spacer(Modifier.height(12.dp))
        FormField(S.systemPrompt, value, { value = it }, placeholder = "You are a helpful assistant.", multiline = true)
    }
}

@Preview(name = "Notices", showBackground = true, backgroundColor = PreviewBg)
@Composable
fun NoticesPreview() = InfernoTheme {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        QuietNotice(S.willSendWhenReady)
        QuietNotice(S.notificationsNotice, onClick = {})
        ErrorNotice(S.couldNotReadImage, onDismiss = {})
        StatusRow(Lucide.Cpu, "Encoding image", running = true)
        StatusRow(Lucide.Cpu, "Encoded image (448x448, 256 tokens)", onClick = {})
        StatusRow(Lucide.Cpu, "Image encode failed", failed = true)
    }
}

/** Tabular figures: both rows must be the same width in the preview. */
@Preview(name = "Numeric tnum", showBackground = true, backgroundColor = PreviewBg)
@Composable
fun NumericPreview() = InfernoTheme {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("12.4 tok/s · 843 tokens · 8.1 s", style = Meta, color = Ink.I500)
        Text("9.8 tok/s · 111 tokens · 1.1 s", style = Meta, color = Ink.I500)
        Text("1.2 GB of 1.9 GB · 12 MB/s", style = Numeric, color = Ink.I100)
        Text("0.9 GB of 1.9 GB · 11 MB/s", style = Numeric, color = Ink.I100)
        Text("Where should we begin?", style = Typography.headlineSmall, color = Ink.White)
        Text(S.emptySubtitle, style = Typography.bodyLarge, color = Ink.I500)
    }
}

/** Temporary home screen until WP5 lands InfernoRoot: every component, on black. */
@Composable
fun ComponentGallery() {
    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 32.dp),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            OwlMark(20.dp)
            Spacer(Modifier.width(10.dp))
            Text(S.appName, style = Typography.titleMedium, color = Ink.White)
        }
        ControlsPreview()
        TogglePreview()
        MarkPreview()
        SheetKitPreview()
        NoticesPreview()
        NumericPreview()
    }
}
