// native_touch.cpp - on-screen controls for a touch screen: fingers in, a game pad's state and look movement out.
//
// The engine already plays with a game pad (client/gpad_core.cpp): the controls here fill the same pad slot a real
// controller would (native_gamepad.cpp publishes it in slot 0 while no controller is connected), so movement,
// buttons, aim assist and the pad's HUD prompts need nothing new. Looking around is not the right stick but mouse
// movement (finger distance -> view angle, no acceleration curve), sent by native_gamepad.cpp with the rest.
//
//   left 45 % of the picture, not on a control   the move stick: it appears where the finger lands; pushed to the
//                                                edge it also holds the left stick's click (sprint)
//   anywhere else, not on a control              look
//   FIRE (right trigger; two of them)            the finger that holds one also looks (drag it like a stick).
//                                                Held with a weapon that fires once per pull (pistols, semi-
//                                                automatic rifles, burst weapons) it pulls the trigger again
//                                                and again, bo1_touch_autofire_rate times a second at most
//                                                (default 5; the weapon's own fire time still applies)
//   AIM (left trigger)                           a toggle
//   JUMP A, CROUCH B, USE X (use / reload), SWAP Y, KNIFE (right stick click), NADE (right shoulder),
//   TAC (left shoulder), the four d-pad directions, II (Start: pause), TAB (Back: scores)
//
// Layout: every control has a center and a size (native_touch.h: fractions of the picture). The defaults are below;
// <fs_homepath>/touch_layout.txt overrides them, one "name x y size" per line, written when layout editing ends
// (the engine's touch_edit command: drag the controls, DONE saves, RESET restores the defaults).
//
// Drawn by the engine (client_mp/cl_touch.cpp) as plain translucent boxes with a label.
//
// This file is not rewritten (tools/p32).
#include "native_touch.h"

#include <client/gpad_backend.h>
#include <chrono>
#include <math.h>
#include <mutex>
#include <stdio.h>
#include <string.h>

