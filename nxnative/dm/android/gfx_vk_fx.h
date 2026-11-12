// Android port: the picture's own passes between the game's frame and the window (gfx_vk_present.c, included there): FSR 1 upscaling with its sharpening, and frame generation.
// All of it runs only when the player turns it on in the pause menu's Display tab, and only with dynamic rendering. Images stay in GENERAL; vk_img_barrier_all separates the passes.
#include "fx_vert.spv.h"
#include "fsr_easu_frag.spv.h"
#include "fsr_rcas_frag.spv.h"
#include "fx_edge_frag.spv.h"
#include "fg_flow_frag.spv.h"
#include "fg_warp_frag.spv.h"
typedef struct { VkImage i; VkDeviceMemory m; VkImageView v, raw; uint32_t w, h; VkFormat f; int fresh; } fx_img;   // raw: the same bytes unconverted, for an sRGB image
static struct { int ready, failed; VkSampler samp; VkDescriptorSetLayout d1, d2, d3; VkPipelineLayout le, lr, lf, lw; VkDescriptorPool pool; VkDescriptorSet se[8], sr[8], sf[8], sw[8];
                VkPipeline easu, rcas, edge, flow, warp; VkFormat rfmt, efmt, wfmt; fx_img out, older, mid, small[2], fl; int sm; VkImage vimg; VkImageView vview; VkFormat vfmt; } g_fx;
