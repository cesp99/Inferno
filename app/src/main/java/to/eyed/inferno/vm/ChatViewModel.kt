package to.eyed.inferno.vm

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import to.eyed.inferno.AppContainer
import to.eyed.inferno.data.Attachment
import to.eyed.inferno.data.ChatMessage
import to.eyed.inferno.data.ChatRepository
import to.eyed.inferno.data.Conversation
import to.eyed.inferno.data.MessageStats
import to.eyed.inferno.engine.ContextManager
import to.eyed.inferno.engine.EngineState
import to.eyed.inferno.engine.FinishReason
import to.eyed.inferno.engine.GenerationEvent
import to.eyed.inferno.engine.GenerationParams
import to.eyed.inferno.engine.GenerationStats
import to.eyed.inferno.engine.ImageEncodeSample
import to.eyed.inferno.engine.LoadedModel
import to.eyed.inferno.engine.PromptImage
import to.eyed.inferno.engine.PromptMessage
import to.eyed.inferno.engine.modelOrNull
import to.eyed.inferno.models.ImageDetail
import to.eyed.inferno.models.ThinkingSpec

/**
 * Conversations + generation (spec 5.5). One private [run] over the PERSISTED history; send / regenerate /
 * editAndResend differ only in what they do to the history first. `activeId` and the pending attachment ids
 * live in the SavedStateHandle.
 */
class ChatViewModel(private val c: AppContainer, private val handle: SavedStateHandle) : ViewModel() {
    private val appVm: AppViewModel get() = appVmRef ?: error("AppViewModel not bound")
    private var appVmRef: AppViewModel? = null
    /** Root binds the activity-scoped AppViewModel once so the crash-loop guard and notices share one owner. */
    fun bind(app: AppViewModel) {
        if (appVmRef != null) return
        appVmRef = app
        // A queued send waits for Ready; it fails when the engine settles in Idle/Error with no load in flight.
        viewModelScope.launch {
            combine(c.engine.state, app.loadInProgress) { s, loading -> s to loading }.collect { (s, loading) ->
                if (s is EngineState.Ready) pendingSend?.let { (text, atts) -> pendingSend = null; dispatch(text, atts) }
                if ((s is EngineState.Idle || s is EngineState.Error) && !loading && pendingSend != null) {
                    pendingSend = null
                    setGen(activeId.value ?: NO_CHAT, GenState.Error((s as? EngineState.Error)?.message ?: "Could not load the model"))
                }
            }
        }
    }

    data class ContextUsage(val used: Int, val nCtx: Int, val approximate: Boolean) {
        val fraction get() = if (nCtx > 0) used.toFloat() / nCtx else 0f
    }

    val conversations: StateFlow<List<Conversation>> = c.chats.conversations
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val activeId: StateFlow<String?> = handle.getStateFlow<String?>(KEY_ACTIVE, null)

    val messages: StateFlow<List<ChatMessage>> = activeId
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else c.chats.messages(id) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Per-conversation generation state; `gen` is the active chat's view of it. */
    private val genStates = MutableStateFlow<Map<String, GenState>>(emptyMap())
    val gen: StateFlow<GenState> = combine(activeId, genStates) { id, m -> (if (id == null) m[NO_CHAT] else m[id]) ?: GenState.Idle }
        .stateIn(viewModelScope, SharingStarted.Eagerly, GenState.Idle)
    val busyIds: StateFlow<Set<String>> = genStates.map { m -> m.filterValues { it.isBusy }.keys }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    private val _pending = MutableStateFlow(restorePending())
    val pending: StateFlow<List<Attachment>> = _pending.asStateFlow()

    private val _lastStats = MutableStateFlow<GenerationStats?>(null)
    val lastStats: StateFlow<GenerationStats?> = _lastStats.asStateFlow()
    private val _trimmedNotice = MutableStateFlow(0)
    val trimmedNotice: StateFlow<Int> = _trimmedNotice.asStateFlow()