namespace
{
enum Kind { BUTTON, TOGGLE, EDIT_DONE, EDIT_RESET };

struct Control
{
    const char *name;
    const char *label;
    float x, y, size;      // the defaults; s_place holds the current values
    unsigned button;       // GPAD_RAW_* bits
    int trigger;           // 1 left, 2 right
    Kind kind;
    bool looks;            // the finger holding it also turns the view
};

const Control kControls[] = {
    { "fire", "FIRE", 0.845f, 0.600f, 0.085f, 0, 2, BUTTON, true },
    { "fire2", "FIRE", 0.065f, 0.540f, 0.055f, 0, 2, BUTTON, true },
    { "aim", "AIM", 0.720f, 0.760f, 0.065f, 0, 1, TOGGLE, false },
    { "jump", "JUMP", 0.955f, 0.650f, 0.060f, GPAD_RAW_A, 0, BUTTON, false },
    { "crouch", "CROUCH", 0.955f, 0.430f, 0.052f, GPAD_RAW_B, 0, BUTTON, false },
    { "use", "USE", 0.800f, 0.380f, 0.060f, GPAD_RAW_X, 0, BUTTON, false },
    { "swap", "SWAP", 0.885f, 0.230f, 0.052f, GPAD_RAW_Y, 0, BUTTON, false },
    { "knife", "KNIFE", 0.735f, 0.540f, 0.052f, GPAD_RAW_RIGHT_THUMB, 0, BUTTON, false },
    { "nade", "NADE", 0.630f, 0.900f, 0.050f, GPAD_RAW_RIGHT_SHOULDER, 0, BUTTON, false },
    { "tac", "TAC", 0.535f, 0.900f, 0.050f, GPAD_RAW_LEFT_SHOULDER, 0, BUTTON, false },
    { "up", "^", 0.100f, 0.200f, 0.034f, GPAD_RAW_DPAD_UP, 0, BUTTON, false },
    { "down", "v", 0.100f, 0.350f, 0.034f, GPAD_RAW_DPAD_DOWN, 0, BUTTON, false },
    { "left", "<", 0.055f, 0.275f, 0.034f, GPAD_RAW_DPAD_LEFT, 0, BUTTON, false },
    { "right", ">", 0.145f, 0.275f, 0.034f, GPAD_RAW_DPAD_RIGHT, 0, BUTTON, false },
    { "pause", "II", 0.965f, 0.065f, 0.040f, GPAD_RAW_START, 0, BUTTON, false },
    { "scores", "TAB", 0.890f, 0.065f, 0.040f, GPAD_RAW_BACK, 0, BUTTON, false },
    // only while the layout is edited
    { "done", "DONE", 0.440f, 0.080f, 0.050f, 0, 0, EDIT_DONE, false },
    { "reset", "RESET", 0.560f, 0.080f, 0.050f, 0, 0, EDIT_RESET, false },
};
const int kControlCount = (int)(sizeof(kControls) / sizeof(kControls[0]));
const int kFingers = 10;
const float kStickRadius = 0.13f;     // of the picture's height
const float kStickZone = 0.45f;       // the left part of the picture where a free finger is the move stick
const float kSprintAt = 0.92f;

struct Place
{
    float x, y, size;
    bool visible;
};

enum Role { NONE, CONTROL, STICK, LOOK, DRAG };

struct Finger
{
    bool down;
    long long id;
    Role role;
    int control;
    float x, y;            // last position
    float grabX, grabY;    // DRAG: finger minus control center
};

std::mutex s_lock;
Place s_place[kControlCount];
bool s_placed;
int s_preset = 0;               // 0 CODM/Shooter 2-finger, 1 Claw 4-finger, 2 Classic Gamepad (Xbox/PS)
int s_held[kControlCount];      // fingers on it
bool s_toggled[kControlCount];
Finger s_finger[kFingers];
bool s_enabled, s_ingame, s_realPad, s_editing;
float s_sensitivity = 1.0f, s_scale = 1.0f, s_aspect = 16.0f / 9.0f;
bool s_aimDrag = true;          // drag camera while holding AIM
bool s_autoSprint = true;       // sprint when pushing move stick forward past threshold
float s_sprintThresh = 0.85f;   // 0.50..0.98
bool s_aimOnFire = false;       // primary FIRE also holds AIM (left fire2 stays hip-fire)
int s_visualStyle = 0;          // 0 CODM/Shooter HD, 1 Xbox Digital, 2 PlayStation Digital
float s_opacity = 0.45f;        // 0.05..1.0
float s_stickX, s_stickY;       // the stick's center while a finger holds it
float s_stickDX, s_stickDY;     // -1..1, y up
bool s_stickHeld;
float s_lookX, s_lookY;         // not yet read, in pixels of a 1280-wide picture
char s_file[512];
bool s_semiAuto;                // the weapon in hand fires once per pull
float s_pullsPerSecond;         // a held FIRE's pulls for such a weapon (0: held)
bool s_fireWasHeld;
double s_fireSince;             // when FIRE went down

// Preloaded layout presets (1 = Claw 4-finger shooter, 2 = Classic console gamepad diamond).
const Place kPresetClaw[kControlCount] = {
    { 0.120f, 0.210f, 0.082f, true }, // fire (top-left index)
    { 0.840f, 0.610f, 0.068f, true }, // fire2 (right thumb)
    { 0.880f, 0.210f, 0.072f, true }, // aim (top-right index)
    { 0.945f, 0.720f, 0.058f, true }, // jump
    { 0.945f, 0.500f, 0.054f, true }, // crouch
    { 0.755f, 0.500f, 0.058f, true }, // use
    { 0.785f, 0.320f, 0.052f, true }, // swap
    { 0.680f, 0.690f, 0.052f, true }, // knife
    { 0.615f, 0.900f, 0.048f, true }, // nade
    { 0.520f, 0.900f, 0.048f, true }, // tac
    { 0.265f, 0.145f, 0.032f, true }, // up
    { 0.265f, 0.275f, 0.032f, true }, // down
    { 0.220f, 0.210f, 0.032f, true }, // left
    { 0.310f, 0.210f, 0.032f, true }, // right
    { 0.965f, 0.065f, 0.038f, true }, // pause
    { 0.895f, 0.065f, 0.038f, true }, // scores
    { 0.440f, 0.080f, 0.050f, true }, // done
    { 0.560f, 0.080f, 0.050f, true }, // reset
};

const Place kPresetGamepad[kControlCount] = {
    { 0.915f, 0.210f, 0.072f, true }, // fire (RT / R2)
    { 0.085f, 0.440f, 0.054f, true }, // fire2 (left helper)
    { 0.085f, 0.210f, 0.072f, true }, // aim (LT / L2)
    { 0.865f, 0.785f, 0.056f, true }, // jump (A / Cross - bottom of diamond)
    { 0.940f, 0.640f, 0.056f, true }, // crouch (B / Circle - right of diamond)
    { 0.790f, 0.640f, 0.056f, true }, // use (X / Square - left of diamond)
    { 0.865f, 0.495f, 0.056f, true }, // swap (Y / Triangle - top of diamond)
    { 0.700f, 0.800f, 0.050f, true }, // knife (R3)
    { 0.795f, 0.210f, 0.054f, true }, // nade (RB / R1)
    { 0.205f, 0.210f, 0.054f, true }, // tac (LB / L1)
    { 0.115f, 0.625f, 0.036f, true }, // up
    { 0.115f, 0.795f, 0.036f, true }, // down
    { 0.065f, 0.710f, 0.036f, true }, // left
    { 0.165f, 0.710f, 0.036f, true }, // right
    { 0.555f, 0.075f, 0.040f, true }, // pause (START / OPTIONS)
    { 0.445f, 0.075f, 0.040f, true }, // scores (BACK / SHARE)
    { 0.440f, 0.180f, 0.050f, true }, // done
    { 0.560f, 0.180f, 0.050f, true }, // reset
};

double Now()
{
    return std::chrono::duration<double>(std::chrono::steady_clock::now().time_since_epoch()).count();
}

void Defaults()
{
    const Place *presetTable = s_preset == 1 ? kPresetClaw : (s_preset == 2 ? kPresetGamepad : nullptr);
    for (int i = 0; i < kControlCount; ++i)
    {
        if (presetTable)
        {
            s_place[i] = presetTable[i];
        }
        else
        {
            s_place[i].x = kControls[i].x;
            s_place[i].y = kControls[i].y;
            s_place[i].size = kControls[i].size;
            s_place[i].visible = true;
        }
    }
    s_placed = true;
}

void Load()
{
    if (!s_placed)
        Defaults();
    FILE *file = s_file[0] ? fopen(s_file, "r") : nullptr;
    if (!file)
        return;
    char line[128];
    while (fgets(line, sizeof(line), file))
    {
        char name[32];
        float x = 0.0f, y = 0.0f, size = 0.0f;
        int vis = 1;
        const int n = sscanf(line, "%31s %f %f %f %d", name, &x, &y, &size, &vis);
        if (n < 4)
            continue;
        for (int i = 0; i < kControlCount; ++i)
        {
            if (strcmp(name, kControls[i].name) || kControls[i].kind == EDIT_DONE || kControls[i].kind == EDIT_RESET)
                continue;
            if (x >= 0.0f && x <= 1.0f && y >= 0.0f && y <= 1.0f && size >= 0.01f && size <= 0.3f)
            {
                s_place[i].x = x;
                s_place[i].y = y;
                s_place[i].size = size;
                s_place[i].visible = (n >= 5) ? (vis != 0) : true;
            }
        }
    }
    fclose(file);
}

void Save()
{
    FILE *file = s_file[0] ? fopen(s_file, "w") : nullptr;
    if (!file)
        return;
    for (int i = 0; i < kControlCount; ++i)
        if (kControls[i].kind != EDIT_DONE && kControls[i].kind != EDIT_RESET)
            fprintf(file, "%s %.4f %.4f %.4f %d\n", kControls[i].name, s_place[i].x, s_place[i].y, s_place[i].size,
                s_place[i].visible ? 1 : 0);
    fclose(file);
}

bool Shown(int i)
{
    const bool editOnly = kControls[i].kind == EDIT_DONE || kControls[i].kind == EDIT_RESET;
    if (editOnly)
        return s_editing;
    return s_editing || s_place[i].visible;
}

float HalfHeight(int i) { return s_place[i].size * s_scale; }
float HalfWidth(int i) { return s_place[i].size * s_scale / s_aspect; }

// the control under a point, the smallest when several overlap; a finger gets a margin of a fifth of the size
int ControlAt(float x, float y)
{
    int best = -1;
    for (int i = 0; i < kControlCount; ++i)
    {
        if (!Shown(i))
            continue;
        const float hw = HalfWidth(i) * 1.2f, hh = HalfHeight(i) * 1.2f;
        if (x < s_place[i].x - hw || x > s_place[i].x + hw || y < s_place[i].y - hh || y > s_place[i].y + hh)
            continue;
        if (best < 0 || s_place[i].size < s_place[best].size)
            best = i;
    }
    return best;
}

void ReleaseAll()
{
    memset(s_finger, 0, sizeof(s_finger));
    memset(s_held, 0, sizeof(s_held));
    s_stickHeld = false;
    s_stickDX = s_stickDY = 0.0f;
    s_lookX = s_lookY = 0.0f;
}

bool Wanted()
{
    return s_enabled && !s_realPad && (s_ingame || s_editing);
}

void MoveStick(float x, float y)
{
    // (x in units of the picture's height, like y)
    float dx = (x - s_stickX) * s_aspect / (kStickRadius * s_scale);
    float dy = (y - s_stickY) / (kStickRadius * s_scale);
    const float length = sqrtf(dx * dx + dy * dy);
    if (length > 1.0f)
    {
        dx /= length;
        dy /= length;
    }
    s_stickDX = dx;
    s_stickDY = -dy;
}

void EndEdit(bool save)
{
    s_editing = false;
    ReleaseAll();
    if (save)
        Save();
}
}

