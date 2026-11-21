// web_cinematic.cpp - Bink 1 API bridge for native builds (backed by FFmpeg in src/native/native_bink.cpp).
//
// gfx_d3d/r_cinematic.cpp calls this API unchanged. Each HBINK handle lives in the low (< 4 GB) static table
// s_handles[] so 32-bit engine pointers work cleanly, while FFmpeg demuxing/decoding and SDL2 audio mixing run
// natively in src/native/native_bink.cpp.
#include <gfx_d3d/r_cinematic.h>
#include <dsound.h>
#include <string.h>
#ifdef BO1_NATIVE
#include <native_bink.h>
#endif

namespace
{
struct Bo1BinkHandle
{
    BINK bink;                  // MUST be first member: r_cinematic.cpp reads BINK fields via HBINK
    int used;
    int nativeId;
    int hasAlpha;
    int eof;
    BINKFRAMEBUFFERS *fbset;
};

Bo1BinkHandle s_handles[4];

Bo1BinkHandle *FromHandle(HBINK bnk)
{
    if (!bnk)
        return nullptr;
    Bo1BinkHandle *h = (Bo1BinkHandle *)bnk;
    if (h < &s_handles[0] || h >= &s_handles[4] || !h->used || h->nativeId <= 0)
        return nullptr;
    return h;
}
} // namespace

