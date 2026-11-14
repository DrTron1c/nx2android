# Saves between a game's versions (dm/host/save_sync.c, the same logic in nxnative/ and bananative/): the builder's "Copy the save from the old app" starts the old
# per-game app to hand its save over (save_sync_export), and the engine takes it at its next start even when its own save changed since the last sync (kept in its
# backups), once: a later save of its own goes out as usual. Built here from nxnative's copy with the Android port's shims (CommonCrypto's SHA-256 from compat/sha256.c)
# and small stand-ins for the Darwin calls; bananative's copy must hold the same hand-over code.
import os, re, shutil, subprocess, tempfile, unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
NX = REPO / 'nxnative/dm'
ID = '0400A3A01EE4C000'

STUBS = r'''#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>
#include <dirent.h>
static int copy_tree(const char *a, const char *b) {
    struct stat st; if (lstat(a, &st)) return -1;
    if (S_ISDIR(st.st_mode)) {
        if (mkdir(b, 0755) && errno != EEXIST) return -1;
        DIR *d = opendir(a); if (!d) return -1; struct dirent *e;
        while ((e = readdir(d))) { if (!strcmp(e->d_name, ".") || !strcmp(e->d_name, "..")) continue;
            char x[4096], y[4096]; snprintf(x, sizeof x, "%s/%s", a, e->d_name); snprintf(y, sizeof y, "%s/%s", b, e->d_name);
            if (copy_tree(x, y)) { closedir(d); return -1; } }
        closedir(d); return 0;
    }
    int in = open(a, O_RDONLY), out = open(b, O_WRONLY | O_CREAT | O_EXCL, 0644); char buf[65536]; ssize_t n;
    if (in < 0 || out < 0) return -1;
    while ((n = read(in, buf, sizeof buf)) > 0) if (write(out, buf, (size_t)n) != n) return -1;
    close(in); close(out); return 0;
}
int clonefile(const char *src, const char *dst, uint32_t flags) { (void)flags; if (!access(dst, F_OK)) { errno = EEXIST; return -1; } return copy_tree(src, dst); }
int renamex_np(const char *from, const char *to, unsigned flags) { (void)flags; return (int)syscall(SYS_renameat2, AT_FDCWD, from, AT_FDCWD, to, 2 /* RENAME_EXCHANGE */); }
int sysctlbyname(const char *n, void *o, size_t *l, void *nw, size_t nl) { (void)n; (void)o; (void)l; (void)nw; (void)nl; errno = ENOENT; return -1; }
'''

MAIN = r'''#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "save_sync.h"
int main(int argc, char **argv) {   // ss mount <save> <title> | ss export <save> <title>
    if (argc < 4) return 2;
    if (!strcmp(argv[1], "mount")) { save_sync_mount(argv[2], argv[3]); return !save_sync_flush(10000); }
    if (!strcmp(argv[1], "export")) { int r = save_sync_export(argv[2], argv[3]); printf("export %d\n", r); return r != 1; }
    return 2;
}
'''


@unittest.skipUnless(shutil.which('cc') and os.name == 'posix' and os.uname().sysname == 'Linux', 'a Linux C compiler is required')
class SaveHandOver(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory(prefix='nx2a-handover-')
        d = Path(cls.tmp.name); (d / 'stubs.c').write_text(STUBS); (d / 'main.c').write_text(MAIN)
        cls.exe = d / 'ss'
        subprocess.run(['cc', '-std=gnu11', '-O1', '-w', '-Dst_mtimespec=st_mtim', '-I', str(NX / 'android/compat/include'), '-I', str(NX / 'host'), '-o', str(cls.exe),
                        str(NX / 'host/save_sync.c'), str(NX / 'android/compat/sha256.c'), str(d / 'stubs.c'), str(d / 'main.c'), '-lpthread'], check=True)

    @classmethod
    def tearDownClass(cls): cls.tmp.cleanup()

    def run_ss(self, env, *args):
        r = subprocess.run([str(self.exe), *args], env=env, capture_output=True, text=True, timeout=60)
        return r.returncode, r.stdout + r.stderr

    def test_the_engine_takes_the_handed_over_save_once(self):
        with tempfile.TemporaryDirectory() as t:
            t = Path(t); env = dict(os.environ, DM_SAVE_SYNC=str(t / 'cloud'))
            a, b = t / 'A/save', t / 'B/save'; a.mkdir(parents=True); b.mkdir(parents=True)
            (a / 'game.sav').write_text('old app progress'); (b / 'game.sav').write_text('engine v1')
            os.utime(b / 'game.sav', (1_700_000_000, 1_700_000_000))
            self.assertEqual(self.run_ss(env, 'mount', str(b), ID)[0], 0)              # the engine in step with the folder
            (b / 'game.sav').write_text('engine v2, newer, not sent')                  # and played on without sending it
            rc, out = self.run_ss(env, 'export', str(a), ID)
            self.assertEqual(rc, 0, out); self.assertIn('export: handed over', out)
            self.assertRegex((t / 'cloud' / ID / 'take.txt').read_text(), r'take\t[0-9a-f]{32}\t\S+')
            rc, out = self.run_ss(env, 'mount', str(b), ID)
            self.assertIn('took the save handed over', out)
            self.assertEqual((b / 'game.sav').read_text(), 'old app progress')
            kept = [p for p in (t / 'B/save-backups').iterdir() if p.name.endswith(' local')]
            self.assertEqual(len(kept), 1); self.assertEqual((kept[0] / 'save/game.sav').read_text(), 'engine v2, newer, not sent')
            (b / 'game.sav').write_text('engine v3'); os.utime(b / 'game.sav', (1_900_000_000, 1_900_000_000))   # once taken, the engine's own saves go out again
            rc, out = self.run_ss(env, 'mount', str(b), ID)
            self.assertNotIn('took the save', out); self.assertIn('sent:', out)
            self.assertEqual((b / 'game.sav').read_text(), 'engine v3')

    def test_nothing_to_hand_over(self):
        with tempfile.TemporaryDirectory() as t:
            t = Path(t); env = dict(os.environ, DM_SAVE_SYNC=str(t / 'cloud')); (t / 'A/save').mkdir(parents=True)
            rc, out = self.run_ss(env, 'export', str(t / 'A/save'), ID)
            self.assertIn('export 0', out); self.assertFalse((t / 'cloud' / ID / 'take.txt').exists())


class SameHandOverInBothEngines(unittest.TestCase):
    def test_bananative_has_the_same_code(self):
        def part(p):
            s = p.read_text()
            take = s[s.index('static int read_take('):s.index('// A backup this file made for')]
            export = s[s.index('int save_sync_export('):s.index('// ---------- the host:')] if '// ---------- the host:' in s[s.index('int save_sync_export('):] else s[s.index('int save_sync_export('):]
            mount = s[s.index('    char token[64];'):s.index('    if (got != -2) switch')]
            return take, export.split('\n}\n')[0], mount
        self.assertEqual(part(REPO / 'nxnative/dm/host/save_sync.c'), part(REPO / 'bananative/dm/host/save_sync.c'))


if __name__ == '__main__':
    unittest.main()
