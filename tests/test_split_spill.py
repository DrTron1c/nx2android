# The shader split on a device (shader_split.py Found): what it writes is what the in-memory scan writes, read from a RomFS image the way the app reads one, and a worker that dies says how
import collections, concurrent.futures as cf, contextlib, hashlib, io, multiprocessing, os, struct, subprocess, sys, tempfile, unittest
from pathlib import Path
from unittest import mock

R = Path(__file__).resolve().parent.parent
TOOLS = R / 'nxnative/dm/gfx/tools'
sys.path[:0] = [str(TOOLS), str(R / 'nxnative/dm/tools')]
os.environ.setdefault('DM_GAME', '0400A3A01EE4C000')
import romfs_image, shader_split as split

CTL = bytes.fromhex('34127698')


def stage(seed, st, size):   # an NVN2 stage: (control section, code area), the code starting with an SPH of that stage
    code = struct.pack('<I', {'vs': 1, 'ps': 5}[st] << 10 | 0x61) + hashlib.sha256(seed).digest()
    code += bytes(size - len(code))
    ctl = bytearray(CTL + b'\xc9\0\0\0' + bytes(0xd0)); struct.pack_into('<I', ctl, 0x38, len(code)); struct.pack_into('<II', ctl, 0x40, len(code), len(code))
    return bytes(ctl), code


def output(secs):   # a Switch 2 GLSLC output of these stages (glslc_layout, glslc_section)
    head = 0xa8 + 0x98 * len(secs)
    body, heads = b'', b''
    for ctl, code in secs:
        off = head + len(body)
        h = bytearray(0x98); struct.pack_into('<3I', h, 0, len(ctl) + len(code), off, 0); struct.pack_into('<4I', h, 0x30, 0, len(ctl), len(code), len(ctl))
        heads += bytes(h); body += ctl + code
    o = bytearray(bytes.fromhex('91688619') + bytes(0xa4))
    struct.pack_into('<I', o, 0x30, 0xc9); struct.pack_into('<I', o, 0x5c, head + len(body)); struct.pack_into('<I', o, 0x64, len(secs))
    return bytes(o) + heads + body


