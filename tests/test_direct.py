import json, shutil, subprocess, tempfile, threading, unittest, zlib
from pathlib import Path
from porter import datapack, direct

ROOT = Path(__file__).resolve().parent.parent
CORE = ROOT / 'nxnative/dm/android/pack_sync_core.h'
MAIN = '''
#include "%s"
int main(int c, char **v) {
    static ps_t p; p.port = atoi(v[1]); snprintf(p.token, sizeof p.token, "%%s", v[2]); p.root = v[3];
    long long n; int s = ps_get(&p, "hello", 0, 2, &n); if (s < 0) { puts("no server"); return 3; }
    close(s);
    if (ps_run(&p)) { printf("failed: %%s\\n", p.msg); return 1; }
    ps_say(&p, "done"); return 0;
}
''' % CORE


class Fingerprint(unittest.TestCase):
    def test_short_file_is_its_crc(self):
        with tempfile.TemporaryDirectory() as t:
            f = Path(t) / 'a'; f.write_bytes(b'hello')
            self.assertEqual(direct.fingerprint(f), zlib.crc32(b'hello'))

    def test_secret_stays(self):
        with tempfile.TemporaryDirectory() as t:
            f = Path(t) / 's.txt'
            self.assertEqual(direct.secret(f), direct.secret(f))


class Waits(unittest.TestCase):
    """install() with the game's half played by this test: the JSON lines are what the app acts on"""
    def run_install(self, play):
        with tempfile.TemporaryDirectory() as t:
            t = Path(t); data = t / 'data'; data.mkdir(); apk = t / 'g.apk'; apk.write_bytes(b'PK')
            (data / 'env.txt').write_text('A=1\n'); (data / datapack.MANIFEST).write_text(json.dumps({'name': 'G', 'package': 'p.q'}))
            seen, real, tell, stage, end, say_, mode = [], direct.serve, direct.tell, direct.PROG.stage, direct.PROG.end, direct.say, direct.PROG.mode
            def serve(d, tok, port, cap=None): srv = real(d, tok, 0, cap); threading.Thread(target=play, args=(srv, data), daemon=True).start(); return srv
            direct.serve, direct.tell = serve, lambda ev, **f: seen.append((ev, f))
            direct.PROG.stage, direct.PROG.end, direct.say, direct.PROG.mode = (lambda *a, **k: None), (lambda *a, **k: None), (lambda *a: None), 'json'
            set_ = direct.PROG.set; direct.PROG.set = lambda **k: None
            try: direct.install(apk, data, 'tok', wait=20)
            finally:
                direct.serve, direct.tell, direct.PROG.stage, direct.PROG.end, direct.say, direct.PROG.mode, direct.PROG.set = real, tell, stage, end, say_, mode, set_
            return seen

    def test_the_game_finishing_opens_it(self):
        def play(srv, data): srv.seen = True; srv.need = 0; srv.finished = True
        seen = self.run_install(play)
        self.assertEqual(seen[0][0], 'install_apk'); self.assertEqual(seen[-1], ('open_game', {'package': 'p.q', 'label': 'G'}))

    def test_a_refused_install_ends_the_port(self):
        def play(srv, data): (data.parent / '.install-failed').write_text('The install was cancelled.')
        with self.assertRaises(direct.BuildError) as e: self.run_install(play)
        self.assertIn('cancelled', str(e.exception))


