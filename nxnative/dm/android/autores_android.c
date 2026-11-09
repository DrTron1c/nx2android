// Android port: Auto resolution (the pause menu's Speed tab, settings.txt autores=, DM_AUTORES; live). A game with dynamic resolution keeps level lists of render
// sizes, which the profile's DM_RES_LEVELS row names and levels_apply (dm/host/loader.c) caps at load. While this is on, the lists are rewritten as the game runs to
// start at the highest level the GPU can draw in the frame's time: the GPU time of the last frames (vk_gpu_ms_recent), smoothed, against the frame rate presentation
// aims at (the FPS lock, else the adaptive step). Over 90% of the frame's time for 3 frames: a level down (3 levels when over 130%); under 70% for 60 frames: a level
// up, never above the Resolution setting's cap (the lists levels_apply left), never below the game's smallest size. After a change it waits 10 frames, the time the
// GPU time takes to show it. The game's own stepping down the list stays. A frame held back by the CPU (the replay, memory) leaves the GPU time low, so the
// resolution stays up: lowering it would not help there. Off: the lists as levels_apply left them
#include <stdatomic.h>
#include <stdint.h>
#include <stdlib.h>
#include "dm.h"
#include "overlay_layout.h"

int dm_res_levels(int *top, int *n, const uint32_t **w, const uint32_t **h);   // dm/host/loader.c
int dm_res_levels_set(int level);
double vk_gpu_ms_recent(void);   // gfx_vk_core.c
int dm_perf_target_fps(void);    // perf_android.c

void dm_autores_tick(void) {   // the present thread, once a game frame
    static int have = -1, top, n, cur = -1, slow, fast, hold, was_on, nlog; static const uint32_t *tw, *th; static double ema, last_g;
    if (have < 0) { have = dm_res_levels(&top, &n, &tw, &th); if (!have && dm_disp(DISP_AUTORES)) LOG("[autores] this game has no level lists (DM_RES_LEVELS): Auto resolution does nothing"); }
    if (!have) return;
    int on = dm_disp(DISP_AUTORES) != 0;
    if (!on) { if (was_on) { dm_res_levels_set(-1); LOG("[autores] off: the resolution back to at most %ux%u", tw[top], th[top]); } was_on = 0; cur = -1; return; }
    if (!was_on) { was_on = 1; cur = top; ema = 0; slow = fast = hold = 0; LOG("[autores] on: between %ux%u and %ux%u, aiming at %d fps", tw[0], th[0], tw[top], th[top], dm_perf_target_fps()); }
    double g = vk_gpu_ms_recent(); if (g <= 0 || g == last_g) return;   // a new frame's GPU time
    last_g = g; ema = ema > 0 ? ema + (g - ema) * 0.15 : g;
    if (hold > 0) { hold--; return; }
    int fps = dm_perf_target_fps(); double budget = 1000.0 / (fps > 0 ? fps : 30); int want = cur;
    if (ema > budget * 0.90) { if (++slow >= 3) { want = cur - (ema > budget * 1.30 ? 3 : 1); slow = 0; } fast = 0; }
    else if (ema < budget * 0.70) { if (++fast >= 60) { want = cur + 1; fast = 0; } slow = 0; }
    else slow = fast = 0;
    if (want < 0) want = 0;
    if (want > top) want = top;
    if (want == cur) return;
    int lists = dm_res_levels_set(want);
    if (nlog++ < 60 || nlog % 100 == 0) LOG("[autores] GPU %.1f ms a frame against %.1f: level %d -> %d (%ux%u), %d level list%s rewritten", ema, budget, cur, want, tw[want], th[want], lists, lists == 1 ? "" : "s");
    cur = want; hold = 10; slow = fast = 0;
}
