package to.eyed.inferno.models

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the repository needs from `engine/InferenceEngine` (WP2); `AppContainer` adapts the real engine to it. */
interface EngineAccess {
    /** `engine.state.value.modelOrNull?.id`: loaded, loading, suspended or generating. */
    val loadedModelId: String?
    val isGenerating: Boolean
    suspend fun unload()
    /** `LlamaNative.estimateCacheClear()` on the engine thread: the no_alloc estimate cache holds the file open. */
    suspend fun clearEstimateCache()
}

/** The two `SettingsState` fields the download flow reads and writes (`data/AppPrefs`, WP4). */
interface DownloadPrefs {
    val allowMeteredDownloads: Boolean
    val selectedModelId: String?
    suspend fun setAllowMeteredDownloads(allow: Boolean)
    suspend fun setSelectedModel(id: String?)
}

/** Connectivity + service start, abstracted so the queue logic runs in JVM unit tests. */
interface DownloadPlatform {
    val isMetered: Boolean
    fun startService()
    /** Reports every validated default network with its metered flag until closed. */
    fun watchNetwork(onAvailable: (metered: Boolean) -> Unit): AutoCloseable
}

class AndroidDownloadPlatform(private val context: Context) : DownloadPlatform {
    private val cm: ConnectivityManager get() = context.getSystemService(ConnectivityManager::class.java)
    override val isMetered: Boolean get() = cm.isActiveNetworkMetered
    override fun startService() = DownloadService.start(context)
    override fun watchNetwork(onAvailable: (metered: Boolean) -> Unit): AutoCloseable {
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
                    onAvailable(!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
            }
        }
        cm.registerDefaultNetworkCallback(cb)
        return AutoCloseable { runCatching { cm.unregisterNetworkCallback(cb) } }
    }
}

/**
 * Single source of truth for "which models exist, where, and what is happening to them" (spec 5.3). Text models
 * (`ModelCatalog`) and image models (`ImageModelCatalog`, WP9b) share the download queue and the storage layout;
 * text models appear in [entries], image models in [imageDownloads].
 *
 * Queue: one file at a time, inside [runQueue] on `DownloadService`'s coroutine. Everything the UI sees is
 * derived from two flows: the disk snapshot (complete files, partial bytes) and the transient per-id state
 * (Queued / Downloading / Verifying / Failed / Paused-reason), so a `.part` left by force-stop or process death
 * shows as `Paused` with its byte count on the next launch without any persisted queue.
 */
