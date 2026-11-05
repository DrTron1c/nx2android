# The port's own try of the game: a crash's frames named from the host before stripping
import subprocess, sys, tempfile, unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from porter import adb


class Report(unittest.TestCase):
    def fake(self, alive):
        a = adb.Adb.__new__(adb.Adb); calls = []
        def run(*args, **kw):
            calls.append(args[1])
            if 'pidof' in args[1]: return '1234\n' if alive else ''
            if 'dropbox' in args[1]: return '#00 pc 0000000000001000  /data/app/x/base.apk!libdmhost.so (offset 0x4000)\nother\n'
            return ''
        a.run = run; return a, calls

    def test_alive(self):
        a, calls = self.fake(True)
        self.assertIsNone(a.launch_report('org.x', wait=0))
        self.assertTrue(any('force-stop' in c for c in calls[1:]))

    def test_crash(self):
        with tempfile.TemporaryDirectory() as t:
            so = Path(t) / 'a.so'
            subprocess.run(['cc', '-shared', '-nostdlib', '-x', 'c', '-', '-o', so], input=b'int crash_here(int x) { return x + 1; }', check=True)
            sym = adb.load_symbols(so)
            at = next(r[0] for r in sym[1] if r[2] == 'crash_here')
            a, _ = self.fake(False); a.run_sym = None
            line = f'#00 pc {at + 4:016x}  /data/app/x/base.apk!libdmhost.so (offset 0x4000)'
            self.assertTrue(adb.name_frame(line, sym).endswith('crash_here+0x4'))
            self.assertEqual(a.launch_report('org.x', wait=0)[1], 'other')


if __name__ == '__main__': unittest.main()
