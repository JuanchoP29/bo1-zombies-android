// native_bink.h - FFmpeg-backed Bink 1 (.bik) video & audio decoder API (src/native/native_bink.cpp).
// Lives in src/native/ (excluded from p32rewrite) so callers in p32tree pass native 64-bit pointers cleanly,
// matching the exact pattern of src/native/native_audio.h.
#pragma once

#ifdef __cplusplus
extern "C" {
#endif

struct Bo1BinkInfo
{
    unsigned int width;
    unsigned int height;
    unsigned int frames;
    unsigned int rateNum;
    unsigned int rateDen;
    unsigned int hasAlpha;
};

int bo1_bink_exists(const char *basePath, const char *name);
int bo1_bink_open(const char *rawPath, struct Bo1BinkInfo *info);
void bo1_bink_close(int id);
int bo1_bink_decode_frame(
    int id,
    void *yBuf, unsigned int yPitch,
    void *crBuf, unsigned int crPitch,
    void *cbBuf, unsigned int cbPitch,
    void *aBuf, unsigned int aPitch);
void bo1_bink_rewind(int id);
void bo1_bink_set_volume(int id, float volume);
void bo1_bink_pause(int id, int paused);
int bo1_bink_wait(int id, unsigned int nextFrameNum);
int bo1_bink_should_skip(int id, unsigned int nextFrameNum);

#ifdef __cplusplus
}
#endif
