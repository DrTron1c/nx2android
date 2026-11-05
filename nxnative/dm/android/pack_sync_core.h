// Installing a port with no adb: Android lets nobody but the game write to the game's files folder, so the game fetches its own data.
// porter/direct.py serves the data pack on this device's loopback while the port waits; assets/pack.txt in the APK has the port and a secret. At a start that finds the server
// (front_android.c) the game does nothing else: it compares its files with the server's list, fetches what differs (a cut-off file goes on from where it stopped), removes what the
// pack no longer has, and restarts. At every other start nothing listens and this costs one refused connection. No Android here, so a test can run it on a PC (tests/test_direct.py).
#include <arpa/inet.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <netinet/in.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/statvfs.h>
#include <sys/time.h>
#include <unistd.h>

enum { PS_CHECKING, PS_COPYING, PS_FINISHING, PS_DONE, PS_FAILED };
typedef struct {
    int port; char token[64]; const char *root;
    _Atomic int phase; _Atomic long long done, total;
    char msg[200];
} ps_t;

// the same CRC as Python's zlib.crc32. Files past 64 MB are compared by 256 pieces of 64 KB (porter/direct.py's fingerprint): the point is to catch a changed file, not a damaged disk
#define PS_WHOLE (64ll << 20)
#define PS_PIECE 65536
#define PS_PIECES 256
static uint32_t ps_tab[256];
static uint32_t ps_crc(uint32_t c, const uint8_t *p, size_t n) {
    if (!ps_tab[1]) for (uint32_t i = 0; i < 256; i++) { uint32_t x = i; for (int k = 0; k < 8; k++) x = x & 1 ? 0xedb88320u ^ x >> 1 : x >> 1; ps_tab[i] = x; }
    c = ~c; while (n--) c = ps_tab[(c ^ *p++) & 255] ^ c >> 8;
    return ~c;
}
static uint32_t ps_fingerprint(const char *path, long long size) {
    int fd = open(path, O_RDONLY); if (fd < 0) return 0;
    uint8_t *b = malloc(1 << 20); uint32_t c = 0;
    if (size <= PS_WHOLE) for (ssize_t n; (n = read(fd, b, 1 << 20)) > 0; ) c = ps_crc(c, b, (size_t)n);
    else for (int i = 0; i < PS_PIECES; i++) { ssize_t n = pread(fd, b, PS_PIECE, (size - PS_PIECE) / (PS_PIECES - 1) * i); if (n > 0) c = ps_crc(c, b, (size_t)n); }
    free(b); close(fd);
    return c ? c : 1;   // 0 means by size alone
}

// ---- one request: HTTP/1.0, the connection ends with the answer ----
static int ps_get(const ps_t *p, const char *what, long long from, int timeout, long long *len) {   // the socket at the body, or -1 (-2: the server answered, and not with a yes)
    int s = socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC, 0); if (s < 0) return -1;
    struct timeval tv = {timeout, 0}; setsockopt(s, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof tv); setsockopt(s, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof tv);
    struct sockaddr_in a = {.sin_family = AF_INET, .sin_port = htons((uint16_t)p->port), .sin_addr.s_addr = htonl(INADDR_LOOPBACK)};
    if (connect(s, (struct sockaddr *)&a, sizeof a)) { close(s); return -1; }
    char q[1400], h[1024]; size_t n = 0; int o = 0;
    o = snprintf(q, sizeof q, "GET /%s/", p->token);
    for (const unsigned char *c = (const unsigned char *)what; *c && o < (int)sizeof q - 64; c++)
        o += (*c >= '0' && *c <= '9') || (*c >= 'a' && *c <= 'z') || (*c >= 'A' && *c <= 'Z') || strchr("-_./", *c) ? snprintf(q + o, 2, "%c", *c) : snprintf(q + o, 4, "%%%02X", *c);
    o += snprintf(q + o, sizeof q - o, " HTTP/1.0\r\nRange: bytes=%lld-\r\n\r\n", from);
    if (send(s, q, (size_t)o, MSG_NOSIGNAL) != o) { close(s); return -1; }
    while (n < sizeof h - 1) {   // a byte at a time: the body has to stay in the socket
        ssize_t r = recv(s, h + n, 1, 0); if (r <= 0) { close(s); return -1; }
        n++; if (n >= 4 && !memcmp(h + n - 4, "\r\n\r\n", 4)) break;
    }
    h[n] = 0;
    int code = 0; if (sscanf(h, "HTTP/%*s %d", &code) != 1) { close(s); return -1; }
    if (code != 200 && code != 206) { close(s); return -2; }
    const char *cl = strstr(h, "Content-Length: "); *len = cl ? atoll(cl + 16) : -1;
    return s;
}
static void ps_say(const ps_t *p, const char *what) { long long n; int s = ps_get(p, what, 0, 5, &n); if (s >= 0) close(s); }
static char *ps_text(const ps_t *p, const char *what) {
    long long n; int s = ps_get(p, what, 0, 60, &n); if (s < 0) return NULL;
    size_t cap = 1 << 16, got = 0; char *b = malloc(cap);
    for (ssize_t r; (r = recv(s, b + got, cap - got - 1, 0)) > 0; ) { got += (size_t)r; if (got + 1 >= cap) b = realloc(b, cap *= 2); }
    close(s); b[got] = 0;
    if (n >= 0 && (long long)got != n) { free(b); return NULL; }
    return b;
}

