package to.eyed.inferno.engine

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PerformanceHintManager
import android.os.PowerManager
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import to.eyed.inferno.data.PerfPreset

/**
 * Core layout + ISA features + RAM figures. Everything here is pure Kotlin (/proc, /sys, ActivityManager) so the
 * dotprod/fp16 gate can run BEFORE System.loadLibrary("inferno") (the .so is built with -march=armv8.2-a+dotprod+fp16).
 * The secondary constructor exists for JVM tests (no Context).
 */
class CpuTopology(
    val probe: Probe,
    val socName: String,
    val totalRamBytes: Long,
    private val availRam: () -> Long,
) {
    constructor(context: Context) : this(probe(), socName(), memoryInfo(context).totalMem, { memoryInfo(context).availMem })

    val nCores: Int get() = probe.nCores
    val nBig: Int get() = probe.nBig
    val bigMask: Int get() = probe.bigMask
    val hasDotprod: Boolean get() = probe.hasDotprod
    val hasFp16: Boolean get() = probe.hasFp16
    val hasI8mm: Boolean get() = probe.hasI8mm
    val isSupported: Boolean get() = hasDotprod && hasFp16

    /** ActivityManager.MemoryInfo.availMem, fresh each call (the memory watchdog polls it every 500 ms). */
    fun availRamBytes(): Long = availRam()

    data class Probe(val nCores: Int, val nBig: Int, val bigMask: Int, val hasDotprod: Boolean, val hasFp16: Boolean,
                     val hasI8mm: Boolean, val hasSve: Boolean)

    companion object {
        private const val SYS_CPU = "/sys/devices/system/cpu"

        /** Pure-Kotlin detection from /proc/cpuinfo + /sys, usable BEFORE System.loadLibrary. */
        fun probe(): Probe {
            val cpuinfo = readText("/proc/cpuinfo") ?: ""
            val possible = readText("$SYS_CPU/possible")
            val n = parseCoreCount(cpuinfo, possible)
            val capacities = (0 until n).map { readText("$SYS_CPU/cpu$it/cpu_capacity")?.trim()?.toLongOrNull() }
            val maxFreqs = (0 until n).map { readText("$SYS_CPU/cpu$it/cpufreq/cpuinfo_max_freq")?.trim()?.toLongOrNull() }
            return parse(cpuinfo, n, capacities, maxFreqs)
        }

        /** Deterministic core of [probe] for tests: [cpuinfo] text, core count, per-core capacity / max-frequency (null = unreadable). */
        fun parse(cpuinfo: String, nCores: Int, capacities: List<Long?>, maxFreqs: List<Long?>): Probe {
            val features = parseFeatures(cpuinfo)
            val mask = bigMask(nCores, capacities, maxFreqs)
            return Probe(
                nCores = nCores, nBig = Integer.bitCount(mask), bigMask = mask,
                hasDotprod = "asimddp" in features, hasFp16 = "asimdhp" in features,
                hasI8mm = "i8mm" in features, hasSve = "sve" in features,
            )
        }

        /** Number of possible cores: "/sys/.../possible" ("0-7"), else the highest "processor : N" + 1, else the JVM view. */
        fun parseCoreCount(cpuinfo: String, possible: String?): Int {
            possible?.trim()?.let { p ->
                val hi = p.substringAfterLast('-', p).toIntOrNull()
                if (hi != null) return hi + 1
            }
            val fromInfo = cpuinfo.lineSequence().filter { it.startsWith("processor") }
                .mapNotNull { it.substringAfter(':').trim().toIntOrNull() }.maxOrNull()
            return if (fromInfo != null) fromInfo + 1 else Runtime.getRuntime().availableProcessors()
        }

        /** Union of the "Features" lines (heterogeneous clusters may list different sets; the ISA gate needs all cores). */
        fun parseFeatures(cpuinfo: String): Set<String> {
            val lines = cpuinfo.lineSequence().filter { it.startsWith("Features") }.map { it.substringAfter(':').trim() }.toList()
            if (lines.isEmpty()) return emptySet()
            // Intersect: a feature must be present on every listed core to be usable by pinned-or-not threads.
            return lines.map { it.split(Regex("\\s+")).filter(String::isNotEmpty).toSet() }.reduce { a, b -> a intersect b }
        }

        /**
         * Big cores = cores at the maximum cpu_capacity (fallback: maximum cpuinfo_max_freq). A homogeneous SoC
         * (or no sysfs data) makes every core "big" so pinning degrades to a no-op instead of to zero threads.
         */
        fun bigMask(nCores: Int, capacities: List<Long?>, maxFreqs: List<Long?>): Int {
            fun maskFor(values: List<Long?>): Int? {
                if (values.size < nCores || values.any { it == null }) return null
                val max = values.maxOf { it!! }
                var m = 0
                values.forEachIndexed { i, v -> if (v == max) m = m or (1 shl i) }
                return m
            }
            return maskFor(capacities) ?: maskFor(maxFreqs) ?: ((1 shl nCores) - 1)
        }

        fun socName(): String = try {
            Build.SOC_MODEL.takeIf { it.isNotBlank() && it != Build.UNKNOWN } ?: "unknown"
        } catch (e: RuntimeException) { "unknown" }

        fun memoryInfo(context: Context): ActivityManager.MemoryInfo =
            ActivityManager.MemoryInfo().also { context.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }

        private fun readText(path: String): String? = try { File(path).takeIf { it.canRead() }?.readText() } catch (e: Exception) { null }
    }
}

