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
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <time.h>
#include <unistd.h>
#ifdef __ANDROID__
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
            return (int)(busy * 100 / total);
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
APerformanceHintManager *s_adpfMgr = nullptr;
APerformanceHintSession *s_adpfSession = nullptr;
AdpfCreateSessionFn s_adpfCreate = nullptr;
AdpfUpdateTargetFn s_adpfUpdateTarget = nullptr;
AdpfReportActualFn s_adpfReportActual = nullptr;
AdpfSetThreadsFn s_adpfSetThreads = nullptr;
int32_t s_adpfTids[3] = { 0, 0, 0 }; // 0 = main, 1 = render, 2 = server
int64_t s_adpfTargetNs = 16666667LL;
int64_t s_frameStartNs = 0;

int64_t MonotonicNs()
{
    timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1000000000LL + (int64_t)ts.tv_nsec;
}

void AdpfLoadLocked()
{
    if (s_adpfInitTried)
        return;
    s_adpfInitTried = true;
    void *lib = dlopen("libandroid.so", RTLD_NOW | RTLD_LOCAL);
    if (!lib)
        return;
    auto getMgr = (AdpfGetManagerFn)dlsym(lib, "APerformanceHint_getManager");
    s_adpfCreate = (AdpfCreateSessionFn)dlsym(lib, "APerformanceHint_createSession");
    s_adpfUpdateTarget = (AdpfUpdateTargetFn)dlsym(lib, "APerformanceHint_updateTargetWorkDuration");
    s_adpfReportActual = (AdpfReportActualFn)dlsym(lib, "APerformanceHint_reportActualWorkDuration");
    s_adpfSetThreads = (AdpfSetThreadsFn)dlsym(lib, "APerformanceHint_setThreads");
    if (getMgr && s_adpfCreate && s_adpfReportActual)
        s_adpfMgr = getMgr();
}

void AdpfSyncSessionLocked()
{
    if (!s_adpfMgr)
        return;
    int32_t list[3];
    size_t count = 0;
    for (int i = 0; i < 3; ++i)
    {
        if (s_adpfTids[i] > 0)
            list[count++] = s_adpfTids[i];
    }
    if (count == 0)
        return;
    if (!s_adpfSession)
    {
        // Create session once both main & render threads are registered (or on first frame if single-threaded).
        if (s_adpfTids[0] > 0 && s_adpfTids[1] > 0)
        {
            s_adpfSession = s_adpfCreate(s_adpfMgr, list, count, s_adpfTargetNs);
            if (s_adpfSession)
                fprintf(stderr, "native: ADPF performance hint session active (%zu threads, target %.2f ms)\n",
                    count, (double)s_adpfTargetNs / 1e6);
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
    if (s_adpfTids[role] != tid)
    {
        s_adpfTids[role] = tid;
        AdpfLoadLocked();
        AdpfSyncSessionLocked();
    }
    pthread_mutex_unlock(&s_adpfMutex);
}

extern "C" void bo1_native_perf_frame_begin(void)
{
    s_frameStartNs = MonotonicNs();
}

extern "C" void bo1_native_perf_frame_end(int targetFps)
{
    if (!s_adpfSession || !s_adpfReportActual || s_frameStartNs <= 0)
        return;
    const int fps = (targetFps >= 30 && targetFps <= 240) ? targetFps : 60;
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
