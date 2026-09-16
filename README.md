# Inferno

Local, on-device AI for Android. Inferno runs language and vision-language models (text + image input) and
text-to-image diffusion models entirely on the phone's CPU. Nothing leaves the device.

Package `to.eyed.inferno` · GPL-3.0 · Android 13+ · arm64-v8a only.

## What it is

* A chat client with streaming Markdown, image attachments (gallery, camera, share-sheet) and a curated catalog
  of small vision-language models that actually run at usable speed on a phone.
* Two native engines, both compiled from pinned git submodules and statically linked into the app:
  * **llama.cpp** (`third_party/llama.cpp`, tag `b10991`, commit `930e2fa`) with `mtmd` for vision, built as
    `libinferno.so`.
  * **stable-diffusion.cpp** (`third_party/stable-diffusion.cpp`, commit `59c23bc`) for image generation, built
    as `libinferno_sd.so`. It is a separate shared object because sd.cpp vendors its own fork of ggml.
* Material 3 Expressive UI in a monochrome black/white language, single activity, no DI or navigation framework.

## Privacy

Everything runs offline. The only network use is downloading model files from Hugging Face when you ask for them
(metered networks trigger a confirmation). There is no telemetry, no analytics, no account and no cloud fallback.
Chats, images and models live in the app's private storage. `allowBackup=false` keeps them out of Android cloud
backup and `dataExtractionRules` (`res/xml/data_extraction_rules.xml`) also excludes them from device-to-device
transfer, so nothing is copied off the phone by the system either (on Android 12+ `allowBackup=false` alone only
covers cloud backup).

## Supported devices

* **CPU:** 64-bit ARM (`arm64-v8a`) with the `dotprod` and `fp16` extensions (Armv8.2-A or newer; every mid-range
  or better SoC since ~2020). The app checks `/proc/cpuinfo` at start-up and refuses to load a model on CPUs
  without them; `i8mm`/SVE are used when present but not required.
* **RAM:** 6 GB minimum, 8 GB recommended. A 2B-parameter Q4_0 model with a 32k context needs ~2.4 GB resident;
  the engine refuses loads that would exceed ~85 % of the available budget and runs a memory watchdog during load,
  image encode and prefill.
* **OS:** Android 13 (API 33) or newer; targets API 37. 16 KB page-size compatible.
* Reference / test device: Solana Seeker (MediaTek Dimensity 7300, 4x Cortex-A78 + 4x Cortex-A55, 8 GB RAM,
  Android 16).

### Why CPU only

The GPU path was measured and rejected on the reference device. With the same llama.cpp commit built with the
Vulkan backend, on the Mali-G615 MC2:

| Backend (Qwen3-VL-2B Q4_0, 4 threads, flash-attn) | prompt 128 tok | generate 32 tok |
|---|---|---|
| Vulkan, all layers offloaded (`ngl 99`) | 3.15 t/s | 5.80 t/s |
| Vulkan build, `ngl 0` | 2.99 t/s | 0.69 t/s |
| **CPU build (KleidiAI + dotprod kernels), 4 threads pinned to the A78 cores** | **97.6 t/s** | **18.4 t/s** |

The Vulkan `llama-mtmd-cli` also crashed on image input, and OpenCL in ggml is Adreno-only. For diffusion the
picture is the same (community reports put Vulkan at ~2x slower than CPU on phones). So Inferno builds and ships
no GPU backend at all; the speed comes from Q4_0 weights on Arm KleidiAI/dotprod kernels, 4 threads pinned to
the big cores, and flash attention.

Measured decode speeds on the reference device (llama-bench, pp128/tg32, 4 pinned threads, cool phone):
Qwen3-VL-2B Q4_0 97.6/18.4 · Qwen3.5-2B Q4_0 86.6/14.8 · MiniCPM-V 4.6 Q4_0 199/29.7 · LFM2.5-VL-1.6B Q4_0
150/29.1 · Qwen3.5-0.8B Q8_0 244/21.5 t/s. Image encoding on the CPU costs 5-10 s per image at 448 px; a
SD1.5-class diffusion step at 512 px costs ~13 s (DreamShaper 8 LCM: 4 steps + TAESD decode = ~63 s per image;
the one-step SDXS-512 needs ~12 s).

## Build

Prerequisites:

