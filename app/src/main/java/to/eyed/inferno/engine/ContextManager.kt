package to.eyed.inferno.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import to.eyed.inferno.data.KvCachePref
import to.eyed.inferno.data.SettingsState
import to.eyed.inferno.models.ImageDetail
import to.eyed.inferno.models.LocalModel
import to.eyed.inferno.models.ModelFamily

/** The two engine calls the planner needs. InferenceEngine implements it; unit tests use a fake. */
interface PlannerEngine {
    suspend fun estimateMemory(model: LocalModel, config: ContextConfig): MemoryEstimate
    suspend fun countPromptTokens(messages: List<PromptMessage>, images: List<PromptImage>): Int
}

class ContextManager(private val engine: PlannerEngine) {

    data class Plan(val config: ContextConfig, val estimate: MemoryEstimate, val clampedFrom: Int?, val reason: String?,
                    val auto: Boolean, val resolvedLabel: String,
                    /** false => the model fits for text only; images are disabled for this session ("Not enough memory for images with this model"). */
                    val visionAllowed: Boolean)

    data class Fit(val messages: List<PromptMessage>, val images: List<PromptImage>, val droppedCount: Int, val droppedImages: Int,
                   val promptTokens: Int, val trimmedBefore: Int, val trimmedThisTurn: Boolean)

    /**
     * One compaction (ContextPolicy.COMPACT): [cut] = index into the history of the first message that stays verbatim
     * (history[0, cut) is folded into the summary), [request] = the prompt that asks the model for the summary,
     * [maxTokens] its output cap. [droppedMaterial] counts oldest messages left out of the request because even the
     * request did not fit (they are lost, exactly as rolling would have lost them).
     */
    data class CompactPlan(val cut: Int, val request: List<PromptMessage>, val maxTokens: Int, val droppedMaterial: Int)

