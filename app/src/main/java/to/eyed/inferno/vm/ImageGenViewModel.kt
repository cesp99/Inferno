package to.eyed.inferno.vm

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import to.eyed.inferno.AppContainer
import to.eyed.inferno.imagegen.GenerationRecord
import to.eyed.inferno.imagegen.ImageGenParams
import to.eyed.inferno.imagegen.ImageGenUiState
import to.eyed.inferno.imagegen.ImageSizePreset
import to.eyed.inferno.models.DownloadState
import to.eyed.inferno.models.ImageCatalogModel
import to.eyed.inferno.models.ImageModelCatalog

/**
 * Thin VM over ImageGenRepository (12.5): prompt, model choice, size preset, steps, seed, generate / cancel,
 * the gallery Flow and the save / share hooks. The prompt and the last seed live in the SavedStateHandle.
 */
class ImageGenViewModel(private val c: AppContainer, private val handle: SavedStateHandle) : ViewModel() {
    val catalog: List<ImageCatalogModel> = ImageModelCatalog.models
    val state: StateFlow<ImageGenUiState> = c.imageGen.state
    val downloads: StateFlow<Map<String, DownloadState>> = c.models.imageDownloads

    val prompt: StateFlow<String> = handle.getStateFlow(KEY_PROMPT, "")
    fun setPrompt(text: String) { handle[KEY_PROMPT] = text }

    private val _modelId = MutableStateFlow(c.prefs.settings.value.selectedImageModelId ?: catalog.first().id)
    val modelId: StateFlow<String> = _modelId.asStateFlow()
    fun selectModel(id: String) { _modelId.value = id; viewModelScope.launch { c.prefs.setSelectedImageModel(id) } }

    val size: StateFlow<ImageSizePreset> = handle.getStateFlow(KEY_SIZE, ImageSizePreset.S512.name)
        .map { runCatching { ImageSizePreset.valueOf(it) }.getOrDefault(ImageSizePreset.S512) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ImageSizePreset.S512)
    fun setSize(s: ImageSizePreset) { handle[KEY_SIZE] = s.name }

    /** null = model default; the repository clamps to the catalog range. */
    val steps: StateFlow<Int?> = handle.getStateFlow<Int?>(KEY_STEPS, null)
    fun setSteps(n: Int?) { handle[KEY_STEPS] = n }

    /** -1 = random. "Reuse seed" copies the last result's seed here. */
    val seed: StateFlow<Long> = handle.getStateFlow(KEY_SEED, -1L)
    fun setSeed(s: Long) { handle[KEY_SEED] = s }

    val gallery: StateFlow<List<GenerationRecord>> = c.generatedImages.all
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()
    fun dismissNotice() { _notice.value = null }

    val model: ImageCatalogModel get() = c.imageGen.modelById(_modelId.value) ?: catalog.first()
    fun isDownloaded(m: ImageCatalogModel): Boolean = c.imageGen.isDownloaded(m)
    fun estimateSeconds(): Int = c.imageGen.estimateSeconds(_modelId.value, size.value, steps.value)

    init {
        // Prefs may not have been read when the VM was created: adopt the persisted choice once they land.
        viewModelScope.launch {
            c.prefs.loaded.first { it }
            c.prefs.settings.value.selectedImageModelId?.let { id -> if (catalog.any { it.id == id }) _modelId.value = id }
        }
    }

    fun generate() {
        val text = prompt.value.trim()
        if (text.isEmpty()) { _notice.value = "Describe the image first"; return }
        if (c.engine.state.value is to.eyed.inferno.engine.EngineState.Generating) { _notice.value = "Finish the current answer first"; return }
        val started = c.imageGen.generate(ImageGenParams(_modelId.value, text, size.value, steps.value, seed.value))
        if (started == null && state.value is ImageGenUiState.NeedsDownload) _notice.value = "Download the image model first"
    }

    /** Same seed, same prompt: the repository keeps the model loaded so this starts immediately. */
    fun regenerate(record: GenerationRecord) { setSeed(record.seed); setPrompt(record.prompt); generate() }
    fun cancel() = c.imageGen.cancel()
    fun reset() = c.imageGen.reset()

    fun download(modelId: String) = c.models.startDownload(modelId)
    fun cancelDownload(modelId: String) = c.models.cancelDownload(modelId)
    fun deleteModel(modelId: String) = viewModelScope.launch { runCatching { c.models.delete(modelId) }.onFailure { _notice.value = it.message } }
    fun deleteImage(id: String) = viewModelScope.launch { runCatching { c.generatedImages.delete(id) }.onFailure { _notice.value = it.message } }

    /** MediaStore Pictures/Inferno; returns the item Uri through [onSaved] for a share/open action. */
    fun saveToPhotos(record: GenerationRecord, onSaved: (Uri) -> Unit = {}) = viewModelScope.launch {
        runCatching { c.generatedImages.exportToPictures(record) }
            .onSuccess { _notice.value = "Saved to Photos"; onSaved(it) }
            .onFailure { _notice.value = it.message ?: "Couldn't save to Photos" }
    }

    /** Share = export to Photos then hand the MediaStore Uri to the chooser (FileProvider does not expose files/images). */
    fun share(record: GenerationRecord, onReady: (Uri) -> Unit) = viewModelScope.launch {
        runCatching { c.generatedImages.exportToPictures(record) }
            .onSuccess(onReady)
            .onFailure { _notice.value = it.message ?: "Couldn't share the image" }
    }

    private companion object {
        const val KEY_PROMPT = "imagePrompt"; const val KEY_SIZE = "imageSize"; const val KEY_STEPS = "imageSteps"; const val KEY_SEED = "imageSeed"
    }
}
