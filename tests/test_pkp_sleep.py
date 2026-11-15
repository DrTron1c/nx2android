# Pokémon Pokopia's sleep mod in the Switch 2 engine (bananative/dm/host/android/pkp_sleep.c, SleepMod.java): getting into a bed asks how long to sleep, the game saves,
# its clock (only the game's) moves on, and the Pokédex shows the clock. Checked here: the frame gate's row and number, the game addresses (Recompia's, the same build), the
# clock skip in the host's user clock, the input hold, the launcher's interface end to end (Java → JNI → host), the menu's choices and the bundled font. With the game's
# main in .gamedump/PokemonPokopia/ (never committed), the gate's instruction and the call after it are checked against the game's code too.
import re, struct, subprocess, shutil, unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
BN = REPO / 'bananative'
HOST = BN / 'dm/host'
JAVA = BN / 'android/app/src/main/java/dev/nxnative/android'
GAME = BN / 'dm/games/0400A3A01EE4C000'
MAIN = REPO / '.gamedump/PokemonPokopia/main.text'   # the game's text, decompressed (never committed)


class Gate(unittest.TestCase):
    def test_row_and_number(self):
        rows = re.findall(r"\((0x[0-9a-f]+), (\d+), '([0-9a-f]{8})', '([^']*)'\)", (GAME / 'patches.py').read_text())
        self.assertIn(('0x0bfc558', '60', 'a902a3e8'), [r[:3] for r in rows])
        dm = (HOST / 'dm.h').read_text()
        self.assertIn('OBS_PKP_FRAME = 60,', dm)
        self.assertGreater(int(re.search(r'OBS_MAX = (\d+)', dm).group(1)), 60)
        self.assertIn('gate_obs_set(OBS_PKP_FRAME, frame_gate)', (HOST / 'android/pkp_sleep.c').read_text())
        self.assertIn('gate 60 at `+0xbfc558`', (BN / 'docs/agents/game-patches.md').read_text())

    @unittest.skipUnless(MAIN.is_file() and shutil.which('llvm-objdump') and shutil.which('llvm-objcopy'), "the game's main (.gamedump) and llvm-objdump")
    def test_the_games_code(self):
        t = MAIN.read_bytes()
        self.assertEqual(struct.unpack_from('<I', t, 0xbfc558)[0], 0xa902a3e8)   # stp x8, x8, [sp, #0x28]: the gate's own instruction, relocatable
        self.assertEqual(struct.unpack_from('<I', t, 0xbfc55c)[0], 0xd63f0120)   # blr x9: the call whose target the gate takes
        self.assertEqual(struct.unpack_from('<I', t, 0xbfc548)[0], 0xf9401529)   # ldr x9, [x9, #0x28]: x9 a vtable slot, final before the gate
        self.assertEqual(struct.unpack_from("<I", t, 0x27e5d8)[0], 0x79130a7c)   # strh w28, [x19, #0x984]: StartAction's store of the action id (+0x984 as read)


class GameState(unittest.TestCase):
    """The addresses Recompia found on this build (runtime/games/pkp/core/pkp_layout.h, ui/pkp_save_now.cpp at ea632e4), as pkp_sleep.c has them"""
    def test_recompias_layout(self):
        src = (HOST / 'android/pkp_sleep.c').read_text()
        want = {'M_PLAYER_MGR': 0x3561928, 'M_GAME_STATE': 0x3561710, 'M_SAVE_MGR': 0x35619d0, 'M_SAVE_ROOT': 0x3561930, 'F_SAVE_REQUEST': 0x241324,
                'F_SAVE_DONE': 0x2412b0, 'PM_PLAYER': 0x30, 'CH_ACTION': 0x18b8, 'AC_ID': 0x984, 'GS_SESSION': 0x90, 'GS_AUTOSAVE': 0x3bb5e0, 'GS_EVENT': 0xd0,
                'EV_BUSY': 0x6ea, 'SR_HOME': 0xa4}
        for k, v in want.items():
            self.assertRegex(src, rf'\b{k} = {v:#x}\b', k)
        self.assertIn('#define PKP_BUILD "76a489f3bd3642fa5e18bd83d32c98ba"', src)
        self.assertIn('uint32_t type = 5, kind = 2;', src)   # the player's own file, what Save and Quit writes
        self.assertIn('r[9] = (uint64_t)(uintptr_t)tick_call;', src)   # game functions run from the game's own call, never on the gate stack
        self.assertIn('process_vm_readv', src)