    /** Wall time of the last model switch: assistant turns older than it carry a stale kvUsed (5.5 contextUsage). */
    private var modelSwitchAt = 0L
    private var lastModelId: String? = null

    val contextUsage: StateFlow<ContextUsage> = combine(messages, c.engine.state) { msgs, state ->
        val nCtx = state.loadedAny?.context?.nCtx ?: 0
        val last = msgs.lastOrNull { it.role == ChatRepository.ROLE_ASSISTANT && it.stats?.finishReason != null }
        val exact = last?.takeIf { it.createdAt >= modelSwitchAt }?.stats?.kvUsedTokens?.takeIf { it > 0 }
        if (exact != null) ContextUsage(exact, nCtx, approximate = false)
        else ContextUsage(msgs.sumOf { it.content.length } / 4, nCtx, approximate = true)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ContextUsage(0, 0, true))

    /** True while an image generation owns the native job: the composer disables Send (12.5). */
    val imageBusy: StateFlow<Boolean> = c.imageGen.state.map { it is to.eyed.inferno.imagegen.ImageGenUiState.Loading || it is to.eyed.inferno.imagegen.ImageGenUiState.Generating }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private var genJob: Job? = null
    private var pendingSend: Pair<String, List<Attachment>>? = null

    init {
        viewModelScope.launch {
            c.engine.state.collect { s ->
                val id = s.modelOrNull?.id
                if (id != lastModelId) { lastModelId = id; modelSwitchAt = System.currentTimeMillis() }
            }
        }
    }

    // ---- conversations ---------------------------------------------------------------------------------------

    /** Clears the active chat; the row is created lazily on the first send so the sidebar never fills with empties. */
    fun newChat() { handle[KEY_ACTIVE] = null; _trimmedNotice.value = 0 }
    fun open(id: String) { handle[KEY_ACTIVE] = id; _trimmedNotice.value = 0 }
    fun rename(id: String, title: String) = viewModelScope.launch { c.chats.setTitle(id, title) }
    fun pin(id: String, pinned: Boolean) = viewModelScope.launch { c.chats.setPinned(id, pinned) }
    fun archive(id: String, archived: Boolean) = viewModelScope.launch { c.chats.setArchived(id, archived) }
    fun delete(id: String) = viewModelScope.launch {
        if (id == activeId.value) { if (gen.value.isBusy) cancel(); handle[KEY_ACTIVE] = null }
        c.chats.delete(id)
    }
    fun deleteAll() = viewModelScope.launch { cancel(); handle[KEY_ACTIVE] = null; c.chats.deleteAll() }
    fun jumpToLatest() { /* UI-only helper: the list owner scrolls; kept for the 5.5 surface */ }

    // ---- attachments -----------------------------------------------------------------------------------------

    fun attachFromUri(uri: Uri) = viewModelScope.launch {
        if (_pending.value.size >= MAX_ATTACHMENTS) return@launch
        runCatching { c.images.importImage(uri) }
            .onSuccess { att -> _pending.update { list -> if (list.any { it.id == att.id } || list.size >= MAX_ATTACHMENTS) list else list + att }; savePending() }
            .onFailure { appVmRef?.notice(it.message ?: "Couldn't read that image") }
    }
    /** Output URI for ACTION_IMAGE_CAPTURE (additive WP7 helper; the UI keeps it in rememberSaveable across the camera app). */
    fun newCameraUri(): Uri = c.images.newCameraUri()
    fun attachCameraResult(uri: Uri) = viewModelScope.launch {
        attachFromUri(uri).join()
        c.images.clearCache()
    }
    fun removeAttachment(index: Int) = viewModelScope.launch {
        val att = _pending.value.getOrNull(index) ?: return@launch
        _pending.update { it.filterIndexed { i, _ -> i != index } }
        savePending()
        c.images.deleteIfUnreferenced(att.id, c.db.dao().imageRefs(att.id))
    }

