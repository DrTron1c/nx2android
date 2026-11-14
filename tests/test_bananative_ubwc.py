# The Switch 2 engine with Turnip on an Adreno 740 sets the texture unit's UBWC flag hint to the system driver's (bananative/dm/gfx/vk_android/vka_ubwc.h), or the game's
# picture flickers whenever Android composites on the GPU (the volume panel, the notification shade). The Xiaomi Pad 6S Pro's HyperOS driver, 512.676.76, needs it on: its
# flicker stopped with FD_DEV_FEATURES=enable_tp_ubwc_flag_hint=1 (2026-11-14), where the rule "v7xx on, v6xx off" gave it off.
import shutil, subprocess, tempfile, unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
VK = REPO / 'bananative/dm/gfx/vk_android'

HARNESS = r'''
#include <stdio.h>
#include <string.h>
#include "vka_ubwc.h"
int main(void) {
    int fails = 0, v, p;
    const char *lib = "....OpenGL ES Shared Library V@0676.76.1 (GIT@...) Compiler.....";
    long at = vka_ubwc_scan(lib, strlen(lib), &v, &p);
    if (at < 0 || v != 676 || p != 76) { printf("FAIL: the Pad 6S Pro's version read as %d.%d\n", v, p); fails++; }
    const char *v7 = "xxxx V@0762.10 yyyyyyyyyyyy";
    if (vka_ubwc_scan(v7, strlen(v7), &v, &p) < 0 || v != 762 || p != 10) { printf("FAIL: 762.10 read as %d.%d\n", v, p); fails++; }
    const char *cut = "xxxx V@0676.7";   /* the end of a block: found in the next one, with the bytes carried over */
    if (vka_ubwc_scan(cut, strlen(cut), &v, &p) >= 0) { printf("FAIL: a match cut at the block's end was taken\n"); fails++; }
    struct { int v, p, want; const char *who; } t[] = {
        {676, 76, 1, "Xiaomi Pad 6S Pro (HyperOS)"}, {676, 71, 1, "OnePlus 12R"}, {676, 53, 0, "AYN Thor"},
        {762, 10, 1, "a v7xx driver"}, {615, 0, 0, "an older v6xx driver"}, {800, 0, 1, "a v8xx driver"}};
    for (unsigned i = 0; i < sizeof t / sizeof *t; i++)
        if (vka_ubwc_hint_for(t[i].v, t[i].p) != t[i].want) { printf("FAIL: %s, 512.%d.%d: hint %d, wanted %d\n", t[i].who, t[i].v, t[i].p, !t[i].want, t[i].want); fails++; }
    if (!fails) printf("ubwc hint: ok\n");
    return fails != 0;
}
'''


@unittest.skipUnless(shutil.which('cc'), 'a host C compiler is required')
class UbwcHint(unittest.TestCase):
    def test_the_tablets_driver_gets_the_hint(self):
        with tempfile.TemporaryDirectory(prefix='nx2a-ubwc-') as tmp:
            c, exe = Path(tmp) / 'ubwc.c', Path(tmp) / 'ubwc'
            c.write_text(HARNESS)
            subprocess.run(['cc', '-std=gnu11', '-O1', '-Wall', '-Werror', '-I', str(VK), str(c), '-o', str(exe)], check=True)
            r = subprocess.run([str(exe)], capture_output=True, text=True, timeout=30)
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)

    def test_the_driver_loader_uses_it(self):
        src = (VK / 'vka_driver.c').read_text()
        self.assertIn('#include "vka_ubwc.h"', src)
        self.assertIn('vka_ubwc_hint_for(v, patch) ? "enable_tp_ubwc_flag_hint=1" : "enable_tp_ubwc_flag_hint=0"', src)


if __name__ == '__main__':
    unittest.main()
