# The narrowed barriers between compute dispatches (nxnative/dm/android/gfx_vk_gbar.c, DM_VK_BAR_NARROW): compiled with mocked Vulkan entry points, the sequences the replay
# makes give the barriers they must: compute-to-compute only between two dispatches with nothing between them, a full barrier before any other command, at the command buffer's
# end and for another command buffer, and a game barrier between two dispatches is the owed one.
import shutil, subprocess, tempfile, unittest
from pathlib import Path

from porter.toolchain import find_ndk

SRC = Path(__file__).resolve().parents[1] / 'nxnative/dm/android/gfx_vk_gbar.c'


class NarrowBarriers(unittest.TestCase):
    @unittest.skipUnless(shutil.which('cc'), 'a host C compiler is required')
    def test_sequences(self):
        ndk, _ = find_ndk()
        headers = next(ndk.glob('toolchains/llvm/prebuilt/*/sysroot/usr/include'), None) if ndk else None
        if not headers: self.skipTest('NDK Vulkan headers are required')
        src = SRC.read_text(encoding='utf-8')
        body = src[src.index('static int g_on;'):]
        fns = ['vkCmdPipelineBarrier', 'vkCmdCopyBuffer', 'vkCmdCopyBufferToImage', 'vkCmdCopyImageToBuffer', 'vkCmdCopyImage', 'vkCmdBlitImage', 'vkCmdFillBuffer',
               'vkCmdUpdateBuffer', 'vkCmdClearColorImage', 'vkCmdClearDepthStencilImage', 'vkCmdResolveImage', 'vkCmdBeginRenderPass', 'vkCmdBeginRendering', 'vkCmdDraw',
               'vkCmdDrawIndexed', 'vkCmdDrawIndirect', 'vkCmdDrawIndexedIndirect', 'vkCmdDispatch', 'vkCmdDispatchIndirect', 'vkCmdResetQueryPool', 'vkCmdWriteTimestamp', 'vkEndCommandBuffer']
        harness = r'''
#define VK_NO_PROTOTYPES
#include <vulkan/vulkan_core.h>
#include <assert.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#define VKLOG(...) ((void)0)
static char trace[4096];
static void put(const char *s) { strcat(trace, s); strcat(trace, " "); }
static VKAPI_ATTR void VKAPI_CALL m_barrier(VkCommandBuffer cb, VkPipelineStageFlags s, VkPipelineStageFlags d, VkDependencyFlags f, uint32_t nm, const VkMemoryBarrier *m,
                                            uint32_t nb, const VkBufferMemoryBarrier *b, uint32_t ni, const VkImageMemoryBarrier *im) {
    (void)f; (void)nm; (void)nb; (void)b; (void)ni; (void)im; (void)m;
    char t[64]; snprintf(t, sizeof t, "%s:%s", s == VK_PIPELINE_STAGE_ALL_COMMANDS_BIT && d == VK_PIPELINE_STAGE_ALL_COMMANDS_BIT ? "full" : s == VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT ? "narrow" : "other",
                       cb == (VkCommandBuffer)(uintptr_t)1 ? "a" : "b"); put(t);
}
static VKAPI_ATTR void VKAPI_CALL m_dispatch(VkCommandBuffer cb, uint32_t x, uint32_t y, uint32_t z) { (void)cb; (void)x; (void)y; (void)z; put("D"); }
static VKAPI_ATTR void VKAPI_CALL m_copy(VkCommandBuffer cb, VkBuffer s, VkBuffer d, uint32_t n, const VkBufferCopy *r) { (void)cb; (void)s; (void)d; (void)n; (void)r; put("copy"); }
static VKAPI_ATTR VkResult VKAPI_CALL m_end(VkCommandBuffer cb) { (void)cb; put("end"); return VK_SUCCESS; }
DECLS
void vk_img_barrier_all(VkCommandBuffer cb) {
    VkMemoryBarrier b = {VK_STRUCTURE_TYPE_MEMORY_BARRIER, NULL, VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT, VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT};
    vkCmdPipelineBarrier(cb, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, 1, &b, 0, NULL, 0, NULL);
}
BODY
static VkCommandBuffer A = (VkCommandBuffer)(uintptr_t)1, B = (VkCommandBuffer)(uintptr_t)2;
static void disp(VkCommandBuffer cb) { vk_gbar_before_dispatch(cb); vkCmdDispatch(cb, 1, 1, 1); vk_gbar_after_dispatch(cb); }
static void game(VkCommandBuffer cb) { if (!vk_gbar_game(cb)) vk_img_barrier_all(cb); }
static void check(const char *want) { if (strcmp(trace, want)) { fprintf(stderr, "got  [%s]\nwant [%s]\n", trace, want); abort(); } trace[0] = 0; }
int main(int argc, char **argv) {
    (void)argv;
    INIT
    if (argc > 1) {   // off: every dispatch between two full barriers, as before
        disp(A); game(A); disp(A); vkEndCommandBuffer(A);
        check("full:a D full:a full:a full:a D full:a end "); return 0;
    }
    setenv("DM_VK_BAR_NARROW", "1", 1); vk_gbar_init(); assert(vk_gbar_on());
    disp(A); disp(A); vkEndCommandBuffer(A);
    check("full:a D narrow:a D full:a end ");
    disp(A); game(A); disp(A); vkEndCommandBuffer(A);   // a game barrier between two dispatches: the owed one
    check("full:a D narrow:a D full:a end ");
    disp(A); vkCmdCopyBuffer(A, 0, 0, 0, NULL); disp(A); vkEndCommandBuffer(A);   // another command first takes the owed barrier in full
    check("full:a D full:a copy full:a D full:a end ");
    disp(A); vkCmdCopyBuffer(B, 0, 0, 0, NULL); disp(A); vkEndCommandBuffer(A);   // a command into another command buffer passes through
    check("full:a D copy narrow:a D full:a end ");
    disp(A); disp(B); vkEndCommandBuffer(B); vkEndCommandBuffer(A);   // a dispatch into another command buffer takes a full barrier there, and the first one's owed barrier is recorded in it
    check("full:a D full:b D full:a full:b end end ");
    game(A); check("full:a ");   // a game barrier with nothing owed: in full
    uint64_t n, s, a; vk_gbar_stats(&n, &s, &a); assert(n == 3 && a == 1);
    return 0;
}
'''
        decls = ''.join(f'PFN_{f} {f};\n' for f in fns)
        mocks = {'vkCmdPipelineBarrier': 'm_barrier', 'vkCmdDispatch': 'm_dispatch', 'vkCmdCopyBuffer': 'm_copy', 'vkEndCommandBuffer': 'm_end'}
        init = ' '.join(f'{f} = (PFN_{f}){mocks.get(f, "(void *)1")};' for f in fns)
        harness = harness.replace('DECLS', decls).replace('BODY', body).replace('INIT', init)
        with tempfile.TemporaryDirectory(prefix='nx2a-gbar-') as tmp:
            c, exe = Path(tmp) / 'check.c', Path(tmp) / 'check'
            c.write_text(harness)
            subprocess.run(['cc', '-std=gnu11', '-Wall', '-Wextra', '-Werror', '-Wno-unused-parameter', '-Wno-cast-function-type', '-idirafter', str(headers), str(c), '-o', str(exe)], check=True)
            subprocess.run([str(exe)], check=True, timeout=15)
            subprocess.run([str(exe), 'off'], check=True, timeout=15)

    def test_wired_into_the_replay(self):
        ex = (SRC.parent / 'gfx_vk_exec.c').read_text(encoding='utf-8')
        self.assertIn('vk_gbar_before_dispatch(cb);', ex); self.assertIn('vk_gbar_after_dispatch(cb);', ex); self.assertIn('if (!vk_gbar_game(vk_cmd())) vk_img_barrier_all(vk_cmd());', ex)
        self.assertIn('vk_gbar_init();', (SRC.parent / 'gfx_vk_core.c').read_text(encoding='utf-8'))
        self.assertIn('env\tDM_VK_BAR_NARROW=1', (SRC.parents[1] / 'games/0400A3A01EE4C000/android.tsv').read_text(encoding='utf-8'))


if __name__ == '__main__':
    unittest.main()
