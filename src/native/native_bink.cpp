// native_bink.cpp - FFmpeg-backed Bink 1 (.bik) video & audio decoder for Android/Linux native builds.
//
// Called by src/web/web_cinematic.cpp (which runs in the 32-bit-pointer p32tree and implements the Bink API
// expected by gfx_d3d/r_cinematic.cpp). Keeping FFmpeg here in src/native/ avoids p32rewrite pointer rewriting.
// Supports Bink 1 video (YUV420P / YUVA420P -> Y, Cr, Cb, A planes) and Bink audio (binkaudio_rdft / dct,
// tracks 0 & 1 mixed into native_audio.cpp's SDL2 mixer callback).
#include "native_bink.h"
#include <SDL2/SDL.h>
#include <ctype.h>
#include <dirent.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>
#include <unistd.h>
#include <string>
#include <vector>

#ifdef BO1_FFMPEG_BINK
extern "C" {
#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libavutil/channel_layout.h>
#include <libavutil/log.h>
#include <libavutil/pixfmt.h>
#include <libavutil/samplefmt.h>
}
#endif

extern "C" {
// native_audio.cpp
void bo1_audio_bink_reset(int sampleRate);
void bo1_audio_bink_push(const float *stereoSamples, int numFrames);
void bo1_audio_bink_set_state(float volume, int paused);
}

namespace
{
std::string NormalizeSlashes(const char *path)
{
    std::string out;
    if (!path)
        return out;
    out.reserve(strlen(path));
    char prev = 0;
    for (const char *p = path; *p; ++p)
    {
        char c = (*p == '\\') ? '/' : *p;
        if (c == '/' && prev == '/')
            continue;
        out.push_back(c);
        prev = c;
    }
    return out;
}

std::string ResolveCasePath(const char *rawPath)
{
    std::string norm = NormalizeSlashes(rawPath);
    if (norm.empty())
        return norm;
    if (access(norm.c_str(), R_OK) == 0)
        return norm;
    const size_t slash = norm.find_last_of('/');
    if (slash == std::string::npos)
        return "";
    const std::string dir = norm.substr(0, slash);
    const std::string file = norm.substr(slash + 1);
    DIR *d = opendir(dir.c_str());
    if (!d)
        return "";
    std::string found;
    while (struct dirent *ent = readdir(d))
    {
        if (strcasecmp(ent->d_name, file.c_str()) == 0)
        {
            found = dir + "/" + ent->d_name;
            break;
        }
    }
    closedir(d);
    return found;
}

#ifdef BO1_FFMPEG_BINK
constexpr int kMaxBinks = 4;
constexpr int kMaxAudioTracks = 2;

struct BinkSlot
{
    bool used = false;
    AVFormatContext *fmt = nullptr;
    AVPacket *pkt = nullptr;

    int videoStream = -1;
    AVCodecContext *videoCtx = nullptr;
    AVFrame *videoFrame = nullptr;

    int audioStream[kMaxAudioTracks] = { -1, -1 };
    AVCodecContext *audioCtx[kMaxAudioTracks] = { nullptr, nullptr };
    AVFrame *audioFrame = nullptr;
    int audioTrackCount = 0;
    int audioSampleRate = 48000;
    std::vector<float> trackStereo[kMaxAudioTracks];
    std::vector<float> mixStereo;

    unsigned int width = 0;
    unsigned int height = 0;
    unsigned int frames = 1;
    unsigned int rateNum = 30;
    unsigned int rateDen = 1;
    unsigned int hasAlpha = 0;