def fdata(n, copies=3, size=0x400):   # a KTGL .fdata: 'PDRK0000', then IDRK entries of G2S containers; every n-th program in a second entry too, as a game repeats its shaders
    out = b'PDRK0000' + struct.pack('<Q', 0x10)
    for i in range(n * copies):
        k = i % n
        vs, ps = stage(b'v%d' % k, 'vs', size), stage(b'p%d' % (k // 2), 'ps', size + 0x100)
        data = b'_S2G3000' + bytes(8) + output([vs, ps]) + output([stage(b's%d' % k, 'ps', size)])
        head = bytearray(0x40); head[:8] = b'IDRK0000'; struct.pack_into('<QQQ', head, 8, 0x40 + len(data), len(data), len(data))
        out += bytes(head) + data
    return out


def nso():   # an empty code module, for main
    h = bytearray(0x100); h[:4] = b'NSO0'
    for i in range(3): struct.pack_into('<III', h, 0x10 + i * 0x10, 0x100 + i * 0x1000, i * 0x1000, 0x1000)
    return bytes(h) + bytes(0x3000)


def scan(path, spill):
    with mock.patch.dict(os.environ, {'DM_SPLIT_SPILL': spill} if spill else {}), mock.patch.object(split, '_SPILL', None):
        if not spill: os.environ.pop('DM_SPLIT_SPILL', None)
        r = split.scan_file((str(path), 'a.fdata'))
        if spill: split._SPILL[1].close()
    return split.spill_read(r)


class Found(unittest.TestCase):
    def test_a_scan_gives_what_the_list_gives(self):
        with tempfile.TemporaryDirectory() as t:
            p = Path(t) / 'a.fdata'; p.write_bytes(fdata(40))
            spill = Path(t) / 'spill'; spill.mkdir()
            rel, plain, pairs, extra = scan(p, None)
            self.assertEqual(len(plain), 40 + 20 + 40); self.assertEqual(len(pairs), 40)
            rel2, kept, pairs2, extra2 = scan(p, str(spill))
            self.assertEqual((rel2, pairs2, extra2), (rel, pairs, extra))
            self.assertEqual(list(kept), list(plain))
            for k, v in plain.items():
                self.assertEqual(kept[k][:2], v[:2]); self.assertEqual(bytes(kept[k][2]), v[2]); self.assertEqual(bytes(kept[k][3]), v[3])
            self.assertEqual(sum(f.stat().st_size for f in spill.iterdir()), sum(len(v[2]) + len(v[3]) for v in plain.values()))   # one copy of each

    def test_the_same_key_with_other_bytes_stops_the_scan(self):
        ctl, code = stage(b'a', 'vs', 0x100)
        with tempfile.TemporaryDirectory() as t, mock.patch.dict(os.environ, {'DM_SPLIT_SPILL': t}), mock.patch.object(split, '_SPILL', None), mock.patch.object(split, 'skey', lambda c, k: 'same'):
            f = split.Found(); f.append(('a', 'vs', ctl, code))
            with self.assertRaises(SystemExit): f.append(('b', 'vs', ctl, code[:-1] + b'\1'))
            split._SPILL[1].close()

    def test_a_scan_with_no_shader_holds_no_file(self):
        with tempfile.TemporaryDirectory() as t, mock.patch.dict(os.environ, {'DM_SPLIT_SPILL': t}), mock.patch.object(split, '_SPILL', None):
            f = split.Found()
            self.assertFalse(f); self.assertEqual(split.uniq_keys(f), {}); self.assertEqual(split.var_pairs(f), set()); self.assertEqual(os.listdir(t), [])

    def test_the_whole_split_writes_the_same_from_a_romfs_image(self):
        with tempfile.TemporaryDirectory() as t:
            t = Path(t); src = t / 'src/motor_asset_re/KIDS_PLATFORM_OUNCE'; src.mkdir(parents=True)
            (src / '0x38f972af.fdata').write_bytes(fdata(60)); (src / '0x05d525ae.fdata').write_bytes(fdata(30)); (t / 'src/other.bin').write_bytes(b'x' * 0x1000)
            romfs_image.pack(t / 'src', t / 'RomFS.img'); (t / 'main').write_bytes(nso())
            def run(name, **env):
                d = t / name; d.mkdir()
                r = subprocess.run([sys.executable, str(TOOLS / 'shader_split.py'), str(t / 'RomFS.img'), str(t / 'main'), str(d / 'pkg'), str(d / 'map'), str(d / 'facts'), str(d / 'pairs'), str(d / 'pso')],
                                   env=dict(os.environ, J='3', DM_GAME='0400A3A01EE4C000', **env), capture_output=True, text=True, cwd=str(t))
                self.assertEqual(r.returncode, 0, r.stdout[-800:] + r.stderr[-800:])
                return {str(p.relative_to(d)): p.read_bytes() for p in sorted(d.rglob('*')) if p.is_file()}
            plain = run('plain')
            (t / 'spill').mkdir()
            self.assertEqual(run('device', DM_SPLIT_SPILL=str(t / 'spill')), plain)
            self.assertTrue(any(k.startswith('pkg/') for k in plain))


def dies(jobs): os.kill(os.getpid(), 9)


class Death(unittest.TestCase):
    def test_a_killed_worker_is_named_and_its_jobs_are_not_counted(self):
        lines = io.StringIO()
        with mock.patch.object(split, 'scan_batch', dies), mock.patch.object(split, 'WORKERS', []), contextlib.redirect_stdout(lines):
            with self.assertRaises(cf.BrokenExecutor):
                try:
                    with cf.ProcessPoolExecutor(2, mp_context=multiprocessing.get_context('fork')) as ex: split.pooled(ex, list(range(8)), 8, chunk=2)
                except cf.BrokenExecutor: split.pool_broke(); raise
        text = lines.getvalue()
        self.assertIn('progress: 0/8', text); self.assertNotIn('8/8', text)
        self.assertRegex(text, r'worker died: exit codes .*-9'); self.assertIn('a worker held up to', text)


if __name__ == '__main__': unittest.main()
