/*
 * Amphora wineandroid host: keep ANativeWindow in :session (Kotlin SurfaceView)
 * and serve dequeue/queue/query/perform over a per-HWND AF_UNIX socketpair.
 * Wine receives the peer fd via SCM_RIGHTS on HOST_SURFACE_CHANGED and installs
 * a forwarding parent for register_native_window (same native_handle contract
 * as dlls/wineandroid.drv/device.c).
 */
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <dlfcn.h>
#include <errno.h>
#include <jni.h>
#include <pthread.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>
#include <stdio.h>
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <poll.h>

#define LOG_TAG "WineAndroidHostAnw"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

/* Mirror of wineandroid android_native.h ANativeWindow (producer ops). */
struct wine_native_base {
    int magic;
    int version;
    void *reserved[4];
    void (*incRef)(struct wine_native_base *base);
    void (*decRef)(struct wine_native_base *base);
};

struct wine_native_buffer {
    struct wine_native_base common;
    int width, height, stride, format, usage;
    void *reserved[2];
    const int *handle; /* native_handle_t* */
    void *reserved_proc[8];
};

struct wine_native_window {
    struct wine_native_base common;
    uint32_t flags;
    int minSwapInterval, maxSwapInterval;
    float xdpi, ydpi;
    intptr_t oem[4];
    int (*setSwapInterval)(struct wine_native_window *window, int interval);
    int (*dequeueBuffer_DEPRECATED)(struct wine_native_window *window,
                                     struct wine_native_buffer **buffer);
    int (*lockBuffer_DEPRECATED)(struct wine_native_window *window,
                                 struct wine_native_buffer *buffer);
    int (*queueBuffer_DEPRECATED)(struct wine_native_window *window,
                                  struct wine_native_buffer *buffer);
    int (*query)(const struct wine_native_window *window, int what, int *value);
    int (*perform)(struct wine_native_window *window, int operation, ...);
    int (*cancelBuffer_DEPRECATED)(struct wine_native_window *window,
                                   struct wine_native_buffer *buffer);
    int (*dequeueBuffer)(struct wine_native_window *window,
                         struct wine_native_buffer **buffer, int *fenceFd);
    int (*queueBuffer)(struct wine_native_window *window,
                       struct wine_native_buffer *buffer, int fenceFd);
    int (*cancelBuffer)(struct wine_native_window *window,
                        struct wine_native_buffer *buffer, int fenceFd);
};

/* native_handle_t */
typedef struct {
    int version;
    int numFds;
    int numInts;
    int data[0];
} native_handle_t;

enum {
    CMD_DEQUEUE = 1,
    CMD_QUEUE = 2,
    CMD_CANCEL = 3,
    CMD_QUERY = 4,
    CMD_PERFORM = 5,
    CMD_SET_SWAP = 6,
    CMD_VK_PRESENT = 7,
    CMD_STOP = 99,
};

#define NB_BUFFERS 8

struct serve_ctx {
    struct wine_native_window *win;
    int sock;
    int hwnd;
    int generation;
    struct wine_native_buffer *buffers[NB_BUFFERS];
    int buffer_lru[NB_BUFFERS];
};

static int write_full(int fd, const void *buf, size_t len)
{
    const char *p = buf;
    while (len) {
        ssize_t n = write(fd, p, len);
        if (n < 0) {
            if (errno == EINTR) continue;
            return -1;
        }
        if (!n) return -1;
        p += n;
        len -= (size_t)n;
    }
    return 0;
}

static int read_full(int fd, void *buf, size_t len)
{
    char *p = buf;
    while (len) {
        ssize_t n = read(fd, p, len);
        if (n < 0) {
            if (errno == EINTR) continue;
            return -1;
        }
        if (!n) return -1;
        p += n;
        len -= (size_t)n;
    }
    return 0;
}


/* Post-Present diag: mmap/AHB-lock client GraphicBuffer on QUEUE N=50/100. */
static int g_anw_client_queue_n;       /* hwnd-sized client QUEUE count */
static int g_anw_mmap_dumps;           /* successful dumps so far (max 2) */
static int g_anw_dump50_was_black;     /* dump on q100 only if q50 was BLACK */

/* NDK AHardwareBuffer usage CPU read bits (hardware_buffer.h). */
#ifndef AHARDWAREBUFFER_USAGE_CPU_READ_RARELY
#define AHARDWAREBUFFER_USAGE_CPU_READ_RARELY  2ULL  /* 1ULL<<1 */
#endif
#ifndef AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN
#define AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN   3ULL  /* rarely|often; NOT 1ULL<<3 */
#endif

static int anw_mmap_try(const native_handle_t *nh, size_t size, void **out)
{
    int i;
    if (!nh || nh->numFds < 1 || !size) return -1;
    for (i = 0; i < nh->numFds; i++) {
        void *p = mmap(NULL, size, PROT_READ, MAP_SHARED, nh->data[i], 0);
        if (p != MAP_FAILED) {
            *out = p;
            return nh->data[i];
        }
    }
    return -1;
}