extern "C" void bo1_native_touch_settings(int enabled, int ingame, float sensitivity, float scale, float aspect)
{
    std::lock_guard<std::mutex> guard(s_lock);
    if (!s_placed)
        Defaults();
    const bool was = Wanted();
    s_enabled = enabled != 0;
    s_ingame = ingame != 0;
    s_sensitivity = sensitivity;
    s_scale = scale > 0.1f ? scale : 1.0f;
    s_aspect = aspect > 0.5f ? aspect : 16.0f / 9.0f;
    if (was && !Wanted())
    {
        // a menu opened under the fingers: nothing stays held (the aim toggle too)
        ReleaseAll();
        memset(s_toggled, 0, sizeof(s_toggled));
    }
}

extern "C" void bo1_native_touch_weapon(int semiAuto, float pullsPerSecond)
{
    s_semiAuto = semiAuto != 0;
    s_pullsPerSecond = pullsPerSecond;
}

extern "C" void bo1_native_touch_layout_file(const char *path)
{
    std::lock_guard<std::mutex> guard(s_lock);
    if (s_file[0] || !path)
        return;
    snprintf(s_file, sizeof(s_file), "%s", path);
    Load();
}

extern "C" int bo1_native_touch_wants_fingers(void)
{
    std::lock_guard<std::mutex> guard(s_lock);
    return Wanted();
}

