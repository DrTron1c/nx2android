# The Switch 2 engine draws nx2android's themed gamepad in its present (bananative/dm/gfx/vk_android/vka_present.c pad_draw), each sprite placed by vko_rect
# (vka_ovl.h) into a swapchain image turned by its preTransform. Checked here: a point of the display lands on the sprite's texel under every turn (the turns are
# rot_copy's, which vko_place already follows: the two agree on whole-pixel blocks), and the view's gamepad atlas is baked as nx2android's engine showed it (the sRGB curve).
import shutil, subprocess, tempfile, unittest, re
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
VK = REPO / 'bananative/dm/gfx/vk_android'

HARNESS = r'''
#include <math.h>
#include <stdio.h>
#include "vka_ovl.h"
static int fails;
#define CHECK(c, ...) do { if (!(c)) { fails++; printf("FAIL line %d: ", __LINE__); printf(__VA_ARGS__); printf("\n"); } } while (0)
// the display point (dx, dy) in pixels → the swapchain image's pixel, as rot_copy turns the picture (vka_ovl.h: ROTATE_90 puts the display's (u, v) at the image's (1 - v, u))
static void to_image(int pre, int W, int H, int ew, int eh, float dx, float dy, float *ix, float *iy) {
    float u = dx / ew, v = dy / eh;
    switch (pre) {
    case VKO_ROT90:  *ix = (1 - v) * W; *iy = u * H; break;
    case VKO_ROT270: *ix = v * W; *iy = (1 - u) * H; break;
    case VKO_ROT180: *ix = (1 - u) * W; *iy = (1 - v) * H; break;
    default:         *ix = u * W; *iy = v * H; break;
    }
}
int main(void) {
    const int pres[4] = {1, VKO_ROT90, VKO_ROT180, VKO_ROT270};
    const int ew = 3048, eh = 2032;   // the Xiaomi Pad 6S Pro in landscape, as the user sees it
    for (int p = 0; p < 4; p++) {
        int pre = pres[p], q = pre == VKO_ROT90 || pre == VKO_ROT270, W = q ? eh : ew, H = q ? ew : eh;
        // a sprite at fractional pixels showing part of a 2048 x 4050 atlas
        float x = 211.37f, y = 1503.6f, w = 260.25f, h = 180.5f, u0 = 0.1f, v0 = 0.3f, u1 = 0.35f, v1 = 0.33f;
        vko_fdraw d = {0};
        CHECK(vko_rect(pre, W, H, ew, eh, x, y, w, h, u0, v0, u1, v1, &d), "pre %d: drawn", pre);
        for (int k = 0; k < 9; k++) {
            float fx = 0.1f + 0.4f * (k % 3), fy = 0.1f + 0.4f * (k / 3), ix, iy;   // points across the sprite
            to_image(pre, W, H, ew, eh, x + fx * w, y + fy * h, &ix, &iy);
            float px = (ix - d.vx) / d.vw, py = (iy - d.vy) / d.vh;
            CHECK(px > 0 && px < 1 && py > 0 && py < 1, "pre %d: the point lies in the viewport (%f %f)", pre, px, py);
            float tu = d.pc[0] * px + d.pc[1] * py + d.pc[4], tv = d.pc[2] * px + d.pc[3] * py + d.pc[5];
            float eu = u0 + fx * (u1 - u0), evv = v0 + fy * (v1 - v0);
            CHECK(fabsf(tu - eu) < 1e-4f && fabsf(tv - evv) < 1e-4f, "pre %d point %d: texel %f %f, wanted %f %f", pre, k, tu, tv, eu, evv);
            CHECK(ix >= d.sx && ix <= d.sx + d.sw && iy >= d.sy && iy <= d.sy + d.sh, "pre %d: inside the scissor", pre);
        }
        CHECK(d.sx >= 0 && d.sy >= 0 && d.sx + d.sw <= W && d.sy + d.sh <= H, "pre %d: the scissor inside the image", pre);
        // whole-pixel blocks: the same viewport and push constants as vko_place
        for (int corner = 0; corner < 4; corner++) {
            int bw = 300, bh = 120, tw = 512, th = 256, ty = 64; vko_draw b = {0}; vko_fdraw f = {0};
            int dx = corner & 1 ? ew - bw : 0, dy = corner & 2 ? eh - bh : 0;
            CHECK(vko_place(pre, W, H, ew, eh, corner, bw, bh, tw, th, ty, &b), "place");
            CHECK(vko_rect(pre, W, H, ew, eh, dx, dy, bw, bh, 0, (float)ty / th, (float)bw / tw, (float)(ty + bh) / th, &f), "rect");
            CHECK(f.vx == b.x && f.vy == b.y && f.vw == b.w && f.vh == b.h, "pre %d corner %d: viewport %f %f %f %f against %d %d %d %d", pre, corner, f.vx, f.vy, f.vw, f.vh, b.x, b.y, b.w, b.h);
            for (int i = 0; i < 6; i++) CHECK(fabsf(f.pc[i] - b.pc[i]) < 1e-6f, "pre %d corner %d: pc[%d] %f against %f", pre, corner, i, f.pc[i], b.pc[i]);
        }
        vko_fdraw o = {0};
        CHECK(!vko_rect(pre, W, H, ew, eh, -500, 10, 100, 100, 0, 0, 1, 1, &o), "pre %d: a sprite off the display is not drawn", pre);
        CHECK(vko_rect(pre, W, H, ew, eh, -50, -20, 100, 100, 0, 0, 1, 1, &o) && o.sx >= 0 && o.sy >= 0, "pre %d: one half off is cut to the image", pre);
    }
    if (!fails) printf("pad placement: ok\n");
    return fails != 0;
}
'''