    float volume = 0.0f;
    bool paused = false;
    uint64_t startMs = 0;
    uint64_t pauseStartMs = 0;
    int skipStreak = 0;
};

BinkSlot s_slots[kMaxBinks];

void FreeSlot(BinkSlot &s)
{
    if (s.videoCtx)
        avcodec_free_context(&s.videoCtx);
    if (s.videoFrame)
        av_frame_free(&s.videoFrame);
    for (int t = 0; t < kMaxAudioTracks; ++t)
    {
        if (s.audioCtx[t])
            avcodec_free_context(&s.audioCtx[t]);
        s.audioStream[t] = -1;
        s.trackStereo[t].clear();
    }
    s.mixStereo.clear();
    if (s.audioFrame)
        av_frame_free(&s.audioFrame);
    if (s.pkt)
        av_packet_free(&s.pkt);
    if (s.fmt)
        avformat_close_input(&s.fmt);
    s.used = false;
    s.videoStream = -1;
    s.audioTrackCount = 0;
    s.startMs = 0;
    s.pauseStartMs = 0;
    s.paused = false;
    s.skipStreak = 0;
}

void CopyPlane(void *dstPtr, unsigned int dstPitch, const uint8_t *src, int srcPitch, unsigned int w, unsigned int h)
{
    if (!dstPtr || !src || w == 0 || h == 0)
        return;
    uint8_t *dst = (uint8_t *)dstPtr;
    const unsigned int copyW = (dstPitch > 0 && dstPitch < w) ? dstPitch : w;
    for (unsigned int y = 0; y < h; ++y)
        memcpy(dst + (size_t)y * dstPitch, src + (ptrdiff_t)y * srcPitch, copyW);
}

void AppendAudioFrame(std::vector<float> &out, const AVFrame *f)
{
    const int samples = f->nb_samples;
    const int ch = f->ch_layout.nb_channels;
    if (samples <= 0 || ch <= 0)
        return;
    const size_t base = out.size();
    out.resize(base + (size_t)samples * 2);
    float *dst = out.data() + base;

    const AVSampleFormat fmt = (AVSampleFormat)f->format;
    auto readSample = [&](int c, int i) -> float {
        if (c >= ch)
            return 0.0f;
        switch (fmt)
        {
            case AV_SAMPLE_FMT_FLTP:
                return ((const float *)f->data[c])[i];
            case AV_SAMPLE_FMT_FLT:
                return ((const float *)f->data[0])[i * ch + c];
            case AV_SAMPLE_FMT_S16P:
                return ((const int16_t *)f->data[c])[i] * (1.0f / 32768.0f);
            case AV_SAMPLE_FMT_S16:
                return ((const int16_t *)f->data[0])[i * ch + c] * (1.0f / 32768.0f);
            default:
                return 0.0f;
        }
    };

    if (ch == 1)
    {
        for (int i = 0; i < samples; ++i)
        {
            const float v = readSample(0, i);
            dst[2 * i] = v;
            dst[2 * i + 1] = v;
        }
    }
    else if (ch == 2)
    {
        for (int i = 0; i < samples; ++i)
        {
            dst[2 * i] = readSample(0, i);
            dst[2 * i + 1] = readSample(1, i);
        }
    }
    else
    {
        // Downmix multi-channel (e.g. 5.1) cleanly to stereo
        for (int i = 0; i < samples; ++i)
        {
            const float fl = readSample(0, i);
            const float fr = readSample(1, i);
            const float fc = ch > 2 ? readSample(2, i) * 0.707f : 0.0f;
            const float bl = ch > 4 ? readSample(4, i) * 0.5f : 0.0f;
            const float br = ch > 5 ? readSample(5, i) * 0.5f : 0.0f;
            dst[2 * i] = fl + fc + bl;
            dst[2 * i + 1] = fr + fc + br;
        }
    }
}

void FlushFrameAudio(BinkSlot &s)
{
    if (s.audioTrackCount <= 0)
        return;
    size_t maxFloats = 0;
    for (int t = 0; t < s.audioTrackCount; ++t)
        if (s.trackStereo[t].size() > maxFloats)
            maxFloats = s.trackStereo[t].size();
    if (maxFloats == 0)
        return;
    if (s.volume > 0.0001f)
    {
        if (s.audioTrackCount == 1 || s.trackStereo[1].empty())
        {
            bo1_audio_bink_push(s.trackStereo[0].data(), (int)(s.trackStereo[0].size() / 2));
        }
        else if (s.trackStereo[0].empty())
        {
            bo1_audio_bink_push(s.trackStereo[1].data(), (int)(s.trackStereo[1].size() / 2));
        }
        else
        {
            s.mixStereo.assign(maxFloats, 0.0f);
            for (int t = 0; t < s.audioTrackCount; ++t)
            {
                const size_t n = s.trackStereo[t].size();
                for (size_t i = 0; i < n; ++i)
                    s.mixStereo[i] += s.trackStereo[t][i];
            }
            bo1_audio_bink_push(s.mixStereo.data(), (int)(maxFloats / 2));
        }
    }
    for (int t = 0; t < s.audioTrackCount; ++t)
        s.trackStereo[t].clear();
}
#endif
} // namespace

extern "C" int bo1_bink_exists(const char *basePath, const char *name)
{
    if (!basePath || !basePath[0] || !name || !name[0])
        return 0;
    std::string p1 = std::string(basePath) + "/main/video/" + name + ".bik";
    if (!ResolveCasePath(p1.c_str()).empty())
        return 1;
    std::string p2 = std::string(basePath) + "/raw/video/" + name + ".bik";
    if (!ResolveCasePath(p2.c_str()).empty())
        return 1;
    return 0;
}

