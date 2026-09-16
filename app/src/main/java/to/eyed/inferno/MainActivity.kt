package to.eyed.inferno

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import to.eyed.inferno.ui.InfernoRoot
import to.eyed.inferno.ui.theme.InfernoTheme
import to.eyed.inferno.vm.AppViewModel
import to.eyed.inferno.vm.BenchViewModel
import to.eyed.inferno.vm.ChatViewModel
import to.eyed.inferno.vm.ImageGenViewModel
import to.eyed.inferno.vm.InfernoVmFactory

/**
 * Single activity (spec 5.1): splash until prefs are read, edge-to-edge, activity-scoped ViewModels, and the
 * two intent entry points (share-to-Inferno and the notification "Stop" action) both funnelled through
 * AppViewModel.enqueue. singleTop in the manifest makes a share while the app is open land in onNewIntent.
 */
class MainActivity : ComponentActivity() {
    private val container: AppContainer get() = (application as InfernoApp).container
    private val appVm: AppViewModel by viewModels { InfernoVmFactory(container) }
    private val chatVm: ChatViewModel by viewModels { InfernoVmFactory(container) }
    private val benchVm: BenchViewModel by viewModels { InfernoVmFactory(container) }
    private val imageVm: ImageGenViewModel by viewModels { InfernoVmFactory(container) }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        splash.setKeepOnScreenCondition { !container.prefsLoaded }
        enableEdgeToEdge()
        chatVm.bind(appVm)
        // Only a fresh launch carries a new share: after a process death the system re-delivers the original
        // ACTION_SEND intent with the restored task, which must not re-import the image into a new chat.
        if (savedInstanceState == null) appVm.enqueue(intent)
        // Sustained performance mode needs a Window; the governor only asks for it (PerfPreset.MAX while generating).
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                container.thermal.sustainedRequested.collect { window.setSustainedPerformanceMode(it) }
            }
        }
        setContent {
            InfernoTheme { InfernoRoot(container, appVm, chatVm, benchVm, imageVm) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        appVm.enqueue(intent)
    }
}