static void anw_write_bmp_crop(const char *path, const uint8_t *bits, int stride_px,
                               int width, int height, int cx, int cy, int cw, int ch)
{
    /* Uncompressed BGR BMP, bottom-up. */
    int x0 = cx - cw / 2, y0 = cy - ch / 2, row_bytes, x, y, fd;
    uint8_t hdr[54];
    uint32_t file_size, off = 54, dib = 40;
    if (x0 < 0) x0 = 0;
    if (y0 < 0) y0 = 0;
    if (x0 + cw > width) cw = width - x0;
    if (y0 + ch > height) ch = height - y0;
    if (cw < 1 || ch < 1) return;
    row_bytes = (cw * 3 + 3) & ~3;
    file_size = off + (uint32_t)row_bytes * (uint32_t)ch;
    memset(hdr, 0, sizeof(hdr));
    hdr[0] = 'B'; hdr[1] = 'M';
    memcpy(hdr + 2, &file_size, 4);
    memcpy(hdr + 10, &off, 4);
    memcpy(hdr + 14, &dib, 4);
    memcpy(hdr + 18, &cw, 4);
    memcpy(hdr + 22, &ch, 4);
    hdr[26] = 1; hdr[28] = 24;
    fd = open(path, O_CREAT | O_TRUNC | O_WRONLY, 0666);
    if (fd < 0) {
        LOGW("mmap-dump open %s failed errno=%d", path, errno);
        return;
    }
    if (write_full(fd, hdr, sizeof(hdr))) {
        close(fd);
        return;
    }
    for (y = ch - 1; y >= 0; y--) {
        uint8_t row[64 * 3 + 4];
        memset(row, 0, (size_t)row_bytes);
        for (x = 0; x < cw; x++) {
            const uint8_t *px = bits + ((size_t)(y0 + y) * (size_t)stride_px + (size_t)(x0 + x)) * 4u;
            row[x * 3 + 0] = px[2]; /* B */
            row[x * 3 + 1] = px[1]; /* G */
            row[x * 3 + 2] = px[0]; /* R */
        }
        if (write_full(fd, row, (size_t)row_bytes)) break;
    }
    close(fd);
    LOGI("mmap-dump wrote %s crop=%dx%d@%d,%d", path, cw, ch, x0, y0);
}

/* Cross-process buffer: Adreno Mapper5 needs AHardwareBuffer_send/recv, not raw SCM_RIGHTS. */
enum { AMPHORA_AHB_NUMFDS = -1 };
typedef void *(*pfn_anwb_get_ahb)(const struct wine_native_buffer *);
typedef int (*pfn_ahb_send_handle)(const void *, int);
typedef void (*pfn_ahb_acquire)(void *);
typedef void (*pfn_ahb_release)(void *);
typedef int (*pfn_ahb_lock)(void *buffer, uint64_t usage, int32_t fence,
                            const void *rect, void **outVirt);
typedef int (*pfn_ahb_unlock)(void *buffer, int32_t *fence);
typedef const native_handle_t *(*pfn_ahb_get_native_handle)(void *);

static pfn_anwb_get_ahb g_anwb_get_ahb;
static pfn_ahb_send_handle g_ahb_send;
static pfn_ahb_acquire g_ahb_acquire;
static pfn_ahb_release g_ahb_release;
static pfn_ahb_lock g_ahb_lock;
static pfn_ahb_unlock g_ahb_unlock;
static pfn_ahb_get_native_handle g_ahb_get_native_handle;
static int g_ahb_host_resolved;

static void host_resolve_ahb(void)
{
    void *lib;
    if (g_ahb_host_resolved) return;
    g_ahb_host_resolved = 1;
    lib = dlopen("libnativewindow.so", RTLD_NOW);
    if (!lib) { LOGE("dlopen libnativewindow: %s", dlerror()); return; }
    /* Official: AHB from dequeued ANativeWindowBuffer — does not steal BufferQueue slot. */
    g_anwb_get_ahb = (pfn_anwb_get_ahb)dlsym(lib, "ANativeWindowBuffer_getHardwareBuffer");
    g_ahb_send = (pfn_ahb_send_handle)dlsym(lib, "AHardwareBuffer_sendHandleToUnixSocket");
    g_ahb_acquire = (pfn_ahb_acquire)dlsym(lib, "AHardwareBuffer_acquire");
    g_ahb_release = (pfn_ahb_release)dlsym(lib, "AHardwareBuffer_release");
    g_ahb_lock = (pfn_ahb_lock)dlsym(lib, "AHardwareBuffer_lock");
    g_ahb_unlock = (pfn_ahb_unlock)dlsym(lib, "AHardwareBuffer_unlock");
    g_ahb_get_native_handle = (pfn_ahb_get_native_handle)dlsym(lib, "AHardwareBuffer_getNativeHandle");
    LOGI("AHB_SEND symbols getHwBuf=%p send=%p acquire=%p release=%p lock=%p unlock=%p getNh=%p",
         (void *)g_anwb_get_ahb, (void *)g_ahb_send, (void *)g_ahb_acquire,
         (void *)g_ahb_release, (void *)g_ahb_lock, (void *)g_ahb_unlock,
         (void *)g_ahb_get_native_handle);
}