    /** Choose the context configuration for a model given settings + free RAM. Runs estimateMemory on the engine thread. */
    suspend fun plan(model: LocalModel, settings: SettingsState, cpu: CpuTopology): Plan {
        val availRam = cpu.availRamBytes()
        val budget = budgetBytes(availRam)
        val gate = (BUDGET_GATE * budget).toLong()
        val catalog = model.catalog
        // Imports carry no catalog row: read the training context and KV geometry from the GGUF header (IO, ~ms).
        val facts = if (catalog == null) withContext(Dispatchers.IO) { GgufHeader.read(model.textPath) } else null
        val nCtxTrain = catalog?.contextMax ?: facts?.contextLength ?: DEFAULT_CTX_TRAIN
        val cap = roundDown(minOf(nCtxTrain, MAX_CTX), CTX_STEP).coerceAtLeast(MIN_CTX)
        val floor = roundDown(minOf(catalog?.defaultCtx ?: 8192, nCtxTrain), CTX_STEP).coerceIn(MIN_CTX, cap)
        val explicit = settings.contextSize.takeIf { it > 0 }
        val nBatch = batchFor(model, settings.imageDetail)
        val swaFull = catalog?.family !in setOf(ModelFamily.GEMMA3, ModelFamily.GEMMA4)
        val kvPerTokenF16 = catalog?.kvBytesPerTokenF16?.toLong() ?: facts?.kvBytesPerTokenF16 ?: DEFAULT_KV_BYTES_PER_TOKEN
        fun config(nCtx: Int): ContextConfig {
            val kvType = kvTypeFor(settings.kvCache, kvPerTokenF16 * nCtx, budget)
            return ContextConfig(
                nCtx = nCtx, nBatch = nBatch, nUbatch = nBatch, nThreads = settings.threads, nThreadsBatch = settings.threads,
                bigCoresOnly = settings.pinBigCores, flashAttention = settings.flashAttention || kvType != KvCacheType.F16,
                kvType = kvType, swaFull = swaFull, poll = settings.poll,
            )
        }
        val cache = HashMap<Int, MemoryEstimate>()
        var probes = 0
        suspend fun estimate(nCtx: Int): MemoryEstimate = cache[nCtx] ?: engine.estimateMemory(model, config(nCtx)).also { cache[nCtx] = it; probes++ }
        fun need(e: MemoryEstimate, vision: Boolean) = if (vision) e.totalBytes else e.totalBytes - e.visionBytes
        suspend fun fits(nCtx: Int, vision: Boolean) = need(estimate(nCtx), vision) <= gate
        // Auto growth stops short of the gate: availMem keeps moving between plan() and the engine's own gate check
        // (page cache of a just-downloaded file, the image engine unloading), and a plan sitting exactly on the edge
        // was refused a second later on the phone. The floor/explicit checks keep the plain gate.
        val growGate = gate - (GROW_HEADROOM * budget).toLong()
        suspend fun grows(nCtx: Int, vision: Boolean) = need(estimate(nCtx), vision) <= growGate

        // Largest CTX_STEP multiple in [lo, hi] that fits under the growth gate, lo known to fit. Tries the cap first (one probe when RAM is plentiful).
        suspend fun searchUp(lo: Int, hi: Int, vision: Boolean): Int {
            var l = lo; var h = hi
            if (h <= l) return l
            val limit = probes + MAX_PROBES
            if (grows(h, vision)) return h
            while (h - l > CTX_STEP && probes < limit) {
                val mid = roundDown(l + (h - l) / 2, CTX_STEP)
                if (mid <= l) break
                if (grows(mid, vision)) l = mid else h = mid
            }
            return l
        }
        // Largest CTX_STEP multiple in [MIN_CTX, start) that fits, or null when even MIN_CTX does not.
        suspend fun searchDown(start: Int, vision: Boolean): Int? {
            if (!fits(MIN_CTX, vision)) return null
            var l = MIN_CTX; var h = start
            val limit = probes + MAX_PROBES
            while (h - l > CTX_STEP && probes < limit) {
                val mid = roundDown(l + (h - l) / 2, CTX_STEP)
                if (mid <= l) break
                if (fits(mid, vision)) l = mid else h = mid
            }
            return l
        }

        val start = explicit?.let { roundDown(minOf(it, cap), CTX_STEP).coerceAtLeast(MIN_CTX) } ?: floor
        var vision = model.hasVision
        var clampedFrom: Int? = null
        var reason: String? = null
        var startFits = fits(start, vision)
        // Step 6 (Auto only): when the floor does not fit with vision but does text-only, run the session text-only rather
        // than shrinking the context. An explicit size is clamped down first (the user asked for vision + that size).
        if (!startFits && vision && explicit == null && fits(start, false)) { vision = false; startFits = true }
        val nCtx: Int = if (startFits) {
            if (explicit != null) start else searchUp(start, cap, vision)
        } else {
            clampedFrom = start
            var down = searchDown(start, vision)
            if (down == null && vision) { vision = false; down = searchDown(start, false) }
            down ?: run {
                val e = estimate(MIN_CTX)
                // Mapped weights are evictable and never counted; the LMK margin is (5.2 step 6).
                val needBytes = e.modelResidentBytes + e.kvBytes + e.computeBytes + BUDGET_MARGIN
                reason = "Needs about ${gb(needBytes)} GB free, ${gb(availRam)} GB available"
                MIN_CTX
            }
        }
        val config = config(nCtx)
        val estimate = estimate(nCtx)
        val label = "${if (explicit == null) "Auto" else "Custom"} · ${tokens(nCtx)} tokens · ${gb(need(estimate, vision))} GB"
        EngineLog.i(TAG, "plan ${model.id}: nCtx=$nCtx kv=${config.kvType} batch=$nBatch vision=$vision probes=$probes clampedFrom=$clampedFrom reason=$reason")
        return Plan(config, estimate, clampedFrom, reason, auto = explicit == null, resolvedLabel = label,
            visionAllowed = vision || !model.hasVision)
    }

