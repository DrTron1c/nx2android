# Android opens the game by looking up ANativeActivity_onCreate in libdmhost.so. The host is built with -fvisibility=hidden and still links without the attribute,
# and the game then closes the moment it opens, with no log at all.
import re, unittest
from pathlib import Path

FRONT = Path(__file__).resolve().parent.parent / 'nxnative/dm/android/front_android.c'


class Entry(unittest.TestCase):
    def test_entry_is_exported(self):
        self.assertRegex(FRONT.read_text(encoding='utf-8'), re.compile(r'^__attribute__\(\(visibility\("default"\)\)\) void ANativeActivity_onCreate\(', re.M))


if __name__ == '__main__': unittest.main()