/* knife7: fstat native_handle fds for buffer-fork identity match. */
static void anw_log_nh_fstat(const char *tag, const native_handle_t *nh)
{
    int i, nints;
    if (!nh) {
        LOGW("%s nh=null", tag);
        return;
    }
    LOGI("%s numFds=%d numInts=%d", tag, nh->numFds, nh->numInts);
    for (i = 0; i < nh->numFds; i++) {
        struct stat st;
        if (fstat(nh->data[i], &st) == 0)
            LOGI("%s fd[%d]=%d st_dev=%llu st_ino=%llu st_size=%lld",
                 tag, i, nh->data[i],
                 (unsigned long long)st.st_dev,
                 (unsigned long long)st.st_ino,
                 (long long)st.st_size);
        else
            LOGW("%s fd[%d]=%d fstat errno=%d", tag, i, nh->data[i], errno);
    }
    nints = nh->numInts < 4 ? nh->numInts : 4;
    if (nints > 0) {
        int a = nh->data[nh->numFds + 0];
        int b = nints > 1 ? nh->data[nh->numFds + 1] : 0;
        int c = nints > 2 ? nh->data[nh->numFds + 2] : 0;
        int d = nints > 3 ? nh->data[nh->numFds + 3] : 0;
        LOGI("%s ints[0..3]=%d,%d,%d,%d (n=%d)", tag, a, b, c, d, nh->numInts);
    }
}

static void anw_log_ahb_identity(const char *tag, int id, int hwnd, void *ahb,
                                 struct wine_native_buffer *buf)
{
    const native_handle_t *nh = NULL;
    if (!buf) return;
    LOGI("%s id=%d hwnd=%08x ahb=%p %dx%d stride=%d fmt=%d usage=0x%x",
         tag, id, hwnd, ahb,
         buf->width, buf->height, buf->stride, buf->format, buf->usage);
    host_resolve_ahb();
    if (ahb && g_ahb_get_native_handle)
        nh = g_ahb_get_native_handle(ahb);
    if (!nh && buf->handle)
        nh = (const native_handle_t *)buf->handle;
    anw_log_nh_fstat(tag, nh);
}

static void anw_fence_wait_brief(int fenceFd)
{
    struct pollfd pfd;
    int pr;
    if (fenceFd < 0) {
        LOGI("mmap-dump fenceFd=-1 (guest waits before QUEUE; host has none) — dump anyway");
        return;
    }
    pfd.fd = fenceFd;
    pfd.events = POLLIN;
    pfd.revents = 0;
    pr = poll(&pfd, 1, 100); /* 100ms brief wait */
    LOGI("mmap-dump fenceFd=%d poll=%d revents=0x%x errno=%d", fenceFd, pr, pfd.revents, errno);
    close(fenceFd);
}

/* Prefer AHardwareBuffer_lock (CPU cache sync); fall back to raw mmap. */
static int anw_lock_pixels(struct wine_native_buffer *buf, void **out_bits,
                           size_t *out_map_size, int *out_via_ahb, void **out_ahb)
{
    const native_handle_t *nh;
    size_t map_size;
    void *ahb = NULL;
    void *bits = NULL;
    int fd, rc;

    *out_bits = NULL;
    *out_via_ahb = 0;
    *out_ahb = NULL;
    if (!buf || !buf->handle) return -1;

    nh = (const native_handle_t *)buf->handle;
    map_size = (size_t)(buf->stride > 0 ? buf->stride : buf->width) *
               (size_t)buf->height * 4u;
    *out_map_size = map_size;

    host_resolve_ahb();
    if (g_anwb_get_ahb && g_ahb_lock && g_ahb_unlock) {
        ahb = g_anwb_get_ahb(buf);
        if (ahb) {
            if (g_ahb_acquire) g_ahb_acquire(ahb);
            rc = g_ahb_lock(ahb, AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN, -1, NULL, &bits);
            if (rc != 0 || !bits) {
                LOGW("mmap-dump AHB_lock OFTEN rc=%d errno=%d — retry RARELY", rc, errno);
                bits = NULL;
                rc = g_ahb_lock(ahb, AHARDWAREBUFFER_USAGE_CPU_READ_RARELY, -1, NULL, &bits);
            }
            if (rc == 0 && bits) {
                *out_bits = bits;
                *out_via_ahb = 1;
                *out_ahb = ahb;
                return 0;
            }
            LOGW("mmap-dump AHB_lock FAIL rc=%d errno=%d — fall back mmap", rc, errno);
            if (g_ahb_release) g_ahb_release(ahb);
            ahb = NULL;
            bits = NULL;
        } else {
            LOGW("mmap-dump getHardwareBuffer null — fall back mmap");
        }
    } else {
        LOGW("mmap-dump AHB lock symbols missing — fall back mmap");
    }

    fd = anw_mmap_try(nh, map_size, &bits);
    if (fd < 0 || !bits) {
        LOGW("mmap-dump mmap FAIL numFds=%d errno=%d", nh ? nh->numFds : -1, errno);
        return -1;
    }
    *out_bits = bits;
    *out_via_ahb = 0;
    return fd; /* mmap fd for logging; >= 0 */
}

