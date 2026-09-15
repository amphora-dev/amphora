#define _GNU_SOURCE
/*
 * Amphora wineandroid host IPC — upstream Wine wire protocol.
 *
 * Binds abstract AF_UNIX SOCK_SEQPACKET "\0\Device\WineAndroid" and speaks the
 * same ioctl framing as dlls/wineandroid.drv/device.c (backport tip 911b761c370):
 *   request:  [int32 code][payload…]     (one SEQPACKET message)
 *   reply:    [int32 status][payload…]   + optional SCM_RIGHTS
 *
 * ANativeWindow stays in :session. Buffer ioctls dequeue/queue here and pass
 * AHardwareBuffer handles via socketpair + AHardwareBuffer_sendHandleToUnixSocket.
 * No private HOST_*=100/101 frames; desktop/surface notify uses the event pipe
 * returned from IOCTL_CREATE_DESKTOP_VIEW (SCM_RIGHTS), matching send_event().
 */
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <pthread.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/types.h>
#include <sys/un.h>
#include <unistd.h>
#include <poll.h>

#define LOG_TAG "WineAndroidHostIpc"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#define IPC_SOCKET_NAME "\0\\Device\\WineAndroid"
#define IPC_SOCKET_ADDR_LEN \
    ((socklen_t)(offsetof(struct sockaddr_un, sun_path) + sizeof(IPC_SOCKET_NAME) - 1))

enum android_ioctl {
    IOCTL_CREATE_DESKTOP_VIEW = 0,
    IOCTL_CREATE_WINDOW,
    IOCTL_DESTROY_WINDOW,
    IOCTL_WINDOW_POS_CHANGED,
    IOCTL_SET_WINDOW_PARENT,
    IOCTL_DEQUEUE_BUFFER,
    IOCTL_QUEUE_BUFFER,
    IOCTL_CANCEL_BUFFER,
    IOCTL_QUERY,
    IOCTL_PERFORM,
    IOCTL_SET_SWAP_INT,
    IOCTL_SET_CAPTURE,
    IOCTL_SET_CURSOR,
    NB_IOCTLS
};

struct ioctl_header {
    int32_t hwnd;
    int32_t opengl; /* BOOL */
};

#define NB_CACHED_BUFFERS 4

/* HAL ANativeWindow (producer) — same layout wineandroid uses. */
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
    const int *handle;
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

struct native_win_data {
    struct wine_native_window *parent;
    struct wine_native_buffer *buffers[NB_CACHED_BUFFERS];
    int32_t hwnd;
    int opengl;
    int generation;
    int api;
    int buffer_format;
    int swap_interval;
    int buffer_lru[NB_CACHED_BUFFERS];
};

/* Win64 wineandroid union event_data wire size (see android.h). */
#define EVENT_DATA_SIZE 64
enum event_type {
    EVENT_DESKTOP_CHANGED = 0,
    EVENT_CONFIG_CHANGED = 1,
    EVENT_SURFACE_CHANGED = 2,
    EVENT_MOTION = 3,
    EVENT_KEYBOARD = 4,
};

/* ---- AHB helpers (dlsym, same as former host_anw) ---- */
typedef void *(*pfn_anwb_get_ahb)(struct wine_native_buffer *);
typedef int (*pfn_ahb_send)(void *ahb, int socketFd);
typedef void (*pfn_ahb_acquire)(void *ahb);
typedef void (*pfn_ahb_release)(void *ahb);

static pfn_anwb_get_ahb g_anwb_get_ahb;
static pfn_ahb_send g_ahb_send;
static pfn_ahb_acquire g_ahb_acquire;
static pfn_ahb_release g_ahb_release;
static pthread_once_t g_ahb_once = PTHREAD_ONCE_INIT;

static void resolve_ahb(void)
{
    void *lib = dlopen("libandroid.so", RTLD_NOW);
    void *ui = dlopen("libui.so", RTLD_NOW);
    if (lib) {
        g_ahb_send = (pfn_ahb_send)dlsym(lib, "AHardwareBuffer_sendHandleToUnixSocket");
        g_ahb_acquire = (pfn_ahb_acquire)dlsym(lib, "AHardwareBuffer_acquire");
        g_ahb_release = (pfn_ahb_release)dlsym(lib, "AHardwareBuffer_release");
    }
    if (ui)
        g_anwb_get_ahb = (pfn_anwb_get_ahb)dlsym(ui, "ANativeWindowBuffer_getHardwareBuffer");
    if (!g_anwb_get_ahb && lib)
        g_anwb_get_ahb = (pfn_anwb_get_ahb)dlsym(lib, "ANativeWindowBuffer_getHardwareBuffer");
    LOGI("AHB resolve send=%p get=%p", (void *)g_ahb_send, (void *)g_anwb_get_ahb);
}

static void ensure_ahb(void) { pthread_once(&g_ahb_once, resolve_ahb); }

/* ---- server state ---- */
static JavaVM *g_vm;
static jobject g_callback; /* global WineAndroidIpcCallbacks */
static jmethodID g_m_createDesktopView;
static jmethodID g_m_createWindow;
static jmethodID g_m_destroyWindow;
static jmethodID g_m_windowPosChanged;
static jmethodID g_m_setParent;
static jmethodID g_m_setCapture;
static jmethodID g_m_setCursor;

