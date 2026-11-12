# The host's forced takeover list (nxnative/dm/host/hle.h HLE_FORCE, HLE_FORCE_BIG): the loader stops the game at start when one name is in it twice (loader.c force_init,
# "duplicate entry in the forced takeover list"). The upstream merge put ClockSnapshot::CreateWithAdjustableSystemClock in hle_time.c twice, and Pokopia aborted before its first frame.
import collections, re, unittest
from pathlib import Path

DM = Path(__file__).resolve().parents[1] / 'nxnative/dm'


class ForcedTakeovers(unittest.TestCase):
    def test_each_name_once(self):
        where = collections.defaultdict(list)
        for p in sorted([*DM.glob('host/**/*.c'), *DM.glob('android/**/*.c'), *DM.glob('gfx/**/*.c')]):
            if 'tests' in p.relative_to(DM).parts: continue
            s = re.sub(r'//[^\n]*', '', p.read_text(encoding='utf-8', errors='replace'))
            for m in re.finditer(r'\bHLE_FORCE(?:_BIG)?\s*\(\s*"([^"]+)"', s):
                where[m.group(1)].append(f'{p.relative_to(DM)}:{s.count(chr(10), 0, m.start()) + 1}')
        self.assertGreater(len(where), 500)
        self.assertEqual({n: w for n, w in where.items() if len(w) > 1}, {})


if __name__ == '__main__':
    unittest.main()