static void anw_unlock_pixels(void *bits, size_t map_size, int via_ahb, void *ahb)
{
    if (via_ahb && ahb) {
        if (g_ahb_unlock) g_ahb_unlock(ahb, NULL);
        if (g_ahb_release) g_ahb_release(ahb);
    } else if (bits && map_size) {
        munmap(bits, map_size);
    }
}

static void anw_dump_client_queue_pixels(struct wine_native_buffer *buf, int hwnd,
                                         int buffer_id, int fenceFd)
{
    size_t map_size = 0;
    void *bits = NULL;
    void *ahb = NULL;
    void *id_ahb = NULL;
    int via_ahb = 0, stride_px, cx, cy, mmap_fd;
    const uint8_t *p;
    int r, g, b, a;
    int r2, g2, b2;
    const char *cls;
    char path[160];
    int queue_n;

    if (!buf || !buf->handle) return;
    /* Client DXGI smoke is 632x446; skip desktop/taskbar. */
    if (buf->width < 200 || buf->height < 200) return;
    if (buf->width > 900 || buf->height > 700) return;

    g_anw_client_queue_n++;
    queue_n = g_anw_client_queue_n;
    /* knife7: always dump both QUEUE 50 and 100 (no BLACK gate). */
    if (queue_n != 50 && queue_n != 100) return;

    anw_fence_wait_brief(fenceFd);

    /* HOST_ID before CLASS — identity of buffer being locked. */
    host_resolve_ahb();
    if (g_anwb_get_ahb) {
        id_ahb = g_anwb_get_ahb(buf);
        if (id_ahb && g_ahb_acquire) g_ahb_acquire(id_ahb);
    }
    anw_log_ahb_identity("HOST_ID", buffer_id, hwnd, id_ahb, buf);
    if (id_ahb && g_ahb_release) g_ahb_release(id_ahb);

    mmap_fd = anw_lock_pixels(buf, &bits, &map_size, &via_ahb, &ahb);
    if (mmap_fd < 0 || !bits) {
        LOGW("mmap-dump FAIL queue_n=%d hwnd=%08x %dx%d fmt=%d stride=%d",
             queue_n, hwnd, buf->width, buf->height, buf->format, buf->stride);
        return;
    }

    stride_px = buf->stride > 0 ? buf->stride : buf->width;
    cx = buf->width / 2;
    cy = buf->height / 2;
    p = (const uint8_t *)bits + ((size_t)cy * (size_t)stride_px + (size_t)cx) * 4u;
    r = p[0]; g = p[1]; b = p[2]; a = p[3];
    /* also sample near-top-left interior */
    p = (const uint8_t *)bits + ((size_t)16 * (size_t)stride_px + (size_t)16) * 4u;
    r2 = p[0]; g2 = p[1]; b2 = p[2];

    /* DXVK clear ~0.85/0.10/0.70 -> ~217,26,179 */
    if ((r > 217 ? r - 217 : 217 - r) <= 40 &&
        (g > 26 ? g - 26 : 26 - g) <= 40 &&
        (b > 179 ? b - 179 : 179 - b) <= 40)
        cls = "MAGENTA";
    else if (r >= 240 && g >= 240 && b >= 240)
        cls = "WHITE";
    else if (r <= 15 && g <= 15 && b <= 15)
        cls = "BLACK";
    else
        cls = "OTHER";

    snprintf(path, sizeof(path),
             "/data/data/app.amphora/files/tmp/anw-mmap-q%d-%dx%d.bmp",
             queue_n, buf->width, buf->height);
    anw_write_bmp_crop(path, (const uint8_t *)bits, stride_px,
                       buf->width, buf->height, cx, cy, 64, 64);

    LOGI("mmap-dump CLASS=%s queue_n=%d hwnd=%08x %dx%d fmt=%d stride=%d "
         "centerRGBA=%d,%d,%d,%d tlRGBA=%d,%d,%d via=%s path=%s",
         cls, queue_n, hwnd, buf->width, buf->height, buf->format, buf->stride,
         r, g, b, a, r2, g2, b2, via_ahb ? "AHB_lock" : "mmap", path);

    anw_unlock_pixels(bits, map_size, via_ahb, ahb);
    g_anw_mmap_dumps++;
    if (queue_n == 50)
        g_anw_dump50_was_black = (cls[0] == 'B'); /* retained for log continuity */
}

