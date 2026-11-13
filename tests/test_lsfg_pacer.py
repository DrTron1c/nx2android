# Frame generation's pacer (nxnative/dm/android/lsfg/lsfg_pacer.c), compiled with the steps gfx_vk_lsfg.c takes around it: a game at a 20 fps lock with a frame over
# 100 ms about once a second (Pokopia on Turnip) still gets its generated frames, a hitch costs only its own frame, and a stall (a load, the menu) starts the second of
# stabilisation again. Before, every frame over 100 ms restarted that second and the session had one generated frame in all ("20 > 20 FPS").
import shutil, subprocess, tempfile, unittest
from pathlib import Path

SRC = Path(__file__).resolve().parents[1] / 'nxnative/dm/android/lsfg'

HARNESS = r'''
#include <stdio.h>
#include <stdlib.h>
#include "lsfg.h"
static lsfg_pacer P; static unsigned long long count; static unsigned fwarm;
static size_t frame(double now) {   // gfx_vk_lsfg.c vk_lsfg_frame: 2x, the chain warm after two frames taken in
    lsfg_plan plan = lsfg_pacer_plan(&P, 3, 2, 0, now);
    int warm = plan.warm && count + 1 >= 2; count++;
    fwarm = warm ? fwarm + 1 : 0;
    return warm && fwarm >= 2 ? plan.generations : 0;
}
int main(void) {
    double t = 100.0; unsigned made = 0, frames = 0, after_hitch = 0, hitches = 0;
    for (int i = 0; i < 600; i++) {   // 30 s at 20 fps, 47-53 ms, a 110-120 ms frame every 20
        int hitch = i % 20 == 19;
        t += hitch ? 0.110 + (i % 3) * 0.005 : 0.047 + (i % 7) * 0.001;
        size_t g = frame(t);
        if (i >= 40) { frames++; made += g > 0; }
        if (hitch && g) { printf("a hitch got a generated frame\n"); return 1; }
        if (i >= 40 && (i - 1) % 20 == 19) { hitches++; after_hitch += g > 0; }
    }
    printf("generated for %u of %u frames, %u of %u frames after a hitch\n", made, frames, after_hitch, hitches);
    if (made * 100 < frames * 90 || after_hitch * 10 < hitches * 9) return 1;
    t += 2.0; if (frame(t)) { printf("a stall got a generated frame\n"); return 1; }   // a 2 s stall: a second without, then again
    unsigned early = 0, late = 0;
    for (int i = 0; i < 60; i++) { t += 0.050; size_t g = frame(t); if (i < 15) early += g > 0; else if (i >= 30) late += g > 0; }
    printf("after a stall: %u of the first 15 frames, %u of the last 30\n", early, late);
    return early || late < 30;
}
'''


class Pacer(unittest.TestCase):
    @unittest.skipUnless(shutil.which('cc'), 'a host C compiler is required')
    def test_hitches_and_stalls(self):
        with tempfile.TemporaryDirectory(prefix='nx2a-lsfg-') as tmp:
            c, exe = Path(tmp) / 'pacer.c', Path(tmp) / 'pacer'
            c.write_text(HARNESS)
            subprocess.run(['cc', '-std=gnu11', '-Wall', '-Werror', '-I', str(SRC), str(c), str(SRC / 'lsfg_pacer.c'), '-lm', '-o', str(exe)], check=True)
            r = subprocess.run([str(exe)], capture_output=True, text=True, timeout=15)
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)


if __name__ == '__main__':
    unittest.main()