static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;
static int g_listen_fd = -1;
static int g_event_sink = -1;
static int g_desktop_client_fd = -1;
static volatile int g_running;
static pthread_t g_accept_thread;
static int g_accept_started;
static struct native_win_data *g_data_map[65536];

static struct native_win_data *get_native_win_data(int32_t hwnd, int opengl)
{
    unsigned idx = (unsigned)(((uint32_t)hwnd & 0xffffu) + !!opengl);
    struct native_win_data *data = g_data_map[idx];
    if (data && data->hwnd == hwnd && !data->opengl == !opengl) return data;
    return NULL;
}

static struct native_win_data *create_native_win_data(int32_t hwnd, int opengl)
{
    unsigned idx = (unsigned)(((uint32_t)hwnd & 0xffffu) + !!opengl);
    struct native_win_data *data = g_data_map[idx];
    int i;
    if (data) {
        if (data->parent) {
            ANativeWindow_release((ANativeWindow *)data->parent);
            data->parent = NULL;
        }
        memset(data->buffers, 0, sizeof(data->buffers));
    } else {
        data = calloc(1, sizeof(*data));
        if (!data) return NULL;
        g_data_map[idx] = data;
    }
    data->hwnd = hwnd;
    data->opengl = opengl;
    data->generation = 0;
    data->api = 0;
    data->buffer_format = 1; /* RGBA_8888-ish default; Surface sets real fmt */
    data->swap_interval = 1;
    for (i = 0; i < NB_CACHED_BUFFERS; i++) data->buffer_lru[i] = -1;
    return data;
}

static void free_native_win_data(struct native_win_data *data)
{
    unsigned idx;
    int i;
    if (!data) return;
    idx = (unsigned)(((uint32_t)data->hwnd & 0xffffu) + !!data->opengl);
    if (data->parent) {
        ANativeWindow_release((ANativeWindow *)data->parent);
        data->parent = NULL;
    }
    for (i = 0; i < NB_CACHED_BUFFERS; i++) data->buffers[i] = NULL;
    free(data);
    if (g_data_map[idx] == data) g_data_map[idx] = NULL;
}

static int register_buffer(struct native_win_data *win, struct wine_native_buffer *buffer,
                           int *is_new)
{
    int i, free_idx = -1, lru = -1;
    *is_new = 0;
    for (i = 0; i < NB_CACHED_BUFFERS; i++) {
        if (win->buffers[i] == buffer) {
            /* bump LRU */
            int j;
            for (j = 0; j < NB_CACHED_BUFFERS; j++)
                if (win->buffer_lru[j] == i) break;
            if (j > 0 && j < NB_CACHED_BUFFERS) {
                memmove(win->buffer_lru + 1, win->buffer_lru, (size_t)j * sizeof(int));
                win->buffer_lru[0] = i;
            } else if (j >= NB_CACHED_BUFFERS) {
                memmove(win->buffer_lru + 1, win->buffer_lru,
                        (NB_CACHED_BUFFERS - 1) * sizeof(int));
                win->buffer_lru[0] = i;
            }
            return i;
        }
        if (!win->buffers[i] && free_idx < 0) free_idx = i;
    }
    if (free_idx < 0) {
        lru = win->buffer_lru[NB_CACHED_BUFFERS - 1];
        if (lru < 0) lru = 0;
        win->buffers[lru] = NULL;
        free_idx = lru;
    }
    win->buffers[free_idx] = buffer;
    *is_new = 1;
    memmove(win->buffer_lru + 1, win->buffer_lru, (NB_CACHED_BUFFERS - 1) * sizeof(int));
    win->buffer_lru[0] = free_idx;
    return free_idx;
}

static struct wine_native_buffer *get_registered_buffer(struct native_win_data *win, int id)
{
    if (id < 0 || id >= NB_CACHED_BUFFERS) return NULL;
    return win->buffers[id];
}

static void wait_fence_and_close(int fence)
{
    if (fence >= 0) close(fence);
}

static int send_event_bytes(const void *data, size_t len)
{
    ssize_t n;
    if (g_event_sink < 0) return -1;
    n = write(g_event_sink, data, len);
    if (n != (ssize_t)len) {
        LOGW("send_event failed n=%zd errno=%d", n, errno);
        return -1;
    }
    return 0;
}

static int send_desktop_changed_event(unsigned width, unsigned height)
{
    uint8_t buf[EVENT_DATA_SIZE];
    memset(buf, 0, sizeof(buf));
    /* desktop: type@0, width@4, height@8 */
    {
        uint32_t evtype = EVENT_DESKTOP_CHANGED;
        memcpy(buf + 0, &evtype, 4);
        memcpy(buf + 4, &width, 4);
        memcpy(buf + 8, &height, 4);
    }
    return send_event_bytes(buf, sizeof(buf));
}

static int send_surface_changed_event(int32_t hwnd, int client, unsigned width,
                                      unsigned height)
{
    uint8_t buf[EVENT_DATA_SIZE];
    uint64_t hwnd64 = (uint32_t)hwnd; /* Win64 HWND low 32 bits */
    int32_t client32 = client ? 1 : 0;
    memset(buf, 0, sizeof(buf));
    /* surface: type@0, hwnd@8, client@16, width@20, height@24 */
    {
        uint32_t evtype = EVENT_SURFACE_CHANGED;
        memcpy(buf + 0, &evtype, 4);
        memcpy(buf + 8, &hwnd64, 8);
        memcpy(buf + 16, &client32, 4);
        memcpy(buf + 20, &width, 4);
        memcpy(buf + 24, &height, 4);
    }
    return send_event_bytes(buf, sizeof(buf));
}