    private fun restorePending(): List<Attachment> =
        handle.get<ArrayList<String>>(KEY_PENDING)?.mapNotNull { s ->
            val p = s.split(':'); if (p.size == 3) c.images.attachment(p[0], p[1].toIntOrNull() ?: 0, p[2].toIntOrNull() ?: 0) else null
        } ?: emptyList()
    private fun savePending() { handle[KEY_PENDING] = ArrayList(_pending.value.map { "${it.id}:${it.width}:${it.height}" }) }

    // ---- send / regenerate / edit / cancel ---------------------------------------------------------------------

    /** Ready/Suspended: run now; Loading: queue one turn (replacing an earlier queued one); Idle: "Choose a model first". */
    fun send(text: String) {
        val current = gen.value
        if (current.isBusy && current !is GenState.Queued) { appVmRef?.notice("Stop the current answer first"); return }
        if (imageBusy.value) { appVmRef?.notice("Wait for the image to finish first"); return }
        val atts = _pending.value
        fun queue() {
            pendingSend = text to atts
            _pending.value = emptyList(); savePending()
            setGen(activeId.value ?: NO_CHAT, GenState.Queued(text))
        }
        when (c.engine.state.value) {
            is EngineState.Loading -> queue()
            is EngineState.Idle, is EngineState.Error -> {
                // Idle with a selected local model (e.g. after an image generation released the LLM, 12.5):
                // reload it and queue the turn instead of bouncing the user to the model picker.
                val selected = c.prefs.settings.value.selectedModelId?.let { c.models.local(it) }
                if (selected != null && appVmRef != null) { appVmRef!!.selectAndLoad(selected.id); queue() }
                else appVmRef?.notice("Choose a model first")
            }
            else -> dispatch(text, atts)
        }
    }

    private fun dispatch(text: String, atts: List<Attachment>) {
        genJob = viewModelScope.launch {
            val id = activeId.value ?: c.chats.create(c.engine.state.value.modelOrNull?.id).id.also { handle[KEY_ACTIVE] = it }
            genStates.update { it - NO_CHAT }
            c.chats.appendUser(id, text, atts)
            if (_pending.value == atts) { _pending.value = emptyList(); savePending() }
            run(id)
        }
    }

    fun regenerate() {
        if (!canStart()) return
        val id = activeId.value ?: return
        genJob = viewModelScope.launch {
            val last = c.chats.messagesOnce(id).lastOrNull { it.role == ChatRepository.ROLE_ASSISTANT }
            if (last != null) c.chats.truncateFrom(id, last.orderIndex)
            run(id)
        }
    }

    /** Drops everything from the edited message on (keeping its images) and re-runs; the message need not be the last user turn. */
    fun editAndResend(messageId: String, newText: String) {
        if (!canStart()) return
        val id = activeId.value ?: return
        genJob = viewModelScope.launch {
            val msg = c.chats.message(messageId) ?: return@launch
            c.chats.truncateFrom(id, msg.orderIndex)
            c.chats.appendUser(id, newText, msg.images)
            run(id)
        }
    }

    fun cancel() {
        pendingSend = null
        val id = activeId.value ?: NO_CHAT
        if (gen.value is GenState.Queued) setGen(id, GenState.Idle)
        genJob?.cancel()
    }

    private fun canStart(): Boolean {
        val s = c.engine.state.value
        if (gen.value.isBusy) { appVmRef?.notice("Stop the current answer first"); return false }
        if (imageBusy.value) { appVmRef?.notice("Wait for the image to finish first"); return false }
        if (s is EngineState.Idle || s is EngineState.Error || s is EngineState.Loading) { appVmRef?.notice("Choose a model first"); return false }
        return true
    }

    // ---- the pipeline ----------------------------------------------------------------------------------------

