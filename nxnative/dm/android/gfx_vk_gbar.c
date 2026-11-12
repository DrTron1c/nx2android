// Android port: narrower barriers between compute dispatches (DM_VK_BAR_NARROW=1, start-up; Pokémon Pokopia's android.tsv sets it). The replay orders every dispatch with
// a full barrier before and after it (gfx_vk_exec.c dispatch), and a game barrier between two dispatches adds a third: ALL_COMMANDS to ALL_COMMANDS with every memory
// access, which on an Adreno flushes and invalidates the colour and depth caches too, three times between two dispatches that only need the first one's shader writes
// seen by the second. Banana Native narrowed the game's barriers between Pokopia's dispatches to compute-to-compute for less GPU time a frame (v0.2.9, vka_gbar.c); this
// is the same idea on this backend: the barrier after a dispatch is owed rather than recorded (vk_gbar_after_dispatch). The next dispatch takes a compute-to-compute
// barrier in its place (vk_gbar_before_dispatch), a game barrier meanwhile is the owed one (vk_gbar_game), and any other command into that command buffer (a copy, a
// fill, a clear, a blit, a pass's begin, a draw, a query, a barrier of its own, the command buffer's end) first records the owed full barrier (the wrappers below, over
// the device's entry points or gfx_vk_dq.c's). Commands into another command buffer, or from another thread, pass straight through.
#include "gfx_vk.h"
#include <stdatomic.h>
#include <stdlib.h>

static int g_on;
static _Atomic(VkCommandBuffer) g_tcb;   // the command buffer a full barrier is owed in (VK_NULL_HANDLE: none); written by the replay thread only
static uint64_t g_narrowed, g_settled, g_absorbed;   // dispatch pairs given the narrow barrier, owed barriers recorded in full, game barriers taken as the owed one

#define GB_FNS(X) X(vkCmdPipelineBarrier) X(vkCmdCopyBuffer) X(vkCmdCopyBufferToImage) X(vkCmdCopyImageToBuffer) X(vkCmdCopyImage) X(vkCmdBlitImage) X(vkCmdFillBuffer) \
    X(vkCmdUpdateBuffer) X(vkCmdClearColorImage) X(vkCmdClearDepthStencilImage) X(vkCmdResolveImage) X(vkCmdBeginRenderPass) X(vkCmdBeginRendering) X(vkCmdDraw) \
    X(vkCmdDrawIndexed) X(vkCmdDrawIndirect) X(vkCmdDrawIndexedIndirect) X(vkCmdDispatch) X(vkCmdDispatchIndirect) X(vkCmdResetQueryPool) X(vkCmdWriteTimestamp) X(vkEndCommandBuffer)
#define GB_NEXT(f) static PFN_##f n_##f;
GB_FNS(GB_NEXT)

static void full(VkCommandBuffer cb) {
    VkMemoryBarrier b = {VK_STRUCTURE_TYPE_MEMORY_BARRIER, NULL, VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT, VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT};
    n_vkCmdPipelineBarrier(cb, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, 1, &b, 0, NULL, 0, NULL);
}
static inline void settle(VkCommandBuffer cb) {   // the owed barrier, recorded in full before another command into its command buffer
    if (cb && atomic_load_explicit(&g_tcb, memory_order_relaxed) == cb) { atomic_store_explicit(&g_tcb, VK_NULL_HANDLE, memory_order_relaxed); full(cb); g_settled++; }
}

