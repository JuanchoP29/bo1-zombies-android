// native_touch.h - on-screen controls for a touch screen (native_touch.cpp). C functions with no pointer-to-pointer
// parameters: the engine side (client_mp/cl_touch.cpp) is built with 4-byte pointers (tools/p32).
//
// Positions are fractions of the game's picture (0..1, x to the right, y down), not of the window: the picture can
// be smaller than the window (bars). Sizes are fractions of the picture's height.
#pragma once

#ifdef __cplusplus
extern "C" {
#endif

// From the engine, every frame: bo1_touch, whether a game is being played with no menu open, bo1_touch_sensitivity,
// bo1_touch_scale and the picture's width / height.
void bo1_native_touch_settings(int enabled, int ingame, float sensitivity, float scale, float aspect);
// The weapon in hand, every frame: whether it fires one shot (or burst) per pull, and how many pulls per second
// a held FIRE makes for such a weapon (0: none, the trigger is just held).
void bo1_native_touch_weapon(int semiAuto, float pullsPerSecond);
// The file the layout is kept in (<fs_homepath>/touch_layout.txt); loads it when it exists. Once.
void bo1_native_touch_layout_file(const char *path);

// From the window's event loop: phase 0 down, 1 moved, 2 up.
void bo1_native_touch_finger(int phase, long long finger, float x, float y);
// 1 while the fingers belong to the controls (a game is being played, or the layout is being edited)
int bo1_native_touch_wants_fingers(void);
// 1 while the controls stand in for a game pad: enabled and no real pad connected
int bo1_native_touch_is_pad(void);
void bo1_native_touch_real_pad(int connected);
// The pad state the controls make (XInput button bits, triggers 0..1, left stick -1..1 with y up) and the look
// movement since the last call, in pixels of a 1280-wide picture.
void bo1_native_touch_read(unsigned *buttons, float *lt, float *rt, float *lx, float *ly, int *lookX, int *lookY);

// For drawing: shape `index` (0, 1, ... until it returns 0): center, half extents, whether it is held, its label
// (copied, at most labelSize - 1 characters).
// style: 0 button, 1 fire (RT/R2), 2 stick ring, 3 stick knob,
//        10 jump (A/Cross), 11 crouch (B/Circle), 12 use (X/Square), 13 swap (Y/Triangle),
//        14 aim (LT/L2), 15 knife (R3), 16 nade (RB/R1), 17 tac (LB/L1), 18 pause, 19 scores.
int bo1_native_touch_shape(int index, float *x, float *y, float *halfWidth, float *halfHeight, int *held, int *style,
    char *label, int labelSize);

// Apply a preloaded layout preset (0 Shooter 2-finger, 1 Claw 4-finger, 2 Classic Gamepad).
void bo1_native_touch_preset(int preset, int saveNow);

// Layout editing: while on, a finger drags the control it lands on and the controls press nothing. Turning it off
// saves the layout. Reset puts every control back to its active preset's default place (and saves).
void bo1_native_touch_edit(int on);
int bo1_native_touch_editing(void);
void bo1_native_touch_reset(void);

#ifdef __cplusplus
}
#endif