    private suspend fun run(conversationId: String) {
        val settings = c.prefs.settings.value
        val loaded: LoadedModel = c.engine.state.value.loadedAny
            ?: run { setGen(conversationId, GenState.Error("Choose a model first")); return }
        val model = loaded.model
        val catalog = model.catalog
        val thinking = catalog?.thinking ?: ThinkingSpec()
        val thinkingOn = settings.thinking && thinking.hasTags
        val params = appVm.effectiveParams()
        val detail = settings.imageDetail
        val stream = Streamer(conversationId)
        setGen(conversationId, GenState.Prefill(0, 0, false))
        var assistantId: String? = null
        try {
            val history = c.chats.messagesOnce(conversationId)
            val conv = conversations.value.firstOrNull { it.id == conversationId }
            // (1) prompt from the persisted history: assistant turns are content only, never their reasoning.
            val prompt = history.map { m ->
                if (m.role == ChatRepository.ROLE_USER) PromptMessage("user", ContextManager.sanitize(m.content), m.images.map { it.id })
                else PromptMessage("assistant", m.content)
            }
            // (2) real RGB for every image still in the prompt (capped at 8, newest kept); the engine skips re-encoding on an id hit.
            val attachments = history.flatMap { it.images }.distinctBy { it.id }.takeLast(MAX_PROMPT_IMAGES)
            val keep = attachments.map { it.id }.toSet()
            val images: List<PromptImage> = if (loaded.visionAllowed && loaded.hasVision) attachments.map { c.images.toPromptImage(it, model, detail) } else emptyList()
            val messages0 = prompt.map { it.copy(imageIds = it.imageIds.filter { id -> id in keep && images.any { img -> img.id == id } }) }
            val system = settings.systemPrompt.takeIf { it.isNotBlank() }
            val nCtx = loaded.context.nCtx
            // (3) message-level truncation with hysteresis (state Trimming only when something is actually dropped).
            val (messages, promptImages) = if (settings.autoTrim) {
                val fit = c.contextManager.fit(system, messages0, images, nCtx, c.contextManager.reserveFor(params, nCtx, thinkingOn), conv?.trimmedBefore ?: 0)
                if (fit.trimmedThisTurn) {
                    setGen(conversationId, GenState.Trimming)
                    c.chats.setTrimmedBefore(conversationId, fit.trimmedBefore)
                    _trimmedNotice.value = fit.droppedCount + fit.droppedImages
                } else _trimmedNotice.value = 0
                fit.messages to fit.images
            } else {
                _trimmedNotice.value = 0
                (if (system == null) messages0 else listOf(PromptMessage("system", system)) + messages0) to images
            }
            val expectedEncodeMs = if (promptImages.isEmpty()) 0L else expectedEncodeMs(settings.calibration[model.id]?.imageEncode?.get(detail.name), catalog?.encodeMsAt448, detail, promptImages)
            appVm.beginFirstTurn()
            setGen(conversationId, GenState.Prefill(0, 0, false, expectedEncodeMs))
            // (4) stream
            c.engine.generate(conversationId, messages, promptImages, params, thinking, settings.thinking, detail).collect { ev ->
                when (ev) {
                    is GenerationEvent.Resuming -> setGen(conversationId, GenState.Prefill(0, 0, false, expectedEncodeMs))
                    is GenerationEvent.Prefill -> setGen(conversationId, GenState.Prefill(ev.done, ev.total, ev.encodingImage, expectedEncodeMs))
                    is GenerationEvent.Thinking -> { stream.thinking(ev.text); stream.publish(false) }
                    is GenerationEvent.Token -> {
                        stream.token(ev.text)
                        if (assistantId == null) assistantId = c.chats.appendAssistant(conversationId, stream.textStr, stream.reasoningOrNull(), null).id
                        else if (stream.shouldPersist()) c.chats.updateAssistant(assistantId!!, stream.textStr, stream.reasoningOrNull(), null)
                        stream.publish(false)
                    }
                    is GenerationEvent.Done -> {
                        val stats = toStats(ev.stats, ev.reason.name, model.id, loaded, params)
                        if (assistantId == null) assistantId = c.chats.appendAssistant(conversationId, stream.textStr, stream.reasoningOrNull(), stats).id
                        else c.chats.updateAssistant(assistantId, stream.textStr, stream.reasoningOrNull(), stats)
                        _lastStats.value = ev.stats
                        if (ev.stats.imageEncodeMs > 0 && promptImages.isNotEmpty()) persistEncodeSample(model.id, detail, ev.stats.imageEncodeMs, promptImages)
                        appVm.markFirstTurnOk()
                        stream.close()
                        setGen(conversationId, GenState.Idle)
                    }
                    GenerationEvent.NeedsTruncation -> { stream.close(); setGen(conversationId, GenState.Error("Conversation is longer than the context window. Enable auto-trim or start a new chat.")) }
                    is GenerationEvent.Error -> {
                        assistantId?.let { c.chats.updateAssistant(it, stream.textStr, stream.reasoningOrNull(), errorStats(model.id)) }
                        stream.close()
                        setGen(conversationId, GenState.Error(ev.message))
                    }
                }
            }
            stream.close()
            if (genStates.value[conversationId]?.isBusy == true) setGen(conversationId, GenState.Idle)
        } catch (e: CancellationException) {
            // User Stop: keep whatever streamed, marked as stopped.
            stream.close()
            withContext(NonCancellable) {
                assistantId?.let { c.chats.updateAssistant(it, stream.textStr, stream.reasoningOrNull(), errorStats(model.id, FinishReason.CANCELLED.name)) }
                setGen(conversationId, GenState.Idle)
            }
            throw e
        } catch (e: Exception) {
            stream.close()
            assistantId?.let { c.chats.updateAssistant(it, stream.textStr, stream.reasoningOrNull(), errorStats(model.id)) }
            setGen(conversationId, GenState.Error(e.message ?: "Generation failed"))
        }
    }