/**
 * Thermal-aware thread count + SoC hints. Reactive part: PowerManager thermal status listener. Proactive part
 * (runs only while generating): polls `PowerManager.getThermalHeadroom(10)` every 10 s and steps down to 3 threads
 * at headroom >= 0.9 before the status changes. ADPF: a `PerformanceHintManager` session over the engine + worker
 * tids with target = 1000 / targetTps ms, fed one `reportActualWorkDuration` per generated token, closed when the
 * turn ends. Sustained performance mode needs a Window, which this class has no access to: [sustainedRequested]
 * is true while a PerfPreset.MAX generation runs on a device that supports it; the Activity applies
 * `window.setSustainedPerformanceMode(it)`.
 *
 * [context] may be null (JVM tests): status stays NONE, headroom NaN, no hint session.
 * [beginGeneration] must run on the engine thread (contextWorkerTids takes a handle).
 */
open class ThermalGovernor(
    context: Context?,
    private val scope: CoroutineScope,
    private val workerTids: (Long) -> IntArray = { LlamaNative.contextWorkerTids(it) },
) {
    private val pm: PowerManager? = context?.getSystemService(PowerManager::class.java)
    private val hints: PerformanceHintManager? =
        if (context != null && Build.VERSION.SDK_INT >= 31) context.getSystemService(PerformanceHintManager::class.java) else null
    private val appContext = context?.applicationContext

    private val _status = MutableStateFlow(pm?.currentThermalStatus ?: PowerManager.THERMAL_STATUS_NONE)
    private val _headroom = MutableStateFlow(Float.NaN)
    private val _sustained = MutableStateFlow(false)

    val status: StateFlow<Int> = _status.asStateFlow()                 // PowerManager.THERMAL_STATUS_*
    val headroom: StateFlow<Float> = _headroom.asStateFlow()           // last getThermalHeadroom(10) sample, NaN when unknown
    val sustainedRequested: StateFlow<Boolean> = _sustained.asStateFlow()

    /** Drives the "Phone is hot" chip: the governor is currently taking threads away. */
    val isHot: Boolean get() = threadsFor(4) < 4

    private var session: PerformanceHintManager.Session? = null
    private var poller: Job? = null
    @Volatile private var lastSampleNs = 0L          // 0 = never sampled

    init {
        pm?.addThermalStatusListener({ it.run() }) { s -> _status.value = s }
    }

    /** headroom >= 0.9 or MODERATE: max(2, requested-1); SEVERE+: max(1, requested-2). */
    open fun threadsFor(requested: Int): Int {
        val s = _status.value
        val h = _headroom.value
        return when {
            s >= PowerManager.THERMAL_STATUS_SEVERE -> maxOf(1, requested - 2)
            s >= PowerManager.THERMAL_STATUS_MODERATE || (h.isFinite() && h >= 0.9f) -> maxOf(2, requested - 1)
            else -> requested
        }
    }

    open fun pauseImageEncoding(): Boolean = _status.value >= PowerManager.THERMAL_STATUS_SEVERE

    open fun beginGeneration(ctx: Long, preset: PerfPreset, targetTps: Double) {
        teardown()
        val targetNs = (1_000_000_000.0 / targetTps.coerceIn(1.0, 500.0)).toLong()
        val hm = hints
        if (hm != null && ctx != 0L) {
            try {
                val tids = workerTids(ctx)
                if (tids.isNotEmpty()) session = hm.createHintSession(tids, targetNs)
            } catch (e: Exception) {
                EngineLog.w(TAG, "ADPF session unavailable: ${e.message}")
            }
        }
        _sustained.value = preset.sustained && (pm?.isSustainedPerformanceModeSupported ?: false)
        // Delay first: the engine samples through refreshHeadroom() right before the turn's thread decision, and
        // getThermalHeadroom is rate-limited (NaN when called again within a second).
        poller = scope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(10_000)
                sampleHeadroom()
            }
        }
    }

    /**
     * One synchronous sample for the thread decision at the start of a turn (rate-limited to the platform's one per
     * second: a second call inside that window keeps the value it has instead of overwriting it with NaN).
     */
    fun refreshHeadroom() {
        val now = System.nanoTime()
        if (lastSampleNs != 0L && now - lastSampleNs < 1_000_000_000L) return
        sampleHeadroom()
    }

    fun reportToken(durationNanos: Long) {
        if (durationNanos <= 0) return
        try { session?.reportActualWorkDuration(durationNanos) } catch (e: Exception) { /* session closed concurrently */ }
    }

    fun endGeneration() {
        teardown()
        // A sample from a finished turn must never decide a later one (the phone may have cooled for minutes);
        // this also clears the "Phone is hot" chip once the turn is over.
        _headroom.value = Float.NaN
    }

    /** Poller + ADPF session down; the headroom sample is kept (beginGeneration runs right after the turn's refresh). */
    private fun teardown() {
        poller?.cancel(); poller = null
        try { session?.close() } catch (e: Exception) { /* already closed */ }
        session = null
        _sustained.value = false
    }

    private fun sampleHeadroom() {
        val p = pm ?: return
        lastSampleNs = System.nanoTime()
        val h = try { p.getThermalHeadroom(10) } catch (e: Exception) { Float.NaN }
        _headroom.value = h
        // API 36 CPU headroom (SystemHealthManager.getCpuHeadroom) is sampled reflectively so the build does not depend
        // on the exact 37.1 signatures; it is logged for the bench JSON, the thread policy keys on thermal headroom.
        if (Build.VERSION.SDK_INT >= 36 && appContext != null) cpuHeadroomPercent()?.let { EngineLog.d(TAG, "cpu headroom ${it}%") }
    }

    private fun cpuHeadroomPercent(): Float? = try {
        val shm = appContext?.getSystemService(Class.forName("android.os.health.SystemHealthManager")) ?: return null
        val m = shm.javaClass.methods.firstOrNull { it.name == "getCpuHeadroom" && it.parameterCount == 1 } ?: return null
        val result = m.invoke(shm, null as Any?) ?: return null
        (result.javaClass.getMethod("getHeadroom").invoke(result) as? Float)?.takeIf { it.isFinite() }
    } catch (e: Throwable) { null }

    companion object {
        private const val TAG = "Thermal"

        /** ADPF target: measured tg t/s when calibrated, else the midpoint of the catalog's "a-b" estimate, else 15. */
        fun targetTps(l: LoadedModel): Double {
            l.calibration?.tgTps?.takeIf { it > 0 }?.let { return it }
            val est = l.model.catalog?.estTgTps ?: return 15.0
            val nums = Regex("[0-9]+(\\.[0-9]+)?").findAll(est).mapNotNull { it.value.toDoubleOrNull() }.toList()
            return if (nums.isEmpty()) 15.0 else nums.average()
        }
    }
}
