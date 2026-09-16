package to.eyed.inferno.ui.theme

import androidx.compose.ui.graphics.Color

// Monochrome — every color has equal RGB channels — with one deliberate
// exception: red, reserved exclusively for destructive actions.
// Flat dark surfaces, no gradients, no glow.
object Ink {
    val Danger = Color(0xFFE5484D) // destructive text/labels
    val DangerDim = Color(0x26E5484D) // destructive control fill (15% red)
    val Pitch = Color(0xFF000000) // app background
    val I900 = Color(0xFF0A0A0A) // sidebar base, code blocks
    val I850 = Color(0xFF121212) // low raised panels
    val I800 = Color(0xFF1A1A1A)
    val I700 = Color(0xFF262626) // hairline borders, dividers
    val I500 = Color(0xFF737373) // secondary text, icons at rest
    val I300 = Color(0xFFA3A3A3) // tertiary labels
    val I100 = Color(0xFFE5E5E5) // body text
    val White = Color(0xFFFFFFFF) // primary text, CTAs, active states

    // Raised surface scale (flat fills on black)
    val Surface = Color(0xFF1E1E1E) // menus, sheets, composer
    val SurfaceLow = Color(0xFF161616) // quiet panels, rows
    val SurfaceHigh = Color(0xFF2A2A2A) // user bubble, chips, active rows
}

/** White-alpha ladder used for glass fills and borders. */
fun whiteA(alpha: Float): Color = Color.White.copy(alpha = alpha)