extern "C" int bo1_bink_open(const char *rawPath, Bo1BinkInfo *info)
{
#ifndef BO1_FFMPEG_BINK
    (void)rawPath;
    (void)info;
    return 0;
#else
    if (!rawPath || !rawPath[0] || !info)
        return 0;
    const std::string resolved = ResolveCasePath(rawPath);
    if (resolved.empty())
        return 0;

    int slotIdx = -1;
    for (int i = 0; i < kMaxBinks; ++i)
    {
        if (!s_slots[i].used)
        {
            slotIdx = i;
            break;
        }
    }
    if (slotIdx < 0)
        return 0;

    av_log_set_level(AV_LOG_ERROR);
    BinkSlot &s = s_slots[slotIdx];
    s = BinkSlot{};
    s.used = true;

    if (avformat_open_input(&s.fmt, resolved.c_str(), nullptr, nullptr) != 0)
    {
        FreeSlot(s);
        return 0;
    }
    if (avformat_find_stream_info(s.fmt, nullptr) < 0)
    {
        FreeSlot(s);
        return 0;
    }

    for (unsigned int i = 0; i < s.fmt->nb_streams; ++i)
    {
        const AVCodecParameters *par = s.fmt->streams[i]->codecpar;
        if (par->codec_type == AVMEDIA_TYPE_VIDEO && s.videoStream < 0)
        {
            s.videoStream = (int)i;
        }
        else if (par->codec_type == AVMEDIA_TYPE_AUDIO && s.audioTrackCount < kMaxAudioTracks)
        {
            s.audioStream[s.audioTrackCount++] = (int)i;
        }
    }
    if (s.videoStream < 0)
    {
        FreeSlot(s);
        return 0;
    }

    AVStream *vs = s.fmt->streams[s.videoStream];
    const AVCodec *vcodec = avcodec_find_decoder(vs->codecpar->codec_id);
    if (!vcodec)
    {
        FreeSlot(s);
        return 0;
    }
    s.videoCtx = avcodec_alloc_context3(vcodec);
    if (!s.videoCtx || avcodec_parameters_to_context(s.videoCtx, vs->codecpar) < 0
        || avcodec_open2(s.videoCtx, vcodec, nullptr) < 0)
    {
        FreeSlot(s);
        return 0;
    }

    s.pkt = av_packet_alloc();
    s.videoFrame = av_frame_alloc();
    s.audioFrame = av_frame_alloc();
    if (!s.pkt || !s.videoFrame || !s.audioFrame)
    {
        FreeSlot(s);
        return 0;
    }

    int openedAudio = 0;
    for (int t = 0; t < s.audioTrackCount; ++t)
    {
        AVStream *as = s.fmt->streams[s.audioStream[t]];
        const AVCodec *acodec = avcodec_find_decoder(as->codecpar->codec_id);
        if (!acodec)
            continue;
        AVCodecContext *actx = avcodec_alloc_context3(acodec);
        if (!actx || avcodec_parameters_to_context(actx, as->codecpar) < 0
            || avcodec_open2(actx, acodec, nullptr) < 0)
        {
            avcodec_free_context(&actx);
            continue;
        }
        s.audioCtx[openedAudio] = actx;
        s.audioStream[openedAudio] = s.audioStream[t];
        if (openedAudio == 0 && actx->sample_rate > 0)
            s.audioSampleRate = actx->sample_rate;
        ++openedAudio;
    }
    s.audioTrackCount = openedAudio;

    s.width = (unsigned int)(s.videoCtx->width > 0 ? s.videoCtx->width : 640);
    s.height = (unsigned int)(s.videoCtx->height > 0 ? s.videoCtx->height : 360);
    AVRational rate = (vs->avg_frame_rate.num > 0 && vs->avg_frame_rate.den > 0)
        ? vs->avg_frame_rate
        : ((vs->r_frame_rate.num > 0 && vs->r_frame_rate.den > 0) ? vs->r_frame_rate : AVRational{ 30, 1 });
    s.rateNum = (unsigned int)rate.num;
    s.rateDen = (unsigned int)rate.den;
    if (vs->nb_frames > 0)
    {
        s.frames = (unsigned int)vs->nb_frames;
    }
    else if (s.fmt->duration > 0)
    {
        const uint64_t est = ((uint64_t)s.fmt->duration * s.rateNum) / (1000000ULL * s.rateDen);
        s.frames = est > 1 ? (unsigned int)est : 99999;
    }
    else
    {
        s.frames = 99999;
    }
    s.hasAlpha = (s.videoCtx->pix_fmt == AV_PIX_FMT_YUVA420P) ? 1u : 0u;

    if (s.audioTrackCount > 0)
    {
        bo1_audio_bink_reset(s.audioSampleRate);
        bo1_audio_bink_set_state(0.0f, 0);
    }

    info->width = s.width;
    info->height = s.height;
    info->frames = s.frames;
    info->rateNum = s.rateNum;
    info->rateDen = s.rateDen;
    info->hasAlpha = s.hasAlpha;

    printf("native: bink: opened '%s' (%ux%u, %u frames @ %u/%u fps, alpha=%u, audioTracks=%d @ %d Hz)\n",
           resolved.c_str(), s.width, s.height, s.frames, s.rateNum, s.rateDen, s.hasAlpha,
           s.audioTrackCount, s.audioSampleRate);
    return slotIdx + 1;
#endif
}

