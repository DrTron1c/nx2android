// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

// SPDX-FileCopyrightText: Copyright 2025 lsfg-vk
// SPDX-License-Identifier: GPL-3.0-or-later

// Lossless Scaling frame generation in the present (lsfg/): the present thread hands each game frame here, and shows the frames generated between it and the one before. Adapted from Banana
// Native's vka_framegen.c, itself from Eden, for this backend. The shaders are read at run time from the player's own Lossless.dll, which the port puts in the game's files (DM_LSFG_DLL), and
// are never in this repository or the APK. A window image is copied in byte for byte and the outputs are images of its own format, so an sRGB picture stays as it was
#include "gfx_vk.h"
#include "lsfg/lsfg.h"
#include <stdlib.h>
#include <string.h>
#include <time.h>
extern pthread_mutex_t g_vk_queue_mu;
double vk_refresh_ms(void);
enum { NFG = 3 };
static struct {
    int state;   // 0 not tried, 1 ready, -1 unavailable
    lsfg_modules mods; lsfg_chain *chain; uint32_t w, h; VkFormat fmt; int flow;
    VkImage out[LSFG_MAX_GENERATIONS]; VkDeviceMemory mem[LSFG_MAX_GENERATIONS]; VkImageView view[LSFG_MAX_GENERATIONS];
    VkCommandPool pool; VkCommandBuffer cb[NFG]; VkFence fence[NFG]; unsigned fi;
    lsfg_pacer pacer; uint64_t count; uint32_t warm;
} F;
static double mono_s(void) { struct timespec t; clock_gettime(CLOCK_MONOTONIC, &t); return t.tv_sec + t.tv_nsec / 1e9; }
static void idle(void) { pthread_mutex_lock(&g_vk_queue_mu); vkDeviceWaitIdle(V.dev); pthread_mutex_unlock(&g_vk_queue_mu); }
static void teardown(void) {
    if (!F.chain && !F.out[0]) return;
    idle();
    if (F.chain) lsfg_chain_free(F.chain);
    F.chain = NULL;
    for (int i = 0; i < LSFG_MAX_GENERATIONS; i++) {
        if (F.view[i]) vkDestroyImageView(V.dev, F.view[i], NULL);
        if (F.out[i]) vkDestroyImage(V.dev, F.out[i], NULL);
        if (F.mem[i]) vkFreeMemory(V.dev, F.mem[i], NULL);
        F.view[i] = VK_NULL_HANDLE; F.out[i] = VK_NULL_HANDLE; F.mem[i] = VK_NULL_HANDLE;
    }
    lsfg_pacer_reset(&F.pacer); F.count = 0; F.warm = 0;
}
static int off(const char *why, const char *more) { F.state = -1; VKLOG("[RED] frame generation unavailable: %s%s", why, more ? more : ""); return 0; }
static int load(void) {
    if (F.state) return F.state > 0;
    VkPhysicalDeviceVulkan12Features f12 = {VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES};
    VkPhysicalDeviceFeatures2 f2 = {VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2, &f12};
    vkGetPhysicalDeviceFeatures2(V.pd, &f2);
    const char *miss = !f12.shaderFloat16 ? "shaderFloat16" : !f2.features.shaderStorageImageExtendedFormats ? "shaderStorageImageExtendedFormats"
                     : !f2.features.shaderStorageImageWriteWithoutFormat ? "shaderStorageImageWriteWithoutFormat" : !V.has_robust2 ? "null descriptors" : NULL;
    VkFormat fmts[3] = {VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_R8_UNORM, VK_FORMAT_R16G16B16A16_SFLOAT};
    for (int i = 0; i < 3 && !miss; i++) {
        VkFormatProperties fp; vkGetPhysicalDeviceFormatProperties(V.pd, fmts[i], &fp);
        if (!(fp.optimalTilingFeatures & VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT) || !(fp.optimalTilingFeatures & VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT)) miss = "storage images of R8, RGBA8 or RGBA16F";
    }
    if (miss) return off("the GPU lacks ", miss);
    const char *e = getenv("DM_LSFG_DLL"), *root = getenv("DM_ROOT"); char path[1100];
    if (!e || !*e) return off("no Lossless.dll: choose it in the builder and port again", NULL);
    snprintf(path, sizeof path, "%s%s%s", e[0] == '/' ? "" : root ? root : ".", e[0] == '/' ? "" : "/", e);
    int r = lsfg_dll_load(path, &F.mods);
    if (r) return off(path, lsfg_status_name(r));
    VKLOG("frame generation: %d shaders from %s", F.mods.n, path);
    F.state = 1;
    return 1;
}
static int rebuild(uint32_t w, uint32_t h, VkFormat fmt, int flow) {
    teardown();
    for (int i = 0; i < LSFG_MAX_GENERATIONS; i++) {
        VkImageCreateInfo ci = {VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO, NULL, VK_IMAGE_CREATE_MUTABLE_FORMAT_BIT | VK_IMAGE_CREATE_EXTENDED_USAGE_BIT, VK_IMAGE_TYPE_2D, fmt, {w, h, 1}, 1, 1, VK_SAMPLE_COUNT_1_BIT, VK_IMAGE_TILING_OPTIMAL,
            VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT, VK_SHARING_MODE_EXCLUSIVE, 0, NULL, VK_IMAGE_LAYOUT_UNDEFINED};
        if (vkCreateImage(V.dev, &ci, NULL, &F.out[i])) { teardown(); return 0; }
        VkMemoryRequirements mr; vkGetImageMemoryRequirements(V.dev, F.out[i], &mr);
        VkMemoryAllocateInfo ai = {VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO, NULL, mr.size, vk_mem_type(mr.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, 0)};
        if (vkAllocateMemory(V.dev, &ai, NULL, &F.mem[i]) || vkBindImageMemory(V.dev, F.out[i], F.mem[i], 0)) { teardown(); return 0; }
        VkImageViewCreateInfo vi = {VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO, NULL, 0, F.out[i], VK_IMAGE_VIEW_TYPE_2D, VK_FORMAT_R8G8B8A8_UNORM, {0}, {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1}};
        if (vkCreateImageView(V.dev, &vi, NULL, &F.view[i])) { teardown(); return 0; }
    }
    F.chain = lsfg_chain_new(&F.mods, (VkExtent2D){w, h}, flow / 100.0f, F.out, F.view);
    if (!F.chain) { teardown(); return 0; }
    F.w = w; F.h = h; F.fmt = fmt; F.flow = flow;
    VKLOG("frame generation: chain for %ux%u, motion at %d%%", w, h, flow);
    return 1;
}
// The frames to show before game frame im: their images and views in out / view, each to be held for *hold seconds. Their size in *ow, *oh. mult: images per game frame, 2 to 4
int vk_lsfg_frame(vkimg_t *im, const int *crop, double period, int mult, VkImage *out, VkImageView *view, uint32_t *ow, uint32_t *oh, double *hold) {
    *hold = 0;
    if (!load()) return 0;
    if (im->fmt != VK_FORMAT_R8G8B8A8_SRGB && im->fmt != VK_FORMAT_R8G8B8A8_UNORM) { VKRED_ONCE(120, "frame generation: a window image of format %d cannot be copied in", im->fmt); return 0; }
    int cx = crop ? crop[0] : 0, cy = crop ? crop[1] : 0, cw = crop && crop[2] ? crop[2] : (int)im->w, ch = crop && crop[3] ? crop[3] : (int)im->h;
    static int flow = -1; if (flow < 0) { const char *e = getenv("DM_LSFG_FLOW"); flow = e && atoi(e) >= 25 && atoi(e) <= 100 ? atoi(e) : 50; }
    if (!F.chain || F.w != (uint32_t)cw || F.h != (uint32_t)ch || F.fmt != im->fmt) {
        if (!rebuild((uint32_t)cw, (uint32_t)ch, im->fmt, flow)) return off("the chain could not be made (see the [RED] lines)", NULL);
    }
    lsfg_plan plan = lsfg_pacer_plan(&F.pacer, LSFG_MAX_GENERATIONS, mult, 0, mono_s());
    uint64_t count = F.count++;
    int warm = plan.warm && count + 1 >= 2;
    F.warm = warm ? F.warm + 1 : 0;
    size_t ng = warm && F.warm >= 2 ? plan.generations : 0;
    if (ng > LSFG_MAX_GENERATIONS) ng = LSFG_MAX_GENERATIONS;
    double rms = vk_refresh_ms();   // the panel shows one image a refresh: more than fit would hold the game back
    if (ng && rms > 0) { double n = period * 1e3 / rms; size_t fit = n < 1.9 ? 0 : (size_t)(n + 0.1) - 1; if (ng > fit) { ng = fit; F.pacer.issued = ng; } }
    unsigned k = F.fi++ % NFG;
    if (!F.pool) {
        VkCommandPoolCreateInfo pi = {VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO, NULL, VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT, V.qf}; VKCHK(vkCreateCommandPool(V.dev, &pi, NULL, &F.pool));
        VkCommandBufferAllocateInfo ai = {VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO, NULL, F.pool, VK_COMMAND_BUFFER_LEVEL_PRIMARY, NFG}; VKCHK(vkAllocateCommandBuffers(V.dev, &ai, F.cb));
        VkFenceCreateInfo fi = {VK_STRUCTURE_TYPE_FENCE_CREATE_INFO, NULL, VK_FENCE_CREATE_SIGNALED_BIT}; for (int i = 0; i < NFG; i++) VKCHK(vkCreateFence(V.dev, &fi, NULL, &F.fence[i]));
    }
    vkWaitForFences(V.dev, 1, &F.fence[k], VK_TRUE, UINT64_MAX); vkResetFences(V.dev, 1, &F.fence[k]);
    VkCommandBuffer cb = F.cb[k]; vkResetCommandBuffer(cb, 0);
    VkCommandBufferBeginInfo bi = {VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO, NULL, VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT};
    vkBeginCommandBuffer(cb, &bi);
    vk_img_barrier_all(cb);
    VkImageCopy c = {{VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1}, {cx, cy, 0}, {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1}, {0, 0, 0}, {(uint32_t)cw, (uint32_t)ch, 1}};
    vkCmdCopyImage(cb, im->img, VK_IMAGE_LAYOUT_GENERAL, lsfg_chain_input(F.chain, count), VK_IMAGE_LAYOUT_GENERAL, 1, &c);
    if (warm) lsfg_chain_shared(F.chain, cb, count);
    for (size_t g = 0; g < ng; g++) lsfg_chain_generate(F.chain, cb, count, ng, g);
    vk_img_barrier_all(cb);
    vkEndCommandBuffer(cb);
    VkSubmitInfo si = {VK_STRUCTURE_TYPE_SUBMIT_INFO, NULL, 0, NULL, NULL, 1, &cb, 0, NULL};
    pthread_mutex_lock(&g_vk_queue_mu); VkResult r = vkQueueSubmit(V.q, 1, &si, F.fence[k]); pthread_mutex_unlock(&g_vk_queue_mu);
    if (r) { VKRED_ONCE(121, "frame generation submission: %d", r); return 0; }
    for (size_t g = 0; g < ng; g++) { out[g] = F.out[g]; view[g] = F.view[g]; }
    *ow = (uint32_t)cw; *oh = (uint32_t)ch;
    double iv = F.pacer.smoothed_interval > 0 ? F.pacer.smoothed_interval : period; if (iv < period) iv = period;
    if (ng) *hold = iv / (double)(ng + 1);
    static int said; if (ng && !said++) VKLOG("frame generation: first %zu generated frame(s), game frame interval %.1f ms", ng, iv * 1e3);
    return (int)ng;
}
