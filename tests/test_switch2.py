# Switch 2 input (porter/switch2.py) and the RomFS image in the data pack; no game files: a dump folder made up for the test
import struct, sys, tempfile, unittest
from pathlib import Path
from types import SimpleNamespace

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from porter import datapack, switch2


def npdm(app):
    d = bytearray(0x200); d[:4] = b'META'
    struct.pack_into('<II', d, 0x70, 0x100, 0x40); d[0x100:0x104] = b'ACI0'; struct.pack_into('<Q', d, 0x110, app)
    return bytes(d)


def dump(root, app):
    for sub in ('base/exefs', 'base/romfs', 'updates/131072/exefs', 'updates/131072/romfs', f'dlc/{app + 0x1001:016X}/0/romfs'): (root / sub).mkdir(parents=True)
    for v in ('base', 'updates/131072'):
        (root / v / 'exefs/main').write_bytes(b'NSO0'); (root / v / 'exefs/main.npdm').write_bytes(npdm(app)); (root / v / 'romfs/a.bin').write_bytes(b'hello')
    (root / f'dlc/{app + 0x1001:016X}/0/romfs/b.bin').write_bytes(b'dlc')


class Switch2(unittest.TestCase):
    def test_finds_a_dump(self):
        with tempfile.TemporaryDirectory() as t:
            root = Path(t); dump(root, 0x0400A3A01EE4C000)
            s = switch2.find(root)
            self.assertEqual((s.kind, s.app, s.versions, switch2.newest(s)), ('dump', 0x0400A3A01EE4C000, [131072], 'v131072'))
            self.assertEqual(s.dlc, [0x0400A3A01EE4C000 + 0x1001])
            self.assertTrue(switch2.is_switch2(s.app) and switch2.has_version(s, 'v0') and not switch2.has_version(s, 'v65536'))

    def test_a_folder_of_nsps_is_not_one(self):
        with tempfile.TemporaryDirectory() as t: self.assertIsNone(switch2.find(Path(t)))

    def test_extract_writes_the_image(self):
        with tempfile.TemporaryDirectory() as t:
            root = Path(t) / 'game'; root.mkdir(); dump(root, 0x0400A3A01EE4C000); work = Path(t) / 'work'
            out, aoc = switch2.extract(switch2.find(root), work, 'v131072')
            self.assertTrue((out / 'ExeFS/main').is_file() and (out / 'RomFS.img').stat().st_size > 0 and (aoc / '1.img').is_file())

    def test_data_pack_keeps_a_romfs_image(self):
        with tempfile.TemporaryDirectory() as t:
            t = Path(t); (t / 'mods').mkdir(); (t / 'mods/main').write_bytes(b'x'); (t / 'RomFS.img').write_bytes(b'img'); (t / 's.dmsv').write_bytes(b's')
            p = SimpleNamespace(name='Game', id='0400A3A01EE4C000', package='org.nx2a.game', env=[], settings={}, requires=['bcn'])
            datapack.build(t / 'out', profile=p, version='v1', display='1', patched=t / 'mods', romfs=t / 'RomFS.img', shaders=t / 's.dmsv')
            self.assertTrue((t / 'out/RomFS.img').is_file() and not (t / 'out/RomFS').exists())


if __name__ == '__main__': unittest.main()
