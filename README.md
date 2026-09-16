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
Chats, images and models live in the app's private storage (`allowBackup=false`, so they are never uploaded by
Android auto-backup either).

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
SD1.5-class diffusion step at 512 px costs ~13 s (SD-Turbo: 2 steps + TAESD decode = ~31 s per image).

## Build

Prerequisites:

* JDK 17 on the host (`JAVA_HOME=/usr/lib/jvm/java-17-openjdk` or equivalent). The Gradle daemon itself runs on
  JDK 25, auto-provisioned through `gradle/gradle-daemon-jvm.properties` + the foojay resolver.
* Android SDK with `platforms;android-37`, `build-tools;36.0.0`, **NDK 28.2.13676358** and **CMake 4.1.2**
  (`sdkmanager "ndk;28.2.13676358" "cmake;4.1.2"`).
* Git with submodule support. ImageMagick 7 only if you regenerate the launcher icons.

```bash
git clone https://github.com/cesp99/Inferno.git
cd Inferno
git submodule update --init --recursive        # llama.cpp (~1.2 GB) + stable-diffusion.cpp
./gradlew :app:assembleDebug                   # first native configure ~3-4 min
./gradlew :app:assembleRelease                 # R8 + resource shrinking + thin LTO for the native code
```

Notes:

* The first CMake configure fetches **KleidiAI v1.24.0** once over the network (ggml's `FetchContent`). For a
  fully offline build, vendor it and pass
  `-DFETCHCONTENT_SOURCE_DIR_KLEIDIAI=<dir> -DFETCHCONTENT_FULLY_DISCONNECTED=ON` through
  `externalNativeBuild.cmake.arguments`.
* Kotlin is built into AGP 9; do not apply `org.jetbrains.kotlin.android`. Versions live in
  `gradle/libs.versions.toml` (AGP 9.4.0, Kotlin 2.4.20, KSP 2.3.12, Compose BOM alpha 2026.09.00 with
  material3 1.5.0-alpha28).
* Native code is always built optimised (`RelWithDebInfo` + `-O3`, `-march=armv8.2-a+dotprod+fp16`), even in
  debug builds; only the Kotlin side is debuggable. `-DINFERNO_LTO=ON` is added for release.
* `BuildConfig.LLAMA_TAG` / `LLAMA_COMMIT` / `SD_COMMIT` are read from the submodules at configure time and shown
  in Settings > About.
* Verify 16 KB alignment after a native change:
  `llvm-readelf -l app/build/intermediates/merged_native_libs/debug/mergeDebugNativeLibs/out/lib/arm64-v8a/libinferno.so | grep LOAD`
  (alignment `0x4000`) and `zipalign -c -P 16 -v 4 app-debug.apk`.
* Launcher icons: `tools/icon/make_icons.sh path/to/Logo.png` regenerates every mipmap from the master logo.

## Model licences

Model weights are downloaded on demand from Hugging Face and are **not** part of this repository or the APK.
Each model's licence is shown on its card and in the download confirmation. The curated catalog (ranked) and
its licences:

| # | Model (text + projector) | Publisher | Licence | Notes |
|---|---|---|---|---|
| 1 | Qwen3.5-2B Q4_0 | Alibaba (GGUF: unsloth) | Apache-2.0 | default pick; 14.8 t/s measured |
| 2 | MiniCPM-V 4.6 Q4_0 + Q8_0 mmproj | OpenBMB (mmproj: ggml-org) | Apache-2.0 | fastest vision; 29.7 t/s measured |
| 3 | LFM2.5-VL-1.6B Q4_0 | Liquid AI | LFM Open License v1.0 (non-OSI; free below Liquid's revenue threshold) | 29.1 t/s measured |
| 4 | Gemma 4 E2B-it QAT Q4_0 + Q8_0 mmproj | Google (mmproj: ggml-org) | Apache-2.0 | best quality tier |
| 5 | LFM2.5-VL-3B Q4_0 | Liquid AI | LFM Open License v1.0 (non-OSI) | strongest grounding |
| 6 | Qwen3.5-0.8B Q8_0 | Alibaba (GGUF: unsloth) | Apache-2.0 | 21.5 t/s measured |
| 7 | OvisOCR2 Q8_0 | ATH-MaaS (GGUF: bartowski) | Apache-2.0 | OCR specialist (reserved tier, not in v1 picker) |
| 8 | PaddleOCR-VL-1.6 Q8_0 | Baidu | Apache-2.0 | OCR specialist (reserved tier, not in v1 picker) |
| 9 | Qwen3.5-4B Q4_0 | Alibaba (GGUF: unsloth) | Apache-2.0 | context-capped (8k f16 / 16k q8 KV) |
| 10 | Qwen3-VL-2B-Instruct Q4_0 | Alibaba (GGUF: unsloth / Qwen) | Apache-2.0 | safest fallback; 18.4 t/s measured |
| 11 | LFM2.5-VL-450M Q8_0 | Liquid AI | LFM Open License v1.0 (non-OSI) | ultra-light |
| 12 | HunyuanOCR-1.5 Q8_0 | Tencent | Tencent Hunyuan Community License (excludes EU/UK/KR) | not bundled or auto-downloaded; region-gated |

Image generation (stable-diffusion.cpp), 512 px, TAESD decoder:

| Model | Licence | Notes |
|---|---|---|
| SD-Turbo (SD2.1-base distillation) q8_0 | Stability AI community licence (non-commercial) | 1-4 steps, ~31 s per image measured |
| DreamShaper-8-LCM q8_0 | CreativeML OpenRAIL-M (SD1.5 derivative) | 4 steps, ~55 s sampling measured |
| SDXS-512-DreamShaper q8_0 | CreativeML OpenRAIL-M | 1 step, smallest/fastest preview tier |
| Anima-Turbo v1.1 Q4_0 (2B DiT + Qwen3-0.6B) | non-commercial (see model card) | quality tier, ~46 s per step |

Always read the model card before use; the licence strings above are what the catalog shows and may lag upstream
changes.

## Licence

Inferno is free software under the **GNU General Public License v3.0** (`LICENSE`). Bundled third-party
components (llama.cpp/ggml and stable-diffusion.cpp under MIT, KleidiAI and the AndroidX/Kotlin/OkHttp/Coil
stack under Apache-2.0, Lucide under ISC, stb/miniaudio public domain or MIT) are listed with their licence texts
in [`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md); the same file is shown in-app under Settings > About >
Open-source licences, next to a link to this repository.
