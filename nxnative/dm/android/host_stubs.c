// Android port: host features that are Apple-only so far, as "not there": cheats (hle_cheats.c needs Mach VM calls) and AV1 movies (hle_movie_av1.c: VideoToolbox, dav1d)
#include <stdint.h>
#include <stdlib.h>
#include "dm.h"
#include "hle_movie_av1.h"
void dm_cheats_init(void) {}
void dm_cheats_present(void) {}
void dm_cheats_exit(void) {}
int dm_cheats_found(void) { return 0; }
int dm_cheats_ncheats(void) { return 0; }
uint64_t dm_cheats_keys_stub(void) { return 0; }
struct mav1 { int unused; };
mav1 *mav1_new(void) { LOG("[RED] AV1 movies are not decoded on Android yet: such a movie shows no picture"); return calloc(1, sizeof(mav1)); }
void mav1_free(mav1 *a) { free(a); }
void mav1_reset(mav1 *a) { (void)a; }
int mav1_decode(mav1 *a, const uint8_t *data, size_t n, mav1_picture *out) { (void)a; (void)data; (void)n; (void)out; return 0; }
const char *mav1_backend(const mav1 *a) { (void)a; return "none"; }
int mav1_av1c(const uint8_t *obu, size_t n, uint8_t c[4], int *w, int *h) { (void)obu; (void)n; (void)c; (void)w; (void)h; return 0; }
