package to.eyed.inferno

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner

/**
 * Test-only Application: the manifest names `.InfernoApp`, and the instrumented process would die with
 * ClassNotFoundException before any test runs if that class were ever missing. [TestRunner] swaps it in.
 */
class TestApp : Application()

/**
 * Instantiates [TestApp] instead of the manifest Application when the real one is not on the class path, so the
 * data/engine device tests do not depend on the real Application class.
 * Select it with `testInstrumentationRunner = "to.eyed.inferno.TestRunner"` (AGP rewrites the name of any
 * `<instrumentation>` in androidTest/AndroidManifest.xml to that property, so a manifest entry alone is ignored).
 * Without editing app/build.gradle.kts a Gradle init script sets the same property for one run:
 *   allprojects { plugins.withId("com.android.application") { android.defaultConfig.testInstrumentationRunner = "to.eyed.inferno.TestRunner" } }
 */
class TestRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader, className: String, context: Context): Application {
        val name = runCatching { cl.loadClass(className); className }.getOrDefault(TestApp::class.java.name)
        return super.newApplication(cl, name, context)
    }
}
