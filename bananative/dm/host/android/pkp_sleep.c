// SPDX-License-Identifier: GPL-3.0-or-later
// nx2android: Pokémon Pokopia's sleep mod, the game's side (the launcher's SleepMod.java is the menu, the fade and the clock). The player gets into a bed: the launcher asks
// how long to sleep, the game saves its player file the way Save and Quit does, its clock moves on 6 to 24 hours (hle_time.c DM_TIME_SKIP: only the game's clock, never the
// phone's), and "Not now" gets the player out of bed. Exact build only: main 76a489f3bd3642fa5e18bd83d32c98ba (update v655360, Ver. 2.0.1).
//
// The game thread, once a frame: the frame step (main+0xbfc404, Recompia's kFrameStep) makes its first virtual call at +0xbfc55c, blr x9 (x0 = x19, a getter with no other
// argument, its result in x0). Observation gate OBS_PKP_FRAME on the stp just before it (+0xbfc558, patches.py) points x9 at tick_call, which runs the frame's work on the game
// thread's own stack, as an ordinary call from the game's code, and then calls the getter the game asked for. Game functions are called only from there: an observation
// callback runs on the thread's gate stack, which a nested gate (an SVC of the called function) would write over.
//
// The game's state, as Recompia (https://github.com/Dittobyte/Recompia, GPL-3.0-or-later; runtime/games/pkp/core/pkp_layout.h and ui/pkp_save_now.cpp) found it on this build:
//   the player manager [main+0x3561928] +0x30 the local player's character; its action component [character+0x18b8]; +0x984 u16 the action running now (0 none);
//   the game state [main+0x3561710]: +0x90 u8 Link Play session bits, +0x3bb5e0 u32 1..4 while the autosave writes, [+0xd0] the event bus, its +0x6ea busy byte;
//   the save manager [main+0x35619d0]: main+0x241324 (manager, request, 0, 0) queues a request (bit 0: queued), main+0x2412b0 (manager, 0) bit 0: its last request finished;
//   the request: u32 type 5, byte 5 = [save root main+0x3561930] +0xa4, u32 +8 kind 2 (the player's own file, what Save and Quit writes).
// Lying in bed is not in the action component (it reads 0 there: the actions are the player's moves, 2026-11-15), and which state the game keeps it in is not known
// from its code, so the launcher teaches the mod the bed's signs (below, "The bed, learned").
// Memory the game owns is read with process_vm_readv on this process, so a pointer that went stale reads as nothing instead of ending the game.
#include "hle.h"
#include "dm.h"
#include "dm_input.h"
#include "input_android.h"
#include <pthread.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/uio.h>
#include <time.h>
#include <unistd.h>

#define PKP_BUILD "76a489f3bd3642fa5e18bd83d32c98ba"
enum {
    M_PLAYER_MGR = 0x3561928, M_GAME_STATE = 0x3561710, M_SAVE_MGR = 0x35619d0, M_SAVE_ROOT = 0x3561930,
    F_SAVE_REQUEST = 0x241324, F_SAVE_DONE = 0x2412b0,
    PM_PLAYER = 0x30, CH_ACTION = 0x18b8, AC_ID = 0x984,
    GS_SESSION = 0x90, GS_AUTOSAVE = 0x3bb5e0, GS_EVENT = 0xd0, EV_BUSY = 0x6ea, SR_HOME = 0xa4,
    SR_PLAY_TIME = 0x4a5418,   // the play time in seconds (double; Recompia's pkp_tt_probe.cpp): it runs in a game, not on the title screen
    SR_TIME_OF_DAY = 0x13d373c, SR_YEAR = 0x13d3740, SR_MONTH = 0x13d3742, SR_DAY = 0x13d3743,   // the game's own time: seconds of the day (float), its date (Recompia's pkp_tt_probe.cpp)
};

static uint64_t g_main;   // main's base, once the build is checked (0: not this game, or not yet)
static uint64_t main_base(void) {
    if (g_main) return g_main;
    if (strcmp(dm_main_build(), PKP_BUILD)) return 0;
    for (int i = 0; i < g_nmod; i++) if (!strcmp(g_mod[i].name, "main")) return g_main = (uint64_t)(uintptr_t)g_mod[i].base;
    return 0;
}
static int rd(uint64_t a, void *out, size_t n) {
    if (!a) return 0;
    struct iovec l = {out, n}, r = {(void *)(uintptr_t)a, n};
    return process_vm_readv(getpid(), &l, 1, &r, 1, 0) == (ssize_t)n;
}
static uint64_t rq(uint64_t a) { uint64_t v = 0; return rd(a, &v, 8) ? v : 0; }

