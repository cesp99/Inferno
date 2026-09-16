package to.eyed.inferno.ui.chat

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.core.layout.WindowSizeClass
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.X
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import to.eyed.inferno.data.ChatRepository
import to.eyed.inferno.data.devBenchmark
import to.eyed.inferno.data.devContextMeter
import to.eyed.inferno.data.devGenerationStats
import to.eyed.inferno.data.devThermalInfo
import to.eyed.inferno.data.devTokenCounter
import to.eyed.inferno.data.devTurnDetails
import to.eyed.inferno.engine.EngineState
import to.eyed.inferno.engine.loadedAny
import to.eyed.inferno.engine.LoadedModel
import to.eyed.inferno.ui.S
import to.eyed.inferno.ui.components.ErrorNotice
import to.eyed.inferno.ui.components.FormField
import to.eyed.inferno.ui.components.GlassButton
import to.eyed.inferno.ui.components.InfernoSheet
import to.eyed.inferno.ui.components.PrimaryButton
import to.eyed.inferno.ui.components.SheetHeader
import to.eyed.inferno.ui.models.ModelSheet
import to.eyed.inferno.ui.theme.Ink
import to.eyed.inferno.ui.theme.defaultEffectsSpec
import to.eyed.inferno.ui.theme.defaultSpatialSpec
import to.eyed.inferno.ui.theme.fastSpatialSpec
import to.eyed.inferno.ui.theme.rememberHaptics
import to.eyed.inferno.vm.AppViewModel
import to.eyed.inferno.vm.ChatViewModel
import to.eyed.inferno.vm.GenState
import to.eyed.inferno.vm.Screen
import to.eyed.inferno.vm.isBusy

/**
 * Chat shell (spec 5.6 ChatRoot): permanent 300 dp pane from the expanded width class, a modal drawer
 * below it; top nav, the reversed message list with the composer overlaid, the jump pill, the
 * in-tree image viewer (shared bounds) and the small sheets (turn details, rename, edit). Everything
 * purely visual lives here in rememberSaveable; everything else is read from the ViewModels.
 */