* JDK 17 on the host (`JAVA_HOME=/usr/lib/jvm/java-17-openjdk` or equivalent). The Gradle daemon itself runs on
  JDK 25, auto-provisioned through `gradle/gradle-daemon-jvm.properties` + the foojay resolver.
* Android SDK with `platforms;android-37.1` (for `:app`, `compileSdk` 37 minor level 1, required by the alpha
  Compose BOM) and `platforms;android-37.0` (for `:sdengine`), `build-tools;36.0.0`, **NDK 28.2.13676358** and
  **CMake 4.1.2**:
  `sdkmanager "platforms;android-37.0" "platforms;android-37.1" "build-tools;36.0.0" "ndk;28.2.13676358" "cmake;4.1.2"`.
  Note the package is `platforms;android-37.0`, not `platforms;android-37`; the latter does not exist.
* Git with submodule support. ImageMagick 7 only if you regenerate the launcher icons.

```bash
git clone https://github.com/cesp99/Inferno.git
cd Inferno
git submodule update --init --recursive        # llama.cpp (~1.2 GB) + stable-diffusion.cpp
./gradlew :app:assembleDebug                   # first native configure ~3-4 min
./gradlew :app:assembleRelease                 # R8 + resource shrinking + thin LTO for the native code
```

There is no store signing key in this repository: the `release` build type is signed with the default debug
keystore (`~/.android/debug.keystore`) so `assembleRelease` produces an installable APK for testing the minified
build. Replace `signingConfig` in `app/build.gradle.kts` with your own key before distributing.

Release APK size on `arm64-v8a`: **46 MB** (debug: 126 MB). Of that, 37 MB is `libinferno_sd.so`
(stable-diffusion.cpp with its own ggml) and 5 MB is `libinferno.so`; the Kotlin/Compose code shrinks to a
single 3.9 MB dex. R8 keep rules in `app/proguard-rules.pro` and `sdengine/consumer-rules.pro` preserve every
JNI entry point and the callback interfaces that the native side resolves by name.

Notes:

