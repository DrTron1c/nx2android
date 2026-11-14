// Android input → the host's pad, touch and motion state, the pure part (no Android or JNI headers, no host state), so tests/t_android_input.c checks it on the Mac.
// input_android.c feeds it from the launcher's events and publishes the results through dm_input.h / front.h.
#pragma once
#include <stddef.h>
#include <stdint.h>
#include "../dm_input.h"

// Android's own numbers (android/keycodes.h, android/input.h), repeated so the Mac build needs no NDK
enum {
    AK_BACK = 4, AK_DPAD_UP = 19, AK_DPAD_DOWN = 20, AK_DPAD_LEFT = 21, AK_DPAD_RIGHT = 22, AK_DPAD_CENTER = 23,
    AK_BUTTON_A = 96, AK_BUTTON_B = 97, AK_BUTTON_C = 98, AK_BUTTON_X = 99, AK_BUTTON_Y = 100, AK_BUTTON_Z = 101,
    AK_BUTTON_L1 = 102, AK_BUTTON_R1 = 103, AK_BUTTON_L2 = 104, AK_BUTTON_R2 = 105, AK_BUTTON_THUMBL = 106, AK_BUTTON_THUMBR = 107,
    AK_BUTTON_START = 108, AK_BUTTON_SELECT = 109, AK_BUTTON_MODE = 110,
};
enum {
    AA_X = 0, AA_Y = 1, AA_Z = 11, AA_RX = 12, AA_RY = 13, AA_RZ = 14, AA_HAT_X = 15, AA_HAT_Y = 16,
    AA_LTRIGGER = 17, AA_RTRIGGER = 18, AA_GAS = 22, AA_BRAKE = 23,
};
enum { AM_DOWN = 0, AM_UP = 1, AM_MOVE = 2, AM_CANCEL = 3, AM_POINTER_DOWN = 5, AM_POINTER_UP = 6 };   // MotionEvent actions (masked)

// ---------- Gamepads ----------
// Face buttons: Android names them by position as on an Xbox pad (BUTTON_A bottom, B right, X left, Y top), the Switch's layout is right A, bottom B, top X, left Y,
// so by default a pad maps by position (pad_apple.h does the same). labelled = 1 maps name for name instead, for a pad whose driver reports Nintendo's labels
uint64_t nxin_key_button(int keycode, int labelled);   // DM_BTN_* bit, 0 when the key is not a pad button
// Button remapping (DM_PAD_MAP, the launcher's Button mapping): each physical source is an Android button by its key name without KEYCODE_ (BUTTON_A … DPAD_RIGHT,
// BUTTON_C, BUTTON_Z); the analog triggers count as BUTTON_L2 / BUTTON_R2 and the hat as DPAD_*. A source with an entry presses its Switch button (or nothing: NONE),
// whatever DM_PAD_LABELLED says; one without keeps the default mapping. The spec: "BUTTON_A=A,BUTTON_B=B,BUTTON_L2=NONE" (Switch names A B X Y L R ZL ZR LS RS PLUS
// MINUS UP DOWN LEFT RIGHT NONE)
enum { NXIN_SRC_N = 18 };
extern const char *const nxin_src_names[NXIN_SRC_N];
extern const char *const nxin_switch_names[16];   // by DM_BTN_* bit
typedef struct { uint64_t to[NXIN_SRC_N]; uint32_t set; } nxin_map;
int nxin_src_of_key(int keycode);   // the source index of a keycode, -1 when it is none
// Parses spec into m (cleared first); returns the number of entries taken, and writes the first entry it could not read to bad ("" when all were read)
int nxin_map_parse(nxin_map *m, const char *spec, char *bad, size_t nbad);
typedef struct {
    const nxin_map *map;           // DM_PAD_MAP, or NULL: the default mapping
    uint64_t keys;                 // buttons held from key events
    float lx, ly, rx, ry;          // sticks as Android reports them (-1..1, y down)
    float hat_x, hat_y, lt, rt;    // D-pad hat (-1, 0, 1) and the triggers (0..1)
    int lt_on, rt_on;              // the triggers as ZL / ZR, with hysteresis
} nxin_pad;
int nxin_pad_key(nxin_pad *p, int keycode, int down, int labelled);   // 1 when the key is a pad button or a mapped source (the launcher then consumes the event)
uint64_t nxin_pad_button(const nxin_pad *p, int keycode, int labelled);   // the Switch button a key presses on this pad (its map first), 0 for none
int nxin_pad_axis(nxin_pad *p, int axis, float v);                     // 1 when the axis is used
// One MotionEvent's axes (only those the device has: InputDevice.getMotionRange). A device that has Z / RZ has its right stick there (Android's key layouts put it there,
// with the triggers on LTRIGGER / RTRIGGER), and its RX / RY are left out, so a pad that also reports RX / RY as idle or as triggers does not pull the stick back. Returns 1 when any is used
int nxin_pad_axes(nxin_pad *p, const int *axes, const float *values, int n);
// Buttons and sticks for dm_pad_set_player: stick y up, ±DM_STICK_MAX, a radial dead zone (0..0.9) rescaled to the full range
void nxin_pad_out(nxin_pad *p, float deadzone, uint64_t *buttons, int32_t *lx, int32_t *ly, int32_t *rx, int32_t *ry);
void nxin_stick(float x, float y, float deadzone, int32_t *ox, int32_t *oy);   // one stick, Android axes in (y down), Switch axes out (y up)

