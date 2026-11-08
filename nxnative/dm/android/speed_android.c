// Android port: the game speed fix (the pause menu's Game speed fix, settings.txt speed=, DM_SPEED_FIX; on by default). A game that scales each update by the
// time its frame took stops scaling at some limit, and every frame past it loses the rest of its time: below that frame rate the game runs in slow motion.
// Pokemon Pokopia 2.0.1's main loop (main+0xbfc404) turns the frame time into a multiplier in 60 Hz units that its updates read: 1.0 within 1.5 ms of 1/60 s,
// else frame time x 60 clamped to [1, 3] (the notes of pp-native, github.com/mshivam019/pp-native, on the same build). Under 20 fps each frame lost what went
// past 50 ms: at 15 fps the game ran at three quarters of its speed, and a 150 ms hitch lost 100 ms for good. Two observation gates (the profile's patches.py,
// dm/tools/patch_nso.py "Registered patches") hand the guest's registers here:
// - OBS_FRAME_MULT, where the multiplier is set. Its parameter names the multiplier's s register (bits 0..4), the frame time's (5..9) and the game's limit (10..13).
//   A frame up to DM_SPEED_MAX (60 Hz units, default 4: 15 fps) gets all its time. What a longer frame still lacks (a hitch) is kept, up to 1/3 s, and given back
//   over the next frames, at most a quarter more a frame (at 30 fps the game then runs up to 1.25 times as fast until it has caught up), instead of being lost.
// - OBS_FIXED_STEP, the game's fixed-step mode, whose frame time is 1/60 s whatever the real time (at 30 fps half speed). Its parameter names the rate's s register
//   (bits 0..4, 60.0 at the gate), the x registers of this frame's tick (5..9) and the previous frame's (10..14), and the tick frequency's d register (15..19).
//   A frame longer than 1/60 s + 1.5 ms takes its measured rate instead, down to 60 / DM_SPEED_MAX; faster frames keep the game's 60.
// Each gate first checks, over its first calls, that the registers hold what its parameter says (a multiplier the game's rule gives for that frame time; 60.0, a
// tick frequency and rising ticks), and turns itself off, logged, when they do not: an address that is not that code changes nothing. Off in the menu, the gates
// only count what they would give back. Larger steps are what the game's limit kept away: physics and collisions take longer steps than the game ever did on the
// Switch 2. The [speed] lines (every 900 frames with anything to report) say how much time the game lost and got back.
#include <math.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include "dm.h"
#include "overlay_layout.h"

#define DEBT_MAX 20.f   // 60 Hz units kept for later (1/3 s); a longer stall (a load) is not made up
#define CHECK_FRAMES 120

static float sreg(const uint64_t *r, int n) { float f; memcpy(&f, (const char *)r + 256 + 16 * n, sizeof f); return f; }   // the gate frame: q0..q31 at +256 (entry.S)
static void sreg_set(uint64_t *r, int n, float f) { memcpy((char *)r + 256 + 16 * n, &f, sizeof f); }
static double dreg(const uint64_t *r, int n) { double d; memcpy(&d, (const char *)r + 256 + 16 * n, sizeof d); return d; }
static float speed_max(void) {
    static float m; if (m) return m;
    const char *e = getenv("DM_SPEED_MAX"); float v = e ? (float)atof(e) : 4.f;
    return m = v < 2.f ? 2.f : v > 6.f ? 6.f : v;
}
static int speed_on(void) { return dm_disp(DISP_SPEED) != 0; }

// ---- the frame multiplier (one thread: the game's main loop) ----
static int g_mstate, g_mseen, g_mbad;   // g_mstate: 0 checking, 1 on, -1 off (the registers are not the multiplier)
static float g_debt;                     // 60 Hz units the game has yet to get back
static unsigned s_frames, s_over, s_quiet; static float s_lost, s_given, s_caught, s_dropped, s_long, s_sum;
static _Atomic unsigned g_fix_calls, g_fix_slow; static int g_fstate;

static void speed_report(void) {
    unsigned fc = atomic_exchange(&g_fix_calls, 0), fs = atomic_exchange(&g_fix_slow, 0);
    if (s_over || fc || !(++s_quiet % 8))
        LOG("[speed] %u frames, average step %.2f (60 Hz units): %u past the game's limit (longest %.0f ms), %.2f s of game time lost there, %.2f s given back at once, "
            "%.2f s caught up later, %.2f s left out (stalls past 1/3 s); %.0f ms still to catch up; fixed-step frames %u (%u slower than 60 fps)%s",
            s_frames, s_frames ? s_sum / s_frames : 0.f, s_over, s_long * 1000.f, s_lost / 60.f, s_given / 60.f, s_caught / 60.f, s_dropped / 60.f, g_debt * 1000.f / 60.f, fc, fs,
            speed_on() ? "" : " - the fix is off in the menu: nothing given back");
    s_frames = s_over = 0; s_lost = s_given = s_caught = s_dropped = s_long = s_sum = 0;
}