static JNIEnv *get_env(int *attached)
{
    JNIEnv *env = NULL;
    *attached = 0;
    if (!g_vm) return NULL;
    if ((*g_vm)->GetEnv(g_vm, (void **)&env, JNI_VERSION_1_6) == JNI_OK) return env;
    if ((*g_vm)->AttachCurrentThread(g_vm, &env, NULL) != 0) return NULL;
    *attached = 1;
    return env;
}

static void release_env(int attached)
{
    if (attached && g_vm) (*g_vm)->DetachCurrentThread(g_vm);
}

/* ---- ioctl handlers ---- */

static int ioctl_create_desktop_view(JNIEnv *env, void *data, size_t in_size,
                                     size_t out_cap, size_t *ret_size, int *reply_fd)
{
    static int event_pipe[2];
    (void)data;
    (void)out_cap;
    *ret_size = 0;
    *reply_fd = -1;
    if (in_size < 12) return -EINVAL; /* hdr + log_flags */

    if (g_event_sink >= 0) {
        close(g_event_sink);
        g_event_sink = -1;
    }
    if (pipe2(event_pipe, O_CLOEXEC | O_NONBLOCK) < 0) {
        LOGE("event pipe failed errno=%d", errno);
        return -1;
    }
    g_event_sink = event_pipe[1];
    *reply_fd = event_pipe[0];

    if (env && g_callback && g_m_createDesktopView)
        (*env)->CallVoidMethod(env, g_callback, g_m_createDesktopView);
    LOGI("IOCTL_CREATE_DESKTOP_VIEW event_fd=%d", *reply_fd);
    return 0;
}

static int ioctl_create_window(JNIEnv *env, void *data, size_t in_size, size_t out_cap,
                               size_t *ret_size, int *reply_fd)
{
    struct {
        struct ioctl_header hdr;
        int32_t parent;
        int32_t is_desktop;
    } *req = data;
    (void)out_cap;
    *ret_size = 0;
    *reply_fd = -1;
    if (in_size < sizeof(*req)) return -EINVAL;
    if (!create_native_win_data(req->hdr.hwnd, req->hdr.opengl)) return -ENOMEM;
    LOGI("IOCTL_CREATE_WINDOW hwnd=%08x opengl=%d parent=%08x desktop=%d", req->hdr.hwnd,
         req->hdr.opengl, req->parent, req->is_desktop);
    if (env && g_callback && g_m_createWindow)
        (*env)->CallVoidMethod(env, g_callback, g_m_createWindow, req->hdr.hwnd,
                               (jboolean)(req->is_desktop != 0),
                               (jboolean)(req->hdr.opengl != 0), req->parent);
    return 0;
}

static int ioctl_destroy_window(JNIEnv *env, void *data, size_t in_size, size_t out_cap,
                                size_t *ret_size, int *reply_fd)
{
    struct ioctl_header *hdr = data;
    struct native_win_data *win;
    (void)out_cap;
    *ret_size = 0;
    *reply_fd = -1;
    if (in_size < sizeof(*hdr)) return -EINVAL;
    win = get_native_win_data(hdr->hwnd, hdr->opengl);
    LOGI("IOCTL_DESTROY_WINDOW hwnd=%08x opengl=%d", hdr->hwnd, hdr->opengl);
    if (env && g_callback && g_m_destroyWindow)
        (*env)->CallVoidMethod(env, g_callback, g_m_destroyWindow, hdr->hwnd);
    if (win) free_native_win_data(win);
    return 0;
}

static int ioctl_window_pos_changed(JNIEnv *env, void *data, size_t in_size, size_t out_cap,
                                    size_t *ret_size, int *reply_fd)
{
    /* hdr + 3*RECT(16) + style + flags + after + owner = 8+48+16 = 72 */
    int32_t *p = data;
    int32_t hwnd, flags, after, owner, style;
    int32_t wl, wt, wr, wb, cl, ct, cr, cb, vl, vt, vr, vb;
    (void)out_cap;
    *ret_size = 0;
    *reply_fd = -1;
    if (in_size < 72) return -EINVAL;
    hwnd = p[0];
    /* p[1] = opengl */
    wl = p[2];
    wt = p[3];
    wr = p[4];
    wb = p[5];
    cl = p[6];
    ct = p[7];
    cr = p[8];
    cb = p[9];
    vl = p[10];
    vt = p[11];
    vr = p[12];
    vb = p[13];
    style = p[14];
    flags = p[15];
    after = p[16];
    owner = p[17];
    LOGI("IOCTL_WINDOW_POS_CHANGED hwnd=%08x flags=%08x", hwnd, flags);
    if (env && g_callback && g_m_windowPosChanged)
        (*env)->CallVoidMethod(env, g_callback, g_m_windowPosChanged, hwnd, flags, after,
                               owner, style, wl, wt, wr, wb, cl, ct, cr, cb, vl, vt, vr, vb);
    return 0;
}

