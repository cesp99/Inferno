# Performance notes

All numbers are from the reference device, a Solana Seeker (MediaTek Dimensity 7300: 4x Cortex-A78 + 4x
Cortex-A55, Mali-G615 MC2, 8 GB RAM, Android 16), on a cool phone.

## CPU on Mali, GPU on Adreno

The GPU path on the Seeker was measured with the same llama.cpp commit built with the Vulkan backend:

| Backend (Qwen3-VL-2B Q4_0, 4 threads, flash attention) | prompt 128 tok | generate 32 tok |
|---|---|---|
| Vulkan, all layers offloaded | 3.15 t/s | 5.80 t/s |
| **CPU, KleidiAI + dotprod kernels, 4 threads pinned to the A78 cores** | **97.6 t/s** | **18.4 t/s** |

The Vulkan multimodal CLI also crashed on image input, and diffusion shows the same picture, so on Mali (and
any non-Qualcomm SoC) the language model runs on the CPU: Q4_0 weights on Arm KleidiAI/dotprod kernels, four
threads pinned to the big cores, flash attention. Image generation is CPU-only everywhere.

On Qualcomm SoCs the language model runs on the Adreno GPU through ggml's OpenCL backend (the phone's own
`libOpenCL.so`, loaded at run time). Measured on a Galaxy Z Fold (Snapdragon 8 Elite Gen 5, Adreno 840, 2 prime
+ 6 performance cores, 16 GB), in-app Quick benchmark (pp512 / tg128, median of 3, phone cool at the start):

| Model | CPU, 4 threads | GPU (OpenCL) |
|---|---|---|
| Qwen3.5-2B Q4_0 | 164.5 / 26.8 t/s | **620.6 / 33.2 t/s** |
| Gemma 4 E2B Q4_0 | 126.7 / **20.1** t/s | 305.5 / 12.8 t/s |

Prefill is 2.4-3.8x faster on the GPU. Decode is faster only when every tensor lives on the GPU: Gemma 4 keeps
its 1.8 GB of per-layer embeddings memory-mapped on the CPU (they would not fit twice), and the resulting
CPU<->GPU split on every layer costs more than the GPU saves. The Auto setting therefore offloads only models
with nothing pinned to the CPU; On forces the GPU for every model. The first GPU load of a session compiles the
OpenCL programs (about 10 s, then cached in the app's cache directory) and the first turn compiles the
flash-attention variants it needs (about 1 s). On this driver the DK=512 flash-attention prefill kernel refuses
to compile (`cl_khr_subgroups`), which ggml handles by running those layers' prefill on the CPU. The vision
encoder runs on the CPU on every phone (its OpenCL path is untested); only the text model is offloaded.

Samsung's skin thresholds are 38/40/42/45 C for LIGHT/MODERATE/SEVERE/CRITICAL: a 30 s benchmark goes from
NONE to SEVERE and back in about two minutes, which is why the thread governor only acts from SEVERE on.

## Language models (llama-bench, pp128 / tg32, 4 pinned threads)

| Model | prompt t/s | generate t/s |
|---|---|---|
| Qwen3.5-0.8B Q8_0 | 244 | 21.5 |
| MiniCPM-V 4.6 Q4_0 | 199 | 29.7 |
| LFM2.5-VL-1.6B Q4_0 | 150 | 29.1 |
| Qwen3-VL-2B Q4_0 | 97.6 | 18.4 |
| Qwen3.5-2B Q4_0 | 86.6 | 14.8 |
| LFM2.5-VL-3B Q4_0 | 62.6 | 9.7 |
| Gemma 4 E2B QAT Q4_0 | 72.0 | 8.8 |

Q4_0 runs about 25 % faster than Q4_K_M on this CPU because only Q4_0 and Q8_0 hit the KleidiAI kernels.
Expect 20-25 % lower numbers after a few minutes of sustained use as the SoC settles to its thermal clocks.

## Vision

Encoding one image at 448 px costs 2.6 s (Gemma 4 E2B) to 6 s (LFM2.5-VL) on the CPU and dominates a vision
turn, which is why the app downscales images before the projector sees them.

## Image generation (512 px, TAESD decoder)

| Model | Steps | Time per image |
|---|---|---|
| SDXS-512 Q8_0 | 1 | ~12 s |
| DreamShaper 8 LCM Q8_0 | 4 | ~63 s |

The full VAE decode alone costs about 80 s here, so the tiny decoder is mandatory. Evaluated and rejected:
SD-Turbo (non-commercial licence), Anima-Turbo (~5 min/image), FLUX.2 klein 4B (12-16 min/image),
Z-Image (over 4 GB), SDXL-Lightning (107 s at 3 GB).
