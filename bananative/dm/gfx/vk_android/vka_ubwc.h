// The Adreno 740's texture-unit UBWC flag hint (TPL1_DBG_ECO_CNTL1.TP_UBWC_FLAG_HINT) that Turnip must use to match the system driver (vka_driver.c ubwc_hint): one
// register for every driver on the GPU, so a mismatch garbles what the system composites on the GPU from Turnip's compressed buffers, and the game's picture flickers
// under the volume panel, the notification shade or anything drawn over it. Pure functions (tests/test_bananative_ubwc.py)
#pragma once
#include <ctype.h>
#include <stddef.h>
#include <stdlib.h>

// The system driver's version in libGLESv2_adreno.so's bytes: "V@0676.76" (512.676.76) → *ver 676, *patch 76 (0 when it has none). The index of the match, -1 for none
// (a match needs its whole text in b: one at the end of a block is found in the next, with the block's last bytes carried over)
static inline long vka_ubwc_scan(const char *b, size_t len, int *ver, int *patch) {
    for (size_t i = 0; i + 12 <= len; i++)
        if (b[i] == 'V' && b[i + 1] == '@' && isdigit((unsigned char)b[i + 2]) && isdigit((unsigned char)b[i + 3]) && isdigit((unsigned char)b[i + 4]) && isdigit((unsigned char)b[i + 5])) {
            *ver = atoi(b + i + 2);
            *patch = b[i + 6] == '.' && isdigit((unsigned char)b[i + 7]) ? atoi(b + i + 7) : 0;
            return (long)i;
        }
    return -1;
}

// The hint for that system driver. Qualcomm's v7xx drivers set it; the v6xx ones mostly leave it off, but late 676 builds set it too: 512.676.53 (AYN Thor) needs it off,
// 512.676.71 (OnePlus 12R, DroidDeck issue 1392) and 512.676.76 (the Xiaomi Pad 6S Pro's HyperOS: its picture stopped flickering with the hint on, 2026-11-14) need it on
static inline int vka_ubwc_hint_for(int ver, int patch) { return ver >= 700 || (ver == 676 && patch >= 71); }
