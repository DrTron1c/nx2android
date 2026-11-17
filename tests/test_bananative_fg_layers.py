# Frame generation in layers (nx2android; bananative/dm/gfx/vk_android/vka_framegen.c "Layers", vka_draw.c layer_scene, lsfg/lsfg_chain.c layer_new, lsfg_layer.comp):
# the replay catches the game's 3D picture in the window texture before the HUD is drawn over it (after a full-screen draw with no blending), the chain generates from
# those pictures, and the newer real frame's HUD (where the frame as shown differs from its picture) is laid back over each generated frame. Lower latency holds each
# generated frame one refresh. The shaders are compiled by the NDK's glslc in the build; here the rule is checked against a reference, and the wiring.
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
VK = REPO / 'bananative/dm/gfx/vk_android'
LSFG = VK / 'lsfg'


def lay(scene, full, gen, eps=2):
    """The layer shader's rule on one pixel (8-bit RGB): no HUD where the frame as shown is its 3D picture; the HUD's own colour where it differs by 4 eps or more; between, blended."""
    m = max(abs(a - b) for a, b in zip(full, scene))
    if m <= eps:
        return gen
    a = min(1.0, (m - eps) / (3 * eps))
    return tuple(round(g + (f - g) * a) for g, f in zip(gen, full))


class Rule(unittest.TestCase):
    def test_rule(self):
        self.assertEqual(lay((40, 90, 30), (40, 90, 30), (44, 95, 31)), (44, 95, 31))       # the 3D picture: the generated pixel (the world moves smoothly)
        self.assertEqual(lay((40, 90, 30), (250, 250, 250), (44, 95, 31)), (250, 250, 250))  # under the HUD: the real HUD, never warped
        mid = lay((40, 90, 30), (45, 90, 30), (10, 10, 10))   # a faint edge of the HUD: half way
        self.assertTrue(10 < mid[0] < 45)

    def test_shader_is_that_rule(self):
        s = (LSFG / 'lsfg_layer.comp').read_text()
        self.assertIn('if (m <= p.eps) return;', s)
        self.assertIn('float a = clamp((m - p.eps) / (3.0 * p.eps), 0.0, 1.0);', s)
        self.assertIn('imageStore(outp, xy, mix(imageLoad(outp, xy), f, a));', s)
        for b, name in ((0, 'scene'), (1, 'full'), (2, 'outp')):
            self.assertIn(f'layout(binding = {b}, rgba8) uniform', s)
            self.assertIn(f'image2D {name};', s)


class Wiring(unittest.TestCase):
    def test_chain(self):
        c = (LSFG / 'lsfg_chain.c').read_text()
        self.assertIn('#include "lsfg_layer.comp.inc"', c)
        self.assertIn('w_store(&w, c->frames[k]); w_store(&w, c->full[k]); w_view(&w, out_views[t]);', c)   # k: the newer frame
        gen = c[c.index('void lsfg_chain_generate('):]
        self.assertLess(gen.index('run(cb, &c->gen_pass'), gen.index('c->layer_pass.pipe'))
        self.assertIn('if (layered && c->layer_on) {', gen)
        self.assertIn('} else if (c->hud_on && VKA_ENV("DM_LSFG_HUD", 1)) {', gen)   # whole frames keep the still-pixel keeper
        self.assertLess(c.index('layer_new(c, ext, out_views);'), c.index('// Every image in GENERAL layout, once'))
        cm = (REPO / 'bananative/android/host/CMakeLists.txt').read_text()
        self.assertIn('${GFX}/vk_android/lsfg/lsfg_layer.comp', cm)
        self.assertIn('add_custom_target(lsfg_hud_spv DEPENDS ${LSFG_HUD_INC} ${LSFG_LAYER_INC})', cm)

    def test_replay_catches_the_picture(self):
        d = (VK / 'vka_draw.c').read_text()
        body = d[d.index('static void layer_scene('):d.index('static void draw_body(vka_dstate *d, const p_set_rt *rt, int has_rt, const p_scissor *sc, int has_sc, const p_draw *dw, const p_draw_idx *di) {')]
        self.assertIn('(d->color.blend_en & 1)', body)    # no blending: the picture, not the HUD over it
        self.assertIn('if (n > 6 || inst > 1) return;', body)   # a full-screen triangle or quad
        self.assertIn('vka_draw_run_break();\n    vka_rp_end();\n    vka_fg_scene(g_cb, im);', body)   # the pass ended, then the copy
        self.assertIn('if (vka_fg_layers_armed()) layer_scene(d, dw, di);', d)
        f = (VK / 'vka_framegen.c').read_text()
        self.assertIn('vk.vkCmdCopyImage(cb, im->img, VK_IMAGE_LAYOUT_GENERAL, dst, VK_IMAGE_LAYOUT_GENERAL, 1, &c);\n        vka_barrier(cb);', f)
        self.assertIn('VkImage src = layered ? sn->img : im->img;', f)
        self.assertIn('F.layered = layered; F.warm_streak = 0;', f)   # nothing generated across a change between whole frames and pictures
        self.assertIn('lsfg_chain_generate(F.chain, cb, count, ng, g, layered);', f)
        self.assertIn('int vka_fg_layers_armed(void) { return 0; }', f)   # a build without LSFG

    def test_lower_latency(self):
        f = (VK / 'vka_framegen.c').read_text()
        self.assertIn('if (ng && rns && VKA_ENV("DM_LSFG_LOWLAT", 0)) { double one = (double)rns / 1e9; if (*hold > one) *hold = one; }', f)
        j = (REPO / 'bananative/android/app/src/main/java/dev/nxnative/android/Lsfg.java').read_text()
        self.assertIn('env.add("DM_LSFG_LAYERS=" + (layers(c) ? 1 : 0));', j)
        self.assertIn('env.add("DM_LSFG_LOWLAT=" + (lowLatency(c) ? 1 : 0));', j)
        self.assertIn('return prefs(c).getBoolean("lsfg_layers", true);', j)
        self.assertIn('return prefs(c).getBoolean("lsfg_lowlat", false);', j)
        self.assertIn('w.write("env DM_LSFG_LAYERS=', j)


if __name__ == '__main__':
    unittest.main()