static int ioctl_set_window_parent(JNIEnv *env, void *data, size_t in_size, size_t out_cap,
                                   size_t *ret_size, int *reply_fd)
{
    struct {
        struct ioctl_header hdr;
        int32_t parent;
    } *req = data;
    (void)out_cap;
    *ret_size = 0;
    *reply_fd = -1;
    if (in_size < sizeof(*req)) return -EINVAL;
    if (!get_native_win_data(req->hdr.hwnd, req->hdr.opengl)) return -ENOENT;
    LOGI("IOCTL_SET_WINDOW_PARENT hwnd=%08x parent=%08x", req->hdr.hwnd, req->parent);
    if (env && g_callback && g_m_setParent)
        (*env)->CallVoidMethod(env, g_callback, g_m_setParent, req->hdr.hwnd, req->parent);
    return 0;
}

static int ioctl_dequeue_buffer(JNIEnv *env, void *data, size_t in_size, size_t out_cap,
                                size_t *ret_size, int *reply_fd)
{
    struct {
        struct ioctl_header hdr;
        int32_t buffer_id;
        int32_t generation;
    } *res = data;
    struct native_win_data *win;
    struct wine_native_buffer *buffer = NULL;
    void *ahb = NULL;
    int fence = -1, ret, is_new = 0;
    (void)env;
    *reply_fd = -1;
    if (out_cap < sizeof(*res) || in_size < sizeof(struct ioctl_header)) return -EINVAL;
    win = get_native_win_data(res->hdr.hwnd, res->hdr.opengl);
    if (!win) return -ENOENT;
    if (!win->parent) return -EWOULDBLOCK;

    res->buffer_id = -1;
    res->generation = 0;
    *ret_size = sizeof(*res);

    ret = win->parent->dequeueBuffer(win->parent, &buffer, &fence);
    if (ret) {
        LOGW("dequeueBuffer hwnd=%08x ret=%d", res->hdr.hwnd, ret);
        return ret;
    }
    if (!buffer) return -1;

    ensure_ahb();
    res->buffer_id = register_buffer(win, buffer, &is_new);
    res->generation = win->generation;

    if (is_new) {
        int sv[2] = {-1, -1};
        if (!g_anwb_get_ahb || !g_ahb_send) {
            wait_fence_and_close(fence);
            return -1;
        }
        ahb = g_anwb_get_ahb(buffer);
        if (!ahb) {
            wait_fence_and_close(fence);
            return -1;
        }
        if (g_ahb_acquire) g_ahb_acquire(ahb);
        if (socketpair(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0, sv) < 0) {
            if (g_ahb_release) g_ahb_release(ahb);
            wait_fence_and_close(fence);
            return -1;
        }
        ret = g_ahb_send(ahb, sv[0]);
        close(sv[0]);
        if (g_ahb_release) g_ahb_release(ahb);
        if (ret) {
            close(sv[1]);
            wait_fence_and_close(fence);
            return ret;
        }
        *reply_fd = sv[1];
    }
    wait_fence_and_close(fence);
    return 0;
}

static int ioctl_queue_or_cancel(int cancel, void *data, size_t in_size, size_t *ret_size,
                                 int *reply_fd)
{
    struct {
        struct ioctl_header hdr;
        int32_t buffer_id;
        int32_t generation;
    } *res = data;
    struct native_win_data *win;
    struct wine_native_buffer *buffer;
    *ret_size = 0;
    *reply_fd = -1;
    if (in_size < sizeof(*res)) return -EINVAL;
    win = get_native_win_data(res->hdr.hwnd, res->hdr.opengl);
    if (!win) return -ENOENT;
    if (!win->parent) return -EWOULDBLOCK;
    if (res->generation != win->generation) return 0;
    buffer = get_registered_buffer(win, res->buffer_id);
    if (!buffer) return -ENOENT;
    if (cancel)
        return win->parent->cancelBuffer(win->parent, buffer, -1);
    return win->parent->queueBuffer(win->parent, buffer, -1);
}

static int ioctl_query(void *data, size_t in_size, size_t out_cap, size_t *ret_size,
                       int *reply_fd)
{
    struct {
        struct ioctl_header hdr;
        int32_t what;
        int32_t value;
    } *res = data;
    struct native_win_data *win;
    int ret;
    *reply_fd = -1;
    if (in_size < sizeof(*res) || out_cap < sizeof(*res)) return -EINVAL;
    win = get_native_win_data(res->hdr.hwnd, res->hdr.opengl);
    if (!win) return -ENOENT;
    if (!win->parent) return -EWOULDBLOCK;
    *ret_size = sizeof(*res);
    ret = win->parent->query(win->parent, res->what, &res->value);
    return ret;
}

enum {
    NATIVE_WINDOW_SET_USAGE = 0,
    NATIVE_WINDOW_CONNECT = 1,
    NATIVE_WINDOW_DISCONNECT = 2,
    NATIVE_WINDOW_SET_CROP = 3,
    NATIVE_WINDOW_SET_BUFFER_COUNT = 4,
    NATIVE_WINDOW_SET_BUFFERS_GEOMETRY = 5,
    NATIVE_WINDOW_SET_BUFFERS_TRANSFORM = 6,
    NATIVE_WINDOW_SET_BUFFERS_TIMESTAMP = 7,
    NATIVE_WINDOW_SET_BUFFERS_DIMENSIONS = 8,
    NATIVE_WINDOW_SET_BUFFERS_FORMAT = 9,
    NATIVE_WINDOW_SET_SCALING_MODE = 10,
    NATIVE_WINDOW_LOCK = 11,
    NATIVE_WINDOW_UNLOCK_AND_POST = 12,
    NATIVE_WINDOW_API_CONNECT = 13,
    NATIVE_WINDOW_API_DISCONNECT = 14,
    NATIVE_WINDOW_SET_BUFFERS_USER_DIMENSIONS = 15,
};