static void *host_ahb_from_buffer(struct wine_native_buffer *buffer)
{
    void *ahb;
    host_resolve_ahb();
    if (!g_anwb_get_ahb || !g_ahb_send || !g_ahb_release || !buffer) return NULL;
    ahb = g_anwb_get_ahb(buffer);
    if (!ahb) {
        LOGW("AHB_SEND ANativeWindowBuffer_getHardwareBuffer returned null");
        return NULL;
    }
    /* getHardwareBuffer does not always acquire — acquire so release is safe after send. */
    if (g_ahb_acquire) g_ahb_acquire(ahb);
    return ahb;
}


static int send_handle_reply(int sock, int status, struct wine_native_buffer *buffer,
                             int buffer_id, int generation)
{
    struct {
        int32_t status;
        int32_t width, height, stride, format, usage;
        int32_t buffer_id, generation;
        int32_t numFds, numInts;
    } hdr;
    const native_handle_t *nh = NULL;
    struct msghdr msg;
    struct iovec iov;
    char control[CMSG_SPACE(sizeof(int) * 64)];
    size_t ints_bytes = 0;
    const int *ints = NULL;
    void *ahb = NULL;

    memset(&hdr, 0, sizeof(hdr));
    hdr.status = status;
    hdr.buffer_id = buffer_id;
    hdr.generation = generation;
    if (!status && buffer && buffer->handle) {
        nh = (const native_handle_t *)buffer->handle;
        hdr.width = buffer->width;
        hdr.height = buffer->height;
        hdr.stride = buffer->stride;
        hdr.format = buffer->format;
        hdr.usage = buffer->usage;
        hdr.numFds = nh->numFds;
        hdr.numInts = nh->numInts;
        ints = &nh->data[nh->numFds];
        ints_bytes = (size_t)nh->numInts * sizeof(int);
        /* Create AHB first so we only advertise numFds=-1 when send can succeed. */
        ahb = host_ahb_from_buffer(buffer);
        if (ahb) {
            hdr.numFds = AMPHORA_AHB_NUMFDS;
            hdr.numInts = 0;
            ints = NULL;
            ints_bytes = 0;
        }
    }

    memset(&msg, 0, sizeof(msg));
    iov.iov_base = &hdr;
    iov.iov_len = sizeof(hdr);
    msg.msg_iov = &iov;
    msg.msg_iovlen = 1;

    if (!ahb && nh && nh->numFds > 0) {
        struct cmsghdr *cmsg;
        size_t fdbytes = sizeof(int) * (size_t)nh->numFds;
        if (fdbytes > sizeof(control) - sizeof(struct cmsghdr)) {
            if (ahb && g_ahb_release) g_ahb_release(ahb);
            return -1;
        }
        msg.msg_control = control;
        msg.msg_controllen = CMSG_SPACE(fdbytes);
        cmsg = CMSG_FIRSTHDR(&msg);
        cmsg->cmsg_level = SOL_SOCKET;
        cmsg->cmsg_type = SCM_RIGHTS;
        cmsg->cmsg_len = CMSG_LEN(fdbytes);
        memcpy(CMSG_DATA(cmsg), nh->data, fdbytes);
    }

    for (;;) {
        ssize_t n = sendmsg(sock, &msg, 0);
        if (n < 0) {
            if (errno == EINTR) continue;
            if (ahb && g_ahb_release) g_ahb_release(ahb);
            return -1;
        }
        break;
    }
    if (ahb) {
        int rc = g_ahb_send(ahb, sock);
        if (rc != 0) {
            LOGE("AHB_SEND sendHandle failed rc=%d id=%d", rc, buffer_id);
            g_ahb_release(ahb);
            return -1;
        }
        LOGI("AHB_SEND ok id=%d %dx%d fmt=%d usage=0x%x",
             buffer_id, hdr.width, hdr.height, hdr.format, hdr.usage);
        /* knife7: identity of buffer just sent — guest GUEST_RECV can match. */
        anw_log_ahb_identity("HOST_SEND", buffer_id, 0, ahb, buffer);
        g_ahb_release(ahb);
        return 0;
    }
    if (ints_bytes && write_full(sock, ints, ints_bytes)) return -1;
    return 0;
}

static int register_buf(struct serve_ctx *ctx, struct wine_native_buffer *buffer)
{
    int i, free_idx = -1, lru_idx = -1;
    for (i = 0; i < NB_BUFFERS; i++) {
        if (ctx->buffers[i] == buffer) return i;
        if (!ctx->buffers[i] && free_idx < 0) free_idx = i;
    }
    if (free_idx < 0) {
        /* drop least recently used */
        lru_idx = ctx->buffer_lru[NB_BUFFERS - 1];
        if (lru_idx < 0) lru_idx = 0;
        if (ctx->buffers[lru_idx]) {
            /* leave buffer lifetime to ANativeWindow */
            ctx->buffers[lru_idx] = NULL;
        }
        free_idx = lru_idx;
    }
    ctx->buffers[free_idx] = buffer;
    /* bump LRU head */
    for (i = 0; i < NB_BUFFERS; i++) {
        if (ctx->buffer_lru[i] == free_idx) break;
        if (ctx->buffer_lru[i] == -1) break;
    }
    if (i >= NB_BUFFERS) i = NB_BUFFERS - 1;
    memmove(ctx->buffer_lru + 1, ctx->buffer_lru, (size_t)i * sizeof(int));
    ctx->buffer_lru[0] = free_idx;
    return free_idx;
}

