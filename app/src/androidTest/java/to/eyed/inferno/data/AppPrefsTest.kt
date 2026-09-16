package to.eyed.inferno.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import to.eyed.inferno.engine.Calibration
import to.eyed.inferno.engine.GenerationParams
import to.eyed.inferno.engine.ImageEncodeSample
import to.eyed.inferno.models.ImageDetail
import java.io.File

@RunWith(AndroidJUnit4::class)
class AppPrefsTest {
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var file: File
    private val scopes = ArrayList<CoroutineScope>()

    @Before fun setUp() {
        file = File(ctx.cacheDir, "prefs-test-${System.nanoTime()}.preferences_pb")
    }

    @After fun tearDown() { scopes.forEach { it.cancel() }; file.delete() }

    private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scopes += it }

    /** DataStore frees its file only once the owning scope's job has COMPLETED, so cancel and join before reopening. */
    private suspend fun CoroutineScope.shutdown() { cancel(); coroutineContext.job.join() }

    /** One DataStore per scope: DataStore refuses two live instances on one file, so a "restart" cancels the previous scope first. */
    private fun prefs(scope: CoroutineScope = newScope()) = AppPrefs(ctx, scope, PreferenceDataStoreFactory.create(scope = scope) { file })

    private suspend fun AppPrefs.await(pred: (SettingsState) -> Boolean): SettingsState =
        withTimeout(5_000) { settings.filter(pred).first() }

    @Test fun everyFieldRoundTripsThroughDisk() = runBlocking {
        val full = SettingsState(
            perfPreset = PerfPreset.COOL, threads = 3, pinBigCores = false, contextSize = 16384, kvCache = KvCachePref.Q8_0,
            flashAttention = true, useMmap = false, poll = 0, keepModelLoaded = false,
            params = GenerationParams(temperature = 0.3f, topK = 20, topP = 0.8f, minP = 0.1f, typicalP = 0.9f, repeatPenalty = 1.1f,
                repeatLastN = 128, frequencyPenalty = 0.2f, presencePenalty = 1.5f, dryMultiplier = 0.8f, dryBase = 1.5f,
                dryAllowedLength = 3, dryPenaltyLastN = 256, seed = 7, maxTokens = 512),
            useModelSamplingDefaults = false, systemPrompt = "You are terse. é—🦉", thinking = true, imageDetail = ImageDetail.HIGH,
            streamingAnimations = false, haptics = false, allowMeteredDownloads = true, notificationsAsked = true,
            selectedModelId = "qwen3.5-2b-q4_0", onboardingDone = true, minLogPriority = 3,
            loadAttemptModelId = "gemma-4-e2b-q4_0", lastLoadCrashModelId = "minicpm-v-4.6-q4_0",
            calibration = mapOf("qwen3.5-2b-q4_0" to Calibration(86.6, 14.8, 2100, 1700000000000,
                imageEncode = mapOf("BALANCED" to ImageEncodeSample(5200, 256)))),
            selectedImageModelId = "sdxs-512-q8_0", imageGenSecPerStep = mapOf("sdxs-512-q8_0:512" to 6.9f),
            contextPolicy = ContextPolicy.STOP, experienceLevel = ExperienceLevel.POWER,
        )
        val scope1 = newScope()
        val p1 = prefs(scope1)
        // settings is seeded with defaults before the first disk read; `loaded` flips once that read landed.
        withTimeout(5_000) { p1.loaded.filter { it }.first() }
        assertTrue(p1.loaded.value)
        assertEquals(SettingsState(), p1.settings.value)
        p1.update { full }
        assertEquals(full, p1.await { it == full })
        // A brand-new instance reading the same file (== process restart) sees the identical state.
        scope1.shutdown()
        val p2 = prefs()
        assertEquals(full, p2.await { it.selectedModelId != null })
        assertEquals(full.imageGenSecPerStep, p2.load())
    }

    @Test fun etaStoreRoundTripsBeforeAndAfterTheFirstRead() = runBlocking {
        val scope1 = newScope()
        val p1 = prefs(scope1)
        assertEquals(emptyMap<String, Float>(), p1.load())          // fresh file, read directly (no wait for `loaded`)
        p1.save(mapOf("dreamshaper-8-lcm-q8_0:512" to 13.5f, "sdxs-512-q8_0:384" to 4.2f))
        assertEquals(13.5f, p1.load()["dreamshaper-8-lcm-q8_0:512"])
        assertEquals(2, p1.await { it.imageGenSecPerStep.size == 2 }.imageGenSecPerStep.size)
        scope1.shutdown()
        val p2 = prefs()
        assertEquals(mapOf("dreamshaper-8-lcm-q8_0:512" to 13.5f, "sdxs-512-q8_0:384" to 4.2f), p2.load())
        p2.save(emptyMap())
        assertEquals(emptyMap<String, Float>(), p2.load())
    }

    @Test fun nullableFieldsClearAndConvenienceSettersApplyRules() = runBlocking {
        val p = prefs()
        p.await { true }
        p.setSelectedModel("a"); p.setLoadAttempt("b"); p.setLastLoadCrash("c")
        p.await { it.selectedModelId == "a" && it.loadAttemptModelId == "b" && it.lastLoadCrashModelId == "c" }
        p.setSelectedModel(null); p.setLoadAttempt(null); p.setLastLoadCrash(null)
        p.await { it.selectedModelId == null && it.loadAttemptModelId == null && it.lastLoadCrashModelId == null }
        p.setFlashAttention(false)
        p.await { !it.flashAttention }
        p.setKvCache(KvCachePref.Q4_0)                    // quantized KV forces flash attention on
        assertTrue(p.await { it.kvCache == KvCachePref.Q4_0 }.flashAttention)
        p.setFlashAttention(false)                        // and keeps it on while quantized
        p.setPerfPreset(PerfPreset.MAX)
        val s = p.await { it.perfPreset == PerfPreset.MAX }
        assertTrue(s.flashAttention)
        assertEquals(4, s.threads); assertEquals(100, s.poll); assertTrue(s.pinBigCores)
        p.setCalibration("m1", Calibration(1.0, 2.0, 3, 4)); p.setCalibration("m2", Calibration(5.0, 6.0, 7, 8))
        assertEquals(setOf("m1", "m2"), p.await { it.calibration.size == 2 }.calibration.keys)
        p.setImageGenSecPerStep("m:512", 13.5f)
        assertEquals(13.5f, p.await { it.imageGenSecPerStep.isNotEmpty() }.imageGenSecPerStep["m:512"])
    }

    @Test fun corruptValuesFallBackToDefaults() = runBlocking {
        val scope = newScope()
        val store = PreferenceDataStoreFactory.create(scope = scope) { file }
        store.edit {
            it[stringPreferencesKey("params")] = "{not json"
            it[stringPreferencesKey("perfPreset")] = "TURBO"
            it[stringPreferencesKey("calibration")] = "[]"
            it[stringPreferencesKey("imageDetail")] = "ULTRA"
        }
        val p = AppPrefs(ctx, scope, store)
        val s = p.await { true }
        assertEquals(GenerationParams(), s.params)
        assertEquals(PerfPreset.AUTO, s.perfPreset)
        assertEquals(emptyMap<String, Calibration>(), s.calibration)
        assertEquals(ImageDetail.BALANCED, s.imageDetail)
    }
}
