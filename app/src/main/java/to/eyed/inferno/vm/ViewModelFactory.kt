package to.eyed.inferno.vm

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.createSavedStateHandle
import to.eyed.inferno.AppContainer

/**
 * Activity-scoped factory (spec 5.5): every VM gets the container plus a [SavedStateHandle] so `screen`,
 * `activeId` and the pending attachment ids survive process death. No DI framework.
 */
class InfernoVmFactory(private val container: AppContainer) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
        val handle = extras.createSavedStateHandle()
        return when {
            modelClass.isAssignableFrom(AppViewModel::class.java) -> AppViewModel(container, handle)
            modelClass.isAssignableFrom(ChatViewModel::class.java) -> ChatViewModel(container, handle)
            modelClass.isAssignableFrom(BenchViewModel::class.java) -> BenchViewModel(container)
            modelClass.isAssignableFrom(ImageGenViewModel::class.java) -> ImageGenViewModel(container, handle)
            else -> throw IllegalArgumentException("Unknown ViewModel ${modelClass.name}")
        } as T
    }
}
