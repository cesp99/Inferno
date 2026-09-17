# Building Inferno

## Prerequisites

* JDK 17. The Gradle daemon itself runs on JDK 25, provisioned automatically through
  `gradle/gradle-daemon-jvm.properties`.
* Android SDK packages:

  ```bash
  sdkmanager "platforms;android-37.0" "platforms;android-37.1" "build-tools;36.0.0" \
             "ndk;28.2.13676358" "cmake;4.1.2"
  ```

  `platforms;android-37.1` is needed by the alpha Compose BOM used by `:app`.
* Git with submodules. Python 3 on the PATH (ggml embeds its OpenCL kernels as strings at configure time).
  ImageMagick 7 only if you regenerate the launcher icons.

## Commands

```bash
git submodule update --init --recursive   # llama.cpp (~1.2 GB), stable-diffusion.cpp, OpenCL-Headers
./gradlew :app:assembleDebug              # first native configure takes 3-4 minutes
./gradlew :app:assembleRelease            # R8, resource shrinking, thin LTO on the native code
./gradlew :app:testDebugUnitTest          # JVM unit tests
```

Release APK size is about 46 MB, of which 37 MB is the image-generation library (stable-diffusion.cpp ships its
own copy of ggml) and 5 MB the language-model library.

## Notes

* **OpenCL without a link dependency.** `libinferno.so` is not linked against any `libOpenCL.so`: ggml's OpenCL
  backend resolves its entry points through `app/src/main/cpp/inferno_opencl.cpp`, which `dlopen`s the vendor
  driver at run time. The same APK therefore runs on phones without OpenCL (CPU path) and on Adreno phones
  (GPU path); see [performance.md](performance.md). When llama.cpp is updated, any new `cl*` call in
  `ggml-opencl` shows up as an unresolved symbol at link time and needs a forwarder there.
* **KleidiAI download.** Each native module fetches KleidiAI v1.24.0 during its first CMake configure. For an
  offline build, extract the [v1.24.0 source](https://github.com/ARM-software/kleidiai/archive/refs/tags/v1.24.0.tar.gz)
  once and pass it to both modules:
  `./gradlew -Pinferno.kleidiaiSrc=/abs/path/kleidiai-1.24.0 :app:assembleDebug`.
* **Shallow clones.** Without reachable tags, `git describe` fails and the build falls back to the pins hard-coded
  in `app/build.gradle.kts` for the version strings shown in Settings > About. Run
  `git -C third_party/llama.cpp fetch --tags --depth=1` to get the real tag.
* **Kotlin** is built into AGP 9; do not apply `org.jetbrains.kotlin.android`. Versions live in
  `gradle/libs.versions.toml`.
* **Native code is always optimised** (`-O3`, `-march=armv8.2-a+dotprod+fp16`), even in debug builds; only the
  Kotlin side is debuggable.
* **R8 keep rules** in `app/proguard-rules.pro` and `sdengine/consumer-rules.pro` preserve every JNI entry point
  and the callback interfaces the native side resolves by name. Keep them when adding native methods.
* **16 KB pages.** After a native change verify alignment with
  `llvm-readelf -l <path>/libinferno.so | grep LOAD` (expect `0x4000`) and `zipalign -c -P 16 -v 4 app.apk`.