// a shader the game saved (gfx_vk_shader.c), back to the build folder for the next port to translate
static void ps_put(const ps_t *p, const char *dir, const char *name) {
    char path[PATH_MAX]; snprintf(path, sizeof path, "%s/%s", dir, name);
    int fd = open(path, O_RDONLY | O_CLOEXEC); struct stat st; if (fd < 0 || fstat(fd, &st) || st.st_size <= 0 || st.st_size > 1 << 26) { if (fd >= 0) close(fd); return; }
    uint8_t *b = malloc((size_t)st.st_size); ssize_t got = b ? read(fd, b, (size_t)st.st_size) : -1; close(fd);
    int s = got == st.st_size ? socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC, 0) : -1;
    struct sockaddr_in a = {.sin_family = AF_INET, .sin_port = htons((uint16_t)p->port), .sin_addr.s_addr = htonl(INADDR_LOOPBACK)};
    struct timeval tv = {10, 0}; if (s >= 0) { setsockopt(s, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof tv); setsockopt(s, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof tv); }
    if (s >= 0 && !connect(s, (struct sockaddr *)&a, sizeof a)) {
        char q[300]; int o = snprintf(q, sizeof q, "PUT /%s/captured/%s HTTP/1.0\r\nContent-Length: %lld\r\n\r\n", p->token, name, (long long)st.st_size);
        if (send(s, q, (size_t)o, MSG_NOSIGNAL) == o) {
            ssize_t off = 0; while (off < got) { ssize_t k = send(s, b + off, (size_t)(got - off), MSG_NOSIGNAL); if (k <= 0) break; off += k; }
            char ans[64]; (void)!recv(s, ans, sizeof ans, 0);
        }
    }
    if (s >= 0) close(s);
    free(b);
}
static void ps_captures(const ps_t *p) {
    char dir[PATH_MAX]; snprintf(dir, sizeof dir, "%s/captured_shaders", p->root);
    DIR *d = opendir(dir); if (!d) return;
    for (struct dirent *e; (e = readdir(d)); ) { size_t l = strlen(e->d_name); if (l == 20 && !strcmp(e->d_name + 16, ".bin")) ps_put(p, dir, e->d_name); }
    closedir(d);
}

