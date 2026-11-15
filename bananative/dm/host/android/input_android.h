// What the Android launcher calls in libnxhost.so besides nxhost_main: input forwarding, touch, motion, rumble and the Activity lifecycle (android/PORTING.md, "Launcher interface").
// Every function may be called from any thread, before or after nxhost_main has started. jni_android.c binds the same functions to the launcher's Kotlin class.
// device: Android's InputDevice id. The host gives each device a player (pad_slots.h: player 1 first, a device that comes back gets its player back).
#pragma once
#include <stdint.h>
#include <stdbool.h>
#include "input_map.h"
#define NXHOST_API __attribute__((visibility("default")))

// ---- Gamepads ----
// A device is connected by its first event too; added only names it (for its player when it comes back) and says whether its face buttons carry Nintendo's labels in Android's names
NXHOST_API int nxhost_input_device_added(int device, const char *name, int labelled);   // the player (0..7), -1 with every player taken
NXHOST_API void nxhost_input_device_removed(int device);
NXHOST_API int nxhost_input_device_player(int device);   // -1 when the device has no player
// The device becomes player 1 (connected first if it was not; name as for added): player 1's holder moves to the device's old player (pad_slot_first). Both players' buttons
// and sticks are let go. 0, or -1 with every player taken
NXHOST_API int nxhost_input_device_first(int device, const char *name);
// KeyEvent from dispatchKeyEvent: keycode and down (ACTION_DOWN; repeats may be passed). True when it is a pad button: the launcher returns true and does not pass it on
NXHOST_API bool nxhost_input_key(int device, int keycode, bool down);
// MotionEvent from dispatchGenericMotionEvent (SOURCE_JOYSTICK / SOURCE_GAMEPAD): one axis (MotionEvent.AXIS_*), or every axis of one event at once (published once)
NXHOST_API bool nxhost_input_axis(int device, int axis, float value);
NXHOST_API bool nxhost_input_axes(int device, const int *axes, const float *values, int n);
NXHOST_API void nxhost_input_deadzone(float dz);   // radial stick dead zone, 0..0.9 (default 0.1, or DM_PAD_DEADZONE)

// ---- Touch ----
// One pointer of a MotionEvent from the SurfaceView's onTouchEvent: action = getActionMasked(), id = getPointerId(i), x / y = the pointer's position divided by the view's width
// and height. ACTION_MOVE carries every pointer: call once per pointer. Touches on an on-screen control drive it; the rest inside the game's picture are touchscreen fingers
NXHOST_API void nxhost_input_touch(int action, int id, float x, float y);
// The view's size in pixels and where the game's picture lies in it (normalized; 0, 0, 1, 1 when the picture fills the view)
NXHOST_API void nxhost_touch_layout(float view_w, float view_h, float pic_x, float pic_y, float pic_w, float pic_h);
NXHOST_API void nxhost_touch_controls_enable(bool on);   // on by default; off sends every touch to the game (a gamepad is in use, say)
// The on-screen controls to draw: their count, and each one's kind / buttons / centre / radius / label (input_map.h). pressed: what each holds now, stick: deflection (-1..1, y up)
NXHOST_API int nxhost_touch_controls(nxhost_touch_control *out, int max);
NXHOST_API void nxhost_touch_controls_state(uint64_t *pressed, int max, float stick[4]);
// The launcher's own layout (it draws the controls; the host still decides what each touch drives): n controls in the units above, at most NXT_MAX; n <= 0 or NULL brings
// the default layout back. Touches held on the old layout are let go. Returns the number of controls now
NXHOST_API int nxhost_touch_controls_set(const nxhost_touch_control *c, int n);
// The same with how touches drive them (input_map.h NXT_STYLE_*: nx2android's themed gamepad takes NXT_STYLE_NX2A, and NXT_STYLE_FLOAT for floating sticks, with each
// control's hw / hh / corner / reach); nxhost_touch_controls_set is style 0
NXHOST_API int nxhost_touch_controls_set_style(int style, const nxhost_touch_control *c, int n);
// The floating sticks' centres now (lx, ly, rx, ry, normalized to the view; -1 for a stick no finger holds); false when the sticks do not float
NXHOST_API bool nxhost_touch_controls_floating(float c[4]);

