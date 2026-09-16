package to.eyed.inferno.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import to.eyed.inferno.engine.CpuTopology
import to.eyed.inferno.ui.S
import to.eyed.inferno.ui.components.CardDivider
import to.eyed.inferno.ui.components.OwlMark
import to.eyed.inferno.ui.components.SectionHeader
import to.eyed.inferno.ui.components.SettingRow
import to.eyed.inferno.ui.components.SettingsCard
import to.eyed.inferno.ui.theme.Ink
import to.eyed.inferno.ui.theme.Numeric
import to.eyed.inferno.ui.theme.Typography

/** Terminal screen: the owl, why, and what the probe found. */
@Composable
fun UnsupportedCpuScreen(cpu: CpuTopology?) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(top = 96.dp, bottom = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        OwlMark(64.dp, color = Ink.I300)
        Spacer(Modifier.height(28.dp))
        Text(S.unsupportedCpu, style = Typography.titleMedium, color = Ink.White, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 360.dp))
        Spacer(Modifier.height(10.dp))
        Text(S.unsupportedBody, style = Typography.bodyMedium, color = Ink.I500, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 360.dp))
        if (cpu != null) {
            Spacer(Modifier.height(24.dp))
            Column(Modifier.fillMaxWidth().widthIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                SectionHeader(S.detectedFeatures)
                SettingsCard {
                    Fact(S.soc, cpu.socName)
                    CardDivider()
                    Fact(S.cores, S.coresLine(cpu.nCores, cpu.nBig))
                    CardDivider()
                    Fact("dotprod", yesNo(cpu.hasDotprod))
                    CardDivider()
                    Fact("fp16", yesNo(cpu.hasFp16))
                    CardDivider()
                    Fact("i8mm", yesNo(cpu.hasI8mm))
                }
            }
        }
    }
}

@Composable
private fun Fact(label: String, value: String) {
    SettingRow(label) { Text(value, style = Numeric, color = Ink.I300) }
}

private fun yesNo(v: Boolean) = if (v) S.yes else S.no
