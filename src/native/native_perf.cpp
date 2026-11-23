// native_perf.cpp - what the FPS counter's "full" mode shows besides frame times: how busy the CPU and the GPU are.
//
// CPU: this process's CPU time over wall time, as a share of all cores (clock_gettime).
// GPU: there is no portable figure. Android kernels publish one per vendor in sysfs, when the app may read it:
//   Qualcomm Adreno  /sys/class/kgsl/kgsl-3d0/gpu_busy_percentage   "37 %"
//                    /sys/class/kgsl/kgsl-3d0/gpubusy               "<busy> <total>" since the last read
//   ARM Mali         /sys/class/misc/mali0/device/utilization       "37"
// -1 when none can be read (desktop Linux, a phone that denies the files).
//
// This file is not rewritten (tools/p32).
#include <sched.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>
#ifdef __ANDROID__
#include <dirent.h>
#include <dlfcn.h>
#include <pthread.h>
#include <sys/resource.h>
#endif

namespace
{
double Seconds(clockid_t clock)
{
    timespec ts;
    clock_gettime(clock, &ts);
    return (double)ts.tv_sec + (double)ts.tv_nsec / 1e9;
}

bool ReadLine(const char *path, char *line, int size)
{
    FILE *file = fopen(path, "r");
    if (!file)
        return false;
    const bool ok = fgets(line, size, file) != nullptr;
    fclose(file);
    return ok;
}
}

// share of all cores this process used since the previous call, 0..100
extern "C" float bo1_native_cpu_percent(void)
{
    static double lastWall, lastCpu;
    static float last;
    const double wall = Seconds(CLOCK_MONOTONIC), cpu = Seconds(CLOCK_PROCESS_CPUTIME_ID);
    if (lastWall > 0.0 && wall - lastWall > 0.05)
    {
        long cores = sysconf(_SC_NPROCESSORS_ONLN);
        if (cores < 1)
            cores = 1;
        last = (float)((cpu - lastCpu) / (wall - lastWall) / (double)cores * 100.0);
    }
    if (lastWall <= 0.0 || wall - lastWall > 0.05)
    {
        lastWall = wall;
        lastCpu = cpu;
    }
    return last;
}

// 0..100, or -1 when the system does not say
extern "C" int bo1_native_gpu_percent(void)
{
    char line[128];
    if (ReadLine("/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage", line, sizeof(line)))
        return atoi(line);
    if (ReadLine("/sys/class/kgsl/kgsl-3d0/gpubusy", line, sizeof(line)))
    {
        long busy = 0, total = 0;
        if (sscanf(line, "%ld %ld", &busy, &total) == 2 && total > 0)
        {
            // Some Adreno kernels (e.g. Honor/Snapdragon 7 Gen 3) expose cumulative ticks since boot
            // rather than per-interval samples; compute the delta between calls so GPU % is live.
            static long s_lastBusy = -1, s_lastTotal = -1;
            static int s_lastPct = 0;
            if (s_lastTotal >= 0 && total > s_lastTotal && busy >= s_lastBusy)
            {
                const long dBusy = busy - s_lastBusy;
                const long dTotal = total - s_lastTotal;
                int pct = (int)(dBusy * 100 / dTotal);
                if (pct < 0) pct = 0;
                if (pct > 100) pct = 100;
                s_lastPct = pct;
                s_lastBusy = busy;
                s_lastTotal = total;
                return s_lastPct;
            }
            if (s_lastTotal < 0 || total < s_lastTotal)
            {
                s_lastBusy = busy;
                s_lastTotal = total;
                s_lastPct = (int)(busy * 100 / total);
            }
            return s_lastPct;
        }
        return 0;
    }
    if (ReadLine("/sys/class/misc/mali0/device/utilization", line, sizeof(line)))
        return atoi(line);
    return -1;
}

// a clock for frame times: seconds, sub-millisecond
extern "C" double bo1_native_seconds(void)
{
    return Seconds(CLOCK_MONOTONIC);
}

namespace
{
int64_t MonotonicNs()
{
    timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1000000000LL + (int64_t)ts.tv_nsec;
}

int64_t s_paceNextNs = 0;
int s_paceLastFps = 0;
} // namespace