static _Atomic int g_cur = -1, g_last, g_beds_in, g_online, g_save, g_getup;
static _Atomic int64_t g_frames;
static _Atomic int g_in_game, g_quits, g_pt_reset = 1;   // a game has run since the title (its play time moved on 5 s); Save and Quits seen; the mark is taken again
static _Atomic uint64_t g_recent;   // the last four actions the player started, newest in the low 16 bits (the in-game panel shows them)
static int g_save_tries, g_save_wait, g_up_frame, g_up_step, g_changes;

// ---- The bed, learned (the in-game panel's three taps: standing, in the bed, standing again) ----
// The halfwords of the player's character (its first 0xb000 bytes) and of its action component (0x1000 bytes) that hold one value while the player stands, another
// while they lie in the bed, and the first again once they stand, each window 15 samples a third frame apart (half a second at 30 fps) with no change inside it, and
// small values on both sides (a state or a flag; positions, timers and pointers drop out): those are the bed's signs. The player is in bed while three in four of
// them hold their bed value, and out once fewer than one in four do. Kept by the launcher (pkp_bed_signs) and given back at each start.
enum { NREG = 2, WIN_SAMPLES = 15, NSIGN = 64 };
static const uint32_t REG_SIZE[NREG] = {0xb000, 0x1000};
typedef struct { uint8_t reg; uint16_t off, bed, awake; } sign_t;
static uint16_t *g_win[3][NREG], *g_tmp[NREG]; static uint8_t *g_moved[3][NREG];
static _Atomic int g_learn, g_learn_done, g_inbed, g_nsign;   // g_learn: 1..3 the window being sampled; g_learn_done: bit k when window k + 1 is sampled
static int g_learn_n, g_learn_skip;
static sign_t g_sign[NSIGN]; static uint32_t g_span[NREG][2];   // each region's lowest and highest offset a sign reads
static pthread_mutex_t g_sign_mu = PTHREAD_MUTEX_INITIALIZER;

// A region into buf; a 4 KB page that does not read is marked moved (moved != NULL) and reads 0xffff. 0 when nothing reads
static int read_region(uint64_t a, uint32_t size, uint16_t *buf, uint8_t *moved) {
    if (!a) return 0;
    if (rd(a, buf, size)) return 1;
    int any = 0;
    for (uint32_t p = 0; p < size; p += 0x1000) {
        uint32_t n = size - p < 0x1000 ? size - p : 0x1000;
        if (rd(a + p, (uint8_t *)buf + p, n)) { any = 1; continue; }
        memset((uint8_t *)buf + p, 0xff, n);
        if (moved) memset(moved + p / 2, 1, n / 2);
    }
    return any;
}

static void learn_sample(uint64_t ch, uint64_t ac) {
    int k = atomic_load(&g_learn) - 1;
    if (k < 0 || k > 2 || g_learn_skip++ % 3) return;
    const uint64_t at[NREG] = {ch, ac};
    for (int r = 0; r < NREG; r++) {
        uint32_t n = REG_SIZE[r] / 2;
        if (!g_win[k][r]) { g_win[k][r] = calloc(n, 2); g_moved[k][r] = calloc(n, 1); }
        if (!g_tmp[r]) g_tmp[r] = calloc(n, 2);
        if (!g_win[k][r] || !g_moved[k][r] || !g_tmp[r]) return;
        if (g_learn_n == 0) memset(g_moved[k][r], 0, n);
        if (!read_region(at[r], REG_SIZE[r], g_tmp[r], g_moved[k][r])) { memset(g_moved[k][r], 1, n); continue; }
        if (g_learn_n == 0) memcpy(g_win[k][r], g_tmp[r], n * 2);
        else for (uint32_t i = 0; i < n; i++) if (g_tmp[r][i] != g_win[k][r][i]) g_moved[k][r][i] = 1;
    }
    if (++g_learn_n >= WIN_SAMPLES) { atomic_fetch_or(&g_learn_done, 1 << k); atomic_store(&g_learn, 0); LOG("pkp sleep: the bed's window %d sampled", k + 1); }
}