static int ioctl_perform(void *data, size_t in_size, size_t *ret_size, int *reply_fd)
{
    struct {
        struct ioctl_header hdr;
        int32_t operation;
        int32_t args[4];
    } *res = data;
    struct native_win_data *win;
    int ret = -ENOENT;
    *ret_size = 0;
    *reply_fd = -1;
    if (in_size < sizeof(*res)) return -EINVAL;
    win = get_native_win_data(res->hdr.hwnd, res->hdr.opengl);
    if (!win) return -ENOENT;
    if (!win->parent) return -EWOULDBLOCK;
    switch (res->operation) {
    case NATIVE_WINDOW_SET_BUFFERS_FORMAT:
        ret = win->parent->perform(win->parent, res->operation, res->args[0]);
        if (!ret) win->buffer_format = res->args[0];
        break;
    case NATIVE_WINDOW_API_CONNECT:
        ret = win->parent->perform(win->parent, res->operation, res->args[0]);
        if (!ret) win->api = res->args[0];
        break;
    case NATIVE_WINDOW_API_DISCONNECT:
        ret = win->parent->perform(win->parent, res->operation, res->args[0]);
        if (!ret) win->api = 0;
        break;
    case NATIVE_WINDOW_SET_USAGE:
    case NATIVE_WINDOW_SET_BUFFERS_TRANSFORM:
    case NATIVE_WINDOW_SET_SCALING_MODE:
        ret = win->parent->perform(win->parent, res->operation, res->args[0]);
        break;
    case NATIVE_WINDOW_SET_BUFFER_COUNT:
        ret = win->parent->perform(win->parent, res->operation, (size_t)res->args[0]);
        break;
    case NATIVE_WINDOW_SET_BUFFERS_DIMENSIONS:
    case NATIVE_WINDOW_SET_BUFFERS_USER_DIMENSIONS:
        ret = win->parent->perform(win->parent, res->operation, res->args[0], res->args[1]);
        break;
    case NATIVE_WINDOW_SET_BUFFERS_GEOMETRY:
        ret = win->parent->perform(win->parent, res->operation, res->args[0], res->args[1],
                                   res->args[2]);
        break;
    case NATIVE_WINDOW_SET_BUFFERS_TIMESTAMP:
        ret = win->parent->perform(win->parent, res->operation,
                                   res->args[0] | ((int64_t)res->args[1] << 32));
        break;
    case NATIVE_WINDOW_CONNECT:
    case NATIVE_WINDOW_DISCONNECT:
    case NATIVE_WINDOW_UNLOCK_AND_POST:
        ret = win->parent->perform(win->parent, res->operation);
        break;
    default:
        LOGW("unsupported perform op %d", res->operation);
        break;
    }
    return ret;
}

static int ioctl_set_swap(void *data, size_t in_size, size_t *ret_size, int *reply_fd)
{
    struct {
        struct ioctl_header hdr;
        int32_t interval;
    } *res = data;
    struct native_win_data *win;
    *ret_size = 0;
    *reply_fd = -1;
    if (in_size < sizeof(*res)) return -EINVAL;
    win = get_native_win_data(res->hdr.hwnd, res->hdr.opengl);
    if (!win) return -ENOENT;
    win->swap_interval = res->interval;
    if (!win->parent) return 0;
    return win->parent->setSwapInterval(win->parent, res->interval);
}

static int ioctl_set_capture(JNIEnv *env, void *data, size_t in_size, size_t *ret_size,
                             int *reply_fd)
{
    struct ioctl_header *hdr = data;
    *ret_size = 0;
    *reply_fd = -1;
    if (in_size < sizeof(*hdr)) return -EINVAL;
    if (hdr->hwnd && !get_native_win_data(hdr->hwnd, hdr->opengl)) return -ENOENT;
    if (env && g_callback && g_m_setCapture)
        (*env)->CallVoidMethod(env, g_callback, g_m_setCapture, hdr->hwnd);
    return 0;
}

static int ioctl_set_cursor(JNIEnv *env, void *data, size_t in_size, size_t *ret_size,
                            int *reply_fd)
{
    /* hdr + id,width,height,hotspotx,hotspoty + bits[width*height] */
    int32_t *p = data;
    int32_t id, width, height, hx, hy, size;
    size_t need;
    jintArray arr = NULL;
    *ret_size = 0;
    *reply_fd = -1;
    if (in_size < 8 + 5 * 4) return -EINVAL;
    id = p[2];
    width = p[3];
    height = p[4];
    hx = p[5];
    hy = p[6];
    if (width < 0 || height < 0 || width > 256 || height > 256) return -EINVAL;
    size = width * height;
    need = (size_t)(8 + 5 * 4 + size * 4);
    if (in_size != need) return -EINVAL;
    if (env && g_callback && g_m_setCursor) {
        arr = (*env)->NewIntArray(env, size);
        if (arr && size > 0)
            (*env)->SetIntArrayRegion(env, arr, 0, size, (jint *)&p[7]);
        (*env)->CallVoidMethod(env, g_callback, g_m_setCursor, id, width, height, hx, hy,
                               arr);
        if (arr) (*env)->DeleteLocalRef(env, arr);
    }
    return 0;
}