    /**
     * Message-level truncation with hysteresis: keep system + newest messages. Never splits a message. When the
     * prompt does not fit, drops oldest user+assistant pairs until `tokens + reserve <= 0.75 * nCtx` (one re-prefill
     * per ~25 % of context, not one per turn), and only if that still fails drops images oldest-first.
     * trimmedThisTurn is true only when something was dropped now (drives TrimmedDivider).
     */
    suspend fun fit(system: String?, history: List<PromptMessage>, images: List<PromptImage>, nCtx: Int, reserve: Int, trimmedBefore: Int): Fit {
        val byId = images.associateBy { it.id }
        val lastUser = history.indexOfLast { it.role == "user" }.coerceAtLeast(0)
        var start = trimmedBefore.coerceIn(0, history.size)
        // The window must open on a user turn: an orphan assistant message confuses the template and the prefix cache.
        start = nextUserIndex(history, start) ?: start
        var dropped = 0
        var droppedImages = 0
        var current = history.subList(start, history.size)
        var messages = assemble(system, current)
        var tokens = count(messages, byId)
        if (tokens + reserve <= nCtx) {
            return Fit(messages, placeholders(messages, byId, full = true), 0, 0, tokens, start, trimmedThisTurn = false)
        }
        val target = (TRIM_TARGET * nCtx).toLong()
        while (tokens + reserve > target) {
            val next = nextUserIndex(history, start + 1) ?: break
            if (next > lastUser) break
            dropped += next - start
            start = next
            current = history.subList(start, history.size)
            messages = assemble(system, current)
            tokens = count(messages, byId)
        }
        if (tokens + reserve > nCtx) {
            // Still too long with only the newest turn(s): images are the bulkiest tokens, drop them oldest-first.
            val order = messages.flatMap { it.imageIds }
            for (id in order) {
                messages = messages.map { m -> if (id in m.imageIds) m.copy(imageIds = m.imageIds - id) else m }
                droppedImages++
                tokens = count(messages, byId)
                if (tokens + reserve <= nCtx) break
            }
        }
        return Fit(messages, placeholders(messages, byId, full = true), dropped, droppedImages, tokens, start,
            trimmedThisTurn = dropped > 0 || droppedImages > 0)
    }

    /** Token count of system + [history] with image placeholders (the policy checks need the number before any generate). */
    suspend fun measure(system: String?, history: List<PromptMessage>, images: List<PromptImage>): Int =
        count(assemble(system, history), images.associateBy { it.id })

    /**
     * Where to cut for a compaction and what to ask the model. [history] = the user/assistant turns after the previous
     * compaction point (no summary rows), [previousSummary] = the summary they follow, if any: it is part of the
     * material so chained compactions lose nothing the model already remembered.
     *
     * Keeps the newest [keepTurns] user turns verbatim, fewer when they alone (plus a summary of [COMPACT_MAX_TOKENS])
     * would not sit under [COMPACT_TARGET] of the window, so one compaction buys real room instead of firing again on
     * the next turn. The newest user turn is never summarized away: it is the question the model is about to answer.
     * [keepTurns] = 0 (carry-over into a fresh chat) folds everything except an unanswered trailing user message.
     * Returns null when there is nothing older than the kept turns.
     */
    suspend fun planCompaction(system: String?, previousSummary: String?, history: List<PromptMessage>, nCtx: Int, reserve: Int,
                               keepTurns: Int = COMPACT_KEEP_TURNS): CompactPlan? {
        if (history.isEmpty()) return null
        val userIdx = history.indices.filter { history[it].role == "user" }
        val unansweredTail = history.last().role == "user"
        // Cut for k kept user turns (the k-th user index from the end); k = 0 keeps only an unanswered trailing question.
        fun cutFor(k: Int): Int = when {
            k <= 0 -> if (unansweredTail) history.lastIndex else history.size
            else -> userIdx.getOrNull(userIdx.size - k) ?: 0
        }
        val target = (COMPACT_TARGET * nCtx).toLong()
        val maxTokens = minOf(COMPACT_MAX_TOKENS, nCtx / 4)
        var k = if (keepTurns <= 0) 0 else minOf(keepTurns, userIdx.size).coerceAtLeast(1)
        var cut = cutFor(k)
        while (k > 1) {
            val kept = history.subList(cut, history.size)
            if (count(assemble(system, kept), emptyMap()) + maxTokens + reserve <= target) break
            k--; cut = cutFor(k)
        }
        val material = history.subList(0, cut)
        if (material.isEmpty()) return null
        // The request itself must fit: drop the oldest material (never the previous summary) until it does.
        var dropped = 0
        var request = compactionRequest(previousSummary, material)
        while (material.size - dropped > 1 && count(request, emptyMap()) + maxTokens > nCtx) {
            dropped++
            request = compactionRequest(previousSummary, material.subList(dropped, material.size))
        }
        return CompactPlan(cut, request, maxTokens, dropped)
    }

    /** True when the assembled next prompt has crossed the trim target: time to compact before sending it. */
    fun needsCompaction(tokens: Int, reserve: Int, nCtx: Int): Boolean = tokens + reserve > (TRIM_TARGET * nCtx).toLong()

