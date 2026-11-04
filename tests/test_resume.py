# A stopped port goes on from where it was: shader_vk.py over a package, with a stand-in for the translator, stopped hard during translation and during packing
import json, os, shutil, signal, stat, subprocess, sys, tempfile, unittest
from pathlib import Path

R = Path(__file__).resolve().parent.parent
sys.path[:0] = [str(R / 'tests'), str(R / 'nxnative/dm/gfx/tools'), str(R / 'nxnative/dm/tools')]
os.environ.setdefault('DM_GAME', '0400A3A01EE4C000')
import romfs_image, test_split_spill as T

TOOL = R / 'nxnative/dm/android/tools/shader_vk.py'
FAKE = '''#!%s
# stands in for dotnet dmsh_xlate.dll: job file, work folder. Writes each shader's SPIR-V, and notes which shaders it was given
import json, os, struct, sys
job, w = sys.argv[2:4]
jobs = json.load(open(job))
with open(os.environ['FAKE_LOG'], 'a') as f: f.write(' '.join(j['key'] for j in jobs) + '\\n')
for j in jobs:
    assert os.path.getsize(j['code']) > 0
    spv = [0x07230203, 0x10000, 0, 16, 0, 4 << 16 | 1, 1, int(j['key'][:8], 16), int(j['key'][8:], 16)]
    open(os.path.join(w, j['key'] + '.spv'), 'wb').write(struct.pack(f'<{len(spv)}I', *spv))
'''


class Resume(unittest.TestCase):
    @classmethod
    def setUpClass(c):
        c.t = tempfile.TemporaryDirectory(); t = c.d = Path(c.t.name)
        src = t / 'src/motor_asset_re/KIDS_PLATFORM_OUNCE'; src.mkdir(parents=True)
        (src / '0x38f972af.fdata').write_bytes(T.fdata(2400, copies=1, size=0x100))
        romfs_image.pack(t / 'src', t / 'RomFS.img'); (t / 'main').write_bytes(T.nso())
        s = t / 'split'; s.mkdir()
        r = subprocess.run([sys.executable, str(T.TOOLS / 'shader_split.py'), str(t / 'RomFS.img'), str(t / 'main'), str(s / 'pkg'), str(s / 'shader_map.tsv'), str(s / 'facts'), str(s / 'pairs')],
                           env=dict(os.environ, J='2'), capture_output=True, text=True, cwd=str(t))
        assert r.returncode == 0, r.stdout[-500:] + r.stderr[-500:]
        # a copy of the profile with this package's map, which the tool reads
        c.games = t / 'games'; shutil.copytree(R / 'nxnative/dm/games/0400A3A01EE4C000', c.games / '0400A3A01EE4C000', ignore=shutil.ignore_patterns('patches.py', '__pycache__'))
        shutil.copyfile(s / 'shader_map.tsv', c.games / '0400A3A01EE4C000/shader_map.tsv')
        c.fake = t / 'fake_dotnet'; c.fake.write_text(FAKE % sys.executable); c.fake.chmod(c.fake.stat().st_mode | stat.S_IXUSR)

    @classmethod
    def tearDownClass(c): c.t.cleanup()

    def run_tool(self, name, resume=None, stop_on=None):
        """stop_on: output at which the tool is killed, the way the system kills a port"""
        d = self.d / name; d.mkdir(exist_ok=True); log = d / 'fake.log'
        env = dict(os.environ, FAKE_LOG=str(log), WORK=str(d), DM_GAMES_DIR=str(self.games), DM_GAMES_FIRST='1')
        cmd = [sys.executable, str(TOOL), str(self.d / 'split/pkg'), str(d / 'out.dmsv'), '--jobs', '1', '--dotnet', str(self.fake), '--xlate', str(self.fake)] + (['--resume', str(resume)] if resume else [])
        p = subprocess.Popen(cmd, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, cwd=str(self.d))
        lines = []
        for l in p.stdout:
            lines.append(l)
            if stop_on and l.startswith(stop_on): p.send_signal(signal.SIGKILL); break
        p.stdout.close(); p.wait()
        return p.returncode, ''.join(lines), log

    def test_stopped_while_translating(self):
        code, out, _ = self.run_tool('clean')
        self.assertEqual(code, 0, out[-600:]); want = (self.d / 'clean/out.dmsv').read_bytes()
        self.assertGreater(len(want), 1000)
        r = self.d / 'a/work'; r.mkdir(parents=True)
        code, out, log = self.run_tool('a', r, stop_on='translated 3000')
        self.assertEqual(code, -9)
        first = set(' '.join(log.read_text().splitlines()).split())
        code, out, log = self.run_tool('a', r)
        self.assertEqual(code, 0, out[-600:])
        self.assertIn('done before', out)
        rows = log.read_text().splitlines()
        self.assertLess(len(rows), 7)   # 4 batches: no more than the one under way is run again
        self.assertEqual((self.d / 'a/out.dmsv').read_bytes(), want)

    def test_stopped_while_packing(self):
        code, out, _ = self.run_tool('clean2')
        want = (self.d / 'clean2/out.dmsv').read_bytes()
        r = self.d / 'b/work'; r.mkdir(parents=True)
        code, out, log = self.run_tool('b', r, stop_on='packed 3500')
        self.assertEqual(code, -9)
        calls = len(log.read_text().splitlines())
        self.assertTrue((r / 'bundle.json').is_file())
        code, out, log = self.run_tool('b', r)
        self.assertEqual(code, 0, out[-600:])
        self.assertEqual(len(log.read_text().splitlines()), calls)   # nothing translated again
        self.assertEqual((self.d / 'b/out.dmsv').read_bytes(), want)

    def test_a_run_with_no_folder_leaves_none_of_its_files(self):
        code, out, _ = self.run_tool('c')
        self.assertEqual(code, 0, out[-600:])
        d = [p for p in (self.d / 'c').iterdir() if p.name.startswith('shvk_')]
        self.assertEqual(len(d), 1); self.assertEqual([p.name for p in d[0].iterdir() if p.suffix == '.spv'], [])


class Device(unittest.TestCase):
    def test_a_moved_port_is_found(self):
        sys.path.insert(0, str(R))
        from porter.adb import Adb
        with tempfile.TemporaryDirectory() as t:
            state = Path(t) / 'connected'
            fake = Path(t) / 'adb'
            fake.write_text(f'''#!/bin/sh
case "$1" in
  devices) echo "List of devices attached"; [ -f {state} ] && echo "$(cat {state}) device" ;;
  connect) [ "$2" = 127.0.0.1:41000 ] && echo "$2" > {state} ;;
  mdns) echo "List of discovered mdns services"; echo "adb-X-Y _adb-tls-connect._tcp 192.168.0.157:41000" ;;
esac
'''); fake.chmod(0o755)
            a = Adb(fake, '127.0.0.1:34207', log=lambda m: None)
            self.assertEqual(a.pick(), '127.0.0.1:41000')

    def test_off_says_so_after_waiting(self):
        sys.path.insert(0, str(R))
        from porter.adb import Adb, AdbError
        with tempfile.TemporaryDirectory() as t:
            fake = Path(t) / 'adb'; fake.write_text('#!/bin/sh\n[ "$1" = devices ] && echo "List of devices attached"\nexit 0\n'); fake.chmod(0o755)
            told = []
            with self.assertRaises(AdbError): Adb(fake, '127.0.0.1:34207', log=lambda m: None).pick(wait=1, waiting=lambda: told.append(1))
            self.assertEqual(told, [1])


if __name__ == '__main__': unittest.main()
