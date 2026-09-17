#include "inferno_cpu.h"

#include <sched.h>
#include <unistd.h>
#include <sys/auxv.h>
#include <asm/hwcap.h>

#include <algorithm>
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

    // Prefer the scheduler's normalised capacity (Seeker: cpu4-7 = 1024), fall back to max freq. Same rule as
    // CpuTopology.bigMask on the Kotlin side: a core is big at >= 60 % of the largest capacity (85 % of the highest
    // frequency), which keeps a second performance tier (Snapdragon 8 Elite: 2x1024 + 6x741) in and only the
    // efficiency cluster (0.2-0.45) out. Cores "at the maximum" would leave the 4 default threads to one or two
    // prime cores, and an oversubscribed spin-barrier pool takes tens of minutes for one calibration.
    auto read_all = [n](const char * leaf, std::vector<long> & out) {
        out.assign((size_t) n, -1);
        for (int i = 0; i < n; i++) {
            out[(size_t) i] = read_long("/sys/devices/system/cpu/cpu" + std::to_string(i) + leaf);
            if (out[(size_t) i] <= 0) return false;     // one unreadable core: the whole source is unusable
        }
        return true;
    };
    std::vector<long> v;
    double fraction = 0.6;
    bool any = read_all("/cpu_capacity", v);
    if (!any) {
        any = read_all("/cpufreq/cpuinfo_max_freq", v);
        fraction = 0.85;
    }
    if (any) {
        long max = -1;
        for (long x : v) max = std::max(max, x);
        const double floor = (double) max * fraction;
        for (int i = 0; i < n; i++) {
            if ((double) v[(size_t) i] >= floor) {
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