class Clock(unittest.TestCase):
    def test_skip_is_the_games_clock_only(self):
        t = (HOST / 'hle_time.c').read_text()
        self.assertIn('static void user_clock(int64_t *sec, int64_t *nsec) { user_clock_base(sec, nsec); *sec += dm_time_skip(); }', t)
        self.assertIn('getenv("DM_TIME_SKIP")', t)
        self.assertNotRegex(t, r'settimeofday|clock_settime')   # the phone's clock is never set
        self.assertIn('DM_TIME_SKIP\tplayer\t', (BN / 'docs/env_switches.tsv').read_text())
        j = (JAVA / 'SleepMod.java').read_text()
        self.assertIn('env.add("DM_TIME_SKIP=" + skip(c))', j)
        self.assertIn('SleepMod.env(this, id, env);', (JAVA / 'GameActivity.java').read_text())


class Input(unittest.TestCase):
    def test_hold(self):
        hid = (HOST / 'hle_hid.c').read_text()
        self.assertIn("if (!pl && atomic_load_explicit(&g_hold, memory_order_relaxed)) {", hid)
        self.assertIn("b = atomic_load(&g_hold_btn);", hid)
        self.assertIn('if (dm_input_held()) {', (HOST / 'hle_touch.c').read_text())
        s = (HOST / 'android/pkp_sleep.c').read_text()
        self.assertIn('dm_input_hold(1, DM_BTN_B, 0, 0, 0, 0)', s)   # Not now: B first


class Interface(unittest.TestCase):
    """Each new Native method reaches the host: Java → nxlaunch.c (a weak host symbol) → input_android.h / vk_android.h"""
    CALLS = {'clockNow': 'nxhost_clock_now', 'clockSkip': 'nxhost_clock_skip', 'pkpState': 'nxhost_pkp_state', 'pkpBeds': 'nxhost_pkp_beds',
             'pkpHold': 'nxhost_pkp_hold', 'pkpSeen': 'nxhost_pkp_seen', 'pkpSave': 'nxhost_pkp_save', 'watchFs': 'nxhost_watch_fs', 'watchFsAge': 'nxhost_watch_fs_age'}

    def test_end_to_end(self):
        java = (JAVA / 'Native.java').read_text()
        jni = (BN / 'android/app/src/main/cpp/nxlaunch.c').read_text()
        hdr = (HOST / 'android/input_android.h').read_text() + (BN / 'dm/gfx/vk_android/vk_android.h').read_text()
        impl = (HOST / 'android/pkp_sleep.c').read_text() + (BN / 'dm/gfx/vk_android/vka_draw.c').read_text()
        for m, c in self.CALLS.items():
            self.assertRegex(java, rf'static native \w+(\[\])? {m}\(', m)
            self.assertIn(f'Java_dev_nxnative_android_Native_{m}(', jni, m)
            self.assertIn(f'#pragma weak {c}\n', jni, c)
            self.assertRegex(hdr, rf'\b{c}\(', c)
            self.assertRegex(impl, rf'\b{c}\([^;]*\) \{{', c)


class Menu(unittest.TestCase):
    def test_choices_and_pokedex(self):
        j = (JAVA / 'SleepMod.java').read_text()
        self.assertIn('static final int[] HOURS = {6, 12, 18, 24};', j)
        self.assertIn('static final String[] CHOICES = {"6 hours", "12 hours", "18 hours", "24 hours", "Not now"};', j)
        seen = (GAME / 'shader_seen.tsv').read_text()
        for k in re.search(r'DEX_FS = \{([^}]*)\}', j).group(1).split(','):
            self.assertIn(k.strip()[2:-1], seen)   # the Pokédex's avatar pixel shaders, as the profile names them
        self.assertIn('hours * 1000L', j)   # one hour a second
        self.assertIn('sleepRows();', (JAVA / 'SidePanel.java').read_text())

    def test_font(self):
        fonts = BN / 'dm/third_party/fonts'
        for f in ('MPLUSRounded1c-Bold-subset.ttf', 'MPLUSRounded1c-ExtraBold-subset.ttf', 'OFL-MPLUSRounded1c.txt'):
            self.assertTrue((fonts / f).is_file(), f)
            self.assertIn(f"'{f}'", (BN / 'android/app/build.gradle').read_text())
        self.assertIn('SIL Open Font License, Version 1.1', (fonts / 'OFL-MPLUSRounded1c.txt').read_text())


if __name__ == '__main__':
    unittest.main()
