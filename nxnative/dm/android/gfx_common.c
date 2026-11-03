// Android port: what both graphics backends (gfx_null.c, gfx_vk_*.c) report for the host's statistics and diagnostic hooks of the Metal backend
// (nvn_mtl.m) that have no counterpart yet: zeros.
#include "dm.h"
#include "nvn_obj.h"
#include "nvn_mtl.h"
#include <string.h>

// ---------------- statistics and diagnostics: nothing to report ----------------
void mtl_vbuf_stats(uint64_t *a, uint64_t *b, uint64_t *c, uint64_t *d) { *a = *b = *c = *d = 0; }
void mtl_res_counts(uint32_t tick, const char *why) { (void)tick; (void)why; }
void mtl_res_snapshot(mtl_res_count *out) { memset(out, 0, sizeof *out); }
void mtl_alloc_stats(uint64_t *w, uint64_t *g, int *n) { *w = *g = 0; *n = 0; }
void mtl_args_stats(uint64_t *e, uint64_t *r) { *e = *r = 0; }
void mtl_barrier_stats(uint64_t *s, uint64_t *f) { *s = *f = 0; }
uint64_t mtl_blit_splits(void) { return 0; }
void mtl_bw(uint64_t *l, uint64_t *s, uint64_t *f) { *l = *s = *f = 0; }
uint64_t mtl_copy_cut_count(void) { return 0; }
uint64_t mtl_cut_count(void) { return 0; }
void mtl_create_ns(uint64_t *l, uint64_t *p) { *l = *p = 0; }
void mtl_create_stats(uint64_t *l, uint64_t *p) { *l = *p = 0; }
uint64_t mtl_drawable_ns_take(void) { return 0; }
uint64_t mtl_ds_omit(void) { return 0; }
__attribute__((weak)) double mtl_gpu_ms(uint64_t f) { (void)f; return 0; }   // gfx_vk_core.c has the real one
int mtl_gpu_span(uint64_t f, uint64_t *t0, uint64_t *t1) { (void)f; *t0 = *t1 = 0; return 0; }
void mtl_lc_stats(uint64_t *t, uint64_t *l) { *t = *l = 0; }
void mtl_mem_stat(unsigned long long frame, unsigned long long rss_mb) { (void)frame; (void)rss_mb; }
uint64_t mtl_pkg_miss(void) { return 0; }
void mtl_pkg_stats(uint64_t *u, uint64_t *r) { *u = *r = 0; }
void mtl_res_stats(char *out, size_t n) { if (n) out[0] = 0; }
void mtl_stats(uint64_t *p, uint64_t *d) { *p = *d = 0; }
void mtl_ub_present(double iv) { (void)iv; }
void mtl_ubd_stats(uint64_t *d, uint64_t *c) { *d = *c = 0; }
void mtl_ubp_stats(uint64_t *m, uint64_t *r, uint64_t *f) { *m = *r = *f = 0; }
void mtl_ubp_why(uint64_t w[5]) { memset(w, 0, 5 * sizeof *w); }
uint64_t mtl_udc_saved_bytes(void) { return 0; }
uint64_t mtl_upload_bytes(void) { return 0; }
uint64_t mtl_upload_gpu_ns_take(void) { return 0; }
uint64_t mtl_upload_ns_take(void) { return 0; }
void mtl_use_stats(uint64_t *c, uint64_t *s) { *c = *s = 0; }
void dm_gfx_passdump_next(long n) { (void)n; }
void dm_gfx_shot_at(long k, const char *path) { (void)k; (void)path; }
void dm_gfx_texdump_next(const char *path) { (void)path; }
void dm_gfx_texlist(int target) { (void)target; }
void dm_gfx_texraw(uint64_t serial, const char *path) { (void)serial; (void)path; }

// ---------------- what the Metal backend added for Switch 2 games and its display link: nothing to do on Vulkan, or nothing to report ----------------
void mtl_ab_gpu(uint64_t f, double gpu_ms, double frame_ms) { (void)f; (void)gpu_ms; (void)frame_ms; }
unsigned mtl_cpu_tex_count(void) { return 0; }
void mtl_cshoist_stats(uint64_t *a, uint64_t *b, uint64_t *c) { *a = *b = *c = 0; }
void mtl_fb_stats(uint64_t *a, uint64_t *b, uint64_t *c, uint64_t *d) { *a = *b = *c = *d = 0; }
void mtl_geohoist_stats(uint64_t *a, uint64_t *b, uint64_t *c) { *a = *b = *c = 0; }
void mtl_geomesh_stats(uint64_t *a, uint64_t *b, uint64_t *c, uint64_t *d, uint64_t *e) { *a = *b = *c = *d = *e = 0; }
void mtl_hybrid_stats(uint64_t *a, uint64_t *b, uint64_t *c) { *a = *b = *c = 0; }
void mtl_sm_stats(uint64_t *a, uint64_t *b, uint64_t *c) { *a = *b = *c = 0; }
int mtl_link_rate(void) { return 60; }
int mtl_link_stalled(void) { return 0; }
int mtl_link_tick(uint64_t n, double *target, double *lead_ms, int *ticks, double *gap_ms) { (void)n; (void)target; (void)lead_ms; (void)ticks; (void)gap_ms; return 0; }
int mtl_link_wait_ticks(int ticks, uint64_t timeout_ns) { (void)ticks; (void)timeout_ns; return 0; }
uint64_t mtl_pkg_edge_skips(void) { return 0; }
void mtl_prepare(void) {}
void mtl_present_flush(void) {}
void mtl_present_test_stall(uint64_t ns) { (void)ns; }
int mtl_probe_ramp_on(void) { return 0; }
void mtl_prog_forget(const prog_t *p) { (void)p; }
void mtl_prog_prefetch(const prog_t *p) { (void)p; }
void mtl_pso_line(char *o, size_t n) { if (n) o[0] = 0; }
int mtl_pso_prebuild_tool(void) { return 0; }
void mtl_queue_activate(uint64_t serial) { (void)serial; }
void mtl_queue_forget(uint64_t serial) { (void)serial; }
void mtl_set_queue_origin_lower_left(int on) { (void)on; }
uint64_t mtl_snap_acquire_wait(void) { return 0; }
int mtl_snap_on(void) { return 0; }
int mtl_warm_wait(int timeout_ms) { (void)timeout_ms; return 1; }
