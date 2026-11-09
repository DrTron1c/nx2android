# The pause menu's Speed tab (nxnative/dm/android): presets, Auto resolution (autores_android.c with dm/host/loader.c's level lists), Shadow resolution
# (gfx_vk_tex.c shadow_shrink with gfx_vk_exec.c pass_shrink), the five tabs' encoding in the menu state, and the builder's port on the fast cores.
import re, unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
AND = ROOT / 'nxnative' / 'dm' / 'android'


def read(p): return Path(p).read_text(encoding='utf-8')


class SpeedTab(unittest.TestCase):
    def test_five_tabs_fit_the_menu_state(self):
        lay = read(AND / 'overlay_layout.h')
        self.assertIn('#define OVM_TABS 5', lay)
        names = re.search(r'OVM_TAB_NAMES\[OVM_TABS\] = \{([^}]*)\}', lay).group(1)
        self.assertEqual(len(re.findall(r'"[^"]+"', names)), 5)
        front, present = read(AND / 'front_android.c'), read(AND / 'gfx_vk_present.c')
        self.assertIn('(atomic_load(&g_menu_tab) >> 2 & 1) << 19', front)   # the third bit: tabs 4 and 0 differ
        self.assertIn('(st >> 19 & 1) << 2', present)
        for v in ('0x80000', '1 << 19'): self.assertNotIn(v, lay)   # bit 19 not taken by a mode flag

    def test_presets(self):
        front = read(AND / 'front_android.c')
        n = int(re.search(r'#define PRESETS (\d+)', read(AND / 'overlay_layout.h')).group(1))
        table = re.search(r'PR_VAL\[PRESETS\]\[PR_N\] = \{(.*?)\n\};', front, re.S).group(1)
        rows = re.findall(r'\{([\d, ]*)\}', table)
        self.assertEqual(len(rows), n)
        npr = len(re.search(r'enum \{ (PR_\w+(?:, PR_\w+)*), PR_N \};', front).group(1).split(','))
        values = {'PR_SCALE': 6, 'PR_AUTORES': 2, 'PR_SHADOWRES': 3, 'PR_VRS': 3, 'PR_ANISO': 4, 'PR_TEXHALF': 3}
        order = re.search(r'enum \{ (PR_\w+(?:, PR_\w+)*), PR_N \};', front).group(1).split(', ')
        for r in rows[1:]:
            v = [int(x) for x in r.split(',')]
            self.assertEqual(len(v), npr)
            for name, x in zip(order, v): self.assertLess(x, values[name], (name, r))
        self.assertIn('case OVM_AUTORES: return DISP_AUTORES;', front); self.assertIn('case OVM_SHADOWRES: return DISP_SHADOWRES;', front)
        for k in ('"preset=%d"', '"autores=%d"', '"shadowres=%d"', 'setenv("DM_VK_SHADOW_RES"'): self.assertIn(k, front)
        self.assertIn('atomic_store(&g_disp[DISP_PRESET], 0)', front)   # a preset's row changed by hand: Custom

    def test_auto_resolution(self):
        loader = read(ROOT / 'nxnative/dm/host/loader.c')
        self.assertIn('int dm_res_levels_set(int level)', loader)
        self.assertIn('(prot & PROT_EXEC)', loader)   # never writes into executable memory
        auto = read(AND / 'autores_android.c')
        self.assertIn('dm_res_levels_set(-1)', auto)   # off: back to the lists the loader left
        self.assertIn('dm_autores_tick(); }', read(AND / 'gfx_vk_present.c'))
        self.assertIn('int dm_perf_target_fps(void)', read(AND / 'perf_android.c'))

    def test_shadow_resolution(self):
        tex, exe = read(AND / 'gfx_vk_tex.c'), read(AND / 'gfx_vk_exec.c')
        self.assertIn('f->vk == VK_FORMAT_D16_UNORM', tex)
        self.assertIn('im->shrink = s;', tex)
        self.assertIn('S.pass_shrink = dim && dim->shrink ? dim->shrink : 0;', exe)
        self.assertIn('if (S.pass_shrink) { float k = 1.0f / (float)(1 << S.pass_shrink);', exe)   # viewports
        self.assertIn('if (S.pass_shrink) { int s = S.pass_shrink; sx >>= s;', exe)                # scissors (and the clears that use them)
        self.assertIn('if (s->shrink || d->shrink)', exe)

    def test_port_on_fast_cores(self):
        env = read(ROOT / 'builder/app/src/main/java/org/nx2a/builder/Env.java')
        self.assertIn('static String fastCoreMask()', env)
        self.assertIn('"/system/bin/taskset -p " + mask + " $$ >/dev/null 2>&1; "', env)


if __name__ == '__main__':
    unittest.main()
