import io, json, tempfile, unittest, zipfile
from pathlib import Path
from porter import driver
from porter.upstream import BuildError

ELF = b'\x7fELF\x02\x01\x01' + bytes(11) + (183).to_bytes(2, 'little') + bytes(32)


def zipped(t, **files):
    p = Path(t) / 'd.zip'
    with zipfile.ZipFile(p, 'w') as z:
        for n, b in files.items(): z.writestr(n.replace('__', '/'), b)
    return p


class Prepare(unittest.TestCase):
    def test_so_goes_under_a_name_android_unpacks(self):
        with tempfile.TemporaryDirectory() as t:
            f = Path(t) / 'vulkan.ad0740.so'; f.write_bytes(ELF)
            out = driver.prepare(f, t)
            self.assertEqual(out.name, 'libcustomdriver.so'); self.assertEqual(out.read_bytes(), ELF)

    def test_zip_uses_meta_json(self):
        with tempfile.TemporaryDirectory() as t:
            f = zipped(t, **{'meta.json': json.dumps({'libraryName': 'vulkan_freedreno.so'}), 'vulkan_freedreno.so': ELF, 'other.so': b'no'})
            self.assertEqual(driver.prepare(f, t).read_bytes(), ELF)

    def test_zip_with_one_library_in_a_folder(self):
        with tempfile.TemporaryDirectory() as t:
            self.assertEqual(driver.prepare(zipped(t, turnip__libvulkan_freedreno_so=b'x', **{'turnip__libvulkan_freedreno.so': ELF}), t).read_bytes(), ELF)

    def test_refuses_what_is_no_arm64_library(self):
        with tempfile.TemporaryDirectory() as t:
            f = Path(t) / 'a.so'; f.write_bytes(b'\x7fELF\x01' + bytes(30))
            with self.assertRaises(BuildError): driver.prepare(f, t)
            g = Path(t) / 'a.txt'; g.write_bytes(ELF)
            with self.assertRaises(BuildError): driver.prepare(g, t)
            with self.assertRaises(BuildError): driver.prepare(zipped(t, **{'readme.txt': 'x'}), t)
