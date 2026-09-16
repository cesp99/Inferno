package to.eyed.inferno.engine

import android.util.Log
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import to.eyed.inferno.imagegen.JobGate

/**
 * Process-wide "one native compute job at a time" gate (spec 12.3 d). The text engine (load, generate, count,
 * bench, unload), the vision encoder (part of generate) and the image-generation repository all take this
 * mutex before touching native code, so two ggml graphs can never run concurrently on an 8 GB phone: the
 * 2026-09-16 kernel panic came from exactly that kind of overlap. [owner] is a diagnostic tag for logs and the
 * UI ("Generating image..." notice while the chat composer is disabled).
 *
 * Reentrant along one coroutine chain: the holder is recorded as a [CoroutineContext] element, so a nested
 * [withJob] from the same coroutine (or a child inheriting its context) runs the block directly instead of
 * dead-locking. This is what lets the image-generation repository call `EngineCoordinator.releaseForImageGen()`
 * -> `InferenceEngine.unload()` from inside its own job. Coroutines launched with an *unrelated* context
 * (e.g. on the app scope) do not inherit the holder and queue on the mutex as usual.
 */
object EngineJob : JobGate {
    private val mutex = Mutex()
    private val _owner = MutableStateFlow<String?>(null)

    /** Tag of the current holder, null when idle. Observable so the UI can gate cross-engine actions. */
    val owner: StateFlow<String?> = _owner.asStateFlow()
    val isBusy: Boolean get() = mutex.isLocked

    /** Marks a coroutine chain that already holds the job. */
    private class Holder(val tag: String) : AbstractCoroutineContextElement(Holder) {
        companion object Key : CoroutineContext.Key<Holder>
    }

    /** True when the calling coroutine (or one of its ancestors) is inside [withJob]. */
    suspend fun heldByCaller(): Boolean = coroutineContext[Holder] != null

    /** Suspends until the job is free, runs [block] as its owner, always releases. Reentrant per coroutine chain. */
    override suspend fun <T> withJob(tag: String, block: suspend () -> T): T {
        if (coroutineContext[Holder] != null) return block()
        mutex.lock()
        _owner.value = tag
        try {
            return withContext(Holder(tag)) { block() }
        } finally {
            _owner.value = null
            mutex.unlock()
        }
    }

    /** Non-blocking acquisition for opportunistic work (memory trims). Pair with [release]. */
    fun tryAcquire(tag: String): Boolean {
        if (!mutex.tryLock()) return false
        _owner.value = tag
        return true
    }

    fun release(tag: String) {
        if (_owner.value != tag) EngineLog.w("EngineJob", "release by '$tag' while owner is '${_owner.value}'")
        _owner.value = null
        mutex.unlock()
    }
}

/**
 * Memory watchdog (spec 12.3 b): while a native job runs it polls availMem every 500 ms and fires [onLowMemory]
 * once when it drops under 500 MB, so the job is cancelled (and the caller unloads) before the LMK or the kernel
 * intervene. [availRam] is ActivityManager.MemoryInfo.availMem on the device; 0 means "unknown" and is ignored.
 */
internal class MemoryWatchdog(private val scope: CoroutineScope, private val availRam: () -> Long) {
    fun launch(onLowMemory: () -> Unit): Job = scope.launch(Dispatchers.Default) {
        while (isActive) {
            delay(POLL_MS)
            val avail = availRam()
            if (avail in 1 until LOW_MEMORY_BYTES) {
                EngineLog.w("Watchdog", "availMem=${avail shr 20} MB, cancelling the native job")
                onLowMemory()
                break
            }
        }
    }

    /** Runs [block] under a watchdog that is cancelled when the block leaves. */
    suspend fun <T> guard(onLowMemory: () -> Unit, block: suspend () -> T): T {
        val w = launch(onLowMemory)
        try { return block() } finally { w.cancel() }
    }

    private companion object { const val POLL_MS = 500L; const val LOW_MEMORY_BYTES = 500L * 1024 * 1024 }
}

/**
 * Logging shim: android.util.Log on the device, println under the host JVM (android.jar stubs throw
 * "Stub!" / "not mocked", which would make every pure engine class untestable without Robolectric).
 */
internal object EngineLog {
    private val android: Boolean = try { Log.isLoggable("Inferno", Log.DEBUG); true } catch (e: RuntimeException) { false }
    fun d(tag: String, msg: String) = if (android) Log.d("Inferno/$tag", msg) else println("D Inferno/$tag: $msg")
    fun i(tag: String, msg: String) = if (android) Log.i("Inferno/$tag", msg) else println("I Inferno/$tag: $msg")
    fun w(tag: String, msg: String) = if (android) Log.w("Inferno/$tag", msg) else println("W Inferno/$tag: $msg")
    fun e(tag: String, msg: String, t: Throwable? = null) = if (android) Log.e("Inferno/$tag", msg, t) else println("E Inferno/$tag: $msg ${t ?: ""}")
}