void vk_gbar_after_dispatch(VkCommandBuffer cb) {
    if (!g_on) { vk_img_barrier_all(cb); return; }
    VkCommandBuffer was = atomic_load_explicit(&g_tcb, memory_order_relaxed);
    if (was && was != cb) settle(was);   // (another command buffer still owed one: it is still being recorded, its end settles it otherwise)
    atomic_store_explicit(&g_tcb, cb, memory_order_relaxed);
}
// Before a dispatch (instead of its full barrier): right after another dispatch, nothing between them, its shader writes made visible to this one's shaders and to an
// indirect dispatch's or draw's arguments; else the full barrier
void vk_gbar_before_dispatch(VkCommandBuffer cb) {
    if (g_on && atomic_load_explicit(&g_tcb, memory_order_relaxed) == cb) {
        atomic_store_explicit(&g_tcb, VK_NULL_HANDLE, memory_order_relaxed);
        VkMemoryBarrier b = {VK_STRUCTURE_TYPE_MEMORY_BARRIER, NULL, VK_ACCESS_SHADER_WRITE_BIT,
                             VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_UNIFORM_READ_BIT | VK_ACCESS_INDIRECT_COMMAND_READ_BIT};
        n_vkCmdPipelineBarrier(cb, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT, 0, 1, &b, 0, NULL, 0, NULL);
        g_narrowed++; return;
    }
    vk_img_barrier_all(cb);
}
// A game barrier outside a pass: 1 = it is the owed one (nothing recorded), 0 = record it in full
int vk_gbar_game(VkCommandBuffer cb) {
    if (!g_on || atomic_load_explicit(&g_tcb, memory_order_relaxed) != cb) return 0;
    g_absorbed++; return 1;
}
void vk_gbar_stats(uint64_t *narrowed, uint64_t *settled, uint64_t *absorbed) { *narrowed = g_narrowed; *settled = g_settled; *absorbed = g_absorbed; }
int vk_gbar_on(void) { return g_on; }