extern "C" int bo1_native_touch_is_pad(void)
{
    return s_enabled && !s_realPad;
}

extern "C" void bo1_native_touch_real_pad(int connected)
{
    std::lock_guard<std::mutex> guard(s_lock);
    if (connected && !s_realPad)
        ReleaseAll();
    s_realPad = connected != 0;
}

extern "C" void bo1_native_touch_finger(int phase, long long id, float x, float y)
{
    std::lock_guard<std::mutex> guard(s_lock);
    Finger *finger = nullptr;
    for (int i = 0; i < kFingers; ++i)
        if (s_finger[i].down && s_finger[i].id == id)
            finger = &s_finger[i];
    if (phase == 0)
    {
        if (!Wanted())
            return;
        for (int i = 0; !finger && i < kFingers; ++i)
            if (!s_finger[i].down)
                finger = &s_finger[i];
        if (!finger)
            return;
        memset(finger, 0, sizeof(*finger));
        finger->down = true;
        finger->id = id;
        finger->x = x;
        finger->y = y;
        const int control = ControlAt(x, y);
        if (s_editing)
        {
            if (control >= 0 && kControls[control].kind == EDIT_DONE)
            {
                EndEdit(true);
            }
            else if (control >= 0 && kControls[control].kind == EDIT_RESET)
            {
                Defaults();
                Save();
                finger->role = NONE;
            }
            else if (control >= 0)
            {
                finger->role = DRAG;
                finger->control = control;
                finger->grabX = x - s_place[control].x;
                finger->grabY = y - s_place[control].y;
            }
            return;
        }
        if (control >= 0)
        {
            finger->role = CONTROL;
            finger->control = control;
            ++s_held[control];
            if (kControls[control].kind == TOGGLE)
                s_toggled[control] = !s_toggled[control];
        }
        else if (x < kStickZone && !s_stickHeld)
        {
            finger->role = STICK;
            s_stickHeld = true;
            s_stickX = x;
            s_stickY = y;
            s_stickDX = s_stickDY = 0.0f;
        }
        else
        {
            finger->role = LOOK;
        }
        return;
    }
    if (!finger)
        return;
    if (phase == 1)
    {
        if (finger->role == DRAG)
        {
            Place &place = s_place[finger->control];
            place.x = x - finger->grabX;
            place.y = y - finger->grabY;
            place.x = place.x < 0.02f ? 0.02f : place.x > 0.98f ? 0.98f : place.x;
            place.y = place.y < 0.03f ? 0.03f : place.y > 0.97f ? 0.97f : place.y;
        }
        else if (finger->role == STICK)
        {
            MoveStick(x, y);
        }
        else if (finger->role == LOOK
            || (finger->role == CONTROL && (kControls[finger->control].looks || (s_aimDrag && kControls[finger->control].trigger == 1))))
        {
            // the picture's height is 720 of these pixels: the same finger distance turns the same in both directions
            s_lookX += (x - finger->x) * 720.0f * s_aspect * s_sensitivity;
            s_lookY += (y - finger->y) * 720.0f * s_sensitivity;
        }
        finger->x = x;
        finger->y = y;
        return;
    }
    // up
    if (finger->role == CONTROL && s_held[finger->control] > 0)
        --s_held[finger->control];
    if (finger->role == STICK)
    {
        s_stickHeld = false;
        s_stickDX = s_stickDY = 0.0f;
    }
    finger->down = false;
}

