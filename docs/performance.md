# Performance notes

All numbers are from the reference device, a Solana Seeker (MediaTek Dimensity 7300: 4x Cortex-A78 + 4x
Cortex-A55, Mali-G615 MC2, 8 GB RAM, Android 16), on a cool phone.

## Why CPU only

The GPU path was measured with the same llama.cpp commit built with the Vulkan backend:

| Backend (Qwen3-VL-2B Q4_0, 4 threads, flash attention) | prompt 128 tok | generate 32 tok |
|---|---|---|
| Vulkan, all layers offloaded | 3.15 t/s | 5.80 t/s |
| **CPU, KleidiAI + dotprod kernels, 4 threads pinned to the A78 cores** | **97.6 t/s** | **18.4 t/s** |

The Vulkan multimodal CLI also crashed on image input, and ggml's OpenCL backend is tuned for Adreno only.
Diffusion shows the same picture. Inferno therefore ships no GPU backend; the speed comes from Q4_0 weights on
Arm KleidiAI/dotprod kernels, four threads pinned to the big cores, and flash attention.

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
