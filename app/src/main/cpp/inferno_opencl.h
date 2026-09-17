#pragma once
// OpenCL loader + GPU policy. ggml-opencl is linked against the forwarders in inferno_opencl.cpp instead of a
// libOpenCL.so, so libinferno.so has no OpenCL link dependency: the vendor driver (/vendor/lib64/libOpenCL.so,
// a public vendor library on Adreno phones) is dlopen'ed at runtime and its absence simply means "no GPU".
// MUST NOT touch ggml (the probe runs before the backend registry is first used).
#include <string>

namespace inferno {

// Mirrors GpuPref in AppPrefs.kt: 0 = AUTO (Adreno only), 1 = ON (any OpenCL GPU), 2 = OFF.
enum class GpuPolicy { AUTO = 0, ON = 1, OFF = 2 };

struct GpuProbe {
    bool        available = false;     // libOpenCL.so loaded and at least one GPU device answered
    bool        adreno    = false;     // Qualcomm Adreno (the only GPU family ggml-opencl is tuned for)
    std::string name;                  // CL_DEVICE_NAME, e.g. "QUALCOMM Adreno(TM) 840"
    std::string version;               // CL_DEVICE_VERSION, e.g. "OpenCL 3.0 Adreno(TM) 840"
    std::string driver;                // CL_DRIVER_VERSION
};

// Sets the policy and the program-cache directory (ggml-opencl caches compiled kernels there across runs).
// The driver is loaded and the device listed whatever the policy; OFF only keeps every load on the CPU. Call
// before the first ggml backend registry use (i.e. before any model load / estimate); policy changes later
// go through the same call with an empty cache_dir.
void opencl_configure(GpuPolicy policy, const std::string & cache_dir);

// Loads the driver and enumerates the first GPU device. Cheap: no context, no kernel build.
const GpuProbe & opencl_probe();

// The policy decision: OFF => false; ON => any available device; AUTO => Adreno only.
bool opencl_use_gpu();
GpuPolicy opencl_policy();

} // namespace inferno