static VKAPI_ATTR void VKAPI_CALL w_vkCmdPipelineBarrier(VkCommandBuffer cb, VkPipelineStageFlags s, VkPipelineStageFlags d, VkDependencyFlags f, uint32_t nm, const VkMemoryBarrier *m,
                                                        uint32_t nb, const VkBufferMemoryBarrier *b, uint32_t ni, const VkImageMemoryBarrier *im) {
    settle(cb); n_vkCmdPipelineBarrier(cb, s, d, f, nm, m, nb, b, ni, im);
}
static VKAPI_ATTR void VKAPI_CALL w_vkCmdCopyBuffer(VkCommandBuffer cb, VkBuffer s, VkBuffer d, uint32_t n, const VkBufferCopy *r) { settle(cb); n_vkCmdCopyBuffer(cb, s, d, n, r); }
static VKAPI_ATTR void VKAPI_CALL w_vkCmdCopyBufferToImage(VkCommandBuffer cb, VkBuffer s, VkImage d, VkImageLayout l, uint32_t n, const VkBufferImageCopy *r) { settle(cb); n_vkCmdCopyBufferToImage(cb, s, d, l, n, r); }
static VKAPI_ATTR void VKAPI_CALL w_vkCmdCopyImageToBuffer(VkCommandBuffer cb, VkImage s, VkImageLayout l, VkBuffer d, uint32_t n, const VkBufferImageCopy *r) { settle(cb); n_vkCmdCopyImageToBuffer(cb, s, l, d, n, r); }
static VKAPI_ATTR void VKAPI_CALL w_vkCmdCopyImage(VkCommandBuffer cb, VkImage s, VkImageLayout sl, VkImage d, VkImageLayout dl, uint32_t n, const VkImageCopy *r) { settle(cb); n_vkCmdCopyImage(cb, s, sl, d, dl, n, r); }
static VKAPI_ATTR void VKAPI_CALL w_vkCmdBlitImage(VkCommandBuffer cb, VkImage s, VkImageLayout sl, VkImage d, VkImageLayout dl, uint32_t n, const VkImageBlit *r, VkFilter f) { settle(cb); n_vkCmdBlitImage(cb, s, sl, d, dl, n, r, f); }
static VKAPI_ATTR void VKAPI_CALL w_vkCmdFillBuffer(VkCommandBuffer cb, VkBuffer b, VkDeviceSize o, VkDeviceSize n, uint32_t v) { settle(cb); n_vkCmdFillBuffer(cb, b, o, n, v); }
static VKAPI_ATTR void VKAPI_CALL w_vkCmdUpdateBuffer(VkCommandBuffer cb, VkBuffer b, VkDeviceSize o, VkDeviceSize n, const void *p) { settle(cb); n_vkCmdUpdateBuffer(cb, b, o, n, p); }
static VKAPI_ATTR void VKAPI_CALL w_vkCmdClearColorImage(VkCommandBuffer cb, VkImage i, VkImageLayout l, const VkClearColorValue *c, uint32_t n, const VkImageSubresourceRange *r) { settle(cb); n_vkCmdClearColorImage(cb, i, l, c, n, r); }
static VKAPI_ATTR void VKAPI_CALL w_vkCmdClearDepthStencilImage(VkCommandBuffer cb, VkImage i, VkImageLayout l, const VkClearDepthStencilValue *c, uint32_t n, const VkImageSubresourceRange *r) { settle(cb); n_vkCmdClearDepthStencilImage(cb, i, l, c, n, r); }
static VKAPI_ATTR void VKAPI_CALL w_vkCmdResolveImage(VkCommandBuffer cb, VkImage s, VkImageLayout sl, VkImage d, VkImageLayout dl, uint32_t n, const VkImageResolve *r) { settle(cb); n_vkCmdResolveImage(cb, s, sl, d, dl, n, r); }
static VKAPI_ATTR void VKAPI_CALL w_vkCmdBeginRenderPass(VkCommandBuffer cb, const VkRenderPassBeginInfo *b, VkSubpassContents c) { settle(cb); n_vkCmdBeginRenderPass(cb, b, c); }
static VKAPI_ATTR void VKAPI_CALL w_vkCmdBeginRendering(VkCommandBuffer cb, const VkRenderingInfo *r) { settle(cb); n_vkCmdBeginRendering(cb, r); }
static VKAPI_ATTR void VKAPI_CALL w_vkCmdDraw(VkCommandBuffer cb, uint32_t a, uint32_t b, uint32_t c, uint32_t d) { settle(cb); n_vkCmdDraw(cb, a, b, c, d); }
static VKAPI_ATTR void VKAPI_CALL w_vkCmdDrawIndexed(VkCommandBuffer cb, uint32_t a, uint32_t b, uint32_t c, int32_t d, uint32_t e) { settle(cb); n_vkCmdDrawIndexed(cb, a, b, c, d, e); }
static VKAPI_ATTR void VKAPI_CALL w_vkCmdDrawIndirect(VkCommandBuffer cb, VkBuffer b, VkDeviceSize o, uint32_t n, uint32_t s) { settle(cb); n_vkCmdDrawIndirect(cb, b, o, n, s); }
static VKAPI_ATTR void VKAPI_CALL w_vkCmdDrawIndexedIndirect(VkCommandBuffer cb, VkBuffer b, VkDeviceSize o, uint32_t n, uint32_t s) { settle(cb); n_vkCmdDrawIndexedIndirect(cb, b, o, n, s); }
static VKAPI_ATTR void VKAPI_CALL w_vkCmdDispatch(VkCommandBuffer cb, uint32_t x, uint32_t y, uint32_t z) { settle(cb); n_vkCmdDispatch(cb, x, y, z); }
static VKAPI_ATTR void VKAPI_CALL w_vkCmdDispatchIndirect(VkCommandBuffer cb, VkBuffer b, VkDeviceSize o) { settle(cb); n_vkCmdDispatchIndirect(cb, b, o); }
static VKAPI_ATTR void VKAPI_CALL w_vkCmdResetQueryPool(VkCommandBuffer cb, VkQueryPool p, uint32_t f, uint32_t n) { settle(cb); n_vkCmdResetQueryPool(cb, p, f, n); }
static VKAPI_ATTR void VKAPI_CALL w_vkCmdWriteTimestamp(VkCommandBuffer cb, VkPipelineStageFlagBits s, VkQueryPool p, uint32_t q) { settle(cb); n_vkCmdWriteTimestamp(cb, s, p, q); }
static VKAPI_ATTR VkResult VKAPI_CALL w_vkEndCommandBuffer(VkCommandBuffer cb) { settle(cb); return n_vkEndCommandBuffer(cb); }

void vk_gbar_init(void) {   // vk_init, after gfx_vk_dq.c's wrappers (these go over them)
    const char *e = getenv("DM_VK_BAR_NARROW"); if (!(e && atoi(e) > 0)) return;
#define GB_HAVE(f) && f
    if (!(1 GB_FNS(GB_HAVE))) { VKLOG("[RED] DM_VK_BAR_NARROW: an entry point is missing; every dispatch keeps its full barriers"); return; }
#define GB_SWAP(f) n_##f = f; f = w_##f;
    GB_FNS(GB_SWAP)
    g_on = 1;
    VKLOG("narrow barriers between dispatches (DM_VK_BAR_NARROW): compute-to-compute where only another dispatch follows");
}
