#include "inferno_cpu.h"

#include <sched.h>
#include <unistd.h>
#include <sys/auxv.h>
#include <asm/hwcap.h>

#include <cstdio>
#include <string>
#include <vector>

namespace inferno {

namespace {

// Reads a single integer from a sysfs file; -1 when unreadable.
long read_long(const std::string & path) {
    FILE * f = fopen(path.c_str(), "r");
    if (!f) {
        return -1;
    }
    long v = -1;
    if (fscanf(f, "%ld", &v) != 1) {
        v = -1;
    }
    fclose(f);
    return v;
}

} // namespace

CpuTopology cpu_topology() {
    CpuTopology t;
    long n = sysconf(_SC_NPROCESSORS_CONF);
    if (n < 1) n = 1;
    if (n > 32) n = 32;                    // big_mask is 32 bits; phones have <= 12 cores
    t.n_cores = (int) n;

    // Prefer the scheduler's normalised capacity (Seeker: cpu4-7 = 1024), fall back to max freq.
    std::vector<long> cap((size_t) n, -1);
    long max = -1;
    bool any = false;
    for (int i = 0; i < n; i++) {
        const std::string base = "/sys/devices/system/cpu/cpu" + std::to_string(i);
        long v = read_long(base + "/cpu_capacity");
        if (v <= 0) {
            v = read_long(base + "/cpufreq/cpuinfo_max_freq");
        }
        cap[(size_t) i] = v;
        if (v > 0) {
            any = true;
            if (v > max) max = v;
        }
    }
    if (any) {
        for (int i = 0; i < n; i++) {
            if (cap[(size_t) i] == max) {
                t.big_mask |= (1u << i);
                t.n_big++;
            }
        }
    }
    // Homogeneous SoC (or unreadable sysfs): every core counts as big so pinning is a no-op.
    if (t.n_big == 0 || t.n_big == t.n_cores) {
        t.big_mask = (n >= 32) ? 0xffffffffu : ((1u << n) - 1u);
        t.n_big = t.n_cores;
    }

    const unsigned long hwcap  = getauxval(AT_HWCAP);
    const unsigned long hwcap2 = getauxval(AT_HWCAP2);
    t.has_dotprod = (hwcap  & HWCAP_ASIMDDP) != 0;
    t.has_fp16    = (hwcap  & HWCAP_ASIMDHP) != 0;
    t.has_sve     = (hwcap  & HWCAP_SVE)     != 0;   // bit 22 of AT_HWCAP, not AT_HWCAP2
    t.has_i8mm    = (hwcap2 & HWCAP2_I8MM)   != 0;
    return t;
}

bool cpu_set_affinity(uint32_t mask) {
    cpu_set_t set;
    CPU_ZERO(&set);
    long n = sysconf(_SC_NPROCESSORS_CONF);
    if (n < 1) n = 1;
    if (n > CPU_SETSIZE) n = CPU_SETSIZE;
    for (int i = 0; i < n; i++) {
        if (mask == 0 || (i < 32 && (mask & (1u << i)))) {
            CPU_SET(i, &set);
        }
    }
    return sched_setaffinity(0, sizeof(set), &set) == 0;
}

} // namespace inferno