static void signs_spans(int n) {
    for (int r = 0; r < NREG; r++) { g_span[r][0] = ~0u; g_span[r][1] = 0; }
    for (int i = 0; i < n; i++) { sign_t *g = &g_sign[i]; if (g->off < g_span[g->reg][0]) g_span[g->reg][0] = g->off; if (g->off + 2u > g_span[g->reg][1]) g_span[g->reg][1] = g->off + 2u; }
}

// The signs from the three windows: -1 when one is missing
static int learn_compute(void) {
    if ((atomic_load(&g_learn_done) & 7) != 7) return -1;
    int n = 0;
    pthread_mutex_lock(&g_sign_mu);
    for (int lim = 0x400; n == 0 && lim <= 0x10000; lim = lim == 0x400 ? 0x10000 : 0x20000)
        for (int r = 0; r < NREG && n < NSIGN; r++) {
            if (!g_win[0][r] || !g_win[1][r] || !g_win[2][r]) continue;
            for (uint32_t i = 0; i < REG_SIZE[r] / 2 && n < NSIGN; i++) {
                if (g_moved[0][r][i] || g_moved[1][r][i] || g_moved[2][r][i]) continue;
                uint16_t a = g_win[0][r][i], b = g_win[1][r][i], c = g_win[2][r][i];
                if (a != c || a == b || a >= lim || b >= lim) continue;
                g_sign[n++] = (sign_t){(uint8_t)r, (uint16_t)(i * 2), b, a};
            }
        }
    signs_spans(n);
    atomic_store(&g_nsign, n); atomic_store(&g_inbed, 0);
    pthread_mutex_unlock(&g_sign_mu);
    LOG("pkp sleep: the bed learned: %d signs%s", n, n ? "" : " (nothing held one value standing and another in the bed)");
    for (int i = 0; i < n && i < 12; i++) LOG("pkp sleep:   %s+%#x: standing %#x, in bed %#x", g_sign[i].reg ? "action component" : "character", g_sign[i].off, g_sign[i].awake, g_sign[i].bed);
    return n;
}

// Whether the player lies in the bed now, by the signs; returns 1 on getting into it. *awake: three in four of the signs at their standing value
static int bed_step(uint64_t ch, uint64_t ac, int *awake) {
    int n = atomic_load(&g_nsign);
    *awake = 0;
    if (!n || pthread_mutex_trylock(&g_sign_mu)) return 0;
    const uint64_t at[NREG] = {ch, ac};
    static uint16_t span[NREG][0xb000 / 2];   // the game thread only
    int ok[NREG] = {0}, hit = 0, up = 0, seen = 0, in = atomic_load(&g_inbed), got = 0;
    for (int r = 0; r < NREG; r++)
        if (at[r] && g_span[r][1] > g_span[r][0] && g_span[r][1] - g_span[r][0] <= REG_SIZE[r])
            ok[r] = g_span[r][1] - g_span[r][0] <= sizeof span[r] && rd(at[r] + g_span[r][0], span[r], g_span[r][1] - g_span[r][0]);
    for (int i = 0; i < n; i++) {
        sign_t *g = &g_sign[i]; uint16_t v;
        if (ok[g->reg]) v = span[g->reg][(g->off - g_span[g->reg][0]) / 2];
        else if (!at[g->reg] || !rd(at[g->reg] + g->off, &v, 2)) continue;
        seen++; hit += v == g->bed; up += v == g->awake;
    }
    pthread_mutex_unlock(&g_sign_mu);
    if (!seen) return 0;
    *awake = up * 4 >= seen * 3;
    if (!in && hit * 4 >= seen * 3) { atomic_store(&g_inbed, 1); got = 1; }
    else if (in && hit * 4 < seen) atomic_store(&g_inbed, 0);
    return got;
}

// The local player's character and its action component (0: none yet)
static void player(uint64_t base, uint64_t *ch, uint64_t *ac) {
    uint64_t pm = rq(base + M_PLAYER_MGR); *ch = pm ? rq(pm + PM_PLAYER) : 0; *ac = *ch ? rq(*ch + CH_ACTION) : 0;
}