static void frame_mult(uint64_t *r, int param) {
    int mr = param & 31, dr = param >> 5 & 31, lim = param >> 10 & 15; if (!lim) lim = 3;
    float m = sreg(r, mr), dt = sreg(r, dr);
    if (g_mstate < 0) return;
    if (!g_mstate) {
        static int reached; if (!reached++) LOG("[speed] frame multiplier gate reached: s%d = %.3f, frame time s%d = %.2f ms (the game's limit %d)", mr, m, dr, dt * 1000.f, lim);
        if (!(dt > 0.f && dt < 10.f)) return;   // no time passed (the clock held while the menu is open) or a stall: says nothing
        float want = fabsf(dt - 1.f / 60.f) <= 0.0015152f ? 1.f : fminf(fmaxf(dt * 60.f, 1.f), (float)lim);   // the game's rule for this frame time
        if (fabsf(m - want) > 0.01f) { if (g_mbad++ < 3) LOG("[speed] frame multiplier check: s%d = %.3f for a frame of %.2f ms, the game's rule gives %.3f", mr, m, dt * 1000.f, want); }
        g_mseen++;
        if (g_mseen < CHECK_FRAMES) return;
        g_mstate = g_mbad > CHECK_FRAMES / 10 ? -1 : 1;
        LOG("[speed] frame multiplier: %d of %d frames %s the game's rule: %s", CHECK_FRAMES - g_mbad, CHECK_FRAMES, g_mstate > 0 ? "followed" : "only followed",
            g_mstate > 0 ? (speed_on() ? "the speed fix is on" : "the speed fix is off in the menu") : "not the multiplier, the gate does nothing");
        if (g_mstate < 0) return;
    }
    float real = dt * 60.f, mx = speed_max(), out = m;   // the frame's time in 60 Hz units
    s_frames++;
    if (real > 0.f && real < 600.f) {   // a frame of 10 s and more is a stall (a load, the app held): nothing to give back
        if (real > m + 0.01f && m >= lim - 0.001f) {   // past the game's limit: it drops real - m
            float lost = real - m; s_over++; s_lost += lost; if (dt > s_long) s_long = dt;
            if (speed_on()) {
                float now = fmaxf(fminf(real, mx), m) - m, rest = lost - now, keep = fminf(rest, fmaxf(DEBT_MAX - g_debt, 0.f));
                out = m + now; g_debt += keep; s_given += now; s_dropped += rest - keep;
            } else s_dropped += lost;
        } else if (g_debt > 0.f && speed_on()) {   // a frame within the limit pays some of it back
            float extra = fminf(g_debt, fminf(0.25f * m, mx - m));
            if (extra > 0.f) { out = m + extra; g_debt -= extra; s_caught += extra; }
        }
    }
    if (!speed_on()) g_debt = 0.f;
    if (out != m) sreg_set(r, mr, out);
    s_sum += out;
    if (s_frames >= 900) speed_report();
}

// ---- the fixed-step frame rate ----
static int g_fseen, g_fbad;
static void fixed_step(uint64_t *r, int param) {
    int sr = param & 31, nr = param >> 5 & 31, pr = param >> 10 & 31, fr = param >> 15 & 31;
    float rate = sreg(r, sr); uint64_t now = r[nr], prev = r[pr]; double f = dreg(r, fr);
    atomic_fetch_add(&g_fix_calls, 1);
    if (g_fstate < 0) return;
    if (!g_fstate) {
        if (!(rate == 60.f && f > 1e6 && f < 1e9 && now >= prev)) g_fbad++;
        if (g_fseen++ == 0) LOG("[speed] fixed-step gate reached: s%d = %.3f, ticks x%d - x%d = %llu, frequency d%d = %.0f", sr, rate, nr, pr, (unsigned long long)(now - prev), fr, f);
        if (g_fseen < 30) return;
        g_fstate = g_fbad > 3 ? -1 : 1;
        LOG("[speed] fixed-step frame rate: %d of 30 frames as expected: %s", 30 - g_fbad, g_fstate > 0 ? "slow frames take their measured rate" : "not that code, the gate does nothing");
        if (g_fstate < 0) return;
    }
    if (now <= prev || !(f > 0)) return;
    double meas = f / (double)(now - prev), lo = 60.0 / speed_max();
    if (meas >= 1.0 / (1.0 / 60.0 + 0.0015152)) return;   // 60 fps and faster: the game's own step
    atomic_fetch_add(&g_fix_slow, 1);
    if (speed_on()) sreg_set(r, sr, (float)(meas < lo ? lo : meas));
}

__attribute__((constructor)) static void speed_attach(void) { gate_obs_set(OBS_FRAME_MULT, frame_mult); gate_obs_set(OBS_FIXED_STEP, fixed_step); }