* Each native module's first CMake configure fetches **KleidiAI v1.24.0** over the network (ggml's
  `FetchContent`): `:app` (llama.cpp's ggml) and `:sdengine` (stable-diffusion.cpp's ggml fork) each download the
  same tarball into their own `.cxx` dir. For a fully offline build, extract the
  [v1.24.0 source](https://github.com/ARM-software/kleidiai/archive/refs/tags/v1.24.0.tar.gz) once and pass its
  directory to both modules with `./gradlew -Pinferno.kleidiaiSrc=/abs/path/kleidiai-1.24.0 :app:assembleDebug`.
  The property maps to the FetchContent name each ggml declares (`-DFETCHCONTENT_SOURCE_DIR_KLEIDIAI` for `:app`,
  `-DFETCHCONTENT_SOURCE_DIR_KLEIDIAI_DOWNLOAD` for `:sdengine`) plus `-DFETCHCONTENT_FULLY_DISCONNECTED=ON`; if
  you set the CMake variables by hand, remember that the two names differ.
* Shallow submodule clones (e.g. `actions/checkout` with the default `fetch-depth: 1`) have no reachable tags, so
  `git describe` fails; the build then falls back to the pins hard-coded in `app/build.gradle.kts` for
  `BuildConfig.LLAMA_TAG` / `LLAMA_COMMIT` / `SD_COMMIT` and prints a warning. Run
  `git -C third_party/llama.cpp fetch --tags --depth=1` to get the real tag. A missing `git` binary is handled the
  same way.
* Kotlin is built into AGP 9; do not apply `org.jetbrains.kotlin.android`. Versions live in
  `gradle/libs.versions.toml` (AGP 9.4.0, Kotlin 2.4.20, KSP 2.3.12, Compose BOM alpha 2026.09.00 with
  material3 1.5.0-alpha28).
* Native code is always built optimised (`RelWithDebInfo` + `-O3`, `-march=armv8.2-a+dotprod+fp16`), even in
  debug builds; only the Kotlin side is debuggable. `-DINFERNO_LTO=ON` is added for release.
* `BuildConfig.LLAMA_TAG` / `LLAMA_COMMIT` / `SD_COMMIT` are read from the submodules at configure time (with the
  fallback above) and shown in Settings > About.
* Verify 16 KB alignment after a native change:
  `llvm-readelf -l app/build/intermediates/merged_native_libs/debug/mergeDebugNativeLibs/out/lib/arm64-v8a/libinferno.so | grep LOAD`
  (alignment `0x4000`) and `zipalign -c -P 16 -v 4 app-debug.apk`.
* Launcher icons: `tools/icon/make_icons.sh path/to/Logo.png` regenerates every mipmap from the master logo.

## Model licences

Model weights are downloaded on demand from Hugging Face and are **not** part of this repository or the APK.
Each model's licence is shown on its card and in the download confirmation. The shipped chat catalog
(`app/src/main/java/to/eyed/inferno/models/ModelCatalog.kt`, picker order) and its licences:

| # | Model (text + projector) | Publisher (GGUF source) | Licence | Notes |
|---|---|---|---|---|
| 1 | Qwen3.5-2B Q4_0 + F16 mmproj | Alibaba (unsloth) | Apache-2.0 | default pick; 14.8 t/s measured; optional thinking mode |
| 2 | MiniCPM-V 4.6 Q4_0 + Q8_0 mmproj | OpenBMB (mmproj: ggml-org) | Apache-2.0 | fastest vision; 29.7 t/s measured; images capped at 448 px |
| 3 | LFM2.5-VL-1.6B Q4_0 + Q8_0 mmproj | Liquid AI | LFM Open License v1.0 (non-OSI; free below Liquid's revenue threshold) | 29.1 t/s measured |
| 4 | Gemma 4 E2B-it QAT Q4_0 + Q8_0 mmproj | Google (mmproj: ggml-org) | Apache-2.0 | best quality tier; 8.8 t/s at sustained clocks |
| 5 | LFM2.5-VL-3B Q4_0 + Q8_0 mmproj | Liquid AI | LFM Open License v1.0 (non-OSI) | strongest grounding; 9.7 t/s at sustained clocks |
| 6 | Qwen3.5-0.8B Q8_0 + F16 mmproj | Alibaba (unsloth) | Apache-2.0 | 21.5 t/s measured |
| 7 | Qwen3-VL-2B-Instruct Q4_0 + Q8_0 mmproj | Alibaba (unsloth / Qwen) | Apache-2.0 | safest fallback; 18.4 t/s measured; large KV cache, context capped |
| 8 | Qwen3.5-4B Q4_0 + F16 mmproj | Alibaba (unsloth) | Apache-2.0 | strongest reasoning/OCR; needs most of the phone's memory |
| 9 | LFM2.5-VL-450M Q8_0 + Q8_0 mmproj | Liquid AI | LFM Open License v1.0 (non-OSI) | ultra-light |

Not in the v1 catalog (reserved for a later `SPECIALIST` tier, no download offered): OvisOCR2 and PaddleOCR-VL-1.6
(both Apache-2.0) and HunyuanOCR-1.5 (Tencent Hunyuan Community License, region-gated; would never be bundled or
auto-downloaded).

Image generation (stable-diffusion.cpp, `app/src/main/java/to/eyed/inferno/models/ImageModelCatalog.kt`), 512 px
default; both models are SD 1.5-family and share one TAESD decoder (`madebyollin/taesd`, MIT):

| Model | Tier | Licence | Notes |
|---|---|---|---|
| DreamShaper 8 LCM q8_0 | Quality | CreativeML OpenRAIL-M | 4 steps (2-8), ~63 s per image measured (13.5 s/step + 3.8 s decode) |
| SDXS-512 q8_0 (`sdxs-512-tinySDdistilled`) | Instant | OpenRAIL++ | 1 step, ~12 s per image measured |

Evaluated on the reference device but not shipped: SD-Turbo q8_0 (~31 s/image but Stability AI non-commercial
licence), Anima-Turbo v1.1 (~5 min/image, non-commercial), FLUX.2 klein 4B (12-16 min/image), Z-Image (4+ GB),
SDXL-Lightning q4_0 (107 s, ~3 GB).

Always read the model card before use; the licence strings for the shipped models above are exactly what the
catalog shows (`ModelCatalog.models` / `ImageModelCatalog.models`) and may lag upstream changes.

## Licence

Inferno is free software under the **GNU General Public License v3.0** (`LICENSE`). Bundled third-party
components (llama.cpp/ggml and stable-diffusion.cpp under MIT, KleidiAI and the AndroidX/Kotlin/OkHttp/Coil
stack under Apache-2.0, Lucide under ISC, stb/miniaudio public domain or MIT) are listed with their licence texts
in [`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md); the same file is shown in-app under Settings > About >
Open-source licences, next to a link to this repository.