extern "C" void bo1_native_touch_read(unsigned *buttons, float *lt, float *rt, float *lx, float *ly, int *lookX, int *lookY)
{
    std::lock_guard<std::mutex> guard(s_lock);
    *buttons = 0;
    *lt = *rt = *lx = *ly = 0.0f;
    *lookX = *lookY = 0;
    if (!Wanted() || s_editing)
        return;
    for (int i = 0; i < kControlCount; ++i)
    {
        const bool on = kControls[i].kind == TOGGLE ? s_toggled[i] : s_held[i] > 0;
        if (!on)
            continue;
        *buttons |= kControls[i].button;
        if (kControls[i].trigger == 1)
            *lt = 1.0f;
        if (kControls[i].trigger == 2)
        {
            *rt = 1.0f;
            if (s_aimOnFire && i == 0)
                *lt = 1.0f;
        }
    }
    // A weapon that fires once per pull: a held FIRE is a row of pulls, each 55 % of its period long. The
    // first pull starts when the finger lands, so a tap is still one shot.
    const bool fireHeld = *rt > 0.0f;
    if (fireHeld && !s_fireWasHeld)
        s_fireSince = Now();
    s_fireWasHeld = fireHeld;
    if (fireHeld && s_semiAuto && s_pullsPerSecond > 0.0f)
    {
        const double period = 1.0 / (double)s_pullsPerSecond;
        if (fmod(Now() - s_fireSince, period) > period * 0.55)
            *rt = 0.0f;
    }
    *lx = s_stickDX;
    *ly = s_stickDY;
    if (s_autoSprint && s_stickHeld && sqrtf(s_stickDX * s_stickDX + s_stickDY * s_stickDY) >= s_sprintThresh && s_stickDY > 0.45f)
        *buttons |= GPAD_RAW_LEFT_THUMB;
    // whole pixels; the rest stays for the next frame
    *lookX = (int)s_lookX;
    *lookY = (int)s_lookY;
    s_lookX -= (float)*lookX;
    s_lookY -= (float)*lookY;
}