@Composable
fun ChatRoot(appVm: AppViewModel, chatVm: ChatViewModel, onBeforeSend: () -> Unit, onDrawerOpenChanged: (Boolean) -> Unit = {}) {
    val engine by appVm.engine.collectAsStateWithLifecycle()
    val settings by appVm.settings.collectAsStateWithLifecycle()
    val liveThreads by appVm.activeThreadsFlow.collectAsStateWithLifecycle()
    val models by appVm.models.collectAsStateWithLifecycle()
    val loadPlan by appVm.loadPlan.collectAsStateWithLifecycle()
    val messages by chatVm.messages.collectAsStateWithLifecycle()
    val gen by chatVm.gen.collectAsStateWithLifecycle()
    val pending by chatVm.pending.collectAsStateWithLifecycle()
    val usage by chatVm.contextUsage.collectAsStateWithLifecycle()
    val imageBusy by chatVm.imageBusy.collectAsStateWithLifecycle()
    val conversations by chatVm.conversations.collectAsStateWithLifecycle()
    val activeId by chatVm.activeId.collectAsStateWithLifecycle()
    val busyIds by chatVm.busyIds.collectAsStateWithLifecycle()
    val contextFull by chatVm.contextFull.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()

    var draft by rememberSaveable { mutableStateOf("") }
    var viewerId by rememberSaveable { mutableStateOf<String?>(null) }
    var detailsId by rememberSaveable { mutableStateOf<String?>(null) }
    var editId by rememberSaveable { mutableStateOf<String?>(null) }
    var renameId by rememberSaveable { mutableStateOf<String?>(null) }
    var modelSheet by rememberSaveable { mutableStateOf(false) }
    var cameraUri by rememberSaveable { mutableStateOf<String?>(null) }
    var announceId by remember { mutableStateOf<String?>(null) }
    var composerHeight by remember { mutableIntStateOf(0) }
    var dismissedError by remember { mutableStateOf<GenState.Error?>(null) }

    val loaded = engine.loadedAny
    val canAttachImages = when (val e = engine) {
        is EngineState.Loading -> e.model.hasVision
        else -> loaded?.let { it.hasVision && it.visionAllowed } ?: false
    }
    val noVisionReason = if (loaded != null && loaded.hasVision && !loaded.visionAllowed) S.noMemoryForVision else S.noVision
    val thinkingAvailable = loaded?.model?.catalog?.thinking?.hasTags == true
    // Idle with a selected, downloaded model is still sendable: the VM reloads and queues the turn (12.5).
    val idleLoadable = settings.selectedModelId?.let { id -> models.any { it.id == id && it.isDownloaded } } == true
    val disabledReason = when {
        engine is EngineState.Idle && !idleLoadable -> S.chooseModelToStart
        engine is EngineState.Error -> S.composerLoadModelToStart
        else -> null
    }
    val trimmedBefore = conversations.firstOrNull { it.id == activeId }?.trimmedBefore ?: 0
    // STOP policy: the ViewModel knows when the next prompt no longer fits; the panel waits for the current turn to end.
    val contextLimit = contextFull && !gen.isBusy
    val canCompact = activeId != null && messages.any { !it.isSummary } && !gen.isBusy && loaded != null && engine !is EngineState.Loading

    // ---- launchers -------------------------------------------------------------------------------------------
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(2)) { uris ->
        uris.take(2).forEach { chatVm.attachFromUri(it) }
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = cameraUri
        if (ok && uri != null) chatVm.attachCameraResult(Uri.parse(uri))
        cameraUri = null
    }
    val cameraAvailable = remember(context) {
        val pm = context.packageManager
        pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) && Intent(MediaStore.ACTION_IMAGE_CAPTURE).resolveActivity(pm) != null
    }
    val pickPhotos = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    val capturePhoto = {
        val uri = chatVm.newCameraUri()
        cameraUri = uri.toString()
        camera.launch(uri)
    }

    // ---- send / stop ------------------------------------------------------------------------------------------
    /** Auto-follow the tail of the list (see the list section below); a send always re-arms it. */
    var follow by remember { mutableStateOf(true) }
    val send = {
        val text = draft.trim()
        if (text.isNotEmpty() || pending.isNotEmpty()) {
            onBeforeSend()
            val accepted = !(gen.isBusy && gen !is GenState.Queued) && !imageBusy
            haptics.gestureEnd()
            chatVm.send(text)
            if (accepted) { draft = ""; follow = true }
        }
    }

    // Done: one Confirm (only while RESUMED) and the TalkBack "Answer complete" announcement on the latest turn.
    LaunchedEffect(Unit) {
        var wasBusy = gen.isBusy
        snapshotFlow { gen.isBusy }.distinctUntilChanged().collect { busy ->
            if (wasBusy && !busy) {
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) haptics.confirm()
                announceId = messages.lastOrNull { it.role == ChatRepository.ROLE_ASSISTANT }?.id
            }
            wasBusy = busy
        }
    }

    // ---- list + auto-follow ------------------------------------------------------------------------------------
    val listState = rememberLazyListState()
    var lastActive by rememberSaveable { mutableStateOf(activeId) }
    LaunchedEffect(activeId) { if (activeId != lastActive) { lastActive = activeId; listState.scrollToItem(0) } }
    val liveKey = when (val g = gen) { is GenState.Streaming -> if (g.text.isBlank()) "blank" else "text"; else -> g::class.simpleName }
    // Follow the tail while the reader is at the bottom; a reader who scrolled up is never fought - the pill is for
    // them. The decision is taken when a scroll ends, not when items arrive: a send puts two items under the
    // reader at once (their bubble and the live placeholder), which used to read as "two items up" and stranded
    // them above their own message.
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (!scrolling) follow = listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset < listState.layoutInfo.viewportSize.height / 2
        }
    }
    LaunchedEffect(messages.size, liveKey) { if (follow) listState.scrollToItem(0) }
    // The pill appears once the latest content is out of reach: the bottom item is fully hidden, or the reader
    // is more than half a screen up inside a long streaming answer (index 0 with a large offset).
    val showJump by remember {
        derivedStateOf {
            val half = listState.layoutInfo.viewportSize.height / 2
            listState.firstVisibleItemIndex >= 1 || listState.firstVisibleItemScrollOffset > half
        }
    }
    val scrolled by remember { derivedStateOf { listState.canScrollForward } }

    // ---- shell ------------------------------------------------------------------------------------------------
    val expanded = currentWindowAdaptiveInfo().windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND)
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    // Root hides its global chips while the drawer is out; targetValue flips as soon as a swipe commits.
    val drawerOut = !expanded && drawerState.targetValue == DrawerValue.Open
    LaunchedEffect(drawerOut) { onDrawerOpenChanged(drawerOut) }
    DisposableEffect(Unit) { onDispose { onDrawerOpenChanged(false) } }
    var paneVisible by rememberSaveable { mutableStateOf(true) }
    val closeNav: () -> Unit = { if (!expanded) scope.launch { drawerState.close() } }
    val openNav: () -> Unit = { if (expanded) paneVisible = !paneVisible else scope.launch { drawerState.open() } }
    val go: (Screen) -> Unit = { s -> closeNav(); appVm.navigate(s) }

    val sidebar: @Composable (Modifier) -> Unit = { m ->
        Sidebar(
            modifier = m,
            conversations = conversations, activeId = activeId, busyIds = busyIds,
            loadedModelName = loaded?.model?.displayName, deviceSummary = appVm.deviceSummary,
            onSelect = { id -> chatVm.open(id); closeNav() },
            onNew = { chatVm.newChat(); draft = ""; closeNav() },
            onCreateImage = { go(Screen.CREATE) }, onGallery = { go(Screen.GALLERY) },
            onRename = { renameId = it.id }, onPin = chatVm::pin, onArchive = chatVm::archive, onDelete = chatVm::delete,
            onOpenModels = { go(Screen.MODELS) }, onOpenSettings = { go(Screen.SETTINGS) }, onOpenBench = { go(Screen.BENCH) },
            onClose = if (expanded) null else closeNav,
            showBenchmark = settings.devBenchmark,
        )
    }

    // The viewer stays composed through its exit morph; the last attachment is kept for that frame span.
    val viewerAtt = viewerId?.let { id -> messages.asSequence().flatMap { it.images }.firstOrNull { it.id == id } }
    var lastViewer by remember { mutableStateOf(viewerAtt) }
    if (viewerAtt != null) lastViewer = viewerAtt
    val backProgress = remember { Animatable(0f) }
    val backSpring = fastSpatialSpec<Float>()
    PredictiveBackHandler(enabled = viewerAtt != null) { progress ->
        try {
            progress.collect { backProgress.snapTo(it.progress) }
            viewerId = null
            backProgress.snapTo(0f)
        } catch (e: CancellationException) {
            backProgress.animateTo(0f, backSpring)
        }
    }

    SharedTransitionLayout(Modifier.fillMaxSize()) {
        AnimatedVisibility(visible = viewerAtt == null, enter = fadeIn(defaultEffectsSpec()), exit = fadeOut(defaultEffectsSpec()), modifier = Modifier.fillMaxSize()) {
            val imageScope = this
            val chatContent: @Composable () -> Unit = {
                Column(Modifier.fillMaxSize()) {
                    TopNav(
                        engine = engine, contextUsage = usage, resolvedCtxLabel = loadPlan?.resolvedLabel, scrolled = scrolled,
                        showDrawerToggle = true, selectedModelName = if (idleLoadable) settings.selectedModelId?.let(appVm::modelDisplayName) else null,
                        onOpenDrawer = openNav, onOpenModelSheet = { modelSheet = true },
                        onNewChat = { haptics.tap(); chatVm.newChat(); draft = "" },
                        showContextRing = settings.devContextMeter,
                        computeLine = if (settings.devThermalInfo) ({ appVm.computeLine(liveThreads) }) else null,
                        onCompactNow = if (canCompact) ({ chatVm.compactNow() }) else null,
                    )
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        if (messages.isEmpty() && (!gen.isBusy || gen is GenState.Queued)) {
                            EmptyState(
                                engine = engine, canAttachImages = canAttachImages, loadable = idleLoadable,
                                onSuggestion = { draft = it }, onDescribePhoto = pickPhotos,
                                onChooseModel = { modelSheet = true },
                                modifier = Modifier.align(Alignment.Center).padding(bottom = 96.dp),
                                computeLine = if (settings.devThermalInfo) appVm.computeLine(liveThreads) else null,
                            )
                        } else {
                            MessagesList(
                                messages = messages, gen = gen, listState = listState, trimmedBefore = trimmedBefore,
                                announceId = announceId, imageScope = imageScope,
                                onRegenerate = chatVm::regenerate, onOpenImage = { viewerId = it.id },
                                onOpenTurnDetails = { detailsId = it.id }, onEdit = { editId = it.id },
                                modifier = Modifier.fillMaxSize(),
                                // The composer starts with a 32 dp gradient; the last turn rests 8 dp into it, so it never fades yet still scrolls under.
                                bottomInset = if (composerHeight == 0) 132.dp else with(LocalDensity.current) { composerHeight.toDp() } - 8.dp,
                                dev = ChatDevFlags(generationStats = settings.devGenerationStats, turnDetails = settings.devTurnDetails, tokenCounter = settings.devTokenCounter),
                            )
                        }
                        Column(Modifier.align(Alignment.BottomCenter).widthIn(max = ChatContentMaxWidth), horizontalAlignment = Alignment.CenterHorizontally) {
                            AnimatedVisibility(showJump, enter = fadeIn(defaultEffectsSpec()), exit = fadeOut(defaultEffectsSpec())) {
                                GlassButton(S.jumpToLatest, icon = Lucide.ChevronDown, onClick = { scope.launch { listState.animateScrollToItem(0) } })
                            }
                            // Measured (not the jump pill: it comes and goes while reading and must not shift the list).
                            Box(Modifier.fillMaxWidth().onSizeChanged { composerHeight = it.height }) {
                                if (contextLimit) {
                                    ContextFullPanel(
                                        used = usage.used, nCtx = usage.nCtx, canCarryOver = canCompact,
                                        onNewChat = { chatVm.newChat(); draft = "" },
                                        onCarryOver = { chatVm.carryOverSummary() },
                                        onSwitchToRolling = { chatVm.switchToRolling() },
                                        modifier = Modifier.padding(12.dp),
                                    )
                                } else {
                                    InputBar(
                                        value = draft, onValueChange = { draft = it },
                                        attachments = pending, onRemoveAttachment = chatVm::removeAttachment,
                                        canAttachImages = canAttachImages, cameraAvailable = cameraAvailable, noVisionReason = noVisionReason,
                                        onPickPhotos = pickPhotos, onCapturePhoto = capturePhoto, onNoVision = appVm::notice,
                                        gen = gen, engine = engine, disabledReason = disabledReason, imageBusy = imageBusy,
                                        onSend = send, onStop = chatVm::cancel,
                                        thinkingAvailable = thinkingAvailable, thinkingOn = settings.thinking, onToggleThinking = appVm::setThinking,
                                    )
                                }
                            }
                        }
                        (gen as? GenState.Error)?.takeIf { it != dismissedError }?.let { err ->
                            ErrorNotice(err.message, onDismiss = { dismissedError = err }, modifier = Modifier.align(Alignment.TopCenter).padding(horizontal = 16.dp, vertical = 8.dp))
                        }
                    }
                }
            }
            if (expanded) {
                Row(Modifier.fillMaxSize()) {
                    AnimatedVisibility(paneVisible, enter = expandHorizontally(defaultSpatialSpec()), exit = shrinkHorizontally(defaultSpatialSpec())) { sidebar(Modifier) }
                    Box(Modifier.weight(1f)) { chatContent() }
                }
            } else {
                // The root already applied safeDrawingPadding; the drawer alone reaches back under the status and
                // navigation bars (its surface and scrim would otherwise stop at the safe edge) and re-pads its content.
                val safe = WindowInsets.safeDrawing
                val density = LocalDensity.current
                val topInset = with(density) { safe.getTop(this).toDp() }
                val bottomInset = with(density) { safe.getBottom(this).toDp() }
                ModalNavigationDrawer(
                    drawerState = drawerState,
                    scrimColor = Color.Black.copy(alpha = 0.6f),
                    modifier = Modifier.bleedVertically(safe),
                    drawerContent = {
                        // Transparent, square sheet: the Sidebar's own I900 column is the visible surface.
                        ModalDrawerSheet(drawerState, drawerContainerColor = Color.Transparent, drawerShape = RectangleShape, windowInsets = WindowInsets(0)) {
                            Box(Modifier.fillMaxHeight().width(SidebarWidth).background(Ink.I900)) {
                                sidebar(Modifier.padding(top = topInset, bottom = bottomInset))
                            }
                        }
                    },
                ) { Box(Modifier.fillMaxSize().padding(top = topInset, bottom = bottomInset)) { chatContent() } }
            }
        }
        AnimatedVisibility(visible = viewerAtt != null, enter = fadeIn(defaultEffectsSpec()), exit = fadeOut(defaultEffectsSpec())) {
            lastViewer?.let { att -> ImageViewer(att, this, backProgress.value, onClose = { viewerId = null }) }
        }
    }

    // ---- sheets -----------------------------------------------------------------------------------------------
    if (modelSheet) ModelSheet(appVm, onDismiss = { modelSheet = false }, onOpenManager = { appVm.navigate(Screen.MODELS) })
    detailsId?.let { id ->
        val m = messages.firstOrNull { it.id == id }
        val stats = m?.stats?.takeIf { settings.devTurnDetails }
        if (stats == null) detailsId = null
        else TurnDetailsSheet(stats, stats.modelId?.let(appVm::modelDisplayName), onDismiss = { detailsId = null })
    }
    renameId?.let { id ->
        val c = conversations.firstOrNull { it.id == id }
        if (c == null) renameId = null
        else TextSheet(S.renameChat, S.chatTitle, c.displayTitle, S.save, multiline = false, onDismiss = { renameId = null }) { chatVm.rename(id, it); renameId = null }
    }
    editId?.let { id ->
        val m = messages.firstOrNull { it.id == id }
        if (m == null) editId = null
        else TextSheet(S.editMessage, S.chat, m.content, S.resend, multiline = true, onDismiss = { editId = null }) { chatVm.editAndResend(id, it); editId = null }
    }
}