// ---- Motion ----
// The phone's gyro and accelerometer through ASensorManager, at 100 Hz, as player 1's six-axis sensor, and phone shakes as DM_BTN_SHAKE. Start in onResume, stop in onPause.
// DM_MOTION=0 or DM_PHONE_MOTION=0 leave the gyro off; DM_SHAKE_G sets the shake threshold (1.5 G)
NXHOST_API bool nxhost_motion_start(void);
NXHOST_API void nxhost_motion_stop(void);
NXHOST_API void nxhost_motion_rotation(int rotation);   // Display.getRotation() (Surface.ROTATION_0..3), on every configuration change

// ---- Rumble ----
// Optional. The host calls fn(ctx, player, intensity, sharpness) from its rumble thread whenever a player's vibration changes: intensity 0 stops it, otherwise 0..1, sharpness 0..1
// (low to high frequency). Set it before nxhost_main; route(player, 1) says that player has a vibrator (the phone for player 1, a controller's VibratorManager for the rest)
typedef void (*nxhost_rumble_fn)(void *ctx, int player, float intensity, float sharpness);
NXHOST_API void nxhost_rumble_callback(nxhost_rumble_fn fn, void *ctx);
NXHOST_API void nxhost_rumble_route(int player, bool available);

// ---- Text input (the keyboard applet) ----
// Optional. The host calls fn from the guest thread that opened the keyboard (not attached to the VM) and that thread waits until fn returns: the launcher shows its input box
// over the game and returns 1 with the confirmed text in out (UTF-8, at most out_cap bytes with the terminating 0), or 0 for cancel. title / sub / guide / initial are UTF-8
// (any of them may be empty); max_chars 0 = no limit (the host cuts the text to the limit in UTF-16 units either way), min_chars 0 = an empty text is accepted.
// Without a callback the applet takes its no-window path (cancel). While fn runs the pads and touches the game saw are let go and no input reaches it
typedef int (*nxhost_text_input_fn)(void *ctx, const char *title, const char *sub, const char *guide, const char *initial, int max_chars, int min_chars, char *out, int out_cap);
NXHOST_API void nxhost_text_input_callback(nxhost_text_input_fn fn, void *ctx);
// Host-internal (front_android.c): asks the callback (-1 without one), with the input held around it
int android_text_input(const char *title, const char *sub, const char *guide, const char *initial, int max_chars, int min_chars, char *out, int out_cap);