    /** The fixed compaction prompt: instruction as system, the transcript (previous summary first) as one user turn. */
    fun compactionRequest(previousSummary: String?, material: List<PromptMessage>): List<PromptMessage> {
        val sb = StringBuilder()
        if (previousSummary != null) sb.append("Your earlier notes (previous summary):\n").append(previousSummary).append("\n\n")
        for (m in material) {
            sb.append(if (m.role == "user") "User: " else "Assistant: ")
            sb.append(sanitize(m.content).trim())
            if (m.imageIds.isNotEmpty()) sb.append(" [").append(m.imageIds.size).append(" image(s) attached]")
            sb.append("\n\n")
        }
        return listOf(PromptMessage("system", COMPACT_INSTRUCTION), PromptMessage("user", sb.toString().trimEnd()))
    }

    /** Cheap analytic KV estimate for the live slider label (no native call). bytesPerElement: F16 2, Q8_0 ~1.06, Q4_0 ~0.56. */
    fun kvBytesAnalytic(nCtx: Int, nLayer: Int, nEmbdKGqa: Int, nEmbdVGqa: Int, kvType: KvCacheType): Long {
        val bytesPerElement = when (kvType) { KvCacheType.F16 -> 2.0; KvCacheType.Q8_0 -> 34.0 / 32; KvCacheType.Q4_0 -> 18.0 / 32 }
        return (nCtx.toLong() * nLayer * (nEmbdKGqa + nEmbdVGqa) * bytesPerElement).toLong()
    }

    /** reserve = clamp(maxTokens or nCtx/8, 1024, 4096) when thinking is on, else 512 (5.2). */
    fun reserveFor(params: GenerationParams, nCtx: Int, thinkingOn: Boolean): Int =
        if (thinkingOn) (if (params.maxTokens > 0) params.maxTokens else nCtx / 8).coerceIn(1024, 4096) else 512

    private suspend fun count(messages: List<PromptMessage>, byId: Map<String, PromptImage>): Int =
        engine.countPromptTokens(messages, placeholders(messages, byId, full = false))

    private fun assemble(system: String?, history: List<PromptMessage>): List<PromptMessage> =
        if (system.isNullOrBlank()) history else listOf(PromptMessage("system", system)) + history

    /** Images referenced by [messages], in prompt order; placeholders (rgb = null) for counting, real bytes for the turn. */
    private fun placeholders(messages: List<PromptMessage>, byId: Map<String, PromptImage>, full: Boolean): List<PromptImage> =
        messages.flatMap { it.imageIds }.distinct().mapNotNull { byId[it] }.map { if (full) it else it.copy(rgb = null) }

    private fun nextUserIndex(history: List<PromptMessage>, from: Int): Int? {
        for (i in from until history.size) if (history[i].role == "user") return i
        return null
    }