static int handle_ioctl_message(JNIEnv *env, int fd)
{
    char buffer[4096];
    char control[CMSG_SPACE(sizeof(int))];
    int32_t code = 0, status = -EINVAL;
    size_t reply_size = 0;
    int reply_fd = -1;
    ssize_t ret;
    struct iovec iov[2] = {{&code, sizeof(code)}, {buffer, sizeof(buffer)}};
    struct iovec reply_iov[2] = {{&status, sizeof(status)}, {buffer, 0}};
    struct msghdr msg = {NULL, 0, iov, 2, NULL, 0, 0};
    struct msghdr reply = {NULL, 0, reply_iov, 2, NULL, 0, 0};
    struct cmsghdr *cmsg;

    ret = recvmsg(fd, &msg, MSG_DONTWAIT);
    if (ret < 0) {
        if (errno == EINTR) return 0;
        if (errno == EAGAIN || errno == EWOULDBLOCK) return -1;
        return 1;
    }
    if (!ret || ret < (ssize_t)sizeof(code)) return 1;
    ret -= (ssize_t)sizeof(code);

    pthread_mutex_lock(&g_lock);
    if ((unsigned)code < NB_IOCTLS) {
        switch (code) {
        case IOCTL_CREATE_DESKTOP_VIEW:
            status = ioctl_create_desktop_view(env, buffer, (size_t)ret, sizeof(buffer),
                                               &reply_size, &reply_fd);
            g_desktop_client_fd = fd;
            break;
        case IOCTL_CREATE_WINDOW:
            status = ioctl_create_window(env, buffer, (size_t)ret, sizeof(buffer),
                                         &reply_size, &reply_fd);
            break;
        case IOCTL_DESTROY_WINDOW:
            status = ioctl_destroy_window(env, buffer, (size_t)ret, sizeof(buffer),
                                          &reply_size, &reply_fd);
            break;
        case IOCTL_WINDOW_POS_CHANGED:
            status = ioctl_window_pos_changed(env, buffer, (size_t)ret, sizeof(buffer),
                                              &reply_size, &reply_fd);
            break;
        case IOCTL_SET_WINDOW_PARENT:
            status = ioctl_set_window_parent(env, buffer, (size_t)ret, sizeof(buffer),
                                             &reply_size, &reply_fd);
            break;
        case IOCTL_DEQUEUE_BUFFER:
            status = ioctl_dequeue_buffer(env, buffer, (size_t)ret, sizeof(buffer),
                                          &reply_size, &reply_fd);
            break;
        case IOCTL_QUEUE_BUFFER:
            status = ioctl_queue_or_cancel(0, buffer, (size_t)ret, &reply_size, &reply_fd);
            break;
        case IOCTL_CANCEL_BUFFER:
            status = ioctl_queue_or_cancel(1, buffer, (size_t)ret, &reply_size, &reply_fd);
            break;
        case IOCTL_QUERY:
            status = ioctl_query(buffer, (size_t)ret, sizeof(buffer), &reply_size, &reply_fd);
            break;
        case IOCTL_PERFORM:
            status = ioctl_perform(buffer, (size_t)ret, &reply_size, &reply_fd);
            break;
        case IOCTL_SET_SWAP_INT:
            status = ioctl_set_swap(buffer, (size_t)ret, &reply_size, &reply_fd);
            break;
        case IOCTL_SET_CAPTURE:
            status = ioctl_set_capture(env, buffer, (size_t)ret, &reply_size, &reply_fd);
            break;
        case IOCTL_SET_CURSOR:
            status = ioctl_set_cursor(env, buffer, (size_t)ret, &reply_size, &reply_fd);
            break;
        default:
            status = -ENOTSUP;
            break;
        }
    } else {
        LOGW("ioctl %d not supported", code);
        status = -ENOTSUP;
    }
    pthread_mutex_unlock(&g_lock);

    reply_iov[1].iov_base = buffer;
    reply_iov[1].iov_len = reply_size;
    if (reply_fd != -1) {
        reply.msg_control = control;
        reply.msg_controllen = sizeof(control);
        cmsg = CMSG_FIRSTHDR(&reply);
        cmsg->cmsg_level = SOL_SOCKET;
        cmsg->cmsg_type = SCM_RIGHTS;
        cmsg->cmsg_len = CMSG_LEN(sizeof(reply_fd));
        memcpy(CMSG_DATA(cmsg), &reply_fd, sizeof(reply_fd));
        reply.msg_controllen = cmsg->cmsg_len;
    }
    ret = sendmsg(fd, &reply, 0);
    if (reply_fd != -1) close(reply_fd);
    return ret < 0 ? 1 : 0;
}

static void serve_client(int fd)
{
    int attached = 0;
    JNIEnv *env = get_env(&attached);
    LOGI("client connected fd=%d", fd);
    for (;;) {
        int r = handle_ioctl_message(env, fd);
        if (r < 0) {
            /* would block — poll */
            struct pollfd pfd = {.fd = fd, .events = POLLIN | POLLHUP | POLLERR};
            int pr = poll(&pfd, 1, 500);
            if (!g_running) break;
            if (pr < 0) {
                if (errno == EINTR) continue;
                break;
            }
            if (pfd.revents & (POLLHUP | POLLERR)) break;
            continue;
        }
        if (r > 0) break;
    }
    LOGI("client disconnected fd=%d", fd);
    if (fd == g_desktop_client_fd) {
        g_desktop_client_fd = -1;
        LOGW("desktop client gone");
    }
    close(fd);
    release_env(attached);
}