static VkFormat fx_unorm(VkFormat f) { return f == VK_FORMAT_R8G8B8A8_SRGB ? VK_FORMAT_R8G8B8A8_UNORM : f == VK_FORMAT_B8G8R8A8_SRGB ? VK_FORMAT_B8G8R8A8_UNORM : f == VK_FORMAT_A8B8G8R8_SRGB_PACK32 ? VK_FORMAT_A8B8G8R8_UNORM_PACK32 : f; }
static void fx_img_free(fx_img *o) { if (o->raw && o->raw != o->v) vkDestroyImageView(V.dev, o->raw, NULL); if (o->v) vkDestroyImageView(V.dev, o->v, NULL); if (o->i) vkDestroyImage(V.dev, o->i, NULL); if (o->m) vkFreeMemory(V.dev, o->m, NULL); memset(o, 0, sizeof *o); }
static void fx_img_make(fx_img *o, uint32_t w, uint32_t h, VkFormat f) {
    if (o->i && o->w == w && o->h == h && o->f == f) return;
    if (o->i) { extern pthread_mutex_t g_vk_queue_mu; pthread_mutex_lock(&g_vk_queue_mu); vkDeviceWaitIdle(V.dev); pthread_mutex_unlock(&g_vk_queue_mu); fx_img_free(o); }
    VkImageCreateInfo ic = {VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO, NULL, VK_IMAGE_CREATE_MUTABLE_FORMAT_BIT, VK_IMAGE_TYPE_2D, f, {w, h, 1}, 1, 1, VK_SAMPLE_COUNT_1_BIT, VK_IMAGE_TILING_OPTIMAL,
        VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT, VK_SHARING_MODE_EXCLUSIVE, 0, NULL, VK_IMAGE_LAYOUT_UNDEFINED};
    VKCHK(vkCreateImage(V.dev, &ic, NULL, &o->i)); VkMemoryRequirements rq; vkGetImageMemoryRequirements(V.dev, o->i, &rq);
    VkMemoryAllocateInfo ai = {VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO, NULL, rq.size, vk_mem_type(rq.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, 0)};
    VKCHK(vkAllocateMemory(V.dev, &ai, NULL, &o->m)); VKCHK(vkBindImageMemory(V.dev, o->i, o->m, 0));
    VkImageViewCreateInfo vi = {VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO, NULL, 0, o->i, VK_IMAGE_VIEW_TYPE_2D, f, {0}, {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1}};
    VKCHK(vkCreateImageView(V.dev, &vi, NULL, &o->v)); o->raw = o->v;
    if (fx_unorm(f) != f) { vi.format = fx_unorm(f); VKCHK(vkCreateImageView(V.dev, &vi, NULL, &o->raw)); }
    o->w = w; o->h = h; o->f = f; o->fresh = 1;
}
static void fx_general(VkCommandBuffer cb, fx_img *o) {   // an image's first use
    if (!o->fresh) return; o->fresh = 0;
    VkImageMemoryBarrier b = {VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER, NULL, 0, VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL, VK_QUEUE_FAMILY_IGNORED, VK_QUEUE_FAMILY_IGNORED, o->i, {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1}};
    vkCmdPipelineBarrier(cb, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, 0, NULL, 0, NULL, 1, &b);
}
static VkShaderModule fx_mod(const uint32_t *c, size_t n) { VkShaderModuleCreateInfo ci = {VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO, NULL, 0, n, c}; VkShaderModule m; VKCHK(vkCreateShaderModule(V.dev, &ci, NULL, &m)); return m; }
static VkPipeline fx_pipe(VkShaderModule vs, const uint32_t *fc, size_t fn, VkPipelineLayout l, VkFormat fmt) {
    VkShaderModule fs = fx_mod(fc, fn);
    VkPipelineShaderStageCreateInfo st[2] = {{VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO, NULL, 0, VK_SHADER_STAGE_VERTEX_BIT, vs, "main", NULL}, {VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO, NULL, 0, VK_SHADER_STAGE_FRAGMENT_BIT, fs, "main", NULL}};
    VkPipelineVertexInputStateCreateInfo vi = {VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO};
    VkPipelineInputAssemblyStateCreateInfo ia = {VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO, NULL, 0, VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST, 0};
    VkPipelineViewportStateCreateInfo vp = {VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO, NULL, 0, 1, NULL, 1, NULL};
    VkPipelineRasterizationStateCreateInfo rs = {VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO, NULL, 0, 0, 0, VK_POLYGON_MODE_FILL, VK_CULL_MODE_NONE, VK_FRONT_FACE_COUNTER_CLOCKWISE, 0, 0, 0, 0, 1.0f};
    VkPipelineMultisampleStateCreateInfo ms = {VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO, NULL, 0, VK_SAMPLE_COUNT_1_BIT};
    VkPipelineColorBlendAttachmentState ba = {.colorWriteMask = 15}; VkPipelineColorBlendStateCreateInfo cb = {VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO, NULL, 0, 0, 0, 1, &ba};
    VkDynamicState dy[2] = {VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR}; VkPipelineDynamicStateCreateInfo ds = {VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO, NULL, 0, 2, dy};
    VkPipelineRenderingCreateInfo pr = {VK_STRUCTURE_TYPE_PIPELINE_RENDERING_CREATE_INFO, NULL, 0, 1, &fmt};
    VkGraphicsPipelineCreateInfo ci = {VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO, &pr, 0, 2, st, &vi, &ia, NULL, &vp, &rs, &ms, NULL, &cb, &ds, l, VK_NULL_HANDLE, 0};
    VkPipeline p = VK_NULL_HANDLE; if (vkCreateGraphicsPipelines(V.dev, V.pcache, 1, &ci, NULL, &p)) { VKRED_ONCE(97, "a picture pass's pipeline failed: the plain scaling stays"); g_fx.failed = 1; p = VK_NULL_HANDLE; }
    vkDestroyShaderModule(V.dev, fs, NULL);
    return p;
}
static VkDescriptorSetLayout fx_dsl(uint32_t n) {
    VkDescriptorSetLayoutBinding b[3]; for (uint32_t i = 0; i < n; i++) b[i] = (VkDescriptorSetLayoutBinding){i, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1, VK_SHADER_STAGE_FRAGMENT_BIT, NULL};
    VkDescriptorSetLayoutCreateInfo ci = {VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO, NULL, 0, n, b}; VkDescriptorSetLayout l; VKCHK(vkCreateDescriptorSetLayout(V.dev, &ci, NULL, &l)); return l;
}
static VkPipelineLayout fx_layout(VkDescriptorSetLayout d, uint32_t push) {
    VkPushConstantRange r = {VK_SHADER_STAGE_FRAGMENT_BIT, 0, push}; VkPipelineLayoutCreateInfo ci = {VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO, NULL, 0, 1, &d, push ? 1u : 0u, &r}; VkPipelineLayout l; VKCHK(vkCreatePipelineLayout(V.dev, &ci, NULL, &l)); return l;
}
static int fx_init(void) {
    if (g_fx.ready) return !g_fx.failed;
    g_fx.ready = 1; if (!V.has_dynrender) { g_fx.failed = 1; return 0; }
    VkSamplerCreateInfo si = {VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO, NULL, 0, VK_FILTER_LINEAR, VK_FILTER_LINEAR, VK_SAMPLER_MIPMAP_MODE_NEAREST, VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE, VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE, VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE};
    VKCHK(vkCreateSampler(V.dev, &si, NULL, &g_fx.samp));
    g_fx.d1 = fx_dsl(1); g_fx.d2 = fx_dsl(2); g_fx.d3 = fx_dsl(3);
    g_fx.le = fx_layout(g_fx.d1, 80); g_fx.lr = fx_layout(g_fx.d1, 32); g_fx.lf = fx_layout(g_fx.d2, 16); g_fx.lw = fx_layout(g_fx.d3, 0);
    VkDescriptorPoolSize sz = {VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 64}; VkDescriptorPoolCreateInfo pi = {VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO, NULL, 0, 32, 1, &sz}; VKCHK(vkCreateDescriptorPool(V.dev, &pi, NULL, &g_fx.pool));
    VkDescriptorSetLayout ls[8];
    for (int i = 0; i < 8; i++) ls[i] = g_fx.d1;
    VkDescriptorSetAllocateInfo da = {VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO, NULL, g_fx.pool, 8, ls}; VKCHK(vkAllocateDescriptorSets(V.dev, &da, g_fx.se)); VKCHK(vkAllocateDescriptorSets(V.dev, &da, g_fx.sr));
    for (int i = 0; i < 8; i++) ls[i] = g_fx.d2;
    VKCHK(vkAllocateDescriptorSets(V.dev, &da, g_fx.sf));
    for (int i = 0; i < 8; i++) ls[i] = g_fx.d3;
    VKCHK(vkAllocateDescriptorSets(V.dev, &da, g_fx.sw));
    VkShaderModule vs = fx_mod(fx_vert_spv, sizeof fx_vert_spv);
    g_fx.easu = fx_pipe(vs, fsr_easu_frag_spv, sizeof fsr_easu_frag_spv, g_fx.le, VK_FORMAT_R8G8B8A8_UNORM);
    g_fx.flow = fx_pipe(vs, fg_flow_frag_spv, sizeof fg_flow_frag_spv, g_fx.lf, VK_FORMAT_R16G16_SFLOAT);
    vkDestroyShaderModule(V.dev, vs, NULL);
    VKLOG("picture passes ready%s", g_fx.failed ? ": no (the plain scaling stays)" : "");
    return !g_fx.failed;
}
static VkPipeline fx_rcas(void) {
    if (g_fx.rcas && g_fx.rfmt == g_sfmt) return g_fx.rcas;
    VkShaderModule vs = fx_mod(fx_vert_spv, sizeof fx_vert_spv); g_fx.rfmt = g_sfmt; g_fx.rcas = fx_pipe(vs, fsr_rcas_frag_spv, sizeof fsr_rcas_frag_spv, g_fx.lr, g_sfmt); vkDestroyShaderModule(V.dev, vs, NULL);
    return g_fx.rcas;
}
static VkPipeline fx_edge(void) {
    if (g_fx.edge && g_fx.efmt == g_sfmt) return g_fx.edge;
    VkShaderModule vs = fx_mod(fx_vert_spv, sizeof fx_vert_spv); g_fx.efmt = g_sfmt; g_fx.edge = fx_pipe(vs, fx_edge_frag_spv, sizeof fx_edge_frag_spv, g_fx.lr, g_sfmt); vkDestroyShaderModule(V.dev, vs, NULL);
    return g_fx.edge;
}
static VkPipeline fx_warp(VkFormat f) {
    if (g_fx.warp && g_fx.wfmt == f) return g_fx.warp;
    VkShaderModule vs = fx_mod(fx_vert_spv, sizeof fx_vert_spv); g_fx.wfmt = f; g_fx.warp = fx_pipe(vs, fg_warp_frag_spv, sizeof fg_warp_frag_spv, g_fx.lw, f); vkDestroyShaderModule(V.dev, vs, NULL);
    return g_fx.warp;
}
static VkImageView fx_view_of(vkimg_t *im, int raw) {   // a view of the game's window image: its bytes (raw) or decoded. Made again when the image changes
    static struct { VkImage i; VkImageView v[2]; } c[4]; static int n;
    for (int k = 0; k < 4; k++) if (c[k].i == im->img && c[k].v[raw]) return c[k].v[raw];
    int k = 0; while (k < 4 && c[k].i != im->img) k++;
    if (k == 4) { k = n++ % 4; c[k].i = VK_NULL_HANDLE; }   // (an older view stays alive: the swapped-out ones are few and small)
    c[k].i = im->img;
    VkImageViewCreateInfo vi = {VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO, NULL, 0, im->img, VK_IMAGE_VIEW_TYPE_2D, raw ? fx_unorm(im->fmt) : im->fmt, {0}, {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1}};
    VkImageViewUsageCreateInfo uc = {VK_STRUCTURE_TYPE_IMAGE_VIEW_USAGE_CREATE_INFO, NULL, VK_IMAGE_USAGE_SAMPLED_BIT}; vi.pNext = &uc;
    if (vkCreateImageView(V.dev, &vi, NULL, &c[k].v[raw])) return VK_NULL_HANDLE;
    return c[k].v[raw];
}
static void fx_set(VkDescriptorSet s, const VkImageView *v, int n) {
    VkDescriptorImageInfo ii[3]; VkWriteDescriptorSet w[3];
    for (int i = 0; i < n; i++) { ii[i] = (VkDescriptorImageInfo){g_fx.samp, v[i], VK_IMAGE_LAYOUT_GENERAL}; w[i] = (VkWriteDescriptorSet){VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET, NULL, s, (uint32_t)i, 0, 1, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, &ii[i]}; }
    vkUpdateDescriptorSets(V.dev, (uint32_t)n, w, 0, NULL);
}
static void fx_begin(VkCommandBuffer cb, VkImageView v, VkImageLayout lay, int x, int y, uint32_t w, uint32_t h, VkExtent2D full) {
    VkRenderingAttachmentInfo ca = {VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO, NULL, v, lay, 0, NULL, VK_IMAGE_LAYOUT_UNDEFINED, VK_ATTACHMENT_LOAD_OP_LOAD, VK_ATTACHMENT_STORE_OP_STORE};
    VkRenderingInfo ri = {VK_STRUCTURE_TYPE_RENDERING_INFO, NULL, 0, {{0, 0}, full}, 1, 0, 1, &ca}; vkCmdBeginRendering(cb, &ri);
    VkViewport vp = {(float)x, (float)y, (float)w, (float)h, 0, 1}; VkRect2D sc = {{x, y}, {w, h}}; vkCmdSetViewport(cb, 0, 1, &vp); vkCmdSetScissor(cb, 0, 1, &sc);
}
// The frame half way from the last one to this: small copies, the flow between them, then both frames moved half way into g_fx.mid. 1 when mid holds it
// The pause menu's Frame gen quality sets the motion field's density: a vector for every 16, 8 (Medium, as before), 6 or 4 pixels each way (the search itself runs on the
// quarter-size copies at every quality, so its reach stays the same); a denser field follows smaller moving things, for more GPU time (the flow pass grows with it)
static int fg_make(VkCommandBuffer cb, uint32_t k, vkimg_t *im) {
    static const uint32_t FD[4] = {16, 8, 6, 4}; uint32_t fd = FD[dm_disp(DISP_FGQ) & 3];
    uint32_t w = im->w, h = im->h, sw = w / 4, sh = h / 4, fw = (w + fd - 1) / fd, fh = (h + fd - 1) / fd;
    if (!fx_init() || !fx_warp(im->fmt)) return 0;
    int first = !g_fx.older.i || g_fx.older.w != w || g_fx.older.h != h || g_fx.older.f != im->fmt;
    fx_img_make(&g_fx.older, w, h, im->fmt); fx_img_make(&g_fx.mid, w, h, im->fmt); fx_img_make(&g_fx.small[0], sw, sh, VK_FORMAT_R8G8B8A8_UNORM); fx_img_make(&g_fx.small[1], sw, sh, VK_FORMAT_R8G8B8A8_UNORM); fx_img_make(&g_fx.fl, fw, fh, VK_FORMAT_R16G16_SFLOAT);
    fx_general(cb, &g_fx.older); fx_general(cb, &g_fx.mid); fx_general(cb, &g_fx.small[0]); fx_general(cb, &g_fx.small[1]); fx_general(cb, &g_fx.fl);
    VkImageView cur = fx_view_of(im, 0); if (!cur || first) return 0;
    fx_img *newer = &g_fx.small[g_fx.sm], *old = &g_fx.small[!g_fx.sm];
    VkImageBlit b = {{VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1}, {{0, 0, 0}, {(int)w, (int)h, 1}}, {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1}, {{0, 0, 0}, {(int)sw, (int)sh, 1}}};
    vkCmdBlitImage(cb, im->img, VK_IMAGE_LAYOUT_GENERAL, newer->i, VK_IMAGE_LAYOUT_GENERAL, 1, &b, VK_FILTER_LINEAR);
    vk_img_barrier_all(cb);
    VkImageView fv[2] = {old->v, newer->v}; fx_set(g_fx.sf[k], fv, 2);
    fx_begin(cb, g_fx.fl.v, VK_IMAGE_LAYOUT_GENERAL, 0, 0, fw, fh, (VkExtent2D){fw, fh});
    vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, g_fx.flow); vkCmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, g_fx.lf, 0, 1, &g_fx.sf[k], 0, NULL);
    float tx[4] = {1.0f / sw, 1.0f / sh, 0, 0}; vkCmdPushConstants(cb, g_fx.lf, VK_SHADER_STAGE_FRAGMENT_BIT, 0, sizeof tx, tx); vkCmdDraw(cb, 3, 1, 0, 0); vkCmdEndRendering(cb);
    vk_img_barrier_all(cb);
    VkImageView wv[3] = {g_fx.older.v, cur, g_fx.fl.v}; fx_set(g_fx.sw[k], wv, 3);
    fx_begin(cb, g_fx.mid.v, VK_IMAGE_LAYOUT_GENERAL, 0, 0, w, h, (VkExtent2D){w, h});
    vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, g_fx.warp); vkCmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, g_fx.lw, 0, 1, &g_fx.sw[k], 0, NULL); vkCmdDraw(cb, 3, 1, 0, 0); vkCmdEndRendering(cb);
    vk_img_barrier_all(cb);
    return 1;
}
// The real frame, once shown, becomes the older one of the next pair
static void fg_keep(VkCommandBuffer cb, vkimg_t *im) {
    if (!g_fx.older.i || g_fx.older.w != im->w || g_fx.older.h != im->h || g_fx.older.f != im->fmt) return;
    VkImageCopy c = {{VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1}, {0, 0, 0}, {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1}, {0, 0, 0}, {im->w, im->h, 1}};
    vkCmdCopyImage(cb, im->img, VK_IMAGE_LAYOUT_GENERAL, g_fx.older.i, VK_IMAGE_LAYOUT_GENERAL, 1, &c); g_fx.sm = !g_fx.sm;
}
// The picture upscaled by EASU into g_fx.out, then sharpened by RCAS (or, with amount, by fx_edge.frag over radius pixels) into dst's rectangle (the swapchain image, or the pre-rotation image) (dx, dy, dw, dh). 0: not done, the blit stays
static int fsr_draw(VkCommandBuffer cb, uint32_t k, VkImage src, VkImageView sv, uint32_t iw, uint32_t ih, int cx, int cy, int cw, int ch, int dx, int dy, int dw, int dh, VkImage dst, VkImageView dview, VkImageLayout *lay, float stops, float radius, float amount) {
    if (!fx_init() || !fx_rcas() || (amount > 0 && !fx_edge())) return 0;
    (void)src; fx_img_make(&g_fx.out, (uint32_t)dw, (uint32_t)dh, VK_FORMAT_R8G8B8A8_UNORM); fx_general(cb, &g_fx.out);
    fx_set(g_fx.se[k], &sv, 1); fx_set(g_fx.sr[k], &g_fx.out.v, 1);
    union { float f; uint32_t u; } a; struct { uint32_t c[16]; float flip[4]; } e; memset(&e, 0, sizeof e);
#define U(x) (a.f = (float)(x), a.u)
    e.c[0] = U((double)cw / dw); e.c[1] = U((double)ch / dh); e.c[2] = U(0.5 * cw / dw - 0.5 + cx); e.c[3] = U(0.5 * ch / dh - 0.5 + cy);
    e.c[4] = U(1.0 / iw); e.c[5] = U(1.0 / ih); e.c[6] = U(1.0 / iw); e.c[7] = U(-1.0 / ih);
    e.c[8] = U(-1.0 / iw); e.c[9] = U(2.0 / ih); e.c[10] = U(1.0 / iw); e.c[11] = U(2.0 / ih);
    e.c[12] = U(0); e.c[13] = U(4.0 / ih);
    e.flip[0] = vk_window_bottom_up() ? 1 : 0; e.flip[1] = (float)dh;
    fx_begin(cb, g_fx.out.v, VK_IMAGE_LAYOUT_GENERAL, 0, 0, (uint32_t)dw, (uint32_t)dh, (VkExtent2D){(uint32_t)dw, (uint32_t)dh});
    vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, g_fx.easu); vkCmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, g_fx.le, 0, 1, &g_fx.se[k], 0, NULL);
    vkCmdPushConstants(cb, g_fx.le, VK_SHADER_STAGE_FRAGMENT_BIT, 0, sizeof e, &e); vkCmdDraw(cb, 3, 1, 0, 0); vkCmdEndRendering(cb);
    vk_img_barrier_all(cb);
    barrier(cb, dst, *lay, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL); *lay = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
    struct { uint32_t con[4]; int32_t ox, oy, linear, pad; } r; memset(&r, 0, sizeof r); r.con[0] = U(pow(2.0, -stops)); r.ox = dx; r.oy = dy; r.linear = g_sfmt == VK_FORMAT_R8G8B8A8_SRGB || g_sfmt == VK_FORMAT_B8G8R8A8_SRGB;
#undef U
    struct { float tx, ty, radius, amount; int32_t ox, oy, linear, pad; } w = {1.0f / dw, 1.0f / dh, radius, amount, dx, dy, r.linear, 0};
    fx_begin(cb, dview, *lay, dx, dy, (uint32_t)dw, (uint32_t)dh, g_sext);
    vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, amount > 0 ? g_fx.edge : g_fx.rcas); vkCmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, g_fx.lr, 0, 1, &g_fx.sr[k], 0, NULL);
    if (amount > 0) vkCmdPushConstants(cb, g_fx.lr, VK_SHADER_STAGE_FRAGMENT_BIT, 0, sizeof w, &w); else vkCmdPushConstants(cb, g_fx.lr, VK_SHADER_STAGE_FRAGMENT_BIT, 0, sizeof r, &r);
    vkCmdDraw(cb, 3, 1, 0, 0); vkCmdEndRendering(cb);
    return 1;
}