extern "C" void bo1_bink_close(int id)
{
#ifdef BO1_FFMPEG_BINK
    if (id < 1 || id > kMaxBinks)
        return;
    BinkSlot &s = s_slots[id - 1];
    if (!s.used)
        return;
    if (s.audioTrackCount > 0)
    {
        bo1_audio_bink_set_state(0.0f, 1);
        bo1_audio_bink_reset(48000);
    }
    FreeSlot(s);
#else
    (void)id;
#endif
}

extern "C" int bo1_bink_decode_frame(
    int id,
    void *yBuf, unsigned int yPitch,
    void *crBuf, unsigned int crPitch,
    void *cbBuf, unsigned int cbPitch,
    void *aBuf, unsigned int aPitch)
{
#ifndef BO1_FFMPEG_BINK
    (void)id; (void)yBuf; (void)yPitch; (void)crBuf; (void)crPitch; (void)cbBuf; (void)cbPitch; (void)aBuf; (void)aPitch;
    return 0;
#else
    if (id < 1 || id > kMaxBinks)
        return 0;
    BinkSlot &s = s_slots[id - 1];
    if (!s.used || !s.fmt || !s.videoCtx)
        return 0;

    if (s.startMs == 0)
        s.startMs = SDL_GetTicks64();

    while (true)
    {
        const int r = av_read_frame(s.fmt, s.pkt);
        if (r < 0)
        {
            FlushFrameAudio(s);
            return 0; // EOF
        }

        if (s.pkt->stream_index == s.videoStream)
        {
            const int sendRc = avcodec_send_packet(s.videoCtx, s.pkt);
            av_packet_unref(s.pkt);
            if (sendRc < 0)
                continue;
            if (avcodec_receive_frame(s.videoCtx, s.videoFrame) == 0)
            {
                const unsigned int w = s.width;
                const unsigned int h = s.height;
                const unsigned int cw = (w + 1u) / 2u;
                const unsigned int ch = (h + 1u) / 2u;
                // FFmpeg YUV420P: data[0] = Y, data[1] = U (Cb), data[2] = V (Cr), data[3] = A
                CopyPlane(yBuf, yPitch, s.videoFrame->data[0], s.videoFrame->linesize[0], w, h);
                CopyPlane(cbBuf, cbPitch, s.videoFrame->data[1], s.videoFrame->linesize[1], cw, ch);
                CopyPlane(crBuf, crPitch, s.videoFrame->data[2], s.videoFrame->linesize[2], cw, ch);
                if (aBuf)
                {
                    if (s.videoFrame->format == AV_PIX_FMT_YUVA420P && s.videoFrame->data[3])
                        CopyPlane(aBuf, aPitch, s.videoFrame->data[3], s.videoFrame->linesize[3], w, h);
                    else
                        memset(aBuf, 255, (size_t)aPitch * h);
                }
                av_frame_unref(s.videoFrame);
                FlushFrameAudio(s);
                return 1;
            }
            continue;
        }

        // Audio packet belonging to one of our opened tracks
        for (int t = 0; t < s.audioTrackCount; ++t)
        {
            if (s.pkt->stream_index == s.audioStream[t] && s.audioCtx[t])
            {
                if (s.volume > 0.0001f && avcodec_send_packet(s.audioCtx[t], s.pkt) == 0)
                {
                    while (avcodec_receive_frame(s.audioCtx[t], s.audioFrame) == 0)
                    {
                        AppendAudioFrame(s.trackStereo[t], s.audioFrame);
                        av_frame_unref(s.audioFrame);
                    }
                }
                break;
            }
        }
        av_packet_unref(s.pkt);
    }
#endif
}

