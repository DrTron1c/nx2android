# The shader split's spill to disk (shader_split.py spill_write, spill_read): what comes back is what went in, and the split's own checks read it
import os, struct, sys, tempfile, unittest
from pathlib import Path

R = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(R / 'nxnative/dm/gfx/tools'))
os.environ.setdefault('DM_GAME', '0400A3A01EE4C000')


class Spill(unittest.TestCase):
    def test_round_trip(self):
        try: import shader_split as s
        except ImportError as e: self.skipTest(str(e))
        ctl, code = bytes(0x780) + b'c', bytes(range(256)) * 8
        with tempfile.TemporaryDirectory() as t:
            r = ('rel', {'k1': ('a#1', 'vs', ctl, code), 'k2': ('a#2', 'ps', ctl[:0x700], code[:0x100])}, {('k1', 'k2')}, None)
            w = s.spill_write(r, t)
            self.assertIsInstance(w[1], tuple); self.assertNotIn(code, repr(w[1]).encode())
            rel, uniq, pr, extra = s.spill_read(w)
            self.assertEqual((rel, pr, extra), ('rel', {('k1', 'k2')}, None))
            for k, v in r[1].items():
                self.assertEqual(uniq[k][:2], v[:2]); self.assertEqual(bytes(uniq[k][2]), v[2]); self.assertEqual(bytes(uniq[k][3]), v[3])
            self.assertEqual(struct.unpack_from('<I', uniq['k1'][3], 4), struct.unpack_from('<I', code, 4))
            self.assertTrue(s.same_shader(tuple(map(bytes, uniq['k1'][2:4])), (ctl, code)))
            del uniq

    def test_a_job_with_no_shaders_stays_as_it_is(self):
        try: import shader_split as s
        except ImportError as e: self.skipTest(str(e))
        r = ('rel', {}, set(), None)
        self.assertEqual(s.spill_read(s.spill_write(r, '/nonexistent')), r)


if __name__ == '__main__': unittest.main()
