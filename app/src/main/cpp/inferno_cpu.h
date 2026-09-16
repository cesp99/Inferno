#pragma once
// Core topology and ISA feature detection. MUST NOT touch ggml: the Kotlin feature gate calls
// cpuTopology() before any dotprod/fp16 code path is allowed to run.
#include <cstdint>

namespace inferno {

struct CpuTopology {
    int      n_cores     = 0;
    int      n_big       = 0;
    uint32_t big_mask    = 0;    // bit i = core i is big
    bool     has_dotprod = false;
    bool     has_fp16    = false;
    bool     has_i8mm    = false;
    bool     has_sve     = false;
};

// /sys cpu_capacity (fallback cpufreq/cpuinfo_max_freq) + getauxval(AT_HWCAP/AT_HWCAP2).
CpuTopology cpu_topology();

// sched_setaffinity(0, mask) for the calling thread; mask == 0 => all cores. Returns false on error.
bool cpu_set_affinity(uint32_t mask);

} // namespace inferno
