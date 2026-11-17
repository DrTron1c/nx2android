# Surround sound (nx2android): Pokopia's sound engine is set to mix 5.1 (dm/host/android/pkp_sound.c, gate 62 on main 2.0.1), the host sends a six-channel output to
# Android as a 5.1 stream that the system's mixer and its effects (a tablet's Dolby Atmos) take, the stereo downmix stays the fallback, and the launcher turns it on for a
# start and says what the tablet does with it. Source checks, as the sleep mod's (tests/test_pkp_sleep.py); the gate's word is checked against the disassembly's.
import re, unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
B = REPO / 'bananative'
HOST = B / 'dm/host'
JAVA = B / 'android/app/src/main/java/dev/nxnative/android'


class Game(unittest.TestCase):
    def test_gate(self):
        p = (B / 'dm/games/0400A3A01EE4C000/patches.py').read_text()
        # ldr w21, [x20, #0x678]: the first instruction after the sound settings parse succeeded (position independent, as a gate's must be)
        self.assertIn("(0x115ccdc, 62, 'b9467a95',", p)
        self.assertIn('OBS_PKP_SOUND = 62,', (HOST / 'dm.h').read_text())
        self.assertIn('OBS_MAX = 63 };', (HOST / 'dm.h').read_text())
        c = (HOST / 'android/pkp_sound.c').read_text()
        self.assertIn('#define PKP_BUILD "76a489f3bd3642fa5e18bd83d32c98ba"', c)
        self.assertIn('enum { PARAMS = 0x80, P_CHANNELS = 0x20, SURROUND = 6 };', c)   # the engine's parameters at x29 - 0x80, +0x20 the channel count
        self.assertIn('if (dm_audio_surround() && was <= 2) *ch = SURROUND;', c)        # only when the host plays 5.1; a count the game set over 2 is left alone
        self.assertIn('gate_obs_set(OBS_PKP_SOUND, sound_gate);', c)


class Host(unittest.TestCase):
    def test_stream(self):
        a = (HOST / 'hle_audio.c').read_text()
        self.assertIn('static int device_channels(const aout *a) { return a->ch == 6 && !dm_audio_surround() ? 2 : a->ch; }', a)
        self.assertIn('if (a->ch != 6 || dev == 6) { pull(a, dst, bytes); return; }', a)   # six channels straight through when the stream is 5.1
        self.assertIn('if (o->ch == 6) AAudioStreamBuilder_setChannelMask(b, AAUDIO_CHANNEL_5POINT1);', a)
        self.assertIn('o->ch == 6 ? AAUDIO_PERFORMANCE_MODE_NONE : AAUDIO_PERFORMANCE_MODE_LOW_LATENCY', a)   # the system mixer, where the effects are
        self.assertIn('o->ch == 6 ? AAUDIO_CONTENT_TYPE_MOVIE : AAUDIO_CONTENT_TYPE_MUSIC', a)
        self.assertIn('o->ch = 2; r = aa_try(o, RATE);', a)   # no 5.1 stream: the downmix
        self.assertIn('o->ch = device_channels(o->a);   // a reopen (a new route) tries 5.1 again', a)
        self.assertIn('int dm_audio_surround(void) { return 0; }', a)   # Apple: unchanged
        self.assertIn('getenv("DM_AUDIO_SURROUND")', a)


class Launcher(unittest.TestCase):
    def test_setting(self):
        self.assertIn('Surround.env(this, env);', (JAVA / 'GameActivity.java').read_text())
        s = (JAVA / 'Surround.java').read_text()
        self.assertIn('return Prefs.of(c).getBoolean(PREF, true);', s)   # on by default
        self.assertIn('env.add("DM_AUDIO_SURROUND=1")', s)
        self.assertIn('AudioDeviceInfo.TYPE_BUILTIN_SPEAKER', s)
        self.assertIn('AudioEffect.queryEffects()', s)
        p = (JAVA / 'SidePanel.java').read_text()
        self.assertIn('soundRows();', p)
        self.assertIn('Surround sound (5.1, all the tablet\'s speakers)', p)
        n = (B / 'android/app/src/main/cpp/nxlaunch.c').read_text()
        self.assertIn('#pragma weak nxhost_audio_info', n)
        self.assertIn('Java_dev_nxnative_android_Native_audioInfo', n)
        self.assertIn('static native boolean audioInfo(int[] out);', (JAVA / 'Native.java').read_text())
        self.assertIn('DM_AUDIO_SURROUND\tplayer\t', (B / 'docs/env_switches.tsv').read_text())


if __name__ == '__main__':
    unittest.main()