// The game's own save request, as its Save and Quit dialog makes it; waits while the autosave writes or an event holds the game (at most 10 s, then FAILED)
static void save_step(uint64_t base) {
    int s = atomic_load(&g_save);
    if (s == PKP_SAVE_ASKED) {
        if (++g_save_tries > 600) { atomic_store(&g_save, PKP_SAVE_FAILED); LOG("[RED] pkp sleep: the game stayed busy for 10 s, the save was not asked"); return; }
        uint64_t game = rq(base + M_GAME_STATE), mgr = rq(base + M_SAVE_MGR), root = rq(base + M_SAVE_ROOT);
        uint32_t autosave = 0; uint8_t home = 0, busy = 0;
        if (!game || !mgr || !root || !rd(game + GS_AUTOSAVE, &autosave, 4) || !rd(root + SR_HOME, &home, 1)) return;
        if (autosave - 1 < 4) return;   // the autosave is writing
        uint64_t ev = rq(game + GS_EVENT);
        if (ev && rd(ev + EV_BUSY, &busy, 1) && busy) { if (g_save_tries == 1) LOG("pkp sleep: an event holds the game, the save waits"); return; }
        uint8_t req[12] = {0}; uint32_t type = 5, kind = 2;
        memcpy(req, &type, 4); req[5] = home; memcpy(req + 8, &kind, 4);
        uint64_t (*ask)(uint64_t, uint64_t, uint64_t, uint64_t) = (void *)(uintptr_t)(base + F_SAVE_REQUEST);
        if (!(ask(mgr, (uint64_t)(uintptr_t)req, 0, 0) & 1)) return;
        LOG("pkp sleep: the game's own save of the player's file is queued (after %d frames)", g_save_tries);
        atomic_store(&g_save, PKP_SAVE_WRITING); g_save_wait = 0;
    } else if (s == PKP_SAVE_WRITING) {
        if (++g_save_wait % 15) return;   // the save manager is asked every quarter second
        uint64_t mgr = rq(base + M_SAVE_MGR);
        uint64_t (*done)(uint64_t, uint64_t) = (void *)(uintptr_t)(base + F_SAVE_DONE);
        if (mgr && (done(mgr, 0) & 1)) { atomic_store(&g_save, PKP_SAVE_DONE); LOG("pkp sleep: the save is written (%.1f s)", g_save_wait / 60.0); }
        else if (g_save_wait > 1800) { atomic_store(&g_save, PKP_SAVE_FAILED); LOG("[RED] pkp sleep: the save manager has not finished after 30 s"); }
    }
}

// Out of bed ("Not now", and after a sleep): B, then while the player still lies in the bed (its signs), the left stick toward the camera for a moment, then the
// player's input back. A player the signs already see out of bed is left alone (B could do something else there)
static void getup_step(int cur) {
    if (!atomic_load(&g_getup)) return;
    if (g_up_step == 0 && g_up_frame == 0 && atomic_load(&g_nsign) && !atomic_load(&g_inbed)) g_up_step = 4;
    int f = g_up_frame++;
    if (g_up_step == 0) { dm_input_hold(1, DM_BTN_B, 0, 0, 0, 0); if (f >= 8) { g_up_step = 1; g_up_frame = 0; } return; }
    if (g_up_step == 1) { dm_input_hold(1, 0, 0, 0, 0, 0); if (f >= 24) { g_up_step = atomic_load(&g_inbed) || !atomic_load(&g_nsign) ? 2 : 4; g_up_frame = 0; } return; }
    if (g_up_step == 2) { dm_input_hold(1, 0, 0, -DM_STICK_MAX, 0, 0); if (f >= 8) { g_up_step = 3; g_up_frame = 0; } return; }
    if (g_up_step == 3) { dm_input_hold(1, 0, 0, 0, 0, 0); if (f >= 20) { g_up_step = 4; g_up_frame = 0; } return; }
    LOG("pkp sleep: out of bed%s (action %d)", atomic_load(&g_inbed) ? " not confirmed: the signs still say in bed" : "", cur);
    dm_input_hold(0, 0, 0, 0, 0, 0); atomic_store(&g_getup, 0);
}

