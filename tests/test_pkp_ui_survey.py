# The sleep mod's survey of Pokopia's files for its font and talk bubble (bananative/android/app/src/main/java/dev/nxnative/android/UiExport.java): a made-up RomFS in the
# game's two forms, KTGL RDB .fdata (stored, zlib-chunked and LZ4-chunked IDRK entries, and a shader container that is skipped) and a LINKDATA pair (a KT chunked-zlib
# record), goes through the real class (javac + java), and the zip holds the font and the talk entries and the survey.
import shutil, struct, subprocess, tempfile, unittest, zipfile, zlib
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
SRC = REPO / 'bananative/android/app/src/main/java/dev/nxnative/android/UiExport.java'

HARNESS = '''package dev.nxnative.android;
public class Harness {
    public static void main(String[] a) throws Exception {
        System.out.println(UiExport.run(new java.io.File(a[0]), new java.io.File(a[1]), line -> {}));
    }
}
'''


def idrk(data, flags, stored, ktid=0x1111, typ=0x2222):
    head = b'IDRK0000' + struct.pack('<QQQ', 0x30 + len(stored), len(stored), len(data)) + struct.pack('<IIII', 0, ktid, typ, flags)
    e = head + stored
    return e + b'\0' * (-len(e) % 16)


def zchunks(data):   # flags 0x100000: u32 length + a zlib stream per 16 KB chunk, then 0
    out = b''
    for i in range(0, len(data), 0x4000):
        z = zlib.compress(data[i:i + 0x4000]); out += struct.pack('<I', len(z)) + z
    return out + struct.pack('<I', 0)


def lz4_literals(data):   # an LZ4 block of literals, then a match: "abc" x4 + the data as literals
    blk = bytes([0x35]) + b'abc' + struct.pack('<H', 3)   # 3 literals, a match of 9 at offset 3 → "abcabcabcabc"
    n = len(data); tok = (min(n, 15) << 4)
    blk += bytes([tok]) + (bytes([n - 15]) if n >= 15 else b'') + data   # (n - 15 < 255 here)
    return struct.pack('<H', len(blk)) + b'\0' * 8 + blk + struct.pack('<H', 0)   # flags 0x500000: u16 length + 8 bytes


def kt_gz(data, cs=0x10000):
    z = zlib.compress(data); body = struct.pack('<I', len(z)) + z
    head = struct.pack('<III', cs, 1, len(data)) + struct.pack('<I', len(body))
    return head + b'\0' * (-len(head) % 0x80) + body


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'a JDK')
class Survey(unittest.TestCase):
    def test_finds_the_font_and_the_talk_window(self):
        with tempfile.TemporaryDirectory(prefix='nx2a-ui-') as tmp:
            t = Path(tmp); rom = t / 'romfs/motor_asset_re/KIDS_PLATFORM_OUNCE'; rom.mkdir(parents=True)
            font = b'_N1G0000' + bytes(range(256)) * 4
            layout = b'SLO\0' + b'\0' * 3000 + b'0250_parts_talk_cursor' + b'\0' * 100
            fd = b'PDRK0000' + struct.pack('<Q', 0x10)
            fd += idrk(font, 0, font)                                     # stored
            fd += idrk(b'_S2G3000' + b'talk' * 10, 0x100000, zchunks(b'_S2G3000' + b'talk' * 10))   # shaders: never a candidate
            fd += idrk(layout, 0x100000, zchunks(layout))                   # zlib chunks
            lz = b'abcabcabcabc' + b'..Balloon_window..'
            fd += idrk(lz, 0x500000, lz4_literals(b'..Balloon_window..'))   # LZ4 chunks
            fd += idrk(b'G1TG0000' + b'\0' * 64, 0, b'G1TG0000' + b'\0' * 64)   # a texture: counted only
            (rom / '0x12345678.fdata').write_bytes(fd)
            rec = b'G1N_' + b'\0' * 200
            ld = rom.parent.parent / 'LD_C0_R.BIN'; li = rom.parent.parent / 'LI_C0_R.IDX'
            blob = kt_gz(rec); ld.write_bytes(blob); li.write_bytes(struct.pack('<QQQQ', 0, len(rec), len(blob), 1))
            cls = t / 'cls'; cls.mkdir(); (t / 'Harness.java').write_text(HARNESS)
            subprocess.run(['javac', '-d', str(cls), str(SRC), str(t / 'Harness.java')], check=True, capture_output=True)
            zp = t / 'out/survey.zip'
            r = subprocess.run(['java', '-cp', str(cls), 'dev.nxnative.android.Harness', str(t / 'romfs'), str(zp)], capture_output=True, text=True, check=True)
            self.assertIn('Done: 2 fonts and 2 talk window candidates', r.stdout)
            with zipfile.ZipFile(zp) as z:
                names = z.namelist(); survey = z.read('survey.txt').decode()
                ents = {n: z.read(n) for n in names if n.startswith('entries/')}
            self.assertEqual(sum(1 for n in ents if '_font_' in n), 2)
            self.assertTrue(any(v.endswith(font) for v in ents.values()))        # the stored font, after its IDRK header
            self.assertTrue(any(v == rec for v in ents.values()))                 # the LINKDATA font, inflated
            self.assertTrue(any(v.endswith(layout) for v in ents.values()))       # the zlib layout with the talk cursor
            self.assertTrue(any(v.endswith(lz) for v in ents.values()))           # the LZ4 entry, its match copied
            self.assertNotIn('_S2G', ''.join(n for n in ents))
            self.assertIn('0250_parts_talk_cursor', survey)
            self.assertIn('type 00002222 G1TG0000', survey)
            self.assertIn('files/LI_C0_R.IDX', names)


if __name__ == '__main__':
    unittest.main()
