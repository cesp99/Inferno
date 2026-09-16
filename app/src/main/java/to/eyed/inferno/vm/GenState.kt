package to.eyed.inferno.vm

sealed interface GenState {
    data object Idle : GenState
    /** A send was accepted while the engine was Loading/Suspended; dispatched automatically at Ready (5.5). */
    data class Queued(val text: String) : GenState
    /** Prompt evaluation. encodingImage true while mtmd runs the vision encoder. expectedMs = predicted duration from Calibration (0 = unknown). */
    data class Prefill(val done: Int, val total: Int, val encodingImage: Boolean, val expectedMs: Long = 0) : GenState
    /** Reasoning tokens arriving, no visible answer yet. tokPerSec is an EMA published at 2 Hz (5.5). */
    data class Thinking(val reasoning: String, val tokPerSec: Float, val thinkingMs: Long) : GenState
    data class Streaming(val text: String, val reasoning: String, val tokPerSec: Float, val tokens: Int, val thinkingMs: Long) : GenState
    data object Trimming : GenState                                   // ContextManager dropping old turns
    data class Error(val message: String) : GenState
}
val GenState.isBusy: Boolean get() = this !is GenState.Idle && this !is GenState.Error
