package to.eyed.inferno

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import to.eyed.inferno.ui.components.ComponentGallery
import to.eyed.inferno.ui.theme.InfernoTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Temporary: the component gallery on black until WP5 wires InfernoRoot.
        setContent {
            InfernoTheme { ComponentGallery() }
        }
    }
}
