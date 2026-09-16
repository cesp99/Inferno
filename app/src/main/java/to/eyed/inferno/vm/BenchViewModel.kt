package to.eyed.inferno.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import to.eyed.inferno.AppContainer
import to.eyed.inferno.BuildConfig
import to.eyed.inferno.engine.Calibration
import to.eyed.inferno.engine.ContextConfig
import to.eyed.inferno.engine.EngineState
import to.eyed.inferno.engine.KvCacheType
import to.eyed.inferno.ui.S
import java.io.File

/**
 * Benchmark screen. Every run holds the engine job through InferenceEngine.bench; the thread matrix
 * reconfigures between rows and restores the user's configuration at the end.
 */
class BenchViewModel(private val c: AppContainer) : ViewModel() {
    data class Row(val label: String, val threads: Int, val pinned: Boolean, val kv: KvCacheType, val ppTps: Double, val tgTps: Double, val at: Long)

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()
    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress.asStateFlow()
    private val _rows = MutableStateFlow<List<Row>>(emptyList())
    val rows: StateFlow<List<Row>> = _rows.asStateFlow()
    val thermal: StateFlow<Int> = c.thermal.status
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()
    fun dismissNotice() { _notice.value = null }

    private var job: Job? = null

    /** Current configuration, pp512 / tg128 x 3. */
    fun runQuick() = start { cfg ->
        val r = bench(reps = 3, weight = 1f, base = 0f)
        addRow(S.benchQuick, cfg, r.ppTps, r.tgTps)
        updateCalibration(cfg, r.ppTps, r.tgTps)
    }

    /**
     * threads {4 pinned, 4 unpinned, 3 pinned, 8} x the current KV type, then "tg128 @ 16k, F16 vs Q8_0"
     * (validates the kvTypeFor threshold on this phone). Rows that match the current preset refresh the calibration.
     */
    fun runMatrix() = start { cfg ->
        val variants = listOf(Triple(4, true, S.bench4Pinned), Triple(4, false, S.bench4Unpinned), Triple(3, true, S.bench3Pinned), Triple(8, false, S.bench8Threads))
        val total = variants.size + 2
        var i = 0
        var threadsChanged = false
        var ctxChanged = false
        try {
            for ((n, pin, label) in variants) {
                threadsChanged = true
                c.engine.setThreads(n, pin, cfg.poll)
                val r = bench(reps = 1, weight = 1f / total, base = i.toFloat() / total)
                addRow(label, cfg.copy(nThreads = n, nThreadsBatch = n, bigCoresOnly = pin), r.ppTps, r.tgTps)
                if (n == cfg.nThreads && pin == cfg.bigCoresOnly) updateCalibration(cfg, r.ppTps, r.tgTps)
                i++
            }
            c.engine.setThreads(cfg.nThreads, cfg.bigCoresOnly, cfg.poll)
            threadsChanged = false
            for (kv in listOf(KvCacheType.F16, KvCacheType.Q8_0)) {
                val big = cfg.copy(nCtx = 16_384, kvType = kv, flashAttention = true)
                // Set before the call: a reconfigure that fails at contextCreate has already freed the old context.
                ctxChanged = true
                val ok = runCatching { c.engine.reconfigure(big) }.onFailure { if (it is CancellationException) throw it; _notice.value = S.benchVariantSkipped(kv.name, it.message) }.isSuccess
                if (!ok) { i++; continue }
                val r = bench(reps = 1, weight = 1f / total, base = i.toFloat() / total)
                addRow(S.benchTg16k(kv.name), big, r.ppTps, r.tgTps)
                i++
            }
            c.engine.reconfigure(cfg)
            ctxChanged = false
        } finally {
            // Stop (job.cancel) or a failing row must not leave the user's model on benchmark threads / a 16k context.
            // EngineJob.withJob suspends on a cancellable mutex, so the restore has to run NonCancellable; a low-memory
            // abort has already unloaded the model, in which case reconfigure throws "No model loaded" and is ignored.
            if (threadsChanged || ctxChanged) withContext(NonCancellable) {
                runCatching {
                    if (threadsChanged) c.engine.setThreads(cfg.nThreads, cfg.bigCoresOnly, cfg.poll)
                    if (ctxChanged) c.engine.reconfigure(cfg)
                }
            }
        }
    }

    fun stop() { job?.cancel() }

    /** files/bench/<ts>.json with build ids, load facts and every row. */
    fun exportJson(): File {
        val l = c.engine.loaded
        val dir = File(c.modelFiles.root.parentFile, "bench").also { it.mkdirs() }
        val file = File(dir, "${System.currentTimeMillis()}.json")
        val json = buildJsonObject {
            put("llamaTag", BuildConfig.LLAMA_TAG)
            put("llamaCommit", BuildConfig.LLAMA_COMMIT)
            put("device", android.os.Build.MODEL)
            put("soc", c.cpu.socName)
            put("model", l?.model?.id ?: "")
            put("loadMs", l?.loadMs ?: 0L)
            put("loadMode", if (c.prefs.settings.value.useMmap) "MMAP" else "DIRECT_IO")
            put("nCtx", l?.context?.nCtx ?: 0)
            put("rows", JsonArray(_rows.value.map { r ->
                JsonObject(mapOf(
                    "label" to JsonPrimitive(r.label), "threads" to JsonPrimitive(r.threads), "pinned" to JsonPrimitive(r.pinned),
                    "kv" to JsonPrimitive(r.kv.name), "ppTps" to JsonPrimitive(r.ppTps), "tgTps" to JsonPrimitive(r.tgTps), "at" to JsonPrimitive(r.at),
                ))
            }))
        }
        file.writeText(json.toString())
        return file
    }

    private fun start(block: suspend (ContextConfig) -> Unit) {
        if (_running.value) return
        val cfg = c.engine.loaded?.context ?: run { _notice.value = S.loadModelFirst; return }
        if (c.engine.state.value !is EngineState.Ready) { _notice.value = S.waitForModelReady; return }
        job = viewModelScope.launch {
            _running.value = true; _progress.value = 0f
            try {
                block(cfg)
                _progress.value = 1f
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _notice.value = e.message ?: S.benchmarkFailed
            } finally {
                _running.value = false
            }
        }
    }

    private suspend fun bench(reps: Int, weight: Float, base: Float) =
        c.engine.bench(nPrompt = 512, nGen = 128, reps = reps) { done, total -> if (total > 0) _progress.value = base + weight * done / total }

    private fun addRow(label: String, cfg: ContextConfig, pp: Double, tg: Double) {
        _rows.value = _rows.value + Row(label, cfg.nThreads, cfg.bigCoresOnly, cfg.kvType, pp, tg, System.currentTimeMillis())
    }

    private suspend fun updateCalibration(cfg: ContextConfig, pp: Double, tg: Double) {
        val l = c.engine.loaded ?: return
        val s = c.prefs.settings.value
        if (cfg.nThreads != s.threads || cfg.bigCoresOnly != s.pinBigCores) return
        val prev = s.calibration[l.model.id]
        val cal = Calibration(pp, tg, l.loadMs, System.currentTimeMillis(), prev?.imageEncode ?: emptyMap())
        withContext(Dispatchers.IO) { c.prefs.setCalibration(l.model.id, cal) }
        c.engine.setCalibration(cal)
    }
}