// ---- files ----
typedef struct { long long size; uint32_t crc; int keep, need; const char *name; } ps_file;
static int ps_name_cmp(const void *a, const void *b) { return strcmp(((const ps_file *)a)->name, ((const ps_file *)b)->name); }
static int ps_safe(const char *n) { return n[0] && n[0] != '/' && !strstr(n, ".."); }   // the list only ever names files inside the folder
static void ps_mkdirs(const char *path) {
    char d[PATH_MAX]; snprintf(d, sizeof d, "%s", path);
    for (char *c = d + 1; *c; c++) if (*c == '/') { *c = 0; mkdir(d, 0700); *c = '/'; }
}
static void ps_stale(const char *dir, const char *rel, const ps_file *by_name, size_t n) {   // what the pack no longer has in a folder it owns: an older version's files, which the game would still find
    DIR *d = opendir(dir); if (!d) return;
    for (struct dirent *e; (e = readdir(d)); ) {
        if (!strcmp(e->d_name, ".") || !strcmp(e->d_name, "..")) continue;
        char full[PATH_MAX], sub[PATH_MAX]; snprintf(full, sizeof full, "%s/%s", dir, e->d_name); snprintf(sub, sizeof sub, "%s/%s", rel, e->d_name);
        struct stat st; if (lstat(full, &st)) continue;
        if (S_ISDIR(st.st_mode)) { ps_stale(full, sub, by_name, n); rmdir(full); continue; }   // rmdir: only if it is now empty
        size_t l = strlen(sub); if (l > 5 && !strcmp(sub + l - 5, ".part")) continue;
        ps_file key = {.name = sub}; if (!bsearch(&key, by_name, n, sizeof key, ps_name_cmp)) unlink(full);
    }
    closedir(d);
}
static void ps_old_captures(const char *root) {   // an older frame capture: half a GB
    DIR *d = opendir(root); if (!d) return;
    for (struct dirent *e; (e = readdir(d)); ) if (!strncmp(e->d_name, "pass_", 5)) { char f[PATH_MAX]; snprintf(f, sizeof f, "%s/%s", root, e->d_name); unlink(f); }
    closedir(d);
}
static int ps_fetch(ps_t *p, const ps_file *f, uint8_t *buf) {   // into <name>.part, so a stopped copy goes on; 0 when the file is whole and checked
    char dst[PATH_MAX], part[PATH_MAX], what[PATH_MAX]; snprintf(dst, sizeof dst, "%s/%s", p->root, f->name); snprintf(part, sizeof part, "%s.part", dst); snprintf(what, sizeof what, "file/%s", f->name);
    ps_mkdirs(dst);
    for (int attempt = 0; attempt < 6; attempt++) {
        if (attempt) sleep(1);
        struct stat st; long long have = stat(part, &st) ? 0 : st.st_size;
        if (have > f->size) { unlink(part); have = 0; }
        if (!f->size) close(open(part, O_WRONLY | O_CREAT | O_CLOEXEC, 0600));
        atomic_fetch_add(&p->done, have);
        long long body = -1; int s = have < f->size ? ps_get(p, what, have, 60, &body) : INT_MAX;
        if (s == -2) { atomic_fetch_sub(&p->done, have); return -1; }
        if (s == -1) { atomic_fetch_sub(&p->done, have); continue; }
        long long at = have;
        if (s != INT_MAX) {
            int fd = open(part, O_WRONLY | O_CREAT | O_CLOEXEC, 0600);
            if (fd < 0) { close(s); snprintf(p->msg, sizeof p->msg, "cannot write %s: %s", f->name, strerror(errno)); return -1; }
            lseek(fd, have, SEEK_SET);
            for (ssize_t r; (r = recv(s, buf, 1 << 20, 0)) > 0; at += r) {
                ssize_t w = 0; while (w < r) { ssize_t k = write(fd, buf + w, (size_t)(r - w)); if (k <= 0) { close(fd); close(s); snprintf(p->msg, sizeof p->msg, "cannot write %s: %s", f->name, strerror(errno)); return -1; } w += k; }
                atomic_fetch_add(&p->done, r);
            }
            close(fd); close(s);
        }
        if (at == f->size) {
            if (!f->crc || ps_fingerprint(part, f->size) == f->crc) { if (!rename(part, dst)) return 0; snprintf(p->msg, sizeof p->msg, "cannot finish %s: %s", f->name, strerror(errno)); return -1; }
            unlink(part);   // the next try starts the file again
        }
        atomic_fetch_sub(&p->done, at);
    }
    snprintf(p->msg, sizeof p->msg, "the copy of %s kept stopping", f->name);
    return -1;
}

