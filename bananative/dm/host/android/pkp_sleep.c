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
// Which actions are a bed is not in the code (the game's data tables hold the actions): the launcher learns it from the action running when the player says "this is the bed".
// Memory the game owns is read with process_vm_readv on this process, so a pointer that went stale reads as nothing instead of ending the game.
#include "hle.h"
#include "dm.h"
#include "dm_input.h"
#include "input_android.h"
#include <stdatomic.h>
#include <string.h>
#include <sys/uio.h>
#include <unistd.h>

#define PKP_BUILD "76a489f3bd3642fa5e18bd83d32c98ba"
enum {
    M_PLAYER_MGR = 0x3561928, M_GAME_STATE = 0x3561710, M_SAVE_MGR = 0x35619d0, M_SAVE_ROOT = 0x3561930,
    F_SAVE_REQUEST = 0x241324, F_SAVE_DONE = 0x2412b0,
    PM_PLAYER = 0x30, CH_ACTION = 0x18b8, AC_ID = 0x984,
    GS_SESSION = 0x90, GS_AUTOSAVE = 0x3bb5e0, GS_EVENT = 0xd0, EV_BUSY = 0x6ea, SR_HOME = 0xa4,
    NBED = 8,
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

static _Atomic int g_cur = -1, g_last, g_beds_in, g_online, g_save, g_bed[NBED], g_nbed, g_getup;
static _Atomic int64_t g_frames;
static int g_save_tries, g_save_wait, g_up_frame, g_up_step, g_changes;

static int is_bed(int id) {
    int n = atomic_load(&g_nbed);
    for (int i = 0; i < n && i < NBED; i++) if (atomic_load(&g_bed[i]) == id) return 1;
    return 0;
}

// The local player's action now (-1: no player yet, or a pointer that does not read)
static int action_now(uint64_t base) {
    uint64_t pm = rq(base + M_PLAYER_MGR), ch = pm ? rq(pm + PM_PLAYER) : 0, ac = ch ? rq(ch + CH_ACTION) : 0;
    uint16_t v; return ac && rd(ac + AC_ID, &v, 2) ? v : -1;
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

// "Not now": B, and while the bed action lasts the left stick toward the camera, then the player's input back
static void getup_step(int cur) {
    if (!atomic_load(&g_getup)) return;
    int f = g_up_frame++;
    if (g_up_step == 0) { dm_input_hold(1, DM_BTN_B, 0, 0, 0, 0); if (f >= 8) { g_up_step = 1; g_up_frame = 0; } return; }
    if (g_up_step == 1) { dm_input_hold(1, 0, 0, 0, 0, 0); if (f >= 24) { g_up_step = is_bed(cur) ? 2 : 4; g_up_frame = 0; } return; }
    if (g_up_step == 2) { dm_input_hold(1, 0, 0, -DM_STICK_MAX, 0, 0); if (f >= 14) { g_up_step = 3; g_up_frame = 0; } return; }
    if (g_up_step == 3) { dm_input_hold(1, 0, 0, 0, 0, 0); if (f >= 20) { g_up_step = 4; g_up_frame = 0; } return; }
    LOG("pkp sleep: out of bed%s (action %d)", is_bed(cur) ? " not confirmed" : "", cur);
    dm_input_hold(0, 0, 0, 0, 0, 0); atomic_store(&g_getup, 0);
}

static void frame_work(void) {
    uint64_t base = main_base();
    if (!base) return;
    atomic_fetch_add(&g_frames, 1);
    uint64_t game = rq(base + M_GAME_STATE); uint8_t sess = 0;
    atomic_store(&g_online, game && rd(game + GS_SESSION, &sess, 1) && sess);
    int cur = action_now(base), was = atomic_exchange(&g_cur, cur);
    if (cur != was) {
        if (cur > 0) atomic_store(&g_last, cur);
        if (cur > 0 && is_bed(cur) && !atomic_load(&g_online) && !atomic_load(&g_getup)) { atomic_fetch_add(&g_beds_in, 1); LOG("pkp sleep: the player got into a bed (action %d)", cur); }
        else if (g_changes < 400 && (cur > 0 || was > 0)) LOG("pkp sleep: the player's action %d -> %d", was, cur);
        g_changes++;
    }
    save_step(base);
    getup_step(cur);
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
int nxhost_pkp_state(int64_t out[6]) {
    out[0] = atomic_load(&g_cur); out[1] = atomic_load(&g_last); out[2] = atomic_load(&g_beds_in);
    out[3] = atomic_load(&g_save); out[4] = atomic_load(&g_online); out[5] = atomic_load(&g_frames);
    return main_base() && gate_obs_present(OBS_PKP_FRAME);
}
void nxhost_pkp_beds(const int *ids, int n) {
    if (n > NBED) n = NBED;
    atomic_store(&g_nbed, 0);
    for (int i = 0; i < n; i++) atomic_store(&g_bed[i], ids[i]);
    atomic_store(&g_nbed, n < 0 ? 0 : n);
    LOG("pkp sleep: %d bed action(s)%s%d", n, n > 0 ? ", the first " : "", n > 0 ? ids[0] : 0);
}
void nxhost_pkp_hold(int mode) {
    if (mode == 2) { g_up_frame = 0; g_up_step = 0; dm_input_hold(1, 0, 0, 0, 0, 0); atomic_store(&g_getup, 1); return; }
    atomic_store(&g_getup, 0);
    dm_input_hold(mode == 1, 0, 0, 0, 0, 0);
}
uint64_t nxhost_pkp_seen(void) { return dm_input_held_seen(); }
void nxhost_pkp_save(void) { g_save_tries = 0; atomic_store(&g_save, PKP_SAVE_ASKED); }

__attribute__((constructor)) static void pkp_sleep_attach(void) { gate_obs_set(OBS_PKP_FRAME, frame_gate); }