// Precision sub-millisecond frame pacer (CLOCK_MONOTONIC).
// Eliminates integer-millisecond drift (e.g. 1000/60 = 16 ms = 62.5 FPS beating against a 60/120 Hz display)
// and wakes cleanly before the deadline to avoid OS timer-slack overshoot.
extern "C" void bo1_native_perf_pace_frame(int maxFps)
{
    if (maxFps <= 0 || maxFps > 500)
    {
        s_paceNextNs = 0;
        s_paceLastFps = 0;
        return;
    }
    const int64_t targetNs = 1000000000LL / (int64_t)maxFps;
    int64_t now = MonotonicNs();
    if (s_paceNextNs == 0 || s_paceLastFps != maxFps)
    {
        s_paceLastFps = maxFps;
        s_paceNextNs = now + targetNs;
        return;
    }
    while (now < s_paceNextNs)
    {
        const int64_t rem = s_paceNextNs - now;
        if (rem > 1500000LL)
        {
            const int64_t wakeNs = s_paceNextNs - 1000000LL;
            timespec ts;
            ts.tv_sec = (time_t)(wakeNs / 1000000000LL);
            ts.tv_nsec = (long)(wakeNs % 1000000000LL);
            clock_nanosleep(CLOCK_MONOTONIC, TIMER_ABSTIME, &ts, nullptr);
        }
        else if (rem > 150000LL)
        {
            sched_yield();
        }
        now = MonotonicNs();
    }
    if (now - s_paceNextNs > (targetNs >> 1))
        s_paceNextNs = now + targetNs; // resync after hitch/loading without bursting frames
    else
        s_paceNextNs += targetNs;      // lock exact phase (60.000 / 90.000 / 120.000 FPS)
}