// ---- Lifecycle ----
// event: 0 onPause, 1 onStop, 2 onStart (after onStop), 3 onResume, 4 pause, 5 continue. The app is suspended while it is stopped (1 until 2) or paused (4 until 5):
// audio suspended, the guest clock frozen, the graphics drained and gated, the guest threads stopped at their next SVC (dm_guest_suspend), the game told Background; it
// comes back OutOfFocus and InFocus once resumed (3). Each change of suspension logs a "[lifecycle] …" line with the threads' CPU since the last one. Audio focus loss (AUDIOFOCUS_LOSS / LOSS_TRANSIENT) is an interruption: nxhost_audio_interrupt(true), and false when focus comes back
NXHOST_API void nxhost_lifecycle(int event);
// Sends the save to the sync folder now (DM_SAVE_SYNC, save_sync.h) if a save since the last send has not gone yet, waiting at most timeout_ms (Stop, before the
// process ends; onStop sends it on a thread of its own). True when the folder has this save (or sync is off)
NXHOST_API bool nxhost_save_flush(int timeout_ms);
// Whether the game is paused (4 until 5), whatever the screen: a game screen created again while the game runs (the activity recreated) shows the pause it finds
NXHOST_API int nxhost_paused(void);
NXHOST_API void nxhost_audio_interrupt(bool on);
// The audio device changed (AudioDeviceCallback: headphones in or out); the streams are opened again on the new route. The host also notices a disconnected stream by itself
NXHOST_API void nxhost_audio_route_changed(void);
// ---- Frame rate cap (the in-game panel) ----
// fps: 0 = the game's own frame rate, else 15, 20, 30, 40, 45 or 60, applied while the game runs (hle_fpslock.c dm_fps_target_set); DM_FPS_TARGET sets it at start.
// The status fills buf with "<cap> <rates> <the game's frame rate now>": the cap in effect, the caps this game's limiters hold (a bit each for 15, 20, 30, 40, 45, 60;
// 0 = none, the game keeps its own frame rate) and the rate the game presents at; returns the cap
NXHOST_API void nxhost_fps_cap_set(int fps);
NXHOST_API int nxhost_fps_cap_status(char *buf, int n);
// Logs which decoders the phone has for H.264, HEVC, VP8, VP9, AV1 and AAC (AMediaCodec_createDecoderByType), for a bring-up log
NXHOST_API void nxhost_codecs_log(void);
// ---- nx2android: Pokémon Pokopia's sleep mod (pkp_sleep.c) and the game's clock ----
// The game's clock: what the game reads now (seconds since 1970), and the skip on top of the host's clock (hle_time.c DM_TIME_SKIP), set live: the phone's own clock is never touched
NXHOST_API int64_t nxhost_clock_now(void);
NXHOST_API void nxhost_clock_skip(int64_t seconds);
// The mod's state: returns 1 when its frame gate runs in this game (Pokopia 2.0.1's main, rewritten with its gate), else 0. out[0] the local player's action now (0 none, -1 unknown),
// out[1] the last action the player started, out[2] how many times the player got into a bed (the bed's signs say so), out[3] the save (PKP_SAVE_*),
// out[4] 1 in a Link Play session (the mod then never asks), out[5] game frames the gate counted, out[6] the last four actions started (16 bits each, newest lowest),
// out[7] bit 0 in bed now, bits 8-15 the bed's signs, bits 16-18 the learning windows sampled, bits 20-21 the window being sampled, bit 24 getting out of bed,
// out[8] how many times the game went back to its title after a player's game (Save and Quit; gate OBS_PKP_GUIDE), out[9] 0
enum { PKP_SAVE_IDLE = 0, PKP_SAVE_ASKED = 1, PKP_SAVE_WRITING = 2, PKP_SAVE_DONE = 3, PKP_SAVE_FAILED = 4 };
NXHOST_API int nxhost_pkp_state(int64_t out[10]);
// Learning the bed (pkp_sleep.c "The bed, learned"): 1, 2, 3 sample the window standing / in the bed / standing again (about half a second of game frames);
// 4 makes the signs from the three (their number, -1 with a window missing); 0 the windows sampled (bits 0-2) and 0x100 while one is sampled
NXHOST_API int nxhost_pkp_learn(int step);
NXHOST_API int nxhost_pkp_signs(char *buf, int cap);   // the signs as text ("reg:offset:bed:standing;..." hex), for the launcher to keep; their number
NXHOST_API void nxhost_pkp_signs_set(const char *signs);   // the signs kept from an earlier start
// 0 the player's own input; 1 held: the game sees nothing of player 1 (dm_input_hold) while the mod's menu is up, nxhost_pkp_seen says what was pressed; 2 held, and the
// player gets out of bed (B, then the left stick toward the camera while the bed action lasts), then back to 0
NXHOST_API void nxhost_pkp_hold(int mode);
NXHOST_API uint64_t nxhost_pkp_seen(void);
// The game's own time of day: out[0] seconds into its day (-1 unknown), out[1] its date (y * 10000 + m * 100 + d, 0 unknown); 1 when read. The sleep stays black until
// the game shows the new time
NXHOST_API int nxhost_pkp_tod(int64_t out[2]);
// The game's focus, as the console's HOME menu changes it (dm_focus_set: 2 lost, 1 back): Pokopia takes a new clock when it comes back to the game
NXHOST_API void nxhost_pkp_focus(int st);
NXHOST_API void nxhost_pkp_save(void);   // the game's own save of the player's file (what Save and Quit writes), at the next game frame; out[3] follows it
