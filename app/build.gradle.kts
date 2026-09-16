plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

// Single source of truth for the engine versions shown in Settings > About and in exported bench JSON.
// Both engines are git submodules; reading the pins at configure time keeps BuildConfig honest after a bump.
val llamaDir = rootProject.file("third_party/llama.cpp")
val sdDir = rootProject.file("third_party/stable-diffusion.cpp")
// Returns null when git is missing, the dir is not a repo, or the command fails (e.g. a depth-1 submodule
// clone from actions/checkout has no tags, so `describe` exits 128). Configuration must never abort on that.
fun gitIn(dir: File, vararg args: String): String? = runCatching {
    providers.exec {
        commandLine("git", "-C", dir.path, *args)
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim().ifEmpty { null }
}.getOrNull()

// Fallback pins for shallow/tagless checkouts. Keep in sync when bumping the submodules
// (ModelCatalogTest asserts minLlamaBuild <= LLAMA_BUILD_NUMBER, so the tag must stay real).
val llamaTag = gitIn(llamaDir, "describe", "--tags", "--abbrev=0")
    ?: "b10991".also { logger.warn("llama.cpp tag not resolvable from git; using fallback $it") }
val llamaCommit = gitIn(llamaDir, "rev-parse", "--short", "HEAD") ?: "930e2fa"
val sdCommit = gitIn(sdDir, "rev-parse", "--short", "HEAD") ?: "59c23bc"

// Offline builds: -Pinferno.kleidiaiSrc=<extracted KleidiAI v1.24.0 dir> points both native modules at a
// vendored copy instead of letting ggml's FetchContent download the tarball. llama.cpp's ggml declares the
// dependency as `kleidiai`; stable-diffusion.cpp's ggml fork declares it as `KleidiAI_Download` (see :sdengine).
val kleidiaiSrc: String? = providers.gradleProperty("inferno.kleidiaiSrc").orNull

android {
    namespace = "to.eyed.inferno"
    // Compose 1.13.0-alpha03 (from the alpha BOM) requires the 37.1 minor SDK revision (platforms;android-37.1).
    compileSdk { version = release(37) { minorApiLevel = 1 } }
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "to.eyed.inferno"
        minSdk = 33
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        // Test runner: swaps in TestApp when the manifest Application is absent, otherwise runs against InfernoApp.
        testInstrumentationRunner = "to.eyed.inferno.TestRunner"

        ndk { abiFilters += listOf("arm64-v8a") }   // 64-bit ARM only; every phone that can run this is arm64

        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_STL=c++_static",
                    "-DANDROID_PLATFORM=android-33",
                    "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON",
                    // Both variants: an -O0 ggml is unusable. RelWithDebInfo (not Release) keeps -g so AGP can
                    // strip the packaged .so and keep unstripped symbols in native-debug-symbols/merged_native_libs
                    // for ndk-stack / Play symbolication of GGML_ASSERT aborts. The -O3 in cFlags/cppFlags wins
                    // over RelWithDebInfo's -O2 (appended later on the command line).
                    "-DCMAKE_BUILD_TYPE=RelWithDebInfo",
                    // ggml options for the CPU-only build (also pinned inside cpp/CMakeLists.txt; passing them
                    // here keeps the settings visible in the CMake cache and survives a CMakeLists rewrite).
                    "-DGGML_CPU_ARM_ARCH=armv8.2-a+dotprod+fp16",
                    "-DGGML_CPU_KLEIDIAI=ON",
                    "-DGGML_OPENMP=OFF",
                    "-DGGML_LLAMAFILE=OFF",
                    "-DGGML_BACKEND_DL=OFF",
                    "-DLLAMA_CURL=OFF",
                    "-DLLAMA_BUILD_TESTS=OFF",
                    "-DLLAMA_BUILD_EXAMPLES=OFF",
                    "-DLLAMA_BUILD_SERVER=OFF",
                    "-DLLAMA_BUILD_TOOLS=OFF",
                    "-DLLAMA_BUILD_MTMD=ON",
                    "-DLLAMA_BUILD_COMMON=OFF",
                    "-DBUILD_SHARED_LIBS=OFF",
                )
                kleidiaiSrc?.let {
                    arguments += listOf("-DFETCHCONTENT_SOURCE_DIR_KLEIDIAI=$it", "-DFETCHCONTENT_FULLY_DISCONNECTED=ON")
                }
                // Cortex-A78/A55-class tuning: dotprod + fp16 arithmetic, no i8mm/SVE on the target SoC.
                cFlags += listOf("-O3", "-march=armv8.2-a+dotprod+fp16", "-mtune=cortex-a78", "-fvisibility=hidden")
                cppFlags += listOf("-O3", "-march=armv8.2-a+dotprod+fp16", "-mtune=cortex-a78", "-fvisibility=hidden", "-fvisibility-inlines-hidden")
            }
        }

        buildConfigField("String", "LLAMA_COMMIT", "\"$llamaCommit\"")
        buildConfigField("String", "LLAMA_TAG", "\"$llamaTag\"")
        buildConfigField("int", "LLAMA_BUILD_NUMBER", llamaTag.removePrefix("b").toIntOrNull()?.toString() ?: "0")
        buildConfigField("String", "SD_COMMIT", "\"$sdCommit\"")
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "4.1.2"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // No store key exists for this open-source repo: release builds are signed with the debug keystore
            // so `assembleRelease` yields an installable APK for testing R8 / resource shrinking / LTO on a device.
            signingConfig = signingConfigs.getByName("debug")
            // Thin LTO only for release: a full LTO link of ggml+llama+mtmd+KleidiAI on every debug change costs minutes.
            externalNativeBuild { cmake { arguments += "-DINFERNO_LTO=ON" } }
        }
        debug {
            // Native stays optimised (see defaultConfig, RelWithDebInfo -O3, no LTO); only Kotlin is debuggable.
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        jniLibs { useLegacyPackaging = false }      // uncompressed + 16 KB aligned in the APK
        resources.excludes += setOf("META-INF/{AL2.0,LGPL2.1}", "META-INF/versions/9/OSGI-INF/MANIFEST.MF")
    }
    room { schemaDirectory("$projectDir/schemas") }
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        optIn.add("androidx.compose.material3.ExperimentalMaterial3Api")            // DropdownMenu flat-styling overload, tooltips
        optIn.add("androidx.compose.material3.ExperimentalMaterial3ExpressiveApi")  // MaterialShapes, Morph.toPath, LoadingIndicator, wavy indicators
        optIn.add("androidx.compose.animation.ExperimentalSharedTransitionApi")     // SharedTransitionLayout for the image viewer
        optIn.add("androidx.compose.foundation.ExperimentalFoundationApi")
        optIn.add("kotlinx.coroutines.ExperimentalCoroutinesApi")
    }
}

// THIRD-PARTY-NOTICES.md lives at the repo root (GitHub renders it there) and is copied into assets/ so
// Settings > About > "Open-source licences" can show the same text offline. The copy is gitignored.
val copyThirdPartyNotices = tasks.register<Copy>("copyThirdPartyNotices") {
    from(rootProject.file("THIRD-PARTY-NOTICES.md"))
    into(layout.projectDirectory.dir("src/main/assets"))
}
tasks.named("preBuild") { dependsOn(copyThirdPartyNotices) }

dependencies {
    implementation(project(":sdengine"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.animation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.adaptive)
    implementation(libs.androidx.graphics.shapes)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.markdown.renderer.m3)
    implementation(libs.coil.compose)
    implementation(libs.icons.lucide)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockwebserver3)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