// Universal Android Dynamic Performance Framework (ADPF, API 31+ loaded dynamically via dlsym so Android 8-11
// devices simply skip it without error) + modest thread priority boost for main and render threads.
#ifdef __ANDROID__
namespace
{
struct APerformanceHintManager;
struct APerformanceHintSession;

using AdpfGetManagerFn = APerformanceHintManager *(*)(void);
using AdpfCreateSessionFn = APerformanceHintSession *(*)(APerformanceHintManager *, const int32_t *, size_t, int64_t);
using AdpfUpdateTargetFn = int (*)(APerformanceHintSession *, int64_t);
using AdpfReportActualFn = int (*)(APerformanceHintSession *, int64_t);
using AdpfSetThreadsFn = int (*)(APerformanceHintSession *, const int32_t *, size_t);

pthread_mutex_t s_adpfMutex = PTHREAD_MUTEX_INITIALIZER;
bool s_adpfInitTried = false;
bool s_adpfCreateLogged = false;
APerformanceHintManager *s_adpfMgr = nullptr;
APerformanceHintSession *s_adpfSession = nullptr;
AdpfCreateSessionFn s_adpfCreate = nullptr;
AdpfUpdateTargetFn s_adpfUpdateTarget = nullptr;
AdpfReportActualFn s_adpfReportActual = nullptr;
AdpfSetThreadsFn s_adpfSetThreads = nullptr;
int32_t s_adpfTids[3] = { 0, 0, 0 }; // 0 = main, 1 = render, 2 = server
int64_t s_adpfTargetNs = 16666667LL;
int64_t s_frameStartNs = 0;
int s_adpfDefaultFps = 0;
int s_adpfFramesWithoutSession = 0;

// Universal ARM64 hybrid CPU topology (Big/Prime vs LITTLE) + DXVK background shader demotion.
// Prevents background dxvk-shader-* threads (which inherit nice -4 from RB_RenderThread) from
// stealing Big/Prime cores during gameplay on Snapdragon, MediaTek, Exynos, and Tensor SoCs.
bool s_topoInit = false;
bool s_hasHybrid = false;
int s_fastCount = 0;
int s_bgCount = 0;
cpu_set_t s_fastMask;
cpu_set_t s_bgMask;
int s_tuneFrameCount = 0;
int s_tuneScansDone = 0;
bool s_tuneLogged = false;

void DetectCoreTopologyLocked()
{
    if (s_topoInit)
        return;
    s_topoInit = true;
    CPU_ZERO(&s_fastMask);
    CPU_ZERO(&s_bgMask);

    long caps[16];
    int online = 0;
    long minCap = 0, maxCap = 0;
    for (int c = 0; c < 16; ++c)
    {
        caps[c] = -1;
        char path[96], line[64];
        snprintf(path, sizeof(path), "/sys/devices/system/cpu/cpu%d/cpu_capacity", c);
        long val = -1;
        if (ReadLine(path, line, sizeof(line)))
            val = atol(line);
        if (val <= 0)
        {
            snprintf(path, sizeof(path), "/sys/devices/system/cpu/cpu%d/cpufreq/cpuinfo_max_freq", c);
            if (ReadLine(path, line, sizeof(line)))
                val = atol(line);
        }
        if (val > 0)
        {
            caps[c] = val;
            if (online == 0 || val < minCap)
                minCap = val;
            if (online == 0 || val > maxCap)
                maxCap = val;
            ++online;
        }
    }
    if (online >= 4 && maxCap > minCap)
    {
        for (int c = 0; c < 16; ++c)
        {
            if (caps[c] <= 0)
                continue;
            if (caps[c] > minCap)
            {
                CPU_SET(c, &s_fastMask);
                ++s_fastCount;
            }
            else
            {
                CPU_SET(c, &s_bgMask);
                ++s_bgCount;
            }
        }
        if (s_fastCount >= 2 && s_bgCount >= 2)
            s_hasHybrid = true;
    }
}

void TuneProcessThreadsLocked()
{
    DetectCoreTopologyLocked();
    DIR *dir = opendir("/proc/self/task");
    if (!dir)
        return;
    int shaderTuned = 0;
    int hotTuned = 0;
    while (dirent *ent = readdir(dir))
    {
        if (ent->d_name[0] < '0' || ent->d_name[0] > '9')
            continue;
        const pid_t tid = (pid_t)atoi(ent->d_name);
        if (tid <= 0)
            continue;
        char commPath[96], comm[64];
        snprintf(commPath, sizeof(commPath), "/proc/self/task/%d/comm", (int)tid);
        if (!ReadLine(commPath, comm, sizeof(comm)))
            continue;
        size_t len = strlen(comm);
        while (len > 0 && (comm[len - 1] == '\n' || comm[len - 1] == '\r'))
            comm[--len] = 0;

        if (strncmp(comm, "dxvk-shader", 11) == 0)
        {
            // Demote background Vulkan shader compilers so they never preempt gameplay/render frames.
            setpriority(PRIO_PROCESS, (id_t)tid, 8);
            if (s_hasHybrid && s_bgCount >= 2)
                sched_setaffinity(tid, sizeof(cpu_set_t), &s_bgMask);
            ++shaderTuned;
        }
        else if (strcmp(comm, "dxvk-cs") == 0 || strcmp(comm, "dxvk-submit") == 0
            || strcmp(comm, "dxvk-queue") == 0 || strcmp(comm, "Server") == 0)
        {
            setpriority(PRIO_PROCESS, (id_t)tid, -4);
            if (s_hasHybrid && s_fastCount >= 3)
                sched_setaffinity(tid, sizeof(cpu_set_t), &s_fastMask);
            ++hotTuned;
        }
    }
    closedir(dir);
    if ((shaderTuned > 0 || hotTuned > 0) && !s_tuneLogged)
    {
        s_tuneLogged = true;
        fprintf(stderr, "native: scheduler tuned (hybrid=%d fast=%d bg=%d, shader=%d -> nice +8, hot=%d -> nice -4)\n",
            s_hasHybrid ? 1 : 0, s_fastCount, s_bgCount, shaderTuned, hotTuned);
        fflush(stderr);
    }
}

int AdpfDefaultFps()
{
    if (s_adpfDefaultFps > 0)
        return s_adpfDefaultFps;
    int best = 60;
    const char *rates = getenv("BO1_REFRESH_RATES");
    if (rates && *rates)
    {
        const char *p = rates;
        while (*p)
        {
            while (*p && (*p < '0' || *p > '9'))
                ++p;
            if (!*p)
                break;
            int hz = atoi(p);
            if (hz >= 30 && hz <= 240 && hz > best)
                best = hz;
            while (*p >= '0' && *p <= '9')
                ++p;
        }
    }
    s_adpfDefaultFps = best;
    return best;
}

void AdpfLoadLocked()
{
    if (s_adpfInitTried)
        return;
    s_adpfInitTried = true;
    void *lib = dlopen("libandroid.so", RTLD_NOW | RTLD_LOCAL);
    void *symHandle = lib ? lib : RTLD_DEFAULT;
    auto getMgr = (AdpfGetManagerFn)dlsym(symHandle, "APerformanceHint_getManager");
    s_adpfCreate = (AdpfCreateSessionFn)dlsym(symHandle, "APerformanceHint_createSession");
    s_adpfUpdateTarget = (AdpfUpdateTargetFn)dlsym(symHandle, "APerformanceHint_updateTargetWorkDuration");
    s_adpfReportActual = (AdpfReportActualFn)dlsym(symHandle, "APerformanceHint_reportActualWorkDuration");
    s_adpfSetThreads = (AdpfSetThreadsFn)dlsym(symHandle, "APerformanceHint_setThreads");
    if (!getMgr || !s_adpfCreate || !s_adpfReportActual)
    {
        fprintf(stderr, "native: ADPF unavailable (symbols missing on this Android version)\n");
        fflush(stderr);
        return;
    }
    s_adpfMgr = getMgr();
    if (!s_adpfMgr)
    {
        fprintf(stderr, "native: ADPF unavailable (APerformanceHint_getManager returned null)\n");
        fflush(stderr);
    }
}

void AdpfSyncSessionLocked(bool allowSingleThread)
{
    if (!s_adpfMgr)
        return;
    int32_t list[3];
    size_t count = 0;
    for (int i = 0; i < 3; ++i)
    {
        const int32_t tid = s_adpfTids[i];
        if (tid <= 0)
            continue;
        bool dup = false;
        for (size_t j = 0; j < count; ++j)
        {
            if (list[j] == tid)
            {
                dup = true;
                break;
            }
        }
        if (!dup)
            list[count++] = tid;
    }
    if (count == 0)
        return;
    if (!s_adpfSession)
    {
        if ((s_adpfTids[0] > 0 && s_adpfTids[1] > 0) || (allowSingleThread && s_adpfTids[0] > 0))
        {
            s_adpfSession = s_adpfCreate(s_adpfMgr, list, count, s_adpfTargetNs);
            if (!s_adpfSession && count > 1)
            {
                // Some OEM PowerHALs reject multi-thread hint sessions; fall back to main thread only.
                s_adpfSession = s_adpfCreate(s_adpfMgr, list, 1, s_adpfTargetNs);
                if (s_adpfSession)
                    count = 1;
            }
            if (s_adpfSession)
            {
                fprintf(stderr, "native: ADPF performance hint session active (%zu threads, target %.2f ms)\n",
                    count, (double)s_adpfTargetNs / 1e6);
                fflush(stderr);
            }
            else if (!s_adpfCreateLogged)
            {
                s_adpfCreateLogged = true;
                fprintf(stderr, "native: ADPF createSession returned null (%zu threads)\n", count);
                fflush(stderr);
            }
        }
    }
    else if (s_adpfSetThreads)
    {
        s_adpfSetThreads(s_adpfSession, list, count);
    }
}
} // namespace

