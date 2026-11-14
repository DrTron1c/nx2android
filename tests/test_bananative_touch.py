# The Switch 2 engine's on-screen gamepad (bananative/dm/host/android/input_map.c) with nx2android's themed controls: NXT_STYLE_NX2A must drive them as nx2android's
# own engine does (nxnative/dm/android/front_android.c touch_event, overlay_layout.h ctl_at): a stick at full tilt at its ring with no dead zone, a control the rounded
# box it is drawn as with its reach, a touch between two the one it is deeper into, the spot between two face buttons pressing both, the D-pad's quarter dead middle and
# eight sectors, any number of fingers on one control, and floating sticks. Banana Native's own rules (style 0) stay as they were: its own test, t_android_input.c.
import shutil, subprocess, tempfile, unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
HOST = REPO / 'bananative/dm/host'

HARNESS = r'''
#include "android/input_map.c"
#include <stdio.h>
static int fails;
#define CHECK(c, ...) do { if (!(c)) { fails++; printf("FAIL line %d: ", __LINE__); printf(__VA_ARGS__); printf("\n"); } } while (0)
#define A 1.6f   /* the screen's aspect: 1600 x 1000 */
static nxhost_touch_control ctl(int kind, uint64_t b, int stick, float x, float y, float hw, float hh, int boxy) {
    nxhost_touch_control c; memset(&c, 0, sizeof c);
    float m = hw < hh ? hw : hh;
    c.kind = kind; c.buttons = b; c.stick = stick; c.x = x; c.y = y; c.r = hw > hh ? hw : hh; c.hw = hw; c.hh = hh; c.corner = boxy ? m * 0.35f : m;
    c.reach = kind == NXT_BUTTON ? fmaxf(m * 0.25f, 0.045f - m) : m * 0.5f;   /* TouchControls.sendThemed, as overlay_layout.h ov_reach */
    return c;
}
enum { LS, DP, BA, BB, BX, BY, ZL, RSK, N };
static nxhost_touch_control L[N];
static void setup(nxin_touch *t, int style) {
    nxin_touch_init(t); t->sw = 1600; t->sh = 1000;
    L[LS] = ctl(NXT_STICK, 0, 0, 0.20f, 0.70f, 0.12f, 0.12f, 0);
    L[DP] = ctl(NXT_DPAD, DM_BTN_UP | DM_BTN_DOWN | DM_BTN_LEFT | DM_BTN_RIGHT, 0, 0.20f, 0.30f, 0.11f, 0.11f, 0);
    L[BA] = ctl(NXT_BUTTON, DM_BTN_A, 0, 0.90f, 0.50f, 0.05f, 0.05f, 0);
    L[BB] = ctl(NXT_BUTTON, DM_BTN_B, 0, 0.85f, 0.60f, 0.05f, 0.05f, 0);
    L[BX] = ctl(NXT_BUTTON, DM_BTN_X, 0, 0.85f, 0.40f, 0.05f, 0.05f, 0);
    L[BY] = ctl(NXT_BUTTON, DM_BTN_Y, 0, 0.80f, 0.50f, 0.05f, 0.05f, 0);
    L[ZL] = ctl(NXT_BUTTON, DM_BTN_ZL, 0, 0.10f, 0.10f, 0.083f, 0.066f, 1);
    L[RSK] = ctl(NXT_STICK, 0, 1, 0.75f, 0.85f, 0.10f, 0.10f, 0);
    int kept = 0; nxin_touch_set_style_layout(t, style, L, N, &kept);
    CHECK(kept == N && t->style == style, "layout kept");
}
static void pad(const nxin_touch *t, uint64_t *b, int32_t *lx, int32_t *ly, int32_t *rx, int32_t *ry) { nxin_touch_pad(t, b, lx, ly, rx, ry); }
static float sx(int c, float k) { return L[c].x + k * L[c].hw / A; }   /* k half widths right of a control's centre, normalized */
int main(void) {
    nxin_touch t; uint64_t b; int32_t lx, ly, rx, ry; nxin_finger f[16];
    setup(&t, NXT_STYLE_NX2A);
    // the stick: no dead zone, full tilt at the ring (its half width), y up, the knob following to the ring
    CHECK(nxin_touch_event(&t, AM_DOWN, 1, sx(LS, 0.05f), L[LS].y) == 1, "the stick takes the touch");
    pad(&t, &b, &lx, &ly, &rx, &ry); CHECK(lx > 1500 && lx < 1800 && ly == 0 && b == 0, "a twentieth of the way out tilts it a twentieth (no dead zone): %d", lx);
    nxin_touch_event(&t, AM_MOVE, 1, sx(LS, 0.5f), L[LS].y);
    pad(&t, &b, &lx, &ly, &rx, &ry); CHECK(abs(lx - DM_STICK_MAX / 2) <= 2, "half way to the ring is half tilt: %d", lx);
    nxin_touch_event(&t, AM_MOVE, 1, sx(LS, 2.0f), L[LS].y);
    pad(&t, &b, &lx, &ly, &rx, &ry); CHECK(lx == DM_STICK_MAX && ly == 0, "past the ring: full tilt %d %d", lx, ly);
    nxin_touch_event(&t, AM_MOVE, 1, L[LS].x, L[LS].y - 0.06f);
    pad(&t, &b, &lx, &ly, &rx, &ry); CHECK(lx == 0 && abs(ly - DM_STICK_MAX / 2) <= 2, "up is +y: %d", ly);
    CHECK(t.pressed[LS] == 1 && b == 0, "a held stick shows held and presses no button");
    nxin_touch_event(&t, AM_MOVE, 1, sx(LS, 3.0f), L[LS].y + 3.0f * L[LS].hw);
    CHECK(fabsf(t.sx[0] - 0.7071f) < 0.001f && fabsf(t.sy[0] + 0.7071f) < 0.001f, "a diagonal past the ring stays on it: %f %f", t.sx[0], t.sy[0]);
    nxin_touch_event(&t, AM_UP, 1, 0, 0);
    pad(&t, &b, &lx, &ly, &rx, &ry); CHECK(lx == 0 && ly == 0 && t.pressed[LS] == 0, "let go: centred");
    // reach: a stick or the D-pad takes touches half its size outside its edge
    CHECK(nxin_touch_event(&t, AM_DOWN, 2, L[LS].x, L[LS].y - 1.4f * L[LS].hw) == 1, "1.4 half widths above the stick still takes it");
    pad(&t, &b, &lx, &ly, &rx, &ry); CHECK(ly == DM_STICK_MAX, "and tilts it all the way: %d", ly);
    nxin_touch_event(&t, AM_UP, 2, 0, 0);
    CHECK(nxin_touch_event(&t, AM_DOWN, 3, L[LS].x, L[LS].y - 1.6f * L[LS].hw) == 2, "1.6 half widths above is the touchscreen");
    nxin_touch_event(&t, AM_UP, 3, 0, 0);
    // a shoulder button is the rounded box it is drawn as: its corner takes the touch (a circle as wide would not)
    CHECK(nxin_touch_event(&t, AM_DOWN, 4, L[ZL].x + 0.95f * L[ZL].hw / A, L[ZL].y + 0.9f * L[ZL].hh) == 1, "ZL's corner");
    pad(&t, &b, &lx, &ly, &rx, &ry); CHECK(b == DM_BTN_ZL, "ZL held");
    // two fingers on one button: held until the last lets go
    CHECK(nxin_touch_event(&t, AM_POINTER_DOWN, 5, L[ZL].x, L[ZL].y) == 1, "a second finger on ZL");
    nxin_touch_event(&t, AM_POINTER_UP, 4, 0, 0);
    pad(&t, &b, &lx, &ly, &rx, &ry); CHECK(b == DM_BTN_ZL, "still held by the other finger");
    nxin_touch_event(&t, AM_UP, 5, 0, 0);
    pad(&t, &b, &lx, &ly, &rx, &ry); CHECK(b == 0, "both up");
    // the D-pad: a quarter of its half width dead in the middle, then eight equal sectors
    nxin_touch_event(&t, AM_DOWN, 6, sx(DP, 0.2f), L[DP].y);
    pad(&t, &b, &lx, &ly, &rx, &ry); CHECK(b == 0, "the middle is dead");
    nxin_touch_event(&t, AM_MOVE, 6, sx(DP, 0.3f), L[DP].y);
    pad(&t, &b, &lx, &ly, &rx, &ry); CHECK(b == DM_BTN_RIGHT, "right");
    float r = 0.6f * L[DP].hw;
    nxin_touch_event(&t, AM_MOVE, 6, L[DP].x + r * cosf(0.35f) / A, L[DP].y + r * sinf(0.35f));   // 20 degrees below right
    pad(&t, &b, &lx, &ly, &rx, &ry); CHECK(b == DM_BTN_RIGHT, "20 degrees is still right");
    nxin_touch_event(&t, AM_MOVE, 6, L[DP].x + r * cosf(0.42f) / A, L[DP].y + r * sinf(0.42f));   // 24 degrees
    pad(&t, &b, &lx, &ly, &rx, &ry); CHECK(b == (DM_BTN_RIGHT | DM_BTN_DOWN), "24 degrees is the diagonal");
    nxin_touch_event(&t, AM_MOVE, 6, L[DP].x - r / A, L[DP].y);
    pad(&t, &b, &lx, &ly, &rx, &ry); CHECK(b == DM_BTN_LEFT, "left");
    nxin_touch_event(&t, AM_UP, 6, 0, 0);
    // face buttons: the spot between two adjacent ones presses both; a finger on one that slides there too
    float mx = (L[BA].x + L[BB].x) / 2, my = (L[BA].y + L[BB].y) / 2;
    CHECK(nxin_touch_event(&t, AM_DOWN, 7, mx, my) == 1, "between A and B");
    pad(&t, &b, &lx, &ly, &rx, &ry); CHECK(b == (DM_BTN_A | DM_BTN_B), "A and B");
    nxin_touch_event(&t, AM_UP, 7, 0, 0);
    nxin_touch_event(&t, AM_DOWN, 8, L[BA].x, L[BA].y);
    pad(&t, &b, &lx, &ly, &rx, &ry); CHECK(b == DM_BTN_A, "A");
    nxin_touch_event(&t, AM_MOVE, 8, (L[BA].x + L[BX].x) / 2, (L[BA].y + L[BX].y) / 2);
    pad(&t, &b, &lx, &ly, &rx, &ry); CHECK(b == (DM_BTN_A | DM_BTN_X), "slid between A and X");
    nxin_touch_event(&t, AM_MOVE, 8, 0.5f, 0.5f);
    pad(&t, &b, &lx, &ly, &rx, &ry); CHECK(b == DM_BTN_A, "a finger keeps its button wherever it slides");
    nxin_touch_event(&t, AM_UP, 8, 0, 0);
    nxin_touch_event(&t, AM_DOWN, 9, (L[BA].x + L[BY].x) / 2, L[BA].y);   // A and Y are not adjacent: no pair
    pad(&t, &b, &lx, &ly, &rx, &ry); CHECK(b == 0 && nxin_touch_fingers(&t, f, 16) == 1, "between A and Y is the touchscreen");
    nxin_touch_event(&t, AM_CANCEL, 0, 0, 0);
    // a cancel lets go of everything
    nxin_touch_event(&t, AM_DOWN, 10, L[BB].x, L[BB].y); nxin_touch_event(&t, AM_POINTER_DOWN, 11, sx(RSK, 0.5f), L[RSK].y);
    pad(&t, &b, &lx, &ly, &rx, &ry); CHECK(b == DM_BTN_B && abs(rx - DM_STICK_MAX / 2) <= 2, "B and the right stick");
    nxin_touch_event(&t, AM_CANCEL, 0, 0, 0);
    pad(&t, &b, &lx, &ly, &rx, &ry); CHECK(b == 0 && rx == 0 && t.np == 0, "cancelled");
    // hidden controls: every touch is the game's
    t.enabled = 0;
    CHECK(nxin_touch_event(&t, AM_DOWN, 12, L[BA].x, L[BA].y) == 2, "hidden: A's place is the touchscreen");
    nxin_touch_event(&t, AM_CANCEL, 0, 0, 0); t.enabled = 1;

    // floating sticks: a touch on no control takes the stick of its half of the screen, centred where it lands
    setup(&t, NXT_STYLE_NX2A | NXT_STYLE_FLOAT);
    float c[4];
    CHECK(nxin_touch_floating(&t, c) && c[0] == -1 && c[2] == -1, "floating, no finger yet");
    CHECK(nxin_touch_event(&t, AM_DOWN, 1, 0.40f, 0.60f) == 1, "the left half's open space takes the left stick");
    pad(&t, &b, &lx, &ly, &rx, &ry); CHECK(lx == 0 && ly == 0, "centred under the finger");
    nxin_touch_floating(&t, c); CHECK(fabsf(c[0] - 0.40f) < 1e-5f && fabsf(c[1] - 0.60f) < 1e-5f, "its centre: %f %f", c[0], c[1]);
    nxin_touch_event(&t, AM_MOVE, 1, 0.40f + 0.5f * L[LS].hw / A, 0.60f);
    pad(&t, &b, &lx, &ly, &rx, &ry); CHECK(abs(lx - DM_STICK_MAX / 2) <= 2, "half tilt from where it landed: %d", lx);
    CHECK(nxin_touch_event(&t, AM_POINTER_DOWN, 2, 0.45f, 0.45f) == 2, "a second finger on that half is the touchscreen");
    CHECK(nxin_touch_event(&t, AM_POINTER_DOWN, 3, L[LS].x, L[LS].y) == 2, "the stick's own place does not take it while it floats");
    CHECK(nxin_touch_event(&t, AM_POINTER_DOWN, 4, 0.99f, 0.99f) == 1, "the right half takes the right stick");
    nxin_touch_floating(&t, c); CHECK(fabsf(c[2] - (1 - L[RSK].hw / A)) < 1e-4f && fabsf(c[3] - (1 - L[RSK].hw)) < 1e-4f, "kept its half width inside the screen: %f %f", c[2], c[3]);
    CHECK(nxin_touch_event(&t, AM_POINTER_DOWN, 5, L[BA].x, L[BA].y) == 1, "the controls still take their touches");
    nxin_touch_event(&t, AM_POINTER_UP, 1, 0, 0);
    nxin_touch_floating(&t, c); CHECK(c[0] == -1 && c[2] >= 0, "the left stick's finger gone");
    pad(&t, &b, &lx, &ly, &rx, &ry); CHECK(lx == 0 && b == DM_BTN_A, "the left stick centred, A still held");
    nxin_touch_event(&t, AM_CANCEL, 0, 0, 0);

    // Banana Native's own rules are untouched by the new fields: style 0 keeps its dead zone and full tilt at 0.8 of the radius
    setup(&t, 0);
    nxin_touch_event(&t, AM_DOWN, 1, L[LS].x + 0.05f * L[LS].r / A, L[LS].y);
    pad(&t, &b, &lx, &ly, &rx, &ry); CHECK(lx == 0, "style 0: the dead zone");
    nxin_touch_event(&t, AM_MOVE, 1, L[LS].x + 0.4f * L[LS].r / A, L[LS].y);
    pad(&t, &b, &lx, &ly, &rx, &ry); CHECK(abs(lx - DM_STICK_MAX / 2) <= 2, "style 0: half tilt at 0.4 of the radius: %d", lx);
    CHECK(nxin_touch_event(&t, AM_POINTER_DOWN, 2, L[LS].x, L[LS].y) == 0, "style 0: one finger a control");
    if (!fails) printf("nx2a touch: ok\n");
    return fails != 0;
}
'''


@unittest.skipUnless(shutil.which('cc'), 'a host C compiler is required')
class Touch(unittest.TestCase):
    def build_run(self, name, source, *flags):
        with tempfile.TemporaryDirectory(prefix='nx2a-touch-') as tmp:
            exe = Path(tmp) / name
            if source is None:
                source = Path(tmp) / (name + '.c'); source.write_text(HARNESS)
            subprocess.run(['cc', '-std=gnu11', '-O1', '-Wall', '-Wno-unused-function', '-Wno-misleading-indentation', *flags, '-I', str(HOST),
                            str(source), '-lm', '-o', str(exe)], check=True)
            r = subprocess.run([str(exe)], capture_output=True, text=True, timeout=30)
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)

    def test_nx2android_rules(self):
        self.build_run('nx2a_touch', None)

    def test_banana_natives_own_test_still_passes(self):
        self.build_run('t_android_input', HOST / 'tests/t_android_input.c')


if __name__ == '__main__':
    unittest.main()