static void *client_thread_main(void *arg)
{
    int fd = *(int *)arg;
    free(arg);
    serve_client(fd);
    return NULL;
}

static void *accept_thread_main2(void *arg)
{
    (void)arg;
    LOGI("listening abstract AF_UNIX SEQPACKET \\0\\Device\\WineAndroid");
    while (g_running) {
        struct pollfd pfd = {.fd = g_listen_fd, .events = POLLIN};
        int pr = poll(&pfd, 1, 500);
        int client;
        int *heap;
        pthread_t th;
        if (!g_running) break;
        if (pr <= 0) continue;
        client = accept4(g_listen_fd, NULL, NULL, SOCK_CLOEXEC | SOCK_NONBLOCK);
        if (client < 0) {
            if (errno == EINTR || errno == EAGAIN) continue;
            LOGE("accept4 failed errno=%d", errno);
            break;
        }
        heap = malloc(sizeof(int));
        if (!heap) {
            close(client);
            continue;
        }
        *heap = client;
        if (pthread_create(&th, NULL, client_thread_main, heap) != 0) {
            free(heap);
            serve_client(client);
            continue;
        }
        pthread_detach(th);
    }
    return NULL;
}

static int bind_listen_socket(void)
{
    struct sockaddr_un addr;
    int fd = socket(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC | SOCK_NONBLOCK, 0);
    if (fd < 0) {
        LOGE("socket SEQPACKET failed errno=%d", errno);
        return -1;
    }
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    memcpy(addr.sun_path, IPC_SOCKET_NAME, sizeof(IPC_SOCKET_NAME) - 1);
    /* Abstract names auto-replace on rebind for same path in Linux. */
    if (bind(fd, (struct sockaddr *)&addr, IPC_SOCKET_ADDR_LEN) < 0) {
        LOGE("bind \\0\\Device\\WineAndroid failed errno=%d", errno);
        close(fd);
        return -1;
    }
    if (listen(fd, 32) < 0) {
        LOGE("listen failed errno=%d", errno);
        close(fd);
        return -1;
    }
    return fd;
}

static int cache_callback_methods(JNIEnv *env, jobject cb)
{
    jclass cls = (*env)->GetObjectClass(env, cb);
    if (!cls) return -1;
    g_m_createDesktopView =
        (*env)->GetMethodID(env, cls, "createDesktopView", "()V");
    g_m_createWindow =
        (*env)->GetMethodID(env, cls, "createWindow", "(IZZI)V");
    g_m_destroyWindow =
        (*env)->GetMethodID(env, cls, "destroyWindow", "(I)V");
    g_m_windowPosChanged = (*env)->GetMethodID(
        env, cls, "windowPosChanged", "(IIIIIIIIIIIIIIIII)V");
    g_m_setParent = (*env)->GetMethodID(env, cls, "setParent", "(II)V");
    g_m_setCapture = (*env)->GetMethodID(env, cls, "setCapture", "(I)V");
    g_m_setCursor = (*env)->GetMethodID(env, cls, "setCursor", "(IIIII[I)V");
    (*env)->DeleteLocalRef(env, cls);
    if (!g_m_createDesktopView || !g_m_createWindow || !g_m_destroyWindow ||
        !g_m_windowPosChanged || !g_m_setParent || !g_m_setCapture || !g_m_setCursor) {
        LOGE("missing WineAndroidIpcCallbacks method(s)");
        return -1;
    }
    return 0;
}