@unittest.skipUnless(shutil.which('cc'), 'a host C compiler is required')
class PadPlacement(unittest.TestCase):
    def test_every_turn(self):
        with tempfile.TemporaryDirectory(prefix='nx2a-pad-') as tmp:
            c, exe = Path(tmp) / 'pad.c', Path(tmp) / 'pad'
            c.write_text(HARNESS)
            subprocess.run(['cc', '-std=gnu11', '-O1', '-Wall', '-Werror', '-I', str(VK), str(c), '-lm', '-o', str(exe)], check=True)
            r = subprocess.run([str(exe)], capture_output=True, text=True, timeout=30)
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)


class PadColours(unittest.TestCase):
    """ThemePad.bake: the curve nx2android's engine applied by writing the UNORM sprites into Pokopia's sRGB swapchain."""
    def test_bake_is_the_srgb_curve(self):
        src = (REPO / 'bananative/android/app/src/main/java/dev/nxnative/android/ThemePad.java').read_text()
        self.assertIn('o.inPremultiplied = false', src)   # the PNG's own colours, not premultiplied ones
        self.assertRegex(src, r'enc\[i\] = Math\.round\(255 \* srgb\(i / 255f\)\)')
        self.assertRegex(src, r't\[i\] = Math\.round\(255 \* srgb\(op \* i / 255f\)\)')
        self.assertIn('1.055 * Math.pow(x, 1 / 2.4) - 0.055', src)
        old = (REPO / 'nxnative/dm/android/gfx_vk_present.c').read_text()
        self.assertIn('so it shows a little lighter there', old)   # the old engine's own note on its sRGB swapchain


class PanelRate(unittest.TestCase):
    """The window's frame rate as nx2android's engine asks it (perf_android.c dm_perf_window: doubled until at least 90), so the panel keeps one fast rate."""
    def test_doubled_to_at_least_90(self):
        src = (VK / 'vka_present.c').read_text()
        self.assertRegex(src, r'int lo = VKA_ENV\("DM_VK_FRAME_RATE_MIN", 90\); if \(lo > 0 && lo <= 240\) while \(fps < lo\) fps \*= 2;')
        self.assertIn('DM_VK_FRAME_RATE_MIN\tswitch\t', (REPO / 'bananative/docs/env_switches.tsv').read_text())
        self.assertIn('while (fps < 90) fps *= 2;', (REPO / 'nxnative/dm/android/perf_android.c').read_text())


if __name__ == '__main__':
    unittest.main()
