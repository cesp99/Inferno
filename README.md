# Inferno

Private AI that runs entirely on your Android phone. Chat with vision-capable language models, ask questions
about photos, and generate images, all on the device's CPU. Nothing leaves the phone.

<p align="center">
  <img src="app/src/main/ic_launcher-playstore.png" width="96" alt="Inferno logo">
</p>

## Features

* **Chat** with streaming Markdown, code blocks, and an optional thinking mode.
* **Vision.** Attach photos from the gallery, the camera, or the share sheet and ask about them.
* **Image generation** with two on-device models: DreamShaper 8 LCM for quality, SDXS-512 for speed.
* **Nine curated models** from 0.5 GB to 3.9 GB, downloaded on demand from Hugging Face.
* **Long conversations.** When a chat outgrows the model's memory, choose rolling history, automatic
  summaries, or a clean stop.
* **Three experience levels.** Normal shows only what a consumer needs; Power user adds context, prompt and
  sampling controls; Developer exposes engine internals and timings.
* **Works in the background.** Answers and images keep generating when you switch apps.

## Privacy

Everything runs offline. The only network use is downloading model files when you ask for them. There is no
telemetry, no analytics, no account, and no cloud fallback. Chats, images, and models stay in the app's private
storage and are excluded from Android backups and device transfers.

## Requirements

* Android 13 or newer, 64-bit ARM.
* 6 GB of RAM minimum, 8 GB recommended.
* A CPU with the `dotprod` and `fp16` extensions, which means most phones from 2020 onward. The app checks at
  start-up.

Tested on a Solana Seeker (MediaTek Dimensity 7300, 8 GB). Speeds there range from 9 tokens/s for the largest
model to 30 tokens/s for the fastest; see [docs/performance.md](docs/performance.md).

## Under the hood

Two native engines, compiled from pinned git submodules and statically linked into the app:

* [llama.cpp](https://github.com/ggml-org/llama.cpp) for language and vision models.
* [stable-diffusion.cpp](https://github.com/leejet/stable-diffusion.cpp) for image generation.

Both run CPU-only with Arm KleidiAI kernels, four threads pinned to the big cores, and flash attention. The GPU
path was measured and turned out many times slower on mobile Mali GPUs; the numbers are in
[docs/performance.md](docs/performance.md). The app itself is Kotlin and Jetpack Compose with a Material 3
Expressive, monochrome design. See [docs/architecture.md](docs/architecture.md).

## Building

```bash
git clone https://github.com/cesp99/Inferno.git
cd Inferno
git submodule update --init --recursive
./gradlew :app:assembleDebug
```

You need JDK 17, NDK 28.2 and CMake 4.1.2. Full details, offline builds, and release notes are in
[docs/building.md](docs/building.md).

## Models

Model weights are not part of this repository or the APK; each model's licence is shown on its card before you
download it.

| Model | Size | Licence |
|---|---|---|
| Qwen3.5 2B (recommended) | 1.9 GB | Apache-2.0 |
| MiniCPM-V 4.6 | 1.2 GB | Apache-2.0 |
| LFM2.5-VL 1.6B | 1.3 GB | LFM Open License v1.0 |
| Gemma 4 E2B | 3.9 GB | Apache-2.0 |
| LFM2.5-VL 3B | 2.2 GB | LFM Open License v1.0 |
| Qwen3.5 0.8B | 1.0 GB | Apache-2.0 |
| Qwen3-VL 2B | 1.5 GB | Apache-2.0 |
| Qwen3.5 4B | 3.3 GB | Apache-2.0 |
| LFM2.5-VL 450M | 0.5 GB | LFM Open License v1.0 |

| Image model | Size | Licence |
|---|---|---|
| DreamShaper 8 LCM | 1.8 GB | CreativeML OpenRAIL-M |
| SDXS-512 | 0.7 GB | OpenRAIL++ |

The LFM Open License is free for personal use and for companies below Liquid AI's revenue threshold; read the
model card before commercial use.

## Licence

Inferno is free software under the [GNU General Public License v3.0](LICENSE). Third-party components and their
licences are listed in [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md) and shown in the app under
Settings > About.