static void *serve_thread(void *arg)
{
    struct serve_ctx *ctx = arg;
    int32_t last_cmd = 0;
    int last_op_ret = 0;
    int saw_queue = 0;
    int queue_ret = 0;
    const char *exit_why = "loop-end";
    int exit_errno = 0;

    LOGI("buffer serve start hwnd=%08x sock=%d", ctx->hwnd, ctx->sock);

    for (;;) {
        int32_t cmd;
        if (read_full(ctx->sock, &cmd, sizeof(cmd))) {
            exit_errno = errno;
            exit_why = (exit_errno == 0) ? "read-cmd-EOF" : "read-cmd-err";
            break;
        }
        last_cmd = cmd;

        if (cmd == CMD_STOP) {
            exit_why = "CMD_STOP";
            break;
        }

        if (cmd == CMD_DEQUEUE) {
            struct wine_native_buffer *buffer = NULL;
            int fence = -1;
            int ret = ctx->win->dequeueBuffer(ctx->win, &buffer, &fence);
            int id = -1;
            last_op_ret = ret;
            if (fence >= 0) close(fence);
            if (!ret && buffer) id = register_buf(ctx, buffer);
            LOGI("serve DEQUEUE hwnd=%08x ret=%d id=%d gen=%d %dx%d fmt=%d",
                 ctx->hwnd, ret, id, ctx->generation,
                 buffer ? buffer->width : -1, buffer ? buffer->height : -1,
                 buffer ? buffer->format : -1);
            if (send_handle_reply(ctx->sock, ret, buffer, id, ctx->generation)) {
                exit_errno = errno;
                exit_why = (exit_errno == 0) ? "dequeue-reply-EOF" : "dequeue-reply-err";
                break;
            }
            continue;
        }

        if (cmd == CMD_QUEUE || cmd == CMD_CANCEL) {
            int32_t buffer_id = -1, generation = 0;
            int ret = -EINVAL;
            if (read_full(ctx->sock, &buffer_id, sizeof(buffer_id))) {
                exit_errno = errno;
                exit_why = (exit_errno == 0) ? "queue-read-id-EOF" : "queue-read-id-err";
                break;
            }
            if (read_full(ctx->sock, &generation, sizeof(generation))) {
                exit_errno = errno;
                exit_why = (exit_errno == 0) ? "queue-read-gen-EOF" : "queue-read-gen-err";
                break;
            }
            if (generation == ctx->generation && buffer_id >= 0 && buffer_id < NB_BUFFERS &&
                ctx->buffers[buffer_id]) {
                if (cmd == CMD_QUEUE) {
                    /* Sample after Present wrote pixels (QUEUE n=50/100); fence always -1 here. */
                    anw_dump_client_queue_pixels(ctx->buffers[buffer_id], ctx->hwnd, buffer_id, -1);
                    ret = ctx->win->queueBuffer(ctx->win, ctx->buffers[buffer_id], -1);
                } else
                    ret = ctx->win->cancelBuffer(ctx->win, ctx->buffers[buffer_id], -1);
            } else {
                ret = 0; /* obsolete */
            }
            last_op_ret = ret;
            if (cmd == CMD_QUEUE) {
                saw_queue = 1;
                queue_ret = ret;
            }
            LOGI("serve %s hwnd=%08x id=%d gen=%d/%d ret=%d",
                 cmd == CMD_QUEUE ? "QUEUE" : "CANCEL",
                 ctx->hwnd, buffer_id, generation, ctx->generation, ret);
            if (write_full(ctx->sock, &ret, sizeof(ret))) {
                exit_errno = errno;
                exit_why = (exit_errno == 0) ? "queue-write-ret-EOF" : "queue-write-ret-err";
                break;
            }
            continue;
        }

        if (cmd == CMD_QUERY) {
            int32_t what = 0, value = 0, ret;
            if (read_full(ctx->sock, &what, sizeof(what))) {
                exit_errno = errno;
                exit_why = (exit_errno == 0) ? "query-read-EOF" : "query-read-err";
                break;
            }
            ret = ctx->win->query(ctx->win, what, &value);
            last_op_ret = ret;
            LOGI("serve QUERY hwnd=%08x what=%d ret=%d val=%d", ctx->hwnd, what, ret, value);
            if (write_full(ctx->sock, &ret, sizeof(ret))) {
                exit_errno = errno;
                exit_why = (exit_errno == 0) ? "query-write-ret-EOF" : "query-write-ret-err";
                break;
            }
            if (write_full(ctx->sock, &value, sizeof(value))) {
                exit_errno = errno;
                exit_why = (exit_errno == 0) ? "query-write-val-EOF" : "query-write-val-err";
                break;
            }
            continue;
        }

        if (cmd == CMD_PERFORM) {
            int32_t op = 0, nargs = 0, args[4], ret = -ENOENT;
            if (read_full(ctx->sock, &op, sizeof(op))) {
                exit_errno = errno;
                exit_why = (exit_errno == 0) ? "perform-read-op-EOF" : "perform-read-op-err";
                break;
            }
            if (read_full(ctx->sock, &nargs, sizeof(nargs))) {
                exit_errno = errno;
                exit_why = (exit_errno == 0) ? "perform-read-nargs-EOF" : "perform-read-nargs-err";
                break;
            }
            if (nargs < 0 || nargs > 4) {
                exit_why = "perform-bad-nargs";
                break;
            }
            if (nargs && read_full(ctx->sock, args, sizeof(int32_t) * (size_t)nargs)) {
                exit_errno = errno;
                exit_why = (exit_errno == 0) ? "perform-read-args-EOF" : "perform-read-args-err";
                break;
            }
            switch (op) {
            case 0: /* SET_USAGE */
            case 6: /* SET_BUFFERS_TRANSFORM */
            case 9: /* SET_BUFFERS_FORMAT */
            case 10: /* SET_SCALING_MODE */
            case 13: /* API_CONNECT */
            case 14: /* API_DISCONNECT */
                if (nargs >= 1) ret = ctx->win->perform(ctx->win, op, args[0]);
                break;
            case 4: /* SET_BUFFER_COUNT */
                if (nargs >= 1) ret = ctx->win->perform(ctx->win, op, (size_t)args[0]);
                break;
            case 8: /* SET_BUFFERS_DIMENSIONS */
            case 15: /* SET_BUFFERS_USER_DIMENSIONS */
                if (nargs >= 2) ret = ctx->win->perform(ctx->win, op, args[0], args[1]);
                break;
            case 5: /* SET_BUFFERS_GEOMETRY */
                if (nargs >= 3) ret = ctx->win->perform(ctx->win, op, args[0], args[1], args[2]);
                break;
            case 1: /* CONNECT */
            case 2: /* DISCONNECT */
            case 12: /* UNLOCK_AND_POST */
                ret = ctx->win->perform(ctx->win, op);
                break;
            default:
                ret = -ENOENT;
                break;
            }
            last_op_ret = ret;
            LOGI("serve PERFORM hwnd=%08x op=%d nargs=%d ret=%d", ctx->hwnd, op, nargs, ret);
            if (write_full(ctx->sock, &ret, sizeof(ret))) {
                exit_errno = errno;
                exit_why = (exit_errno == 0) ? "perform-write-ret-EOF" : "perform-write-ret-err";
                break;
            }
            continue;
        }

        if (cmd == CMD_VK_PRESENT) {
            /* Dead path: PE Present uses AHB_SC IPC, not HostVk blit. Keep enum
             * + stub reply so a stray cmd 7 cannot desync the buffer sock. */
            int32_t reply[4] = { -1, -1, -ENOENT, 0 };
            last_op_ret = -ENOENT;
            LOGW("serve VK_PRESENT stub (HostVk removed) hwnd=%08x", ctx->hwnd);
            if (write_full(ctx->sock, reply, sizeof(reply))) {
                exit_errno = errno;
                exit_why = (exit_errno == 0) ? "vkpresent-write-EOF" : "vkpresent-write-err";
                break;
            }
            continue;
        }

        if (cmd == CMD_SET_SWAP) {
            int32_t interval = 0, ret;
            if (read_full(ctx->sock, &interval, sizeof(interval))) {
                exit_errno = errno;
                exit_why = (exit_errno == 0) ? "setswap-read-EOF" : "setswap-read-err";
                break;
            }
            ret = ctx->win->setSwapInterval(ctx->win, interval);
            last_op_ret = ret;
            if (write_full(ctx->sock, &ret, sizeof(ret))) {
                exit_errno = errno;
                exit_why = (exit_errno == 0) ? "setswap-write-ret-EOF" : "setswap-write-ret-err";
                break;
            }
            continue;
        }

        LOGW("unknown buffer cmd %d hwnd=%08x", cmd, ctx->hwnd);
        exit_why = "unknown-cmd";
        break;
    }

    LOGI("buffer serve exit hwnd=%08x why=%s errno=%d(%s) last_cmd=%d last_op_ret=%d queue_seen=%d queue_ret=%d",
         ctx->hwnd, exit_why, exit_errno, exit_errno ? strerror(exit_errno) : "ok",
         last_cmd, last_op_ret, saw_queue, queue_ret);
    close(ctx->sock);
    ANativeWindow_release((ANativeWindow *)ctx->win);
    free(ctx);
    return NULL;
}