extern "C" void bo1_bink_rewind(int id)
{
#ifdef BO1_FFMPEG_BINK
    if (id < 1 || id > kMaxBinks)
        return;
    BinkSlot &s = s_slots[id - 1];
    if (!s.used || !s.fmt)
        return;
    av_seek_frame(s.fmt, s.videoStream, 0, AVSEEK_FLAG_BACKWARD);
    if (s.videoCtx)
        avcodec_flush_buffers(s.videoCtx);
    for (int t = 0; t < s.audioTrackCount; ++t)
    {
        if (s.audioCtx[t])
            avcodec_flush_buffers(s.audioCtx[t]);
        s.trackStereo[t].clear();
    }
    if (s.audioTrackCount > 0)
    {
        bo1_audio_bink_reset(s.audioSampleRate);
        bo1_audio_bink_set_state(s.volume, s.paused ? 1 : 0);
    }
    s.startMs = 0;
    s.skipStreak = 0;
#else
    (void)id;
#endif
}

extern "C" void bo1_bink_set_volume(int id, float volume)
{
#ifdef BO1_FFMPEG_BINK
    if (id < 1 || id > kMaxBinks)
        return;
    BinkSlot &s = s_slots[id - 1];
    if (!s.used)
        return;
    if (volume < 0.0f)
        volume = 0.0f;
    if (volume > 1.0f)
        volume = 1.0f;
    s.volume = volume;
    if (s.audioTrackCount > 0)
        bo1_audio_bink_set_state(volume, -1);
#else
    (void)id; (void)volume;
#endif
}

extern "C" void bo1_bink_pause(int id, int paused)
{
#ifdef BO1_FFMPEG_BINK
    if (id < 1 || id > kMaxBinks)
        return;
    BinkSlot &s = s_slots[id - 1];
    if (!s.used)
        return;
    const bool want = paused != 0;
    if (want == s.paused)
        return;
    s.paused = want;
    const uint64_t now = SDL_GetTicks64();
    if (want)
    {
        s.pauseStartMs = now;
    }
    else if (s.startMs != 0 && s.pauseStartMs != 0 && now > s.pauseStartMs)
    {
        s.startMs += (now - s.pauseStartMs);
        s.pauseStartMs = 0;
    }
    if (s.audioTrackCount > 0)
        bo1_audio_bink_set_state(-1.0f, want ? 1 : 0);
#else
    (void)id; (void)paused;
#endif
}

extern "C" int bo1_bink_wait(int id, unsigned int nextFrameNum)
{
#ifdef BO1_FFMPEG_BINK
    if (id < 1 || id > kMaxBinks)
        return 0;
    BinkSlot &s = s_slots[id - 1];
    if (!s.used || s.startMs == 0 || nextFrameNum <= 1 || s.rateNum == 0)
        return 0;
    if (s.paused)
        return 1;
    const uint64_t now = SDL_GetTicks64();
    const uint64_t elapsed = now >= s.startMs ? (now - s.startMs) : 0;
    const uint64_t target = (uint64_t)(nextFrameNum - 1) * 1000ULL * s.rateDen / s.rateNum;
    return elapsed < target ? 1 : 0;
#else
    (void)id; (void)nextFrameNum;
    return 0;
#endif
}

extern "C" int bo1_bink_should_skip(int id, unsigned int nextFrameNum)
{
#ifdef BO1_FFMPEG_BINK
    if (id < 1 || id > kMaxBinks)
        return 0;
    BinkSlot &s = s_slots[id - 1];
    if (!s.used || s.startMs == 0 || nextFrameNum <= 1 || s.rateNum == 0 || s.paused)
    {
        s.skipStreak = 0;
        return 0;
    }
    const uint64_t now = SDL_GetTicks64();
    const uint64_t elapsed = now >= s.startMs ? (now - s.startMs) : 0;
    const uint64_t frameDur = (1000ULL * s.rateDen) / s.rateNum;
    const uint64_t target = (uint64_t)(nextFrameNum - 1) * 1000ULL * s.rateDen / s.rateNum;
    if (elapsed > target + frameDur * 2 && s.skipStreak < 2)
    {
        ++s.skipStreak;
        return 1;
    }
    s.skipStreak = 0;
    return 0;
#else
    (void)id; (void)nextFrameNum;
    return 0;
#endif
}