    companion object {
        private const val TAG = "ContextManager"
        const val MIN_CTX = 2048; const val CTX_STEP = 1024; const val MAX_CTX = 131072
        const val TRIM_TARGET = 0.75f
        // ---- Context policy (COMPACT / STOP) ----
        /** After a compaction the kept turns + a full-size summary + the reserve stay under this share of the window. */
        const val COMPACT_TARGET = 0.5f
        const val COMPACT_KEEP_TURNS = 4
        const val COMPACT_MAX_TOKENS = 512
        const val COMPACT_TEMPERATURE = 0.3f
        const val COMPACT_INSTRUCTION = "Summarize the conversation so far for your own memory: facts, decisions, user preferences, open tasks. Be concise, bullet points, no preamble."
        /** STOP shows the blocking notice from this share of the window even before a turn fails to fit. */
        const val STOP_FRACTION = 0.92f
        /** STOP policy: the chat has reached the model's memory limit (usage share, or the next prompt not fitting is checked by the caller). */
        fun contextFull(used: Int, nCtx: Int): Boolean = nCtx > 0 && used >= (STOP_FRACTION * nCtx).toInt()
        /** The system text the model sees: the user's system prompt plus the latest summary as a memory note. */
        fun systemWith(systemPrompt: String?, summary: String?): String? {
            val parts = listOfNotNull(systemPrompt?.takeIf { it.isNotBlank() }, summary?.takeIf { it.isNotBlank() }?.let { "Summary of the earlier conversation (your own notes):\n$it" })
            return parts.takeIf { it.isNotEmpty() }?.joinToString("\n\n")
        }
        /** Planner gate: a plan is accepted when its estimate stays below this fraction of the budget (spec 5.2 step 4, 12.3 a). */
        const val BUDGET_GATE = 0.85
        /** Share of the budget Auto leaves free above the floor, so the load-time gate check has slack. */
        const val GROW_HEADROOM = 0.025
        const val MAX_PROBES = 6
        /** Free RAM that would pass the gate for a plan needing [need] bytes (the inverse of the gate, for messages). */
        fun freeNeededFor(need: Long): Long = (need / BUDGET_GATE).toLong() + BUDGET_MARGIN
        private const val DEFAULT_CTX_TRAIN = 32768
        private const val DEFAULT_KV_BYTES_PER_TOKEN = 64L * 1024   // Qwen3-VL-2B-class worst case when nothing is known
        private const val BUDGET_MARGIN = 512L * 1024 * 1024
        const val IMPORT_IMAGE_TOKENS_MAX = 1024
        const val IMPORT_MAX_IMAGE_EDGE_PX = 1024
        const val IMPORT_ENCODER_PEAK_BYTES = 700L * 1024 * 1024

        /** AUTO keys on KV bytes, not on n_ctx: hybrid models with 6 attention layers hold 32k of f16 KV in ~384 MB (Q8_0 would add a dequant per attention op for ~190 MB), while Qwen3-VL-2B at 16k f16 = 1.8 GB needs Q8_0. */
        fun kvTypeFor(pref: KvCachePref, kvBytesF16: Long, budget: Long) = when (pref) {
            KvCachePref.AUTO -> if (kvBytesF16 > maxOf(600L shl 20, (0.2 * budget).toLong())) KvCacheType.Q8_0 else KvCacheType.F16
            KvCachePref.F16 -> KvCacheType.F16; KvCachePref.Q8_0 -> KvCacheType.Q8_0; KvCachePref.Q4_0 -> KvCacheType.Q4_0 }

        /** User text containing the literal mtmd marker would be parsed as an image slot; break it. */
        fun sanitize(userText: String): String = userText.replace("<__media__>", "<_media_>")

        fun budgetBytes(availRam: Long): Long = availRam - BUDGET_MARGIN

        /** image_min_tokens for mmprojLoad: only dynamic-resolution projectors honour it (5.2 plan step 2). */
        fun imageMinTokens(model: LocalModel): Int = model.catalog?.let { if (it.dynamicResolution) it.imageTokensMin else 0 } ?: 0
        /** image_max_tokens for mmprojLoad: min(detail, catalog cap) when dynamicResolution, else 0 (fixed tiling ignores it). */
        fun imageMaxTokens(model: LocalModel, detail: ImageDetail): Int =
            model.catalog?.let { if (it.dynamicResolution) minOf(detail.maxTokens, it.imageTokensMax) else 0 } ?: 0
        /**
         * Image pre-resize contract for data/ImageUtil: the long edge handed to the engine is at most this many
         * pixels (min(detail.maxEdgePx, catalog.maxImageEdgePx); imports 1024), packed RGB888, id = sha256 of the bytes.
         */
        fun imageEdgeCap(model: LocalModel, detail: ImageDetail): Int =
            minOf(detail.maxEdgePx, model.catalog?.maxImageEdgePx ?: IMPORT_MAX_IMAGE_EDGE_PX)

        /**
         * n_batch floor for a vision model: the largest image chunk it can emit must fit one ubatch. Dynamic-resolution
         * projectors are clamped to min(detail, catalog cap) via image_max_tokens and fixed-tiling ones emit exactly the
         * catalog total, so the catalog cap (imports: 1024) is the ceiling regardless of [ImageDetail]; sizing the
         * batch on HIGH's 1024 would only double the compute buffer for tokens no projector produces.
         */
        @Suppress("UNUSED_PARAMETER")   // kept for source compatibility with the callers
        fun batchFor(model: LocalModel, detail: ImageDetail): Int =
            if (!model.hasVision) 512 else roundUp64(maxOf(512, model.catalog?.imageTokensMax ?: IMPORT_IMAGE_TOKENS_MAX))

        fun roundUp64(n: Int): Int = (n + 63) / 64 * 64
        fun roundDown(n: Int, step: Int): Int = n / step * step
        fun gb(bytes: Long): String = String.format(java.util.Locale.US, "%.1f", bytes / 1_073_741_824.0)
        fun tokens(n: Int): String = String.format(java.util.Locale.US, "%,d", n)
    }
}