/* ---- C API for wineandroid_host_ipc.c (registerSurface AMPHORA_BUF sock) ---- */

void *amphora_host_anw_start_serve(ANativeWindow *win, int hwnd, int sock_fd)
{
    struct serve_ctx *ctx;
    pthread_t th;
    int i;

    if (!win || sock_fd < 0) return NULL;

    ctx = calloc(1, sizeof(*ctx));
    if (!ctx) {
        close(sock_fd);
        return NULL;
    }
    ctx->win = (struct wine_native_window *)win;
    ctx->sock = sock_fd;
    ctx->hwnd = hwnd;
    ctx->generation = 1;
    for (i = 0; i < NB_BUFFERS; i++) ctx->buffer_lru[i] = -1;

    /* serve thread owns one ref; caller may release its copy */
    ANativeWindow_acquire(win);

    if (pthread_create(&th, NULL, serve_thread, ctx)) {
        LOGE("pthread_create failed for hwnd=%08x", hwnd);
        ANativeWindow_release(win);
        close(sock_fd);
        free(ctx);
        return NULL;
    }
    pthread_detach(th);
    LOGI("host AMPHORA_BUF serve started hwnd=%08x sock=%d", hwnd, sock_fd);
    return ctx;
}

void amphora_host_anw_bump_generation(void *serve_ptr)
{
    struct serve_ctx *ctx = serve_ptr;
    if (ctx) ctx->generation++;
}