JNIEXPORT jboolean JNICALL
Java_app_amphora_gamesession_wineandroid_WineAndroidNative_nativeStartServer(
    JNIEnv *env, jclass clazz, jobject callbacks)
{
    (void)clazz;
    if (!callbacks) return JNI_FALSE;
    if ((*env)->GetJavaVM(env, &g_vm) != 0) return JNI_FALSE;

    pthread_mutex_lock(&g_lock);
    if (g_running) {
        pthread_mutex_unlock(&g_lock);
        return JNI_TRUE;
    }
    if (g_callback) {
        (*env)->DeleteGlobalRef(env, g_callback);
        g_callback = NULL;
    }
    g_callback = (*env)->NewGlobalRef(env, callbacks);
    if (cache_callback_methods(env, g_callback) != 0) {
        (*env)->DeleteGlobalRef(env, g_callback);
        g_callback = NULL;
        pthread_mutex_unlock(&g_lock);
        return JNI_FALSE;
    }
    ensure_ahb();
    g_listen_fd = bind_listen_socket();
    if (g_listen_fd < 0) {
        (*env)->DeleteGlobalRef(env, g_callback);
        g_callback = NULL;
        pthread_mutex_unlock(&g_lock);
        return JNI_FALSE;
    }
    g_running = 1;
    g_accept_started = 0;
    if (pthread_create(&g_accept_thread, NULL, accept_thread_main2, NULL) != 0) {
        g_running = 0;
        close(g_listen_fd);
        g_listen_fd = -1;
        (*env)->DeleteGlobalRef(env, g_callback);
        g_callback = NULL;
        pthread_mutex_unlock(&g_lock);
        return JNI_FALSE;
    }
    g_accept_started = 1;
    pthread_mutex_unlock(&g_lock);
    LOGI("WineAndroid IPC server started");
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_app_amphora_gamesession_wineandroid_WineAndroidNative_nativeStopServer(
    JNIEnv *env, jclass clazz)
{
    int i;
    (void)clazz;
    pthread_mutex_lock(&g_lock);
    g_running = 0;
    if (g_listen_fd >= 0) {
        close(g_listen_fd);
        g_listen_fd = -1;
    }
    if (g_event_sink >= 0) {
        close(g_event_sink);
        g_event_sink = -1;
    }
    for (i = 0; i < 65536; i++) {
        if (g_data_map[i]) {
            free_native_win_data(g_data_map[i]);
            g_data_map[i] = NULL;
        }
    }
    if (g_callback) {
        (*env)->DeleteGlobalRef(env, g_callback);
        g_callback = NULL;
    }
    int join = g_accept_started;
    g_accept_started = 0;
    pthread_mutex_unlock(&g_lock);
    if (join) pthread_join(g_accept_thread, NULL);
    LOGI("WineAndroid IPC server stopped");
}

JNIEXPORT jboolean JNICALL
Java_app_amphora_gamesession_wineandroid_WineAndroidNative_nativeRegisterSurface(
    JNIEnv *env, jclass clazz, jint hwnd, jobject surface, jboolean opengl)
{
    struct native_win_data *data;
    ANativeWindow *win;
    int w = 0, h = 0;
    (void)clazz;
    if (!surface) return JNI_FALSE;
    win = ANativeWindow_fromSurface(env, surface);
    if (!win) {
        LOGE("ANativeWindow_fromSurface failed hwnd=%08x", hwnd);
        return JNI_FALSE;
    }
    pthread_mutex_lock(&g_lock);
    data = get_native_win_data(hwnd, opengl ? 1 : 0);
    if (!data) data = create_native_win_data(hwnd, opengl ? 1 : 0);
    if (!data) {
        pthread_mutex_unlock(&g_lock);
        ANativeWindow_release(win);
        return JNI_FALSE;
    }
    if (data->parent == (struct wine_native_window *)win) {
        ANativeWindow_release(win);
        pthread_mutex_unlock(&g_lock);
        return JNI_TRUE;
    }
    if (data->parent) {
        ANativeWindow_release((ANativeWindow *)data->parent);
        memset(data->buffers, 0, sizeof(data->buffers));
    }
    data->parent = (struct wine_native_window *)win;
    data->generation++;
    if (data->api)
        data->parent->perform(data->parent, NATIVE_WINDOW_API_CONNECT, data->api);
    data->parent->perform(data->parent, NATIVE_WINDOW_SET_BUFFERS_FORMAT, data->buffer_format);
    data->parent->setSwapInterval(data->parent, data->swap_interval);
    data->parent->query(data->parent, 0, &w); /* NATIVE_WINDOW_WIDTH */
    data->parent->query(data->parent, 1, &h); /* NATIVE_WINDOW_HEIGHT */
    send_surface_changed_event(hwnd, opengl ? 1 : 0, (unsigned)w, (unsigned)h);
    pthread_mutex_unlock(&g_lock);
    LOGI("registerSurface hwnd=%08x opengl=%d %dx%d gen=%d", hwnd, (int)opengl, w, h,
         data->generation);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_app_amphora_gamesession_wineandroid_WineAndroidNative_nativeUnregisterSurface(
    JNIEnv *env, jclass clazz, jint hwnd, jboolean opengl)
{
    struct native_win_data *data;
    (void)env;
    (void)clazz;
    pthread_mutex_lock(&g_lock);
    data = get_native_win_data(hwnd, opengl ? 1 : 0);
    if (data && data->parent) {
        ANativeWindow_release((ANativeWindow *)data->parent);
        data->parent = NULL;
        data->generation++;
        memset(data->buffers, 0, sizeof(data->buffers));
        send_surface_changed_event(hwnd, opengl ? 1 : 0, 0, 0);
    }
    pthread_mutex_unlock(&g_lock);
}

JNIEXPORT void JNICALL
Java_app_amphora_gamesession_wineandroid_WineAndroidNative_nativeNotifyDesktopChanged(
    JNIEnv *env, jclass clazz, jint width, jint height)
{
    (void)env;
    (void)clazz;
    pthread_mutex_lock(&g_lock);
    if (width > 0 && height > 0)
        send_desktop_changed_event((unsigned)width, (unsigned)height);
    pthread_mutex_unlock(&g_lock);
    LOGI("DESKTOP_CHANGED %dx%d (event_sink=%d)", width, height, g_event_sink);
}

JNIEXPORT void JNICALL
Java_app_amphora_gamesession_wineandroid_WineAndroidNative_nativeNotifyConfigChanged(
    JNIEnv *env, jclass clazz, jint dpi)
{
    uint8_t buf[EVENT_DATA_SIZE];
    (void)env;
    (void)clazz;
    memset(buf, 0, sizeof(buf));
    {
        uint32_t evtype = EVENT_CONFIG_CHANGED;
        uint32_t dpi32 = (uint32_t)dpi;
        memcpy(buf + 0, &evtype, 4);
        memcpy(buf + 4, &dpi32, 4);
    }
    pthread_mutex_lock(&g_lock);
    send_event_bytes(buf, sizeof(buf));
    pthread_mutex_unlock(&g_lock);
}
