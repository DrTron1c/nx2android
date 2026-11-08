# The game speed fix (nxnative/dm/android/speed_android.c): Pokemon Pokopia's observation gates in its patches.py, the host's gate numbers, the gate frame's layout
# the callbacks read, and the pause menu's row and settings key.
import importlib.util, re, unittest
from pathlib import Path

NX = Path(__file__).resolve().parents[1] / 'nxnative' / 'dm'


def load(path):
    spec = importlib.util.spec_from_file_location('pkp_patches', path); m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m)
    return m


class SpeedFix(unittest.TestCase):
    def test_gate_numbers_match_the_host(self):
        dm_h = (NX / 'host/dm.h').read_text(encoding='utf-8')
        nums = {k: int(v) for k, v in re.findall(r'\b(OBS_\w+) = (\d+)', dm_h)}
        p = load(NX / 'games/0400A3A01EE4C000/patches.py')
        self.assertEqual(p.obs_frame_mult, nums['OBS_FRAME_MULT'])
        self.assertEqual(p.obs_fixed_step, nums['OBS_FIXED_STEP'])
        self.assertLess(max(p.obs_frame_mult, p.obs_fixed_step), nums['OBS_MAX'])
        gates = p.OBS['76a489f3bd3642fa5e18bd83d32c98ba']   # the profile's main build (game.tsv build row)
        self.assertIn('main=76a489f3bd3642fa5e18bd83d32c98ba', (NX / 'games/0400A3A01EE4C000/game.tsv').read_text(encoding='utf-8'))
        by = {oid & 0xff: (off, oid >> 8, ow) for off, oid, ow, why in gates}
        off, prm, ow = by[p.obs_frame_mult]
        self.assertEqual((off, prm & 31, prm >> 5 & 31, prm >> 10 & 15, ow), (0xbfc4fc, 0, 8, 3, None))   # s0 multiplier, s8 frame time, limit 3
        off, prm, ow = by[p.obs_fixed_step]
        self.assertEqual((off, prm & 31, prm >> 5 & 31, prm >> 10 & 31, prm >> 15 & 31, ow), (0x89e398, 0, 28, 25, 8, None))   # s0 rate, x28 now, x25 previous, d8 frequency
        for off, oid, ow, why in gates: self.assertLess(oid, 1 << 31)   # gates.tsv's argument is read with %d (loader.c register_gates)

    def test_gate_frame_layout(self):
        entry = (NX / 'host/entry.S').read_text(encoding='utf-8')
        self.assertIn('stp q0, q1, [x30, #256]', entry)   # saved at +256
        self.assertIn('ldp q0, q1, [sp, #256]', entry)    # and restored from there: a callback's write reaches the guest
        src = (NX / 'android/speed_android.c').read_text(encoding='utf-8')
        self.assertIn('256 + 16 * n', src)
        self.assertIn('gate_obs_set(OBS_FRAME_MULT, frame_mult)', src)
        self.assertIn('gate_obs_set(OBS_FIXED_STEP, fixed_step)', src)

    def test_menu_row_and_setting(self):
        lay = (NX / 'android/overlay_layout.h').read_text(encoding='utf-8')
        tabs = re.search(r'OVM_TAB_ITEMS\[OVM_TABS\]\[OVM_PER_TAB\] = \{(.*?)\};', lay).group(1)
        self.assertIn('OVM_SPEED', tabs)
        self.assertIn('DISP_SPEED, DISP_N', lay)
        front = (NX / 'android/front_android.c').read_text(encoding='utf-8')
        self.assertIn('"speed=%d"', front)
        self.assertIn('speed=%d\\n', front)
        self.assertIn('item == OVM_SPEED ? DISP_SPEED', front)
        present = (NX / 'android/gfx_vk_present.c').read_text(encoding='utf-8')
        labels = re.search(r'OVM_LABELS\[OVM_N\] = \{(.*?)\};', present).group(1)
        enum = re.search(r'enum \{ (OVM_OVERLAY.*?), OVM_N \};', lay).group(1).split(', ')
        self.assertEqual(len(re.findall(r'"[^"]*"', labels)), len(enum))   # one label per row
        self.assertEqual(re.findall(r'"[^"]*"', labels)[enum.index('OVM_SPEED')], '"Game speed fix"')


if __name__ == '__main__':
    unittest.main()
