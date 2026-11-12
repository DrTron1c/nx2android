# The selected shader build (nxnative/dm/android/tools/shader_vk.py selection): a profile's shader_seen.tsv names the shaders its game is known to use, and only those
# (with their paired pixel shaders) are translated unless the port asks for every shader (porter --all-shaders, the builder's checkbox).
import re, unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
GAMES = REPO / 'nxnative/dm/games'


class ShaderSelection(unittest.TestCase):
    def test_selected_keys_are_in_the_map(self):
        for seen in sorted(GAMES.glob('*/shader_seen.tsv')):
            keys = {l[:16] for l in seen.read_text(encoding='utf-8').splitlines() if re.match(r'[0-9a-f]{16}\b', l)}
            loc = {c[3] for c in (l.split('\t') for l in (seen.parent / 'shader_map.tsv').read_text(encoding='utf-8').splitlines()) if len(c) > 4 and c[0].endswith('.bin')}
            self.assertTrue(keys, seen)
            self.assertLessEqual(len(keys - loc), len(keys) // 100, f'{seen}: most selected keys must be in the map')
            self.assertLess(len(keys & loc), len(loc), f'{seen}: a selection smaller than the map')

    def test_the_porter_and_the_builder_pass_the_choice(self):
        sv = (REPO / 'nxnative/dm/android/tools/shader_vk.py').read_text(encoding='utf-8')
        self.assertIn("'--all-shaders'", sv); self.assertIn('def selection(loc):', sv); self.assertIn("NX2A_ALL_SHADERS", sv)
        pipe = (REPO / 'porter/pipeline.py').read_text(encoding='utf-8')
        self.assertIn("(['--all-shaders'] if every else [])", pipe)
        self.assertIn("'the choice of shaders'", pipe)   # changing it makes the bundle again, and the log says why
        self.assertIn('all_shaders=a.all_shaders', (REPO / 'porter/cli.py').read_text(encoding='utf-8'))
        java = (REPO / 'builder/app/src/main/java/org/nx2a/builder/MainActivity.java').read_text(encoding='utf-8')
        self.assertIn('a.add("--all-shaders")', java)


if __name__ == '__main__':
    unittest.main()