    /** Accumulates the turn and publishes GenState at <= 12.5 Hz with a 2 Hz tok/s EMA. */
    private inner class Streamer(private val id: String) {
        val text = StringBuilder()
        val reasoning = StringBuilder()
        private val startNs = System.nanoTime()
        private var firstTokenNs = 0L
        private var lastTokenNs = 0L
        private var thinkingEndNs = 0L
        private var tokens = 0
        private var tps = 0f
        private var shownTps = 0f
        private var lastTpsPublish = 0L
        private var lastPublish = 0L
        private var trailing: Job? = null
        private var closed = false
        /** Terminal event reached: no publish (including the trailing one) may land after it. */
        fun close() { closed = true; trailing?.cancel(); trailing = null }
        private var lastPersistTokens = 0
        private var lastPersistNs = 0L
        fun reasoningOrNull() = reasoning.toString().takeIf { it.isNotEmpty() }
        val textStr: String get() = text.toString()
        fun thinking(piece: String) { tick(); reasoning.append(piece) }
        fun token(piece: String) { tick(); if (thinkingEndNs == 0L && reasoning.isNotEmpty()) thinkingEndNs = System.nanoTime(); text.append(piece) }
        private fun tick() {
            val now = System.nanoTime()
            if (firstTokenNs == 0L) firstTokenNs = now
            else { val inst = 1e9f / (now - lastTokenNs).coerceAtLeast(1); tps = if (tps == 0f) inst else 0.8f * tps + 0.2f * inst }
            lastTokenNs = now; tokens++
        }
        fun shouldPersist(): Boolean {
            val now = System.nanoTime()
            if (tokens - lastPersistTokens >= PERSIST_TOKENS || now - lastPersistNs >= PERSIST_NS) { lastPersistTokens = tokens; lastPersistNs = now; return true }
            return false
        }
        fun publish(force: Boolean) {
            if (closed) return
            val now = System.nanoTime()
            if (!force && now - lastPublish < PUBLISH_NS) {
                if (trailing == null) trailing = viewModelScope.launch { delay(PUBLISH_NS / 1_000_000); trailing = null; publish(true) }
                return
            }
            lastPublish = now
            if (now - lastTpsPublish >= TPS_NS) { lastTpsPublish = now; shownTps = (tps * 10).toInt() / 10f }
            val thinkingMs = when { reasoning.isEmpty() -> 0L; thinkingEndNs > 0 -> (thinkingEndNs - startNs) / 1_000_000; else -> (now - startNs) / 1_000_000 }
            val state = if (text.isEmpty() && reasoning.isNotEmpty()) GenState.Thinking(reasoning.toString(), shownTps, thinkingMs)
            else GenState.Streaming(text.toString(), reasoning.toString(), shownTps, tokens, thinkingMs)
            setGen(id, state)
        }
    }