/** Small sheet with one multi-line field and a primary action (rename a chat, edit a message). */
@Composable
private fun TextSheet(title: String, label: String, initial: String, action: String, multiline: Boolean, onDismiss: () -> Unit, onSubmit: (String) -> Unit) {
    var text by rememberSaveable(initial) { mutableStateOf(initial) }
    InfernoSheet(onDismiss = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            SheetHeader(title, Lucide.X, onLeading = onDismiss)
            // The sheet exists to edit this one value: focus it straight away; a title submits from the keyboard's Done.
            FormField(label, text, { text = it }, multiline = multiline, autoFocus = true, onDone = { if (text.isNotBlank()) onSubmit(text.trim()) })
            PrimaryButton(action, enabled = text.isNotBlank(), onClick = { onSubmit(text.trim()) }, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** Grows the node by the vertical [insets] and shifts it up by the top one: a child that must reach under the bars inside an already safe-padded parent. */
private fun Modifier.bleedVertically(insets: WindowInsets): Modifier = layout { measurable, constraints ->
    val top = insets.getTop(this)
    val extra = top + insets.getBottom(this)
    val placeable = measurable.measure(constraints.copy(minHeight = constraints.minHeight + extra, maxHeight = constraints.maxHeight + extra))
    layout(placeable.width, constraints.maxHeight) { placeable.place(0, -top) }
}
