# The GPU saver (variable rate shading, nxnative/dm/android/gfx_vk_exec.c vrs_pass) and Texture filtering (gfx_vk_tex.c): start-up settings that reach the device,
# the pipelines and the samplers, and their pause menu rows.
import re, unittest
from pathlib import Path

AND = Path(__file__).resolve().parents[1] / 'nxnative' / 'dm' / 'android'


class GpuSaver(unittest.TestCase):
    def test_device_and_pipelines(self):
        core = (AND / 'gfx_vk_core.c').read_text(encoding='utf-8')
        self.assertIn('getenv("DM_VK_VRS")', core)
        self.assertIn('on[non++] = "VK_KHR_fragment_shading_rate"; V.has_vrs = 1;', core)   # the extension only with the feature
        self.assertIn('fvrs.primitiveFragmentShadingRate = 0; fvrs.attachmentFragmentShadingRate = 0;', core)
        exe = (AND / 'gfx_vk_exec.c').read_text(encoding='utf-8')
        self.assertIn('VK_DYNAMIC_STATE_FRAGMENT_SHADING_RATE_KHR};', exe)   # last in the list: left out without the saver
        self.assertIn('sizeof dyn / sizeof *dyn - (V.has_vrs ? 0 : 1)', exe)
        self.assertEqual(exe.count('vrs_pass('), 2)   # its definition, and every pass begin
        self.assertIn('if (vk_dq_active()) vk_dq_drain();', exe)
        self.assertIn('int vk_dq_active(void) { return g_ring != NULL; }', (AND / 'gfx_vk_dq.c').read_text(encoding='utf-8'))

    def test_texture_filtering(self):
        tex = (AND / 'gfx_vk_tex.c').read_text(encoding='utf-8')
        self.assertIn('getenv("DM_VK_ANISO")', tex)
        self.assertIn('ci.anisotropyEnable = an > 1.0f;', tex)

    def test_menu_and_settings(self):
        lay = (AND / 'overlay_layout.h').read_text(encoding='utf-8')
        self.assertIn('#define OVM_PER_TAB 7', lay)
        tabs = re.search(r'OVM_TAB_ITEMS\[OVM_TABS\]\[OVM_PER_TAB\] = \{(.*?)\};', lay).group(1)
        for row in re.findall(r'\{([^{}]*)\}', tabs): self.assertEqual(len(row.split(',')), 7, row)
        self.assertIn('OVM_ANISO}', tabs); self.assertIn('OVM_VRS}', tabs)
        front = (AND / 'front_android.c').read_text(encoding='utf-8')
        for k in ('"vrs=%d"', '"aniso=%d"', 'setenv("DM_VK_VRS"', 'setenv("DM_VK_ANISO"', 'item == OVM_VRS ? 3'): self.assertIn(k, front)


if __name__ == '__main__':
    unittest.main()