// ---------- On-screen controls ----------
// A data model the launcher draws: each control has a centre in normalized screen coordinates (0..1, origin top left) and a radius in units of the screen height,
// so circles stay round on any aspect ratio. Touches that land on a control drive it; every other touch inside the game's picture is a touchscreen finger
enum { NXT_BUTTON = 0, NXT_STICK = 1, NXT_DPAD = 2 };
#define NXT_MAX 24
// stick: 0 left, 1 right (NXT_STICK only). hw, hh, corner, reach: the control as a rounded box, for NXT_STYLE_NX2A (0 in the other style): half its width and height,
// its corners' radius and how far outside its edge it still takes a touch, all in units of the screen height
typedef struct { int kind; uint64_t buttons; int stick; float x, y, r; char label[8]; float hw, hh, corner, reach; } nxhost_touch_control;
typedef struct { int id, ctl, slot; float x, y; } nxin_pointer;   // ctl: control index, or -1 for a touchscreen finger in slot (dm_touch id), -2 for a touch outside both
// How touches drive the controls. 0, Banana Native's: round controls with grab rings, a stick at full tilt at 0.8 of its radius past a dead zone, one finger a control.
// NXT_STYLE_NX2A, nx2android's own engine's (nxnative/dm/android/front_android.c touch_event, overlay_layout.h ctl_at), for its themed gamepad (TouchControls with a
// ThemePad): a control is the rounded box it is drawn as, with its reach, a touch between two takes the one it is deeper into, a spot between two adjacent face buttons
// presses both, a stick is at full tilt at its ring (its half width) with no dead zone, the D-pad has a quarter of its half width dead in the middle and eight equal
// sectors, and any number of fingers may hold a control (each event is worked out again from every finger). NXT_STYLE_FLOAT (with NX2A): the sticks float, a touch
// on no control takes the stick of its half of the screen, centred where it lands (kept a stick's half width inside the screen)
enum { NXT_STYLE_NX2A = 1, NXT_STYLE_FLOAT = 2 };
typedef struct {
    nxhost_touch_control c[NXT_MAX]; int n;
    int enabled;                    // 0: the controls are hidden and every touch goes to the game's touchscreen
    int style;                      // NXT_STYLE_* bits
    float sw, sh;                   // screen size in pixels (aspect for hit tests; any unit)
    float gx, gy, gw, gh;           // the game's picture inside the screen, normalized
    nxin_pointer p[16]; int np;
    uint64_t pressed[NXT_MAX];      // what each control holds now (buttons), for the launcher's drawing; in NXT_STYLE_NX2A a stick a finger holds has 1 (not a button)
    float sx[2], sy[2];             // stick deflection, -1..1, y up
    int fptr[2]; float fx[2], fy[2];   // NXT_STYLE_FLOAT: the pointer id on each floating stick (-1 none) and its centre (normalized screen coordinates)
} nxin_touch;
void nxin_touch_init(nxin_touch *t);              // the default layout, enabled, 16:9 screen, picture full screen
void nxin_touch_default_layout(nxin_touch *t);
int nxin_touch_floating(const nxin_touch *t, float c[4]);   // the floating sticks' centres (lx, ly, rx, ry, normalized; -1 for a stick no finger holds); 0 when they do not float
// A layout of the launcher's own (it draws the controls): n controls (at most NXT_MAX), or the default layout when n <= 0. Pointers on the old layout are let go
// (bit 1 / 2 of the return say whether the pad / touchscreen changed, as nxin_touch_event); *kept is the number of controls now
int nxin_touch_set_layout(nxin_touch *t, const nxhost_touch_control *c, int n, int *kept);
int nxin_touch_set_style_layout(nxin_touch *t, int style, const nxhost_touch_control *c, int n, int *kept);   // the same with the style (NXT_STYLE_* bits)
// One pointer event: action is MotionEvent's (masked), id its pointer id, x / y normalized screen coordinates. Returns bit 1 when the pad state changed, bit 2 when the touchscreen did
int nxin_touch_event(nxin_touch *t, int action, int id, float x, float y);
void nxin_touch_pad(const nxin_touch *t, uint64_t *buttons, int32_t *lx, int32_t *ly, int32_t *rx, int32_t *ry);
typedef struct { uint32_t id; float x, y; } nxin_finger;   // the same layout as front.h's dm_touch
int nxin_touch_fingers(const nxin_touch *t, nxin_finger *out, int max);   // touchscreen fingers in the picture's normalized coordinates

// ---------- Motion ----------
// Android sensors: x to the right of the natural (portrait) screen, y to its top, z out of it, as CoreMotion; acceleration in m/s² as the specific force (+9.81 on z lying face up),
// rotation rate in rad/s. Out: the Switch frame for the display rotation (Surface.ROTATION_0..3), acceleration in G as gravity's direction, as dm_motion_set_switch takes them
void nxin_motion_to_switch(int rotation, const float acc_ms2[3], const float gyro[3], float acc_g[3], float rot[3]);
// Shaking the phone (DM_BTN_SHAKE), as the iOS front end detects it: gravity followed by a slow low-pass (about 0.8 s), a sample whose acceleration beyond it exceeds thr G is a hit,
// shaking lasts until 120 ms after the last hit. Returns 1 while shaking
typedef struct { float g[3], thr; int init; uint64_t last_ns, hit_ns; } nxin_shake;
int nxin_shake_feed(nxin_shake *s, const float acc_g[3], uint64_t t_ns);
