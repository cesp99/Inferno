# Architecture

Inferno is a single-activity Compose app with no dependency-injection framework and no navigation library.
`AppContainer` is the composition root; ViewModels take the container.

```
MainActivity ─▶ AppViewModel / ChatViewModel / BenchViewModel / ImageGenViewModel
                        │
                        ▼
                  AppContainer
   ┌──────────────┬──────┴───────┬────────────────┬──────────────────┐
   ▼              ▼              ▼                ▼                  ▼
InferenceEngine  ModelRepository ChatRepository  ImageGenRepository  AppPrefs (DataStore)
   │              │              │                │
   │              ├─ ModelFiles  └─ ChatDatabase  ├─ ImageEngine (sdengine module)
   │              └─ ModelDownloader (OkHttp)     └─ GeneratedImageStoreImpl
   └─ LlamaNative (JNI) ─▶ libinferno.so = llama.cpp + mtmd + KleidiAI, static
                            libinferno_sd.so = stable-diffusion.cpp with its own forked ggml
```

## Two native libraries

stable-diffusion.cpp vendors a forked ggml (FP8 types, int8 conv kernels) that cannot be linked with
llama.cpp's ggml, so it lives in the `:sdengine` Gradle module and ships as a second shared object. Both are
built by CMake from the pinned submodules under `third_party/`, for `arm64-v8a` only, with
`-march=armv8.2-a+dotprod+fp16`, KleidiAI on, OpenMP off, and 16 KB page alignment.

## Engine rules

* **One native job at a time.** `EngineJob` is a process-wide mutex shared by text, vision and image
  generation. The LLM is unloaded before an image run and lazily reloaded on the next send.
* **Threads.** Four persistent ggml threadpools pinned to the big cores (detected from
  `cpuinfo_max_freq`); `ThermalGovernor` steps the count down to 3 and 2 as the thermal status rises.
* **Memory.** `ContextManager.plan()` estimates model + KV + compute + encoder bytes from a `no_alloc`
  load and refuses anything above 85 % of the budget; a watchdog polls `availMem` during load, encode and
  prefill and cancels the job under 500 MB. This exists because a 5.6 GB image-encode once kernel-panicked
  the test phone.
* **Prefix reuse.** KV state checkpoints (ring of 3) make regenerate and edit cheap on the hybrid
  Qwen3.5 / LFM2.5 models; a sliding-window guard covers Gemma.
* **Images** are downscaled before the projector sees them (Fast 336 px, Balanced 448 px, High = the
  catalog's maximum). Encoding is the dominant cost of a vision turn.

## Context policies

`SettingsState.contextPolicy` selects what happens when a chat outgrows the window:

* **Rolling** (default): whole oldest turns are dropped from the prompt; the history stays on disk and a
  divider marks where the model's memory starts.
* **Compact**: at 75 % usage the model summarises the older turns into a `SUMMARY` message and later
  prompts use system prompt + summary + recent turns; compactions chain.
* **Stop**: sending pauses with a panel offering a new chat (optionally seeded with a one-off summary) or a
  switch to rolling.

## Developer mode

Off by default. When on, sub-toggles reveal the meta line under answers, the context ring, turn details,
the benchmark screen, model tech specs, thermal/thread info and the live token counter, plus an Engine card
with build tags, CPU features and model buffer types. Normal users see none of it.

## Storage

```
files/models/<id>/<file>.gguf     text model, projector, .part + .part.json while downloading
files/models/taesd/…              tiny decoder shared by both image models
files/images/<sha256>.jpg         attachments + 256 px thumbs
files/images/gen/<uuid>.png       generated images
chats.db (Room v3)                conversations, messages, message images, generated images
```