@unittest.skipUnless(shutil.which('cc'), 'no C compiler')
class GameCopies(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory(); t = Path(cls.tmp.name)
        (t / 'm.c').write_text(MAIN); cls.exe = t / 'sync'
        subprocess.run(['cc', '-O1', '-w', '-o', cls.exe, t / 'm.c'], check=True)

    @classmethod
    def tearDownClass(cls): cls.tmp.cleanup()

    def setUp(self):
        self.d = tempfile.TemporaryDirectory(); t = Path(self.d.name)
        self.pack, self.game = t / 'pack', t / 'game'; self.game.mkdir()
        (self.pack / 'mods').mkdir(parents=True); (self.pack / 'RomFS').mkdir()
        (self.pack / 'mods/main').write_bytes(b'code' * 1000)
        (self.pack / 'mods/empty').write_bytes(b'')
        (self.pack / 'RomFS/a b.bin').write_bytes(b'r' * 5000)
        (self.pack / 'env.txt').write_text('A=1\n')
        (self.pack / 'settings.txt').write_text('fresh=1\n')
        (self.pack / datapack.MANIFEST).write_text(json.dumps({'name': 't', 'package': 'p'}))

    def tearDown(self): self.d.cleanup()

    def sync(self, token='tok'):
        srv = direct.serve(self.pack, 'tok', 0, Path(self.d.name) / 'captured')
        try:
            r = subprocess.run([self.exe, str(srv.server_address[1]), token, self.game], capture_output=True, text=True, timeout=60)
            return r, srv
        finally: srv.shutdown(); srv.server_close()

    def same(self, rel): self.assertEqual((self.game / rel).read_bytes(), (self.pack / rel).read_bytes(), rel)

    def test_first_copy(self):
        r, srv = self.sync()
        self.assertEqual(r.returncode, 0, r.stdout); self.assertTrue(srv.finished); self.assertTrue(srv.need > 0)
        for rel in ('mods/main', 'mods/empty', 'RomFS/a b.bin', 'env.txt', 'settings.txt', datapack.MANIFEST): self.same(rel)

    def test_nothing_to_copy(self):
        self.sync(); r, srv = self.sync()
        self.assertEqual(r.returncode, 0, r.stdout); self.assertEqual(srv.need, 0)

    def test_change_of_the_same_size_is_found(self):
        self.sync(); (self.pack / 'mods/main').write_bytes(b'CODE' * 1000)
        r, srv = self.sync()
        self.assertEqual(srv.need, 4000 + (self.pack / datapack.MANIFEST).stat().st_size); self.same('mods/main')

    def test_stale_goes_and_settings_stay(self):
        self.sync()
        (self.game / 'mods/old').write_bytes(b'x'); (self.game / 'settings.txt').write_text('mine=1\n'); (self.game / 'log.txt').write_text('l'); (self.game / 'pass_1.bin').write_text('p')
        self.assertEqual(self.sync()[0].returncode, 0)
        self.assertFalse((self.game / 'mods/old').exists()); self.assertFalse((self.game / 'pass_1.bin').exists())
        self.assertEqual((self.game / 'settings.txt').read_text(), 'mine=1\n'); self.assertTrue((self.game / 'log.txt').exists())

    def test_saved_shaders_come_back(self):
        (self.game / 'captured_shaders').mkdir(); (self.game / 'captured_shaders/0123456789abcdef.bin').write_bytes(b'DMSC' * 100); (self.game / 'captured_shaders/notes.txt').write_text('x')
        r, srv = self.sync()
        self.assertEqual(r.returncode, 0, r.stdout); self.assertEqual(srv.got, ['0123456789abcdef.bin'])
        self.assertEqual((Path(self.d.name) / 'captured/0123456789abcdef.bin').read_bytes(), b'DMSC' * 100)

    def test_cut_off_copy_goes_on(self):
        (self.game / 'mods').mkdir(); (self.game / 'mods/main.part').write_bytes(b'code' * 400)
        self.assertEqual(self.sync()[0].returncode, 0, ); self.same('mods/main'); self.assertFalse((self.game / 'mods/main.part').exists())

    def test_damaged_part_is_copied_again(self):
        (self.game / 'mods').mkdir(); (self.game / 'mods/main.part').write_bytes(b'xxxx' * 400)
        self.assertEqual(self.sync()[0].returncode, 0); self.same('mods/main')

    def test_big_file_by_pieces(self):
        (self.pack / 'shaders.dmsv').write_bytes(bytes(range(256)) * ((direct.WHOLE >> 8) + 4096))
        r = self.sync()[0]; self.assertEqual(r.returncode, 0, r.stdout); self.same('shaders.dmsv')
        self.assertEqual(self.sync()[1].need, 0)

    def test_wrong_secret_is_refused(self):
        r, srv = self.sync('other')
        self.assertEqual(r.returncode, 3); self.assertFalse(srv.seen); self.assertEqual(list(self.game.iterdir()), [])
