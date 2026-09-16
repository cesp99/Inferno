package to.eyed.inferno.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Thermometer
import com.composables.icons.lucide.X
import kotlinx.coroutines.CancellationException
import to.eyed.inferno.AppContainer
import to.eyed.inferno.engine.EngineState
import to.eyed.inferno.models.DownloadService
import to.eyed.inferno.ui.chat.ChatRoot
import to.eyed.inferno.ui.components.ChipPill
import to.eyed.inferno.ui.components.ErrorNotice
import to.eyed.inferno.ui.components.GlassButton
import to.eyed.inferno.ui.components.InfernoSheet
import to.eyed.inferno.ui.components.PrimaryButton
import to.eyed.inferno.ui.components.SheetHeader
import to.eyed.inferno.ui.bench.BenchScreen
import to.eyed.inferno.ui.create.CreateScreen
import to.eyed.inferno.ui.create.GalleryScreen
import to.eyed.inferno.ui.models.ModelManagerScreen
import to.eyed.inferno.ui.onboarding.FirstRunScreen
import to.eyed.inferno.ui.onboarding.UnsupportedCpuScreen
import to.eyed.inferno.ui.settings.SettingsScreen
import to.eyed.inferno.ui.theme.Ink
import to.eyed.inferno.ui.theme.LocalAnimations
import to.eyed.inferno.ui.theme.LocalHaptics
import to.eyed.inferno.ui.theme.Typography
import to.eyed.inferno.ui.theme.defaultEffectsSpec
import to.eyed.inferno.ui.theme.defaultSpatialSpec
import to.eyed.inferno.ui.theme.fastSpatialSpec
import to.eyed.inferno.ui.theme.rememberHaptics
import to.eyed.inferno.vm.AppViewModel
import to.eyed.inferno.vm.BenchViewModel
import to.eyed.inferno.vm.ChatViewModel
import to.eyed.inferno.vm.ImageGenViewModel
import to.eyed.inferno.vm.PendingAction
import to.eyed.inferno.vm.Screen
import to.eyed.inferno.vm.isBusy

/**
 * Screen switch + global sheets (spec 5.6 Root.kt). Until WP7/WP8/WP9b-ui land, every screen is a minimal
 * placeholder from ui/Placeholders.kt built from the WP6 components, so the whole pipeline runs end to end.
 * Predictive back order (6.7): sheets (M3) > screen (MODELS/BENCH/... -> CHAT with a scale/translate/alpha preview) > system.
 */