extern "C" int bo1_native_touch_shape(int index, float *x, float *y, float *halfWidth, float *halfHeight, int *held,
    int *style, char *label, int labelSize)
{
    std::lock_guard<std::mutex> guard(s_lock);
    if (!s_placed)
        Defaults();
    int at = 0;
    for (int i = 0; i < kControlCount; ++i)
    {
        if (!Shown(i))
            continue;
        if (at++ != index)
            continue;
        *x = s_place[i].x;
        *y = s_place[i].y;
        *halfWidth = HalfWidth(i);
        *halfHeight = HalfHeight(i);
        *held = kControls[i].kind == TOGGLE ? s_toggled[i] : s_held[i] > 0;
        int st = 0;
        if (kControls[i].trigger == 2)
            st = 1;
        else if (kControls[i].trigger == 1)
            st = 14;
        else if (kControls[i].button == GPAD_RAW_A)
            st = 10;
        else if (kControls[i].button == GPAD_RAW_B)
            st = 11;
        else if (kControls[i].button == GPAD_RAW_X)
            st = 12;
        else if (kControls[i].button == GPAD_RAW_Y)
            st = 13;
        else if (kControls[i].button == GPAD_RAW_RIGHT_THUMB)
            st = 15;
        else if (kControls[i].button == GPAD_RAW_RIGHT_SHOULDER)
            st = 16;
        else if (kControls[i].button == GPAD_RAW_LEFT_SHOULDER)
            st = 17;
        else if (kControls[i].button == GPAD_RAW_START)
            st = 18;
        else if (kControls[i].button == GPAD_RAW_BACK)
            st = 19;
        *style = st;
        snprintf(label, (size_t)labelSize, "%s", kControls[i].label);
        return 1;
    }
    // the move stick while a finger holds it: its ring, then its knob
    if (s_stickHeld && !s_editing && (index == at || index == at + 1))
    {
        const bool knob = index == at + 1;
        const float size = kStickRadius * s_scale * (knob ? 0.4f : 1.0f);
        *x = s_stickX + (knob ? s_stickDX * kStickRadius * s_scale / s_aspect : 0.0f);
        *y = s_stickY - (knob ? s_stickDY * kStickRadius * s_scale : 0.0f);
        *halfWidth = size / s_aspect;
        *halfHeight = size;
        *held = knob;
        *style = knob ? 3 : 2;
        label[0] = 0;
        return 1;
    }
    return 0;
}

extern "C" void bo1_native_touch_edit(int on)
{
    std::lock_guard<std::mutex> guard(s_lock);
    if (on && !s_editing)
    {
        ReleaseAll();
        memset(s_toggled, 0, sizeof(s_toggled));
        s_editing = true;
    }
    else if (!on && s_editing)
    {
        EndEdit(true);
    }
}

extern "C" int bo1_native_touch_editing(void)
{
    return s_editing;
}

extern "C" void bo1_native_touch_preset(int preset, int saveNow)
{
    std::lock_guard<std::mutex> guard(s_lock);
    if (preset < 0 || preset > 2)
        preset = 0;
    s_preset = preset;
    Defaults();
    if (saveNow)
        Save();
}