    private fun setGen(id: String, state: GenState) {
        genStates.update { m -> if (state is GenState.Idle) m - id else m + (id to state) }
    }

    private fun toStats(s: GenerationStats, finish: String, modelId: String, l: LoadedModel, params: GenerationParams) = MessageStats(
        promptTokens = s.promptTokens, reusedTokens = s.reusedTokens, generatedTokens = s.generatedTokens, prefillMs = s.prefillMs,
        decodeMs = s.decodeMs, imageEncodeMs = s.imageEncodeMs, kvUsedTokens = s.kvUsedTokens, nCtx = s.nCtx,
        paramsJson = json.encodeToString(GenerationParams.serializer(), params), templateName = l.templateName, templateSupported = l.templateSupported,
        finishReason = finish, modelId = modelId,
    )

    private fun errorStats(modelId: String, finish: String = FinishReason.ERROR.name) =
        MessageStats(promptTokens = 0, generatedTokens = 0, prefillMs = 0, decodeMs = 0, finishReason = finish, modelId = modelId)

    /** Calibration sample (ms per image at this detail), else the catalog 448 px figure scaled by pixels, else the llama-bench fallbacks. */
    private fun expectedEncodeMs(sample: ImageEncodeSample?, msAt448: Int?, detail: ImageDetail, images: List<PromptImage>): Long {
        val px = images.sumOf { it.width.toLong() * it.height }
        if (sample != null && sample.ms > 0) return sample.ms * images.size
        if (msAt448 != null && msAt448 > 0) return (msAt448 * px / (448.0 * 448)).toLong()
        return when (detail) { ImageDetail.FAST -> 8_400L; ImageDetail.BALANCED -> 11_400L; ImageDetail.HIGH -> 13_100L } * images.size
    }

    private suspend fun persistEncodeSample(modelId: String, detail: ImageDetail, ms: Long, images: List<PromptImage>) {
        val cal = c.prefs.settings.value.calibration[modelId] ?: c.engine.loaded?.calibration ?: return
        val per = ms / images.size
        c.prefs.setCalibration(modelId, cal.copy(imageEncode = cal.imageEncode + (detail.name to ImageEncodeSample(per, 0))))
    }

    /** Ready, Generating or Suspended: the facts of the resident weights (loadedOrNull excludes Suspended). */
    private val EngineState.loadedAny: LoadedModel?
        get() = when (this) { is EngineState.Ready -> loaded; is EngineState.Generating -> loaded; is EngineState.Suspended -> loaded; else -> null }

    private companion object {
        const val KEY_ACTIVE = "activeId"
        const val KEY_PENDING = "pendingIds"
        /** Key for the gen state of a not-yet-created chat (queued send before the first message). */
        const val NO_CHAT = ""
        const val MAX_ATTACHMENTS = 2
        const val MAX_PROMPT_IMAGES = 8
        const val PUBLISH_NS = 80_000_000L
        const val TPS_NS = 500_000_000L
        const val PERSIST_NS = 2_000_000_000L
        const val PERSIST_TOKENS = 200
        val json = Json { encodeDefaults = true }
    }
}