@Composable
fun InfernoRoot(container: AppContainer, appVm: AppViewModel, chatVm: ChatViewModel, benchVm: BenchViewModel, imageVm: ImageGenViewModel) {
    val settings by appVm.settings.collectAsStateWithLifecycle()
    val screen by appVm.screen.collectAsStateWithLifecycle()
    val showFirstRun by appVm.showFirstRun.collectAsStateWithLifecycle()
    val notice by appVm.notice.collectAsStateWithLifecycle()
    val isHot by appVm.isHot.collectAsStateWithLifecycle()
    val metered by appVm.meteredConfirm.collectAsStateWithLifecycle()
    val pending by appVm.pendingIntent.collectAsStateWithLifecycle()
    val engine by appVm.engine.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    CompositionLocalProvider(LocalAnimations provides settings.streamingAnimations, LocalHaptics provides settings.haptics) {
        val haptics = rememberHaptics()

        // Notifications permission, the single rule (5.6): asked once before the first download or send, never again.
        val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
        val ensureNotifications: () -> Unit = {
            if (!settings.notificationsAsked) {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                appVm.markNotificationsAsked()
            }
        }

        // Model Ready -> one Confirm, only while the activity is RESUMED (6.7).
        LaunchedEffect(engine is EngineState.Ready) {
            if (engine is EngineState.Ready && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) haptics.confirm()
        }

        // Pending intents: notification Stop, share-to-Inferno.
        LaunchedEffect(pending) {
            when (val p = pending) {
                null -> Unit
                PendingAction.Stop -> { chatVm.cancel(); appVm.consumePending() }
                is PendingAction.Share -> {
                    when {
                        !container.isCpuSupported || showFirstRun -> Unit
                        chatVm.gen.value.isBusy -> appVm.notice(S.finishCurrentAnswer)
                        else -> { appVm.navigate(Screen.CHAT); chatVm.newChat(); p.uris.take(2).forEach { chatVm.attachFromUri(it) } }
                    }
                    appVm.consumePending()
                }
            }
        }

        Box(Modifier.fillMaxSize().safeDrawingPadding()) {
            when {
                !container.isCpuSupported -> UnsupportedCpuScreen(container.cpu)
                showFirstRun -> FirstRunScreen(appVm, onBeforeDownload = ensureNotifications)
                else -> Screens(appVm, chatVm, benchVm, imageVm, screen, ensureNotifications)
            }

            // Global notices, top-centre: error card, then the thermal chip.
            Column(Modifier.align(Alignment.TopCenter).padding(horizontal = 16.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                notice?.let { ErrorNotice(it, onDismiss = appVm::dismissNotice) }
                if (isHot) {
                    Spacer(Modifier.height(8.dp))
                    ChipPill(S.phoneIsHot, onClick = {}, icon = Lucide.Thermometer, textColor = Ink.I300)
                }
            }

            metered?.let { req ->
                MeteredConfirmSheet(
                    name = container.models.displayName(req.modelId), bytes = req.bytes,
                    onWait = { appVm.waitForWifi(req.modelId) },
                    onDownload = { always -> appVm.confirmMetered(req.modelId, always) },
                )
            }
        }
    }
}

@Composable
private fun Screens(appVm: AppViewModel, chatVm: ChatViewModel, benchVm: BenchViewModel, imageVm: ImageGenViewModel, screen: Screen, ensureNotifications: () -> Unit) {
    // Predictive back preview for the screen level: the outgoing screen scales/slides/fades with the gesture.
    val back = remember { Animatable(0f) }
    val springBack = fastSpatialSpec<Float>()
    PredictiveBackHandler(enabled = screen != Screen.CHAT) { progress ->
        try {
            progress.collect { back.snapTo(it.progress) }
            appVm.back()
            back.snapTo(0f)
        } catch (e: CancellationException) {
            back.animateTo(0f, springBack)
        }
    }
    LaunchedEffect(screen) { if (screen == Screen.CHAT) appVm.onChatDisplayed() }

    val slide = defaultSpatialSpec<IntOffset>()
    val fade = defaultEffectsSpec<Float>()
    AnimatedContent(
        targetState = screen,
        transitionSpec = {
            val forward = targetState != Screen.CHAT && (initialState == Screen.CHAT || targetState == Screen.GALLERY)
            val enter = slideInHorizontally(slide) { if (forward) it / 4 else -it / 4 } + fadeIn(fade)
            val exit = slideOutHorizontally(slide) { if (forward) -it / 4 else it / 4 } + fadeOut(fade)
            enter togetherWith exit
        },
        label = "screen",
        modifier = Modifier.fillMaxSize().graphicsLayer {
            val p = back.value
            scaleX = 1f - 0.1f * p; scaleY = 1f - 0.1f * p
            translationX = 0.25f * size.width * p
            alpha = 1f - 0.3f * p
        },
    ) { s ->
        when (s) {
            Screen.CHAT -> ChatRoot(appVm, chatVm, onBeforeSend = ensureNotifications)
            Screen.MODELS -> ModelManagerScreen(appVm, onBeforeDownload = ensureNotifications)
            Screen.SETTINGS -> SettingsScreen(appVm, chatVm)
            Screen.BENCH -> BenchScreen(benchVm, appVm)
            Screen.CREATE -> CreateScreen(imageVm, appVm, chatVm, onBeforeDownload = ensureNotifications)
            Screen.GALLERY -> GalleryScreen(imageVm, appVm, chatVm)
        }
    }
}

/** "Download 1.9 GB over mobile data?" [Wait for Wi-Fi | Download anyway | Always allow] (6.1 flow 2). */
@Composable
private fun MeteredConfirmSheet(name: String, bytes: Long, onWait: () -> Unit, onDownload: (always: Boolean) -> Unit) {
    InfernoSheet(onDismiss = onWait) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SheetHeader("Download ${DownloadService.fmt(bytes)} over mobile data?", Lucide.X, onLeading = onWait)
            Text(name, style = Typography.bodyMedium, color = Ink.I300)
            PrimaryButton(S.downloadAnyway, onClick = { onDownload(false) }, modifier = Modifier.fillMaxWidth())
            GlassButton(S.alwaysAllow, onClick = { onDownload(true) }, modifier = Modifier.fillMaxWidth())
            GlassButton(S.waitForWifi, onClick = onWait, modifier = Modifier.fillMaxWidth())
        }
    }
}
