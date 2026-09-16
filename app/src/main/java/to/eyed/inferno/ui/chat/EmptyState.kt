package to.eyed.inferno.ui.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Cpu
import com.composables.icons.lucide.Lucide
import to.eyed.inferno.engine.EngineState
import to.eyed.inferno.ui.S
import to.eyed.inferno.ui.components.GlassButton
import to.eyed.inferno.ui.components.MorphingMark
import to.eyed.inferno.ui.theme.Ink
import to.eyed.inferno.ui.theme.Typography
import to.eyed.inferno.ui.theme.defaultEffectsSpec

/**
 * First empty chat: the owl mark (breathing only while the engine loads), a short hint and two or
 * three suggestion chips that fill the composer. With no model: "Choose a model to start" + a button.
 */
@Composable
fun EmptyState(
    engine: EngineState,
    canAttachImages: Boolean,
    onSuggestion: (String) -> Unit,
    onDescribePhoto: () -> Unit,
    onChooseModel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val idle = engine is EngineState.Idle || engine is EngineState.Error
    val loading = engine is EngineState.Loading
    Column(
        modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        MorphingMark(48.dp, active = loading)
        Spacer(Modifier.height(22.dp))
        Text(S.emptyTitle, style = Typography.headlineSmall, color = Ink.White, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        val fade = defaultEffectsSpec<Float>()
        AnimatedContent(
            targetState = idle,
            transitionSpec = { fadeIn(fade) togetherWith fadeOut(fade) },
            label = "emptySubtitle",
        ) { noModel ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    if (noModel) S.chooseModelToStart else S.emptySubtitle,
                    style = Typography.bodyMedium, color = Ink.I500, textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(24.dp))
                if (noModel) {
                    GlassButton(S.chooseModelButton, onClick = onChooseModel, icon = Lucide.Cpu)
                } else {
                    // The chips are prompts, not commands: they only fill the composer so the user still owns the send.
                    FlowRow(
                        Modifier.widthIn(max = 360.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SuggestionChip(S.suggestionExplain) { onSuggestion(S.promptExplain) }
                        SuggestionChip(S.suggestionDraft) { onSuggestion(S.promptDraft) }
                        if (canAttachImages) SuggestionChip(S.suggestionPhoto, onDescribePhoto)
                        else SuggestionChip(S.suggestionIdeas) { onSuggestion(S.promptIdeas) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SuggestionChip(label: String, onClick: () -> Unit) {
    GlassButton(label, onClick = onClick, textColor = Ink.I100)
}
