package to.eyed.inferno.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Copy
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.X
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import to.eyed.inferno.data.MessageStats
import to.eyed.inferno.ui.S
import to.eyed.inferno.ui.components.CardDivider
import to.eyed.inferno.ui.components.GlassButton
import to.eyed.inferno.ui.components.InfernoSheet
import to.eyed.inferno.ui.components.SettingRow
import to.eyed.inferno.ui.components.SettingsCard
import to.eyed.inferno.ui.components.SheetHeader
import to.eyed.inferno.ui.components.copyToClipboard
import to.eyed.inferno.ui.theme.Ink
import to.eyed.inferno.ui.theme.Numeric
import to.eyed.inferno.ui.theme.rememberHaptics
import java.util.Locale

/**
 * Everything llama.cpp measured for one assistant turn, from the persisted [MessageStats] only - so it
 * works for any message in any reopened chat. Rows whose value is 0 / null are hidden.
 */
@Composable
fun TurnDetailsSheet(stats: MessageStats, modelName: String?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val rows = remember(stats, modelName) { detailRows(stats, modelName) }
    InfernoSheet(onDismiss = onDismiss, wide = true) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SheetHeader(S.turnDetails, Lucide.X, onLeading = onDismiss)
            SettingsCard {
                rows.forEachIndexed { i, (label, value) ->
                    if (i > 0) CardDivider()
                    SettingRow(label) { Text(value, style = Numeric, color = Ink.I100) }
                }
            }
            GlassButton(
                S.copyAsJson,
                icon = Lucide.Copy,
                onClick = { haptics.tap(); copyToClipboard(context, statsJson(stats, modelName), "Turn details") },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private fun n(v: Number): String = String.format(Locale.US, "%,d", v.toLong())

/** Label -> value pairs in display order; zero / null values are dropped here so the card never shows a dead row. */
internal fun detailRows(s: MessageStats, modelName: String?): List<Pair<String, String>> {
    val out = mutableListOf<Pair<String, String>>()
    (modelName ?: s.modelId)?.let { out += S.modelRow to it }
    if (s.promptTokens > 0) out += S.promptTokens to (n(s.promptTokens) + if (s.reusedTokens > 0) " (${n(s.reusedTokens)} ${S.reusedFromCache})" else "")
    if (s.generatedTokens > 0) out += S.generatedTokens to n(s.generatedTokens)
    if (s.prefillMs > 0) out += S.prefill to "${n(s.prefillMs)} ms · ${String.format(Locale.US, "%.0f", s.prefillTps)} t/s"
    if (s.decodeMs > 0) out += S.decode to "${formatDuration(s.decodeMs)} · ${String.format(Locale.US, "%.1f", s.decodeTps)} t/s"
    if (s.imageEncodeMs > 0) out += S.imageEncode to "${n(s.imageEncodeMs)} ms"
    if (s.nCtx > 0) out += S.contextUsedRow to "${n(s.kvUsedTokens)} / ${n(s.nCtx)}"
    out += S.finishReason to (s.finishReason?.lowercase()?.replace('_', ' ') ?: S.interrupted)
    samplingSummary(s.paramsJson)?.let { out += S.sampling to it }
    s.templateName?.takeIf { it.isNotBlank() }?.let { out += S.template to (if (s.templateSupported) it else "$it · ${S.chatmlFallback}") }
        ?: run { if (!s.templateSupported) out += S.template to S.chatmlFallback }
    return out
}

/** "temp 0.7 · top-p 0.8 · top-k 20 · min-p 0.05" from the persisted GenerationParams JSON; null when absent. */
internal fun samplingSummary(paramsJson: String?): String? {
    if (paramsJson.isNullOrBlank()) return null
    val obj = runCatching { Json.parseToJsonElement(paramsJson) as? JsonObject }.getOrNull() ?: return null
    fun f(key: String): Double? = obj[key]?.jsonPrimitive?.content?.toDoubleOrNull()
    val parts = listOfNotNull(
        f("temperature")?.let { "temp ${trim(it)}" },
        f("topP")?.let { "top-p ${trim(it)}" },
        f("topK")?.let { "top-k ${it.toInt()}" },
        f("minP")?.let { "min-p ${trim(it)}" },
    )
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

private fun trim(v: Double): String = String.format(Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')

private fun statsJson(s: MessageStats, modelName: String?): String = buildJsonObject {
    put("model", modelName ?: s.modelId)
    put("promptTokens", s.promptTokens); put("reusedTokens", s.reusedTokens); put("generatedTokens", s.generatedTokens)
    put("prefillMs", s.prefillMs); put("decodeMs", s.decodeMs); put("imageEncodeMs", s.imageEncodeMs)
    put("decodeTps", s.decodeTps); put("prefillTps", s.prefillTps)
    put("kvUsedTokens", s.kvUsedTokens); put("nCtx", s.nCtx)
    put("finishReason", s.finishReason); put("template", s.templateName); put("templateSupported", s.templateSupported)
    s.paramsJson?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull()?.let { p -> put("params", p) } }
}.toString()
