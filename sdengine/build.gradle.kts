plugins {
    alias(libs.plugins.android.library)
}

// Image-generation engine: stable-diffusion.cpp compiled into its own libinferno_sd.so.
// It is a separate module (and shared object) because stable-diffusion.cpp vendors a
// forked ggml (FP8 types, int8 conv kernels) that cannot be merged with llama.cpp's ggml.
android {
    namespace = "to.eyed.inferno.sd"
    compileSdk {
        version = release(37)
    }
    ndkVersion = "28.2.13676358"

    defaultConfig {
        minSdk = 33
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_STL=c++_static",
                    "-DANDROID_PLATFORM=android-33",
                    "-DGGML_CPU_ARM_ARCH=armv8.2-a+dotprod+fp16",
                    "-DGGML_CPU_KLEIDIAI=ON",
                    "-DGGML_OPENMP=OFF",
                    "-DGGML_LLAMAFILE=OFF",
                    "-DGGML_BACKEND_DL=OFF",
                    "-DCMAKE_BUILD_TYPE=Release",
                )
                cppFlags += listOf("-O3", "-fvisibility=hidden", "-fvisibility-inlines-hidden")
                cFlags += listOf("-O3", "-fvisibility=hidden")
            }
        }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "4.1.2"
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            consumerProguardFiles("consumer-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.kotlinx.coroutines.android)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
