# Frame generation in the Switch 2 engine keeps the game's HUD (bananative/dm/gfx/vk_android/lsfg/lsfg_chain.c hud_new, lsfg_hud.comp): after LSFG generates a frame,
# a pixel the game left unchanged between the two real frames (a still HUD) is the newer real frame's, so text and icons are never warped. The shader is compiled by the
# NDK's glslc in the build (android/host/CMakeLists.txt); here its rule is checked against a reference, and the wiring in the chain and the build.
import re, unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
LSFG = REPO / 'bananative/dm/gfx/vk_android/lsfg'


def keep(prev, cur, gen, eps=2):
    """The shader's rule on one pixel (8-bit RGBA): the newer real frame's where the game left it unchanged (within eps on every colour channel), else LSFG's."""
    return cur if max(abs(a - b) for a, b in zip(prev[:3], cur[:3])) <= eps else gen


class HudKeeper(unittest.TestCase):
    def test_rule(self):
        hud = (250, 240, 200, 255)
        self.assertEqual(keep(hud, hud, (90, 80, 70, 255)), hud)                       # a still HUD pixel: the real one, not LSFG's smear
        self.assertEqual(keep((10, 10, 10, 255), (12, 11, 10, 255), (0, 0, 0, 255)), (12, 11, 10, 255))   # under grain: still unchanged
        self.assertEqual(keep((10, 10, 10, 255), (60, 10, 10, 255), (35, 10, 10, 255)), (35, 10, 10, 255))   # moving: LSFG's

    def test_shader_is_that_rule(self):
        src = (LSFG / 'lsfg_hud.comp').read_text()
        self.assertIn('#version 450', src)
        self.assertRegex(src, r'vec3 d = abs\(a\.rgb - b\.rgb\);\s*if \(max\(d\.r, max\(d\.g, d\.b\)\) <= p\.eps\) imageStore\(outp, xy, b\);')
        for b, name in ((0, 'prev'), (1, 'cur'), (2, 'outp')):
            self.assertRegex(src, rf'layout\(binding = {b}, rgba8\) uniform (readonly )?image2D {name};')

    def test_the_chain_runs_it_after_generate(self):
        c = (LSFG / 'lsfg_chain.c').read_text()
        self.assertIn('#include "lsfg_hud.comp.inc"', c)
        gen = c[c.index('void lsfg_chain_generate('):]
        self.assertLess(gen.index('run(cb, &c->gen_pass'), gen.index('c->hud_pass.pipe'))
        self.assertIn('w_store(&w, c->frames[1 - k]); w_store(&w, c->frames[k]); w_view(&w, out_views[t]);', c)   # as the generate pass's sets: k the newer frame
        self.assertIn('c->hud_sets[gen][count % 2]', gen)
        self.assertIn('hud_new(c, out_views);', c)

    def test_the_build_compiles_the_shader(self):
        cm = (REPO / 'bananative/android/host/CMakeLists.txt').read_text()
        self.assertIn('-mfmt=num -o ${LSFG_HUD_INC} ${GFX}/vk_android/lsfg/lsfg_hud.comp', cm)
        self.assertIn('add_dependencies(${tgt} lsfg_hud_spv)', cm)
        self.assertIn('${GEN}/lsfg ${GEN}/${game}', cm)


if __name__ == '__main__':
    unittest.main()