// The whole job. 0: the folder matches the pack
static int ps_run(ps_t *p) {
    char *list = ps_text(p, "list");
    if (!list) { snprintf(p->msg, sizeof p->msg, "the builder app did not send the list of files"); return -1; }
    size_t nf = 0, cap = 1024, no = 0; ps_file *f = malloc(cap * sizeof *f); const char *owned[16];
    for (char *l = list, *e; *l; l = e + 1) {   // F size crc keep name, or O name; the manifest comes last
        e = strchr(l, '\n'); if (!e) break; *e = 0;
        if (l[0] == 'O' && l[1] == ' ' && no < 16 && ps_safe(l + 2)) owned[no++] = l + 2;
        else if (l[0] == 'F') {
            long long size; unsigned crc; int keep, at = 0;
            if (sscanf(l, "F %lld %x %d %n", &size, &crc, &keep, &at) != 3 || !at || !ps_safe(l + at)) continue;
            if (nf == cap) f = realloc(f, (cap *= 2) * sizeof *f);
            f[nf++] = (ps_file){size, crc, keep, 0, l + at};
        }
    }
    int rc = -1; long long need = 0, freed = 0, part_bytes = 0; size_t todo = 0;
    atomic_store(&p->total, (long long)nf); atomic_store(&p->done, 0);
    for (size_t i = 0; i < nf; i++) {
        char dst[PATH_MAX], part[PATH_MAX]; snprintf(dst, sizeof dst, "%s/%s", p->root, f[i].name); snprintf(part, sizeof part, "%s.part", dst);
        struct stat st; int have = !stat(dst, &st);
        f[i].need = !have || (!f[i].keep && (st.st_size != f[i].size || (f[i].crc && ps_fingerprint(dst, f[i].size) != f[i].crc)));
        if (f[i].need) {
            todo++; need += f[i].size; if (have) freed += st.st_size;
            if (!stat(part, &st) && st.st_size <= f[i].size) part_bytes += st.st_size;
        }
        atomic_fetch_add(&p->done, 1);
    }
    if (todo && nf) { char m[PATH_MAX]; snprintf(m, sizeof m, "%s/%s", p->root, f[nf - 1].name); if (!f[nf - 1].need) { f[nf - 1].need = 1; todo++; need += f[nf - 1].size; } unlink(m); }   // a folder with its manifest is a complete one
    struct statvfs v;
    if (!statvfs(p->root, &v) && (long long)v.f_bavail * (long long)v.f_frsize + freed + part_bytes < need + (64ll << 20)) {
        snprintf(p->msg, sizeof p->msg, "the tablet has %.1f GB free and the game needs %.1f GB more", (double)v.f_bavail * v.f_frsize / (1 << 30), (double)(need - freed - part_bytes) / (1 << 30));
        goto out;
    }
    { char m[64]; snprintf(m, sizeof m, "need/%lld", need); ps_say(p, m); }
    atomic_store(&p->done, 0); atomic_store(&p->total, need); atomic_store(&p->phase, PS_COPYING);
    { uint8_t *buf = malloc(1 << 20);
      for (size_t i = 0; i < nf; i++) {
          if (!f[i].need) continue;
          char dst[PATH_MAX]; snprintf(dst, sizeof dst, "%s/%s", p->root, f[i].name); unlink(dst);   // first, so a changed big file needs no room for both
          if (ps_fetch(p, &f[i], buf)) { free(buf); if (!p->msg[0]) snprintf(p->msg, sizeof p->msg, "the builder app stopped sending %s", f[i].name); goto out; }
      }
      free(buf); }
    atomic_store(&p->phase, PS_FINISHING);
    { ps_file *sorted = malloc((nf + 1) * sizeof *sorted); memcpy(sorted, f, nf * sizeof *f); qsort(sorted, nf, sizeof *sorted, ps_name_cmp);
      for (size_t i = 0; i < no; i++) { char d[PATH_MAX]; snprintf(d, sizeof d, "%s/%s", p->root, owned[i]); ps_stale(d, owned[i], sorted, nf); }
      free(sorted); }
    ps_old_captures(p->root);
    ps_captures(p);
    rc = 0;
out:
    free(f); free(list);
    return rc;
}