static void frame_work(void) {
    uint64_t base = main_base();
    if (!base) return;
    atomic_fetch_add(&g_frames, 1);
    uint64_t game = rq(base + M_GAME_STATE); uint8_t sess = 0;
    atomic_store(&g_online, game && rd(game + GS_SESSION, &sess, 1) && sess);
    uint64_t ch, ac; player(base, &ch, &ac);
    {   // a game runs: its play time moved on 5 s since the title (a title scene with a player in it is no game; without a play time to read, no Save and Quit counts)
        static double mark; double pt; uint64_t root = rq(base + M_SAVE_ROOT);
        if (root && rd(root + SR_PLAY_TIME, &pt, 8) && pt >= 0 && pt < 1e9) {
            if (atomic_exchange(&g_pt_reset, 0)) mark = pt;
            if (pt - mark >= 5.0) atomic_store(&g_in_game, 1);
        }
    }
    uint16_t av; int cur = ac && rd(ac + AC_ID, &av, 2) ? av : -1, was = atomic_exchange(&g_cur, cur);
    if (cur != was) {
        if (cur > 0) { atomic_store(&g_last, cur); atomic_store(&g_recent, atomic_load(&g_recent) << 16 | (uint16_t)cur); }
        if (g_changes++ < 200 && (cur > 0 || was > 0)) LOG("pkp sleep: the player's action %d -> %d", was, cur);
    }
    learn_sample(ch, ac);
    // A game loaded with the player in bed (Save and Quit there, then back in) is no getting into bed: the menu waits until the player of this load has been seen
    // standing (the bed's signs at their standing values) for 30 frames in a row, at least 8 s after the player appeared (a new player manager, character or action
    // component, or one again after none)
    static uint64_t ch_seen; static int64_t ch_since; static int up_frames, armed;
    uint64_t who = rq(base + M_PLAYER_MGR) * 31 + ch * 7 + ac;
    struct timespec ts; clock_gettime(CLOCK_MONOTONIC, &ts);
    int64_t now = ts.tv_sec * 1000000000ll + ts.tv_nsec;
    if (who != ch_seen || !ch) { if (who != ch_seen) ch_since = now; ch_seen = who; up_frames = 0; armed = 0; if (ch && atomic_load(&g_nsign)) LOG("pkp sleep: a player appeared (a load): no sleep menu until they are seen standing"); }
    int awake = 0, into = bed_step(ch, ac, &awake);
    up_frames = awake && !atomic_load(&g_inbed) ? up_frames + 1 : 0;
    if (!armed && ch && up_frames >= 30 && now - ch_since >= 8000000000ll) armed = 1;
    // and only after the player interacted with the bed: A went down in the 6 s before (a load, or anything else that puts the player in bed, asks nothing)
    uint64_t a_ns = dm_input_a_ns(), host_ns = now_ns();
    int pressed = a_ns && host_ns - a_ns <= 6000000000ull;
    if (into) {
        if (!armed) LOG("pkp sleep: in bed since the game loaded: no sleep menu");
        else if (!pressed) LOG("pkp sleep: in bed without pressing A just before (a load or a scene): no sleep menu");
        else if (!atomic_load(&g_online) && !atomic_load(&g_getup) && !atomic_load(&g_learn)) { atomic_fetch_add(&g_beds_in, 1); LOG("pkp sleep: the player got into a bed"); }
    }
    save_step(base);
    getup_step(cur);
}

// The key guide widget takes a set (Recompia's pkp_title.cpp): 152 is the title's ((-) Settings), 183 the title with Credits. After a game, that is Save and Quit
static void guide_gate(uint64_t *r, int param) {
    (void)param;
    unsigned set = (unsigned)(r[1] & 0xff);
    if (!main_base() || (set != 152 && set != 183)) return;
    atomic_store(&g_pt_reset, 1);
    if (atomic_exchange(&g_in_game, 0)) { atomic_fetch_add(&g_quits, 1); LOG("pkp sleep: back at the title after a game (Save and Quit)"); }
}

// The gate's call: the frame's work, then the getter the frame step asked for (x0 its object). One game thread runs the frame step, so one target is kept
static _Atomic uint64_t g_getter;
static uint64_t tick_call(uint64_t self) {
    uint64_t (*getter)(uint64_t) = (void *)(uintptr_t)atomic_load_explicit(&g_getter, memory_order_relaxed);
    frame_work();
    return getter(self);
}
static void frame_gate(uint64_t *r, int param) {
    (void)param;
    if (!main_base() || !r[9]) return;
    atomic_store_explicit(&g_getter, r[9], memory_order_relaxed);
    r[9] = (uint64_t)(uintptr_t)tick_call;
}