void amphora_host_anw_stop_serve(void *serve_ptr)
{
    struct serve_ctx *ctx = serve_ptr;
    int32_t cmd = CMD_STOP;
    int fd;

    if (!ctx) return;
    fd = ctx->sock;
    if (fd >= 0) {
        write_full(fd, &cmd, sizeof(cmd));
        /* serve thread closes ctx->sock on exit; shutdown to unblock read */
        shutdown(fd, SHUT_RDWR);
    }
}

JNIEXPORT jlong JNICALL
Java_app_amphora_gamesession_wineandroid_WineAndroidNative_nativeAcquireWindow(
    JNIEnv *env, jclass clazz, jobject surface)
{
    ANativeWindow *win;
    (void)clazz;
    if (!surface) return 0;
    win = ANativeWindow_fromSurface(env, surface);
    if (!win) {
        LOGE("ANativeWindow_fromSurface failed");
        return 0;
    }
    /* fromSurface already acquires one reference for the caller */
    return (jlong)(intptr_t)win;
}

JNIEXPORT void JNICALL
Java_app_amphora_gamesession_wineandroid_WineAndroidNative_nativeReleaseWindow(
    JNIEnv *env, jclass clazz, jlong handle)
{
    (void)env;
    (void)clazz;
    if (!handle) return;
    ANativeWindow_release((ANativeWindow *)(intptr_t)handle);
}

JNIEXPORT void JNICALL
Java_app_amphora_gamesession_wineandroid_WineAndroidNative_nativeBumpGeneration(
    JNIEnv *env, jclass clazz, jlong servePtr)
{
    (void)env;
    (void)clazz;
    amphora_host_anw_bump_generation((void *)(intptr_t)servePtr);
}

JNIEXPORT jlong JNICALL
Java_app_amphora_gamesession_wineandroid_WineAndroidNative_nativeStartBufferServe(
    JNIEnv *env, jclass clazz, jlong windowHandle, jint hwnd, jint sockFd)
{
    (void)env;
    (void)clazz;
    return (jlong)(intptr_t)amphora_host_anw_start_serve(
        (ANativeWindow *)(intptr_t)windowHandle, (int)hwnd, (int)sockFd);
}

JNIEXPORT void JNICALL
Java_app_amphora_gamesession_wineandroid_WineAndroidNative_nativeStopBufferServe(
    JNIEnv *env, jclass clazz, jlong servePtr, jint sockFd)
{
    (void)env;
    (void)clazz;
    (void)sockFd;
    amphora_host_anw_stop_serve((void *)(intptr_t)servePtr);
}

JNIEXPORT jint JNICALL
Java_app_amphora_gamesession_wineandroid_WineAndroidNative_nativeWindowWidth(
    JNIEnv *env, jclass clazz, jlong windowHandle)
{
    (void)env;
    (void)clazz;
    if (!windowHandle) return 0;
    return ANativeWindow_getWidth((ANativeWindow *)(intptr_t)windowHandle);
}

JNIEXPORT jint JNICALL
Java_app_amphora_gamesession_wineandroid_WineAndroidNative_nativeWindowHeight(
    JNIEnv *env, jclass clazz, jlong windowHandle)
{
    (void)env;
    (void)clazz;
    if (!windowHandle) return 0;
    return ANativeWindow_getHeight((ANativeWindow *)(intptr_t)windowHandle);
}