extern "C" void bo1_native_touch_reset(void)
{
    std::lock_guard<std::mutex> guard(s_lock);
    Defaults();
    Save();
}

extern "C" void bo1_native_touch_response(int aimDrag, int autoSprint, float sprintThresh, int aimOnFire)
{
    std::lock_guard<std::mutex> guard(s_lock);
    s_aimDrag = aimDrag != 0;
    s_autoSprint = autoSprint != 0;
    s_sprintThresh = sprintThresh < 0.45f ? 0.45f : (sprintThresh > 0.98f ? 0.98f : sprintThresh);
    s_aimOnFire = aimOnFire != 0;
}

extern "C" void bo1_native_touch_visual(int visualStyle, float opacity)
{
    std::lock_guard<std::mutex> guard(s_lock);
    s_visualStyle = visualStyle < 0 ? 0 : (visualStyle > 2 ? 2 : visualStyle);
    s_opacity = opacity < 0.05f ? 0.05f : (opacity > 1.0f ? 1.0f : opacity);
}

extern "C" int bo1_native_touch_hud_read(float *out, int maxFloats)
{
    if (!out || maxFloats < 12)
        return 0;
    std::lock_guard<std::mutex> guard(s_lock);
    if (!s_placed)
        Defaults();

    // mode: 0 = hidden, 1 = menu navigation pad, 2 = in-game HD tactical HUD
    int mode = 0;
    if (s_enabled && !s_realPad)
        mode = (s_ingame || s_editing) ? 2 : 1;

    out[0] = (float)mode;
    out[1] = (float)s_visualStyle;
    out[2] = s_opacity;
    out[3] = s_scale;
    out[4] = s_stickHeld ? 1.0f : 0.0f;
    out[5] = s_stickX;
    out[6] = s_stickY;
    out[7] = s_stickDX;
    out[8] = s_stickDY;
    out[9] = s_sprintThresh;
    out[10] = s_autoSprint ? 1.0f : 0.0f;

    if (mode != 2)
    {
        out[11] = 0.0f;
        return 12;
    }

    int count = 0;
    int pos = 12;
    for (int i = 0; i < kControlCount; ++i)
    {
        if (!Shown(i))
            continue;
        if (kControls[i].kind == EDIT_DONE || kControls[i].kind == EDIT_RESET)
            continue;
        if (pos + 6 > maxFloats)
            break;
        int st = 0;
        if (i == 0) st = 1; // primary fire
        else if (i == 1) st = 2; // left hip-fire
        else if (kControls[i].trigger == 1) st = 14;
        else if (kControls[i].button == GPAD_RAW_A) st = 10;
        else if (kControls[i].button == GPAD_RAW_B) st = 11;
        else if (kControls[i].button == GPAD_RAW_X) st = 12;
        else if (kControls[i].button == GPAD_RAW_Y) st = 13;
        else if (kControls[i].button == GPAD_RAW_RIGHT_THUMB) st = 15;
        else if (kControls[i].button == GPAD_RAW_RIGHT_SHOULDER) st = 16;
        else if (kControls[i].button == GPAD_RAW_LEFT_SHOULDER) st = 17;
        else if (kControls[i].button == GPAD_RAW_START) st = 18;
        else if (kControls[i].button == GPAD_RAW_BACK) st = 19;
        else if (kControls[i].button == GPAD_RAW_DPAD_UP) st = 20;
        else if (kControls[i].button == GPAD_RAW_DPAD_DOWN) st = 21;
        else if (kControls[i].button == GPAD_RAW_DPAD_LEFT) st = 22;
        else if (kControls[i].button == GPAD_RAW_DPAD_RIGHT) st = 23;

        const bool held = kControls[i].kind == TOGGLE ? s_toggled[i] : s_held[i] > 0;
        out[pos++] = s_place[i].x;
        out[pos++] = s_place[i].y;
        out[pos++] = HalfWidth(i);
        out[pos++] = HalfHeight(i);
        out[pos++] = held ? 1.0f : 0.0f;
        out[pos++] = (float)st;
        ++count;
    }
    out[11] = (float)count;
    return pos;
}