class ModelRepository(
    private val files: ModelFiles,
    private val downloader: ModelDownloader,
    private val engine: EngineAccess,
    private val prefs: DownloadPrefs,
    private val platform: DownloadPlatform,
    private val scope: CoroutineScope,
    private val catalog: List<CatalogModel> = ModelCatalog.visible,
    private val imageCatalog: List<ImageCatalogModel> = ImageModelCatalog.models,
) {
    constructor(catalog: ModelCatalog, files: ModelFiles, downloader: ModelDownloader, engine: EngineAccess, prefs: DownloadPrefs,
                context: Context, scope: CoroutineScope) :
        this(files, downloader, engine, prefs, AndroidDownloadPlatform(context), scope, catalog.visible)

    data class MeteredRequest(val modelId: String, val bytes: Long)

    /** Disk truth: complete models, and bytes present (complete files + .part) for ids that are not complete. */
    private data class Snapshot(val models: List<LocalModel> = emptyList(), val imageReady: Set<String> = emptySet(), val progress: Map<String, Long> = emptyMap())

    private val snapshot = MutableStateFlow(Snapshot())
    private val transient = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    private val _storage = MutableStateFlow(StorageInfo(0, 0, 0, 0))
    private val _notice = MutableSharedFlow<String>(extraBufferCapacity = 8)
    private val _meteredConfirm = MutableStateFlow<MeteredRequest?>(null)

    val storage: StateFlow<StorageInfo> = _storage.asStateFlow()
    val notice: SharedFlow<String> = _notice.asSharedFlow()
    val meteredConfirm: StateFlow<MeteredRequest?> = _meteredConfirm.asStateFlow()

    /** catalog.visible ∪ local, sorted by tier then catalog order; other local catalog rows next; imports last. */
    val entries: StateFlow<List<ModelEntry>> = combine(snapshot, transient) { s, t ->
        val locals = s.models.associateBy { it.id }
        val rows = catalog.withIndex().sortedWith(compareBy({ it.value.tier.ordinal }, { it.index })).map { (_, m) ->
            ModelEntry(m.id, m, locals[m.id], stateFor(m.id, s, t))
        }
        val extra = s.models.filter { it.catalog != null && catalog.none { c -> c.id == it.id } }
            .map { ModelEntry(it.id, it.catalog, it, DownloadState.Downloaded) }
        val imports = s.models.filter { it.catalog == null }.sortedBy { it.importedAt }
            .map { ModelEntry(it.id, null, it, DownloadState.Downloaded) }
        rows + extra + imports
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Download state per image model id (WP9b's Create screen cards). */
    val imageDownloads: StateFlow<Map<String, DownloadState>> = combine(snapshot, transient) { s, t ->
        imageCatalog.associate { it.id to stateFor(it.id, s, t) }
    }.stateIn(scope, SharingStarted.Eagerly, emptyMap())

    private val lock = Any()
    private val queue = ArrayDeque<String>()
    private var activeId: String? = null
    private var activeJob: Job? = null
    /** Ids paused by the system / a lost network: restarted on foreground or reconnect without user action. */
    private val autoResume = LinkedHashSet<String>()
    /** Reason to attach to the Paused state of the active id once its job has been cancelled (pauseAll). */
    private val pauseReasons = HashMap<String, String>()
    private var networkWatch: AutoCloseable? = null
    private var stateSink: ((String, DownloadState) -> Unit)? = null

    init { refresh() }

    // ---- queries ------------------------------------------------------------------------------------------------

    fun local(modelId: String): LocalModel? = snapshot.value.models.firstOrNull { it.id == modelId }
    fun downloadState(modelId: String): DownloadState = stateFor(modelId, snapshot.value, transient.value)
    fun displayName(modelId: String): String = catalog.firstOrNull { it.id == modelId }?.displayName
        ?: imageCatalog.firstOrNull { it.id == modelId }?.displayName ?: local(modelId)?.displayName ?: modelId
    val isQueueEmpty: Boolean get() = synchronized(lock) { queue.isEmpty() && activeId == null }

    private fun downloadsFor(id: String): List<DownloadableFile>? =
        catalog.firstOrNull { it.id == id }?.downloads ?: imageCatalog.firstOrNull { it.id == id }?.downloads

    private fun stateFor(id: String, s: Snapshot, t: Map<String, DownloadState>): DownloadState {
        val live = t[id]
        if (live != null && live !is DownloadState.Paused) return live
        if (s.models.any { it.id == id } || id in s.imageReady) return DownloadState.Downloaded
        val progress = s.progress[id] ?: 0L
        if (progress > 0 || live is DownloadState.Paused) {
            val total = downloadsFor(id)?.let { files.totalBytes(it) } ?: 0L
            return DownloadState.Paused(progress, total, live?.reason)
        }
        return DownloadState.NotDownloaded
    }

    // ---- disk ---------------------------------------------------------------------------------------------------

    fun refresh() { scope.launch { refreshNow() } }

    suspend fun refreshNow() = withContext(Dispatchers.IO) {
        val scan = files.scan()
        val imageReady = imageCatalog.filter { files.isDownloaded(it.downloads) }.map { it.id }.toSet()
        val progress = HashMap<String, Long>()
        for (m in catalog) if (scan.models.none { it.id == m.id }) presentBytes(m.downloads)?.let { progress[m.id] = it }
        // Only this model's own directory counts: the shared TAESD file being complete must not read as "Paused".
        for (m in imageCatalog) if (m.id !in imageReady) presentBytes(m.downloads.filter { it.subdir == m.id })?.let { progress[m.id] = it }
        snapshot.value = Snapshot(scan.models, imageReady, progress)
        _storage.value = files.storageInfo()
    }

    private fun presentBytes(dl: List<DownloadableFile>): Long? =
        dl.sumOf { if (files.isComplete(it)) it.sizeBytes else files.partFile(files.fileFor(it)).length() }.takeIf { it > 0 }

    // ---- queue control ------------------------------------------------------------------------------------------

    /** Enqueues (text then mmproj) and starts DownloadService. Refuses with `notice` when free space < remaining + 256 MB. */
    fun startDownload(modelId: String, allowMetered: Boolean = false) {
        val dl = downloadsFor(modelId) ?: return
        if (files.isDownloaded(dl)) { refresh(); return }
        synchronized(lock) { if (modelId == activeId || modelId in queue) return }
        val remaining = files.remainingBytes(dl)
        if (files.storageInfo().freeBytes < remaining + FREE_SPACE_MARGIN) { _notice.tryEmit("Not enough storage for this download"); return }
        if (platform.isMetered && !allowMetered && !prefs.allowMeteredDownloads) { _meteredConfirm.value = MeteredRequest(modelId, remaining); return }
        _meteredConfirm.update { if (it?.modelId == modelId) null else it }
        synchronized(lock) { queue.addLast(modelId); autoResume.remove(modelId) }
        transient.update { it + (modelId to DownloadState.Queued) }
        updateNetworkWatch()
        platform.startService()
    }

    fun confirmMetered(modelId: String, always: Boolean) {
        _meteredConfirm.value = null
        scope.launch {
            if (always) prefs.setAllowMeteredDownloads(true)
            startDownload(modelId, allowMetered = true)
        }
    }

    /** "Wait for Wi-Fi": the download starts by itself on the next unmetered network. */
    fun waitForWifi(modelId: String) {
        _meteredConfirm.value = null
        synchronized(lock) { autoResume += modelId }
        updateNetworkWatch()
    }

    /** -> Paused (keeps .part). */
    fun cancelDownload(modelId: String) {
        synchronized(lock) {
            autoResume.remove(modelId)
            if (activeId == modelId) { activeJob?.cancel(); return }
            queue.remove(modelId)
        }
        transient.update { it - modelId }
        updateNetworkWatch()
    }

    /** -> NotDownloaded: cancels first when the id is transferring, then removes .part + .part.json. */
    fun discardPartial(modelId: String) {
        val job = synchronized(lock) { autoResume.remove(modelId); queue.remove(modelId); if (activeId == modelId) activeJob?.also { it.cancel() } else null }
        scope.launch {
            job?.join()
            downloadsFor(modelId)?.let { files.discardPartial(it) } ?: files.discardPartial(modelId)
            transient.update { it - modelId }
            refreshNow()
        }
    }

    /** DownloadService.onTimeout / network loss: everything pending becomes Paused(reason) and is auto-resumed later. */
    fun pauseAll(reason: String) {
        val pending: List<String>
        val job: Job?
        synchronized(lock) {
            pending = queue.toList(); queue.clear()
            autoResume += pending
            activeId?.let { autoResume += it; pauseReasons[it] = reason }
            job = activeJob
        }
        transient.update { t -> t + pending.associateWith { DownloadState.Paused(0, 0, reason) } }
        job?.cancel()
        updateNetworkWatch()
    }

    /** ON_START => restart what the system or the network interrupted (the dataSync budget resets in the foreground). */
    fun onAppForeground(foreground: Boolean) {
        if (!foreground) return
        resumePending(platform.isMetered)
    }

    private fun resumePending(metered: Boolean) {
        if (metered && !prefs.allowMeteredDownloads) return
        val ids = synchronized(lock) { autoResume.toList() }
        ids.forEach { startDownload(it, allowMetered = true) }
    }

    private fun updateNetworkWatch() = synchronized(lock) {
        val needed = autoResume.isNotEmpty()
        if (needed && networkWatch == null) networkWatch = platform.watchNetwork { metered -> resumePending(metered) }
        else if (!needed) { networkWatch?.close(); networkWatch = null }
    }

    // ---- the queue itself (DownloadService coroutine) ------------------------------------------------------------

    /** Called only by DownloadService on its own coroutine; returns when the queue is empty. */
    suspend fun runQueue(onState: (modelId: String, DownloadState) -> Unit) {
        stateSink = onState
        try {
            while (true) {
                val id = synchronized(lock) { queue.removeFirstOrNull()?.also { activeId = it } } ?: break
                coroutineScope {
                    val job = launch { runOne(id) }
                    synchronized(lock) { activeJob = job }
                    job.join()
                }
                synchronized(lock) { activeId = null; activeJob = null }
            }
        } finally {
            synchronized(lock) { activeId = null; activeJob = null }
            stateSink = null
            updateNetworkWatch()
        }
    }

    private suspend fun runOne(id: String) {
        val dl = downloadsFor(id) ?: run { transient.update { it - id }; return }
        val total = files.totalBytes(dl)
        val count = dl.size
        var doneBefore = 0L
        var lastStorageTick = 0L
        fun set(state: DownloadState) { transient.update { it + (id to state) }; stateSink?.invoke(id, state) }
        try {
            dl.forEachIndexed { i, d ->
                if (files.isComplete(d)) { doneBefore += d.sizeBytes; return@forEachIndexed }
                val dest = files.fileFor(d)
                set(DownloadState.Downloading(doneBefore + files.partFile(dest).length(), total, 0, i + 1, count))
                downloader.download(d, dest, onProgress = { got, _, bps ->
                    set(DownloadState.Downloading(doneBefore + got, total, bps, i + 1, count))
                    val now = System.nanoTime()
                    if (now - lastStorageTick > STORAGE_TICK_NS) { lastStorageTick = now; _storage.value = files.storageInfo() }
                }, onVerifying = { set(DownloadState.Verifying) })
                doneBefore += d.sizeBytes
            }
            refreshNow()
            transient.update { it - id }
            stateSink?.invoke(id, DownloadState.Downloaded)
        } catch (e: CancellationException) {
            val reason = synchronized(lock) { pauseReasons.remove(id) }
            // Rescan before the job completes so the Paused row shows the right byte count immediately.
            withContext(NonCancellable) { refreshNow() }
            transient.update { it + (id to DownloadState.Paused(0, 0, reason)) }
            stateSink?.invoke(id, DownloadState.Paused(0, 0, reason))
            throw e
        } catch (e: DownloadException) {
            val state = if (e.message == "No connection") {
                synchronized(lock) { autoResume += id }
                DownloadState.Paused(0, 0, "No connection")
            } else DownloadState.Failed(e.message ?: "Download failed", e.resumable)
            transient.update { it + (id to state) }
            stateSink?.invoke(id, state)
            refreshNow()
        }
    }

    // ---- delete / import ----------------------------------------------------------------------------------------

    /**
     * (1) cancel + await the queue for this id, (2) never delete under a live model: refuse while generating,
     * unload otherwise, (3) drop the estimate cache, (4) delete the directory, (5) clear the selection, (6) rescan.
     */
    suspend fun delete(modelId: String) {
        val job = synchronized(lock) { queue.remove(modelId); autoResume.remove(modelId); if (activeId == modelId) activeJob?.also { it.cancel() } else null }
        job?.join()
        if (engine.loadedModelId == modelId) {
            if (engine.isGenerating) { _notice.tryEmit("Stop the current answer first"); return }
            engine.unload()
        }
        engine.clearEstimateCache()
        files.delete(modelId)
        transient.update { it - modelId }
        if (prefs.selectedModelId == modelId) prefs.setSelectedModel(null)
        refreshNow()
    }

    /** Settings > "Delete all models": unload up front, then every local model, image model and partial. */
    suspend fun deleteAll() {
        if (engine.isGenerating) { _notice.tryEmit("Stop the current answer first"); return }
        engine.unload()
        val ids = LinkedHashSet<String>()
        val s = snapshot.value
        ids += s.models.map { it.id }; ids += s.imageReady; ids += s.progress.keys
        ids += synchronized(lock) { queue.toList() + listOfNotNull(activeId) }
        ids.forEach { delete(it) }
        files.delete(ImageModelCatalog.TAESD_DIR_ID)
        refreshNow()
    }

    suspend fun import(uri: Uri, displayName: String?, isMmproj: Boolean, pairWith: String?): LocalModel =
        files.importGguf(uri, displayName, isMmproj, pairWith).also { refreshNow() }

    companion object {
        const val FREE_SPACE_MARGIN = 256L shl 20
        private const val STORAGE_TICK_NS = 2_000_000_000L
    }
}
