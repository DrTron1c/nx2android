# Use fast cores (nxnative/dm/android/perf_android.c, from BananaNative's f9b1433): the game's threads, named "0x<handle> <name>" by the host, are kept off the small
# cores every 5 s, and the replay thread on its DM_PIN_REPLAY cores again.
import re, unittest
from pathlib import Path

NX = Path(__file__).resolve().parents[1] / 'nxnative' / 'dm'


class FastCores(unittest.TestCase):
    def test_guest_threads_are_named_as_placed(self):
        thr = (NX / 'host/hle_thread.c').read_text(encoding='utf-8')
        self.assertIn('snprintf(hn, sizeof hn, "%#x %s", t->handle', thr)   # "%#x" writes the "0x" the placement looks for
        self.assertIn('pthread_setname_np("0x1001 main thread")', thr)
        perf = (NX / 'android/perf_android.c').read_text(encoding='utf-8')
        self.assertIn('!strncmp(name, "0x", 2)', perf)

    def test_runs_with_and_without_a_replay_pin(self):
        perf = (NX / 'android/perf_android.c').read_text(encoding='utf-8')
        body = re.search(r'void dm_perf_replay_thread\(void\) \{(.*?)\n\}', perf, re.S).group(1)
        self.assertEqual(body.count('dm_perf_fast_cores('), 2)   # without DM_PIN_REPLAY, and after it
        self.assertIn('getenv("DM_FAST_CORES")', perf)


if __name__ == '__main__':
    unittest.main()
