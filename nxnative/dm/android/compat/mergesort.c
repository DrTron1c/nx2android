// BSD's stable sort, which bionic lacks (hle_hid.c)
#include <stdlib.h>
#include <string.h>
int mergesort(void *base, size_t n, size_t size, int (*cmp)(const void *, const void *)) {
    if (n < 2) return 0;
    char *a = base, *t = malloc(n * size); if (!t) return -1;
    for (size_t w = 1; w < n; w *= 2) {
        for (size_t lo = 0; lo < n; lo += 2 * w) {
            size_t mid = lo + w < n ? lo + w : n, hi = lo + 2 * w < n ? lo + 2 * w : n, i = lo, j = mid, k = lo;
            while (i < mid && j < hi) { if (cmp(a + j * size, a + i * size) < 0) memcpy(t + k++ * size, a + j++ * size, size); else memcpy(t + k++ * size, a + i++ * size, size); }
            if (i < mid) { memcpy(t + k * size, a + i * size, (mid - i) * size); k += mid - i; }
            if (j < hi) memcpy(t + k * size, a + j * size, (hi - j) * size);
        }
        memcpy(a, t, n * size);
    }
    free(t); return 0;
}