extern "C" {

HBINK BinkOpen(const char *name, U32 flags)
{
#ifdef BO1_NATIVE
    // Reject BINKFROMMEMORY (0x04000000): name is a raw buffer pointer rather than a file path.
    if (!name || (flags & 0x04000000u) != 0 || !name[0])
        return 0;

    Bo1BinkHandle *slot = nullptr;
    for (int i = 0; i < 4; ++i)
    {
        if (!s_handles[i].used)
        {
            slot = &s_handles[i];
            break;
        }
    }
    if (!slot)
        return 0;

    Bo1BinkInfo info{};
    const int nativeId = bo1_bink_open(name, &info);
    if (nativeId <= 0)
        return 0;

    memset(slot, 0, sizeof(*slot));
    slot->used = 1;
    slot->nativeId = nativeId;
    slot->hasAlpha = (int)info.hasAlpha;
    slot->eof = 0;
    slot->bink.Width = info.width;
    slot->bink.Height = info.height;
    slot->bink.Frames = info.frames > 0 ? info.frames : 1;
    slot->bink.FrameNum = 1;
    slot->bink.LastFrameNum = 0;
    slot->bink.FrameRate = info.rateNum > 0 ? info.rateNum : 30;
    slot->bink.FrameRateDiv = info.rateDen > 0 ? info.rateDen : 1;
    return &slot->bink;
#else
    (void)name;
    (void)flags;
    return 0;
#endif
}

char *BinkGetError(void)
{
    // Empty error text: R_Cinematic_CheckBinkError asserts on any non-empty error string.
    static char error[] = "";
    return error;
}

void BinkClose(HBINK bnk)
{
    Bo1BinkHandle *h = FromHandle(bnk);
    if (!h)
        return;
    bo1_bink_close(h->nativeId);
    memset(h, 0, sizeof(*h));
}

S32 BinkControlBackgroundIO(HBINK bink, U32 control)
{
    (void)bink;
    (void)control;
    return 0;
}

S32 BinkDoFrame(HBINK bnk)
{
    Bo1BinkHandle *h = FromHandle(bnk);
    if (!h || !h->fbset)
        return 0;
    const U32 bufIdx = h->fbset->FrameNum < BINKMAXFRAMEBUFFERS ? h->fbset->FrameNum : 0;
    BINKFRAMEPLANESET *planes = &h->fbset->Frames[bufIdx];
    const int ok = bo1_bink_decode_frame(
        h->nativeId,
        planes->YPlane.Buffer, planes->YPlane.BufferPitch,
        planes->cRPlane.Buffer, planes->cRPlane.BufferPitch,
        planes->cBPlane.Buffer, planes->cBPlane.BufferPitch,
        planes->APlane.Buffer, planes->APlane.BufferPitch);
    if (!ok)
    {
        h->eof = 1;
        h->bink.FrameNum = h->bink.Frames;
    }
    h->bink.LastFrameNum = h->bink.FrameNum;
    return 0;
}

void BinkGetFrameBuffersInfo(HBINK bink, BINKFRAMEBUFFERS *fbset)
{
    if (!fbset)
        return;
    memset(fbset, 0, sizeof(*fbset));
    Bo1BinkHandle *h = FromHandle(bink);
    if (!h)
        return;
    fbset->TotalFrames = BINKMAXFRAMEBUFFERS;
    fbset->YABufferWidth = h->bink.Width;
    fbset->YABufferHeight = h->bink.Height;
    fbset->cRcBBufferWidth = (h->bink.Width + 1u) / 2u;
    fbset->cRcBBufferHeight = (h->bink.Height + 1u) / 2u;
    fbset->FrameNum = 0;
    for (int i = 0; i < BINKMAXFRAMEBUFFERS; ++i)
    {
        fbset->Frames[i].YPlane.Allocate = 1;
        fbset->Frames[i].cRPlane.Allocate = 1;
        fbset->Frames[i].cBPlane.Allocate = 1;
        fbset->Frames[i].APlane.Allocate = h->hasAlpha ? 1 : 0;
    }
}

void BinkGetRealtime(HBINK bink, BINKREALTIME *run, U32 frames)
{
    (void)frames;
    if (!run)
        return;
    memset(run, 0, sizeof(*run));
    Bo1BinkHandle *h = FromHandle(bink);
    if (!h)
        return;
    run->FrameNum = h->bink.FrameNum;
    run->FrameRate = h->bink.FrameRate;
    run->FrameRateDiv = h->bink.FrameRateDiv;
    run->Frames = h->bink.Frames;
    // Report full I/O buffer so r_cinematic.cpp never pauses/seizes disc I/O for buffering.
    run->ReadBufferSize = 1;
    run->ReadBufferUsed = 1;
}

void BinkNextFrame(HBINK bnk)
{
    Bo1BinkHandle *h = FromHandle(bnk);
    if (!h)
        return;
    if (h->eof || h->bink.FrameNum >= h->bink.Frames)
    {
        bo1_bink_rewind(h->nativeId);
        h->eof = 0;
        h->bink.FrameNum = 1;
    }
    else
    {
        ++h->bink.FrameNum;
    }
    if (h->fbset)
        h->fbset->FrameNum ^= 1u;
}

BINKSNDOPEN BinkOpenDirectSound(UINTa param)
{
    (void)param;
    return 0;
}

S32 BinkPause(HBINK bnk, S32 pause)
{
    Bo1BinkHandle *h = FromHandle(bnk);
    if (!h)
        return 0;
    bo1_bink_pause(h->nativeId, pause);
    return pause;
}

void BinkRegisterFrameBuffers(HBINK bink, BINKFRAMEBUFFERS *fbset)
{
    Bo1BinkHandle *h = FromHandle(bink);
    if (!h)
        return;
    h->fbset = fbset;
}

void BinkSetIOSize(U32 iosize)
{
    (void)iosize;
}

void BinkSetMemory(BINKMEMALLOC a, BINKMEMFREE f)
{
    (void)a;
    (void)f;
}

S32 BinkSetSoundSystem(BINKSNDSYSOPEN open, UINTa param)
{
    (void)open;
    (void)param;
    return 0;
}

void BinkSetSoundTrack(U32 total_tracks, U32 *tracks)
{
    (void)total_tracks;
    (void)tracks;
}

void BinkSetVolume(HBINK bnk, U32 trackid, S32 volume)
{
    Bo1BinkHandle *h = FromHandle(bnk);
    if (!h)
        return;
    // r_cinematic.cpp sets tracks 0 & 1 to active volume and tracks 2..4 to 0; track 0 drives our stereo mix.
    if (trackid == 0)
        bo1_bink_set_volume(h->nativeId, (float)volume / 32768.0f);
}

S32 BinkShouldSkip(HBINK bink)
{
    Bo1BinkHandle *h = FromHandle(bink);
    if (!h)
        return 0;
    return bo1_bink_should_skip(h->nativeId, h->bink.FrameNum);
}

S32 BinkWait(HBINK bink)
{
    Bo1BinkHandle *h = FromHandle(bink);
    if (!h)
        return 0;
    return bo1_bink_wait(h->nativeId, h->bink.FrameNum);
}

// Bink's DirectSound output (R_CinematicInitSound): audio is mixed natively via SDL2 in native_audio.cpp
HRESULT DirectSoundCreate8(LPCGUID device, LPDIRECTSOUND8 *ds, LPUNKNOWN outer)
{
    (void)device;
    (void)outer;
    if (ds)
        *ds = nullptr;
    return DSERR_NODRIVER;
}

} // extern "C"