// ---- The launcher's interface (input_android.h) ----
int64_t nxhost_clock_now(void) { return dm_time_user_now(); }
void nxhost_clock_skip(int64_t seconds) { dm_time_skip_set(seconds); }
int nxhost_pkp_state(int64_t out[10]) {
    out[0] = atomic_load(&g_cur); out[1] = atomic_load(&g_last); out[2] = atomic_load(&g_beds_in);
    out[3] = atomic_load(&g_save); out[4] = atomic_load(&g_online); out[5] = atomic_load(&g_frames); out[6] = (int64_t)atomic_load(&g_recent);
    out[7] = atomic_load(&g_inbed) | atomic_load(&g_nsign) << 8 | atomic_load(&g_learn_done) << 16 | atomic_load(&g_learn) << 20 | atomic_load(&g_getup) << 24;
    out[8] = atomic_load(&g_quits); out[9] = 0;
    return main_base() && gate_obs_present(OBS_PKP_FRAME);
}
int nxhost_pkp_learn(int step) {
    if (step >= 1 && step <= 3) { atomic_fetch_and(&g_learn_done, ~(1 << (step - 1))); g_learn_n = 0; g_learn_skip = 0; atomic_store(&g_learn, step); return 0; }
    if (step == 4) return learn_compute();
    return atomic_load(&g_learn_done) | (atomic_load(&g_learn) ? 0x100 : 0);
}
int nxhost_pkp_signs(char *buf, int cap) {
    int n = 0, w = 0;
    if (cap > 0) buf[0] = 0;
    pthread_mutex_lock(&g_sign_mu);
    for (int i = 0; i < atomic_load(&g_nsign); i++) {
        int k = snprintf(buf + w, cap > w ? (size_t)(cap - w) : 0, "%s%x:%x:%x:%x", i ? ";" : "", g_sign[i].reg, g_sign[i].off, g_sign[i].bed, g_sign[i].awake);
        if (k < 0 || w + k >= cap) break;
        w += k; n++;
    }
    pthread_mutex_unlock(&g_sign_mu);
    return n;
}
void nxhost_pkp_signs_set(const char *s) {
    int n = 0;
    pthread_mutex_lock(&g_sign_mu);
    while (s && *s && n < NSIGN) {
        unsigned r, off, bed, awake;
        if (sscanf(s, "%x:%x:%x:%x", &r, &off, &bed, &awake) == 4 && r < NREG && off + 2 <= REG_SIZE[r] && !(off & 1))
            g_sign[n++] = (sign_t){(uint8_t)r, (uint16_t)off, (uint16_t)bed, (uint16_t)awake};
        s = strchr(s, ';'); if (s) s++;
    }
    signs_spans(n);
    atomic_store(&g_nsign, n); atomic_store(&g_inbed, 0);
    pthread_mutex_unlock(&g_sign_mu);
    LOG("pkp sleep: %d bed signs", n);
}
void nxhost_pkp_hold(int mode) {
    if (mode == 2) { g_up_frame = 0; g_up_step = 0; dm_input_hold(1, 0, 0, 0, 0, 0); atomic_store(&g_getup, 1); return; }
    atomic_store(&g_getup, 0);
    dm_input_hold(mode == 1, 0, 0, 0, 0, 0);
}
uint64_t nxhost_pkp_seen(void) { return dm_input_held_seen(); }
int nxhost_pkp_tod(int64_t out[2]) {
    uint64_t base = main_base(), root = base ? rq(base + M_SAVE_ROOT) : 0;
    float tod; uint16_t y; uint8_t m, d;
    out[0] = -1; out[1] = 0;
    if (!root || !rd(root + SR_TIME_OF_DAY, &tod, 4) || !(tod >= 0 && tod < 2 * 86400.0f)) return 0;
    out[0] = (int64_t)tod;
    if (rd(root + SR_YEAR, &y, 2) && rd(root + SR_MONTH, &m, 1) && rd(root + SR_DAY, &d, 1)) out[1] = y * 10000 + m * 100 + d;
    return 1;
}
void nxhost_pkp_focus(int st) { if (st >= 1 && st <= 2) dm_focus_set(st); }
void nxhost_pkp_save(void) { g_save_tries = 0; atomic_store(&g_save, PKP_SAVE_ASKED); }

__attribute__((constructor)) static void pkp_sleep_attach(void) { gate_obs_set(OBS_PKP_FRAME, frame_gate); gate_obs_set(OBS_PKP_GUIDE, guide_gate); }
