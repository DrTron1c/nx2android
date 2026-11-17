// SPDX-License-Identifier: GPL-3.0-or-later
// nx2android: Pokémon Pokopia in 5.1 surround (DM_AUDIO_SURROUND=1, the in-game panel's Surround sound). Exact build only: main 76a489f3bd3642fa5e18bd83d32c98ba (Ver. 2.0.1).
//
// Pokopia's sound engine takes its output's channel count from the game's own sound settings file, not from the system: main+0x115cbf0 has main+0x124ccc0 parse the
// file into the engine's parameters on its stack (x29 - 0x80), and parameter +0x20 is the channel count. The engine sizes its memory from it (main+0x11cf320), builds
// its buses with it (main+0x11d2f60 passes it on at +0x11d33a4 and seven other places), and its AudioOut output asks for 6 channels when it is over 2, else 2
// (main+0x11d8d7c: at most 8, 1 → 2; main+0x11dc254), sizing its buffers by the same rule; its fill callback (main+0x11d9224) is given the count the output opened with.
// The game itself imports no audio device query (no GetActiveChannelCount), so nothing the system answers changes it. Observation gate OBS_PKP_SOUND on the first
// instruction after a parse that succeeded (main+0x115ccdc, patches.py) sets the count to 6 when the host plays 5.1 (hle_audio.c dm_audio_surround), before the engine
// reads it; the host then sends the six channels to Android as a 5.1 stream. Found by disassembly on 2026-11-17; whether the engine pans to the back channels as on
// its other platforms is heard on the device.
#include "hle.h"
#include "dm.h"
#include "input_android.h"
#include <stdatomic.h>
#include <stdint.h>
#include <string.h>

#define PKP_BUILD "76a489f3bd3642fa5e18bd83d32c98ba"
enum { PARAMS = 0x80, P_CHANNELS = 0x20, SURROUND = 6 };
static _Atomic int g_game_was = -1, g_game_now = -1;   // the channel count in the game's file, and what the engine got (nxhost_audio_info)

static void sound_gate(uint64_t *r, int param) {
    (void)param;
    if (strcmp(dm_main_build(), PKP_BUILD) || !r[29]) return;
    uint32_t *ch = (uint32_t *)(uintptr_t)(r[29] - PARAMS + P_CHANNELS);
    uint32_t was = *ch;
    if (dm_audio_surround() && was <= 2) *ch = SURROUND;
    atomic_store(&g_game_was, (int)was); atomic_store(&g_game_now, (int)*ch);
    LOG("pkp sound: the game's sound settings ask for %u channels%s", was, *ch != was ? ": 6 (5.1 surround, DM_AUDIO_SURROUND)" : "");
}

// The launcher's status line: {surround asked, the game's file's channels, the engine's, the last output's channels from the game, the stream's, its mask, low latency}
int nxhost_audio_info(int out[7]) {
    int s[4] = {0, 0, 0, 0};
    dm_audio_info(s);
    out[0] = dm_audio_surround(); out[1] = atomic_load(&g_game_was); out[2] = atomic_load(&g_game_now);
    out[3] = s[0]; out[4] = s[1]; out[5] = s[2]; out[6] = s[3];
    return 1;
}

__attribute__((constructor)) static void pkp_sound_attach(void) { gate_obs_set(OBS_PKP_SOUND, sound_gate); }