extern "C" void bo1_native_perf_register_thread(int role)
{
    if (role < 0 || role > 2)
        return;
    // Request a gentle priority boost for hot game threads (silently ignored if restricted by OS).
    setpriority(PRIO_PROCESS, 0, -4);
    const int32_t tid = (int32_t)gettid();
    pthread_mutex_lock(&s_adpfMutex);
    DetectCoreTopologyLocked();
    if (s_hasHybrid && s_fastCount >= 3)
        sched_setaffinity(0, sizeof(cpu_set_t), &s_fastMask);
    if (s_adpfTids[role] != tid)
    {
        s_adpfTids[role] = tid;
        AdpfLoadLocked();
        AdpfSyncSessionLocked(false);
    }
    pthread_mutex_unlock(&s_adpfMutex);
}

extern "C" void bo1_native_perf_frame_begin(void)
{
    s_frameStartNs = MonotonicNs();
}

extern "C" void bo1_native_perf_frame_end(int targetFps)
{
    ++s_tuneFrameCount;
    if (s_tuneScansDone < 4 && (s_tuneFrameCount == 30 || s_tuneFrameCount == 180 || s_tuneFrameCount == 480 || s_tuneFrameCount == 900))
    {
        ++s_tuneScansDone;
        pthread_mutex_lock(&s_adpfMutex);
        TuneProcessThreadsLocked();
        pthread_mutex_unlock(&s_adpfMutex);
    }
    if (!s_adpfSession && s_adpfMgr && !s_adpfCreateLogged && ++s_adpfFramesWithoutSession == 60)
    {
        pthread_mutex_lock(&s_adpfMutex);
        AdpfSyncSessionLocked(true);
        pthread_mutex_unlock(&s_adpfMutex);
    }
    if (!s_adpfSession || !s_adpfReportActual || s_frameStartNs <= 0)
        return;
    const int fps = (targetFps >= 30 && targetFps <= 240) ? targetFps : AdpfDefaultFps();
    const int64_t targetNs = 1000000000LL / (int64_t)fps;
    if (targetNs != s_adpfTargetNs && s_adpfUpdateTarget)
    {
        s_adpfTargetNs = targetNs;
        s_adpfUpdateTarget(s_adpfSession, targetNs);
    }
    int64_t workNs = MonotonicNs() - s_frameStartNs;
    // Clamp outliers (loading screens / pause / app switch) so Android's scheduler tracks active gameplay smoothly.
    if (workNs < 100000LL)
        workNs = 100000LL;
    else if (workNs > 100000000LL)
        workNs = 100000000LL;
    s_adpfReportActual(s_adpfSession, workNs);
}
#else
extern "C" void bo1_native_perf_register_thread(int) {}
extern "C" void bo1_native_perf_frame_begin(void) {}
extern "C" void bo1_native_perf_frame_end(int) {}
#endif
