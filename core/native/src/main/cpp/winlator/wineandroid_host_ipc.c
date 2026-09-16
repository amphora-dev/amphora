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
#include <android/input.h>
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
    IOCTL_GET_BUFFER_SOCK,
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
    /* Tip-model AMPHORA_BUF sock: host serve thread + wine peer fd. */
    void *anw_serve;
    int buf_wine_fd;
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

/* Win64 INPUT / MOUSEINPUT field values (winuser.h) — guest is x86_64 Wine. */
#define WA_INPUT_MOUSE              0
#define WA_MOUSEEVENTF_MOVE         0x0001
#define WA_MOUSEEVENTF_LEFTDOWN     0x0002
#define WA_MOUSEEVENTF_LEFTUP       0x0004
#define WA_MOUSEEVENTF_RIGHTDOWN    0x0008
#define WA_MOUSEEVENTF_RIGHTUP      0x0010
#define WA_MOUSEEVENTF_MIDDLEDOWN   0x0020
#define WA_MOUSEEVENTF_MIDDLEUP     0x0040
#define WA_MOUSEEVENTF_WHEEL        0x0800
#define WA_MOUSEEVENTF_ABSOLUTE     0x8000
#define WA_WHEEL_DELTA              120

/* Win64 KEYBDINPUT / INPUT_KEYBOARD (winuser.h). */
#define WA_INPUT_KEYBOARD           1
#define WA_KEYEVENTF_EXTENDEDKEY    0x0001
#define WA_KEYEVENTF_KEYUP          0x0002

#ifndef ARRAY_SIZE
#define ARRAY_SIZE(a) (sizeof(a) / sizeof((a)[0]))
#endif

/* Virtual-key codes used by upstream keycode_to_vkey (winuser.rh). */
#define VK_BACK                0x08
#define VK_TAB                 0x09
#define VK_RETURN              0x0D
#define VK_CAPITAL             0x14
#define VK_KANA                0x15
#define VK_ESCAPE              0x1B
#define VK_SPACE               0x20
#define VK_PRIOR               0x21
#define VK_NEXT                0x22
#define VK_END                 0x23
#define VK_HOME                0x24
#define VK_LEFT                0x25
#define VK_UP                  0x26
#define VK_RIGHT               0x27
#define VK_DOWN                0x28
#define VK_INSERT              0x2D
#define VK_DELETE              0x2E
#define VK_LWIN                0x5B
#define VK_RWIN                0x5C
#define VK_NUMPAD0             0x60
#define VK_NUMPAD1             0x61
#define VK_NUMPAD2             0x62
#define VK_NUMPAD3             0x63
#define VK_NUMPAD4             0x64
#define VK_NUMPAD5             0x65
#define VK_NUMPAD6             0x66
#define VK_NUMPAD7             0x67
#define VK_NUMPAD8             0x68
#define VK_NUMPAD9             0x69
#define VK_MULTIPLY            0x6A
#define VK_ADD                 0x6B
#define VK_SUBTRACT            0x6D
#define VK_DECIMAL             0x6E
#define VK_DIVIDE              0x6F
#define VK_F1                  0x70
#define VK_F2                  0x71
#define VK_F3                  0x72
#define VK_F4                  0x73
#define VK_F5                  0x74
#define VK_F6                  0x75
#define VK_F7                  0x76
#define VK_F8                  0x77
#define VK_F9                  0x78
#define VK_F10                 0x79
#define VK_F11                 0x7A
#define VK_F12                 0x7B
#define VK_NUMLOCK             0x90
#define VK_SCROLL              0x91
#define VK_LSHIFT              0xA0
#define VK_RSHIFT              0xA1
#define VK_LCONTROL            0xA2
#define VK_RCONTROL            0xA3
#define VK_LMENU               0xA4
#define VK_RMENU               0xA5
#define VK_MEDIA_NEXT_TRACK    0xB0
#define VK_MEDIA_PREV_TRACK    0xB1
#define VK_MEDIA_STOP          0xB2
#define VK_MEDIA_PLAY_PAUSE    0xB3
#define VK_OEM_1               0xBA
#define VK_OEM_PLUS            0xBB
#define VK_OEM_COMMA           0xBC
#define VK_OEM_MINUS           0xBD
#define VK_OEM_PERIOD          0xBE
#define VK_OEM_2               0xBF
#define VK_OEM_3               0xC0
#define VK_OEM_4               0xDB
#define VK_OEM_5               0xDC
#define VK_OEM_6               0xDD
#define VK_OEM_7               0xDE

/*
 * Win64 union event_data.motion wire offsets (matches surface packing + probe):
 *   type@0, hwnd@8, INPUT.type@16, mi.dx@24, mi.dy@28, mouseData@32,
 *   dwFlags@36, time@40, dwExtraInfo@48; total EVENT_DATA_SIZE=64.
 *
 * Win64 union event_data.kbd (largest member, size 64):
 *   type@0, hwnd@8, lock_state@16, INPUT.type@24, ki.wVk@32, ki.wScan@34,
 *   dwFlags@36, time@40, dwExtraInfo@48.
 */

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

/* wineandroid_host_anw.c — tip AMPHORA_BUF serve against real ANativeWindow. */
extern void *amphora_host_anw_start_serve(ANativeWindow *win, int hwnd, int sock_fd);
extern void amphora_host_anw_stop_serve(void *serve_ptr);
extern void amphora_host_anw_bump_generation(void *serve_ptr);

static void stop_anw_buf_serve(struct native_win_data *data)
{
    if (!data) return;
    if (data->anw_serve) {
        amphora_host_anw_stop_serve(data->anw_serve);
        data->anw_serve = NULL;
    }
    if (data->buf_wine_fd >= 0) {
        close(data->buf_wine_fd);
        data->buf_wine_fd = -1;
    }
}

/* Start host AMPHORA_BUF serve for WSI Present (opengl client). Returns 0 or -errno. */
static int start_anw_buf_serve(struct native_win_data *data)
{
    int sv[2] = {-1, -1};
    void *serve;

    if (!data || !data->parent) return -ENOENT;
    stop_anw_buf_serve(data);
    if (socketpair(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0, sv) < 0) {
        LOGE("AMPHORA_BUF socketpair failed hwnd=%08x errno=%d", data->hwnd, errno);
        return -errno;
    }
    /* sv[0] = host serve end (owned by serve thread); sv[1] = wine peer kept until ioctl. */
    serve = amphora_host_anw_start_serve((ANativeWindow *)data->parent, data->hwnd, sv[0]);
    if (!serve) {
        close(sv[1]);
        LOGE("AMPHORA_BUF start_serve failed hwnd=%08x", data->hwnd);
        return -EIO;
    }
    data->anw_serve = serve;
    data->buf_wine_fd = sv[1];
    LOGI("AMPHORA_BUF sock ready hwnd=%08x opengl=%d wine_fd=%d", data->hwnd, data->opengl,
         data->buf_wine_fd);
    return 0;
}

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
        stop_anw_buf_serve(data);
        if (data->parent) {
            ANativeWindow_release((ANativeWindow *)data->parent);
            data->parent = NULL;
        }
        memset(data->buffers, 0, sizeof(data->buffers));
    } else {
        data = calloc(1, sizeof(*data));
        if (!data) return NULL;
        data->buf_wine_fd = -1;
        g_data_map[idx] = data;
    }
    data->hwnd = hwnd;
    data->opengl = opengl;
    data->generation = 0;
    data->buf_wine_fd = -1;
    data->anw_serve = NULL;
    /* Match upstream wineandroid create_native_win_data: GDI must be CPU
     * producer so nativeRegisterSurface's API_CONNECT actually runs. OpenGL
     * stays 0 until EGL connect (do not force CPU on GL windows). */
    data->api = opengl ? 0 : 2; /* NATIVE_WINDOW_API_CPU */
    /* Keep PF_RGBA_8888 (=1) for SurfaceView/BufferQueue compatibility.
     * HA262AAH: SET_BUFFERS_FORMAT(BGRA=5) coincided with a full-system
     * crash — do not push BGRA as producer format on this path.
     * Correct colors via host R/B swizzle (or CPU convert) instead.
     * Upstream wineandroid still prefers PF_BGRA_8888 in-process. */
    data->buffer_format = 1; /* PF_RGBA_8888 — safe for Android Surface */
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
    stop_anw_buf_serve(data);
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

/* Port of dlls/wineandroid.drv/window.c motion_event → desktop event pipe. */
static int send_motion_event(int32_t hwnd, int action, int x, int y, int state,
                             int vscroll)
{
    static int button_state;
    uint8_t buf[EVENT_DATA_SIZE];
    uint64_t hwnd64 = (uint32_t)hwnd;
    uint32_t evtype = EVENT_MOTION;
    uint32_t input_type = WA_INPUT_MOUSE;
    int32_t dx = x;
    int32_t dy = y;
    uint32_t mouse_data = 0;
    uint32_t dw_flags = WA_MOUSEEVENTF_MOVE | WA_MOUSEEVENTF_ABSOLUTE;
    uint32_t time = 0;
    uint64_t extra = 0;
    int mask = action & AMOTION_EVENT_ACTION_MASK;
    int prev_state;
    int send_state = state;

    if (!(mask == AMOTION_EVENT_ACTION_DOWN || mask == AMOTION_EVENT_ACTION_UP ||
          mask == AMOTION_EVENT_ACTION_CANCEL || mask == AMOTION_EVENT_ACTION_SCROLL ||
          mask == AMOTION_EVENT_ACTION_MOVE || mask == AMOTION_EVENT_ACTION_HOVER_MOVE ||
          mask == AMOTION_EVENT_ACTION_BUTTON_PRESS ||
          mask == AMOTION_EVENT_ACTION_BUTTON_RELEASE))
        return -1;

    /* Match upstream: BUTTON_RELEASE must not look like a bare touch UP. */
    if (mask == AMOTION_EVENT_ACTION_BUTTON_RELEASE)
        send_state |= (int)0x80000000;

    prev_state = button_state;
    button_state = send_state;

    switch (mask) {
    case AMOTION_EVENT_ACTION_DOWN:
    case AMOTION_EVENT_ACTION_BUTTON_PRESS:
        if ((send_state & ~prev_state) & AMOTION_EVENT_BUTTON_PRIMARY)
            dw_flags |= WA_MOUSEEVENTF_LEFTDOWN;
        if ((send_state & ~prev_state) & AMOTION_EVENT_BUTTON_SECONDARY)
            dw_flags |= WA_MOUSEEVENTF_RIGHTDOWN;
        if ((send_state & ~prev_state) & AMOTION_EVENT_BUTTON_TERTIARY)
            dw_flags |= WA_MOUSEEVENTF_MIDDLEDOWN;
        if (!(send_state & ~prev_state)) /* finger touch */
            dw_flags |= WA_MOUSEEVENTF_LEFTDOWN;
        break;
    case AMOTION_EVENT_ACTION_UP:
    case AMOTION_EVENT_ACTION_CANCEL:
    case AMOTION_EVENT_ACTION_BUTTON_RELEASE:
        if ((prev_state & ~send_state) & AMOTION_EVENT_BUTTON_PRIMARY)
            dw_flags |= WA_MOUSEEVENTF_LEFTUP;
        if ((prev_state & ~send_state) & AMOTION_EVENT_BUTTON_SECONDARY)
            dw_flags |= WA_MOUSEEVENTF_RIGHTUP;
        if ((prev_state & ~send_state) & AMOTION_EVENT_BUTTON_TERTIARY)
            dw_flags |= WA_MOUSEEVENTF_MIDDLEUP;
        if (!(prev_state & ~send_state)) /* finger touch */
            dw_flags |= WA_MOUSEEVENTF_LEFTUP;
        break;
    case AMOTION_EVENT_ACTION_SCROLL:
        dw_flags |= WA_MOUSEEVENTF_WHEEL;
        mouse_data = (uint32_t)(vscroll < 0 ? -WA_WHEEL_DELTA : WA_WHEEL_DELTA);
        break;
    case AMOTION_EVENT_ACTION_MOVE:
    case AMOTION_EVENT_ACTION_HOVER_MOVE:
        break;
    default:
        return -1;
    }

    memset(buf, 0, sizeof(buf));
    memcpy(buf + 0, &evtype, 4);
    memcpy(buf + 8, &hwnd64, 8);
    memcpy(buf + 16, &input_type, 4);
    memcpy(buf + 24, &dx, 4);
    memcpy(buf + 28, &dy, 4);
    memcpy(buf + 32, &mouse_data, 4);
    memcpy(buf + 36, &dw_flags, 4);
    memcpy(buf + 40, &time, 4);
    memcpy(buf + 48, &extra, 8);
    return send_event_bytes(buf, sizeof(buf));
}

static const uint16_t keycode_to_vkey[] =
{
    0,                   /* AKEYCODE_UNKNOWN */
    0,                   /* AKEYCODE_SOFT_LEFT */
    0,                   /* AKEYCODE_SOFT_RIGHT */
    0,                   /* AKEYCODE_HOME */
    0,                   /* AKEYCODE_BACK */
    0,                   /* AKEYCODE_CALL */
    0,                   /* AKEYCODE_ENDCALL */
    '0',                 /* AKEYCODE_0 */
    '1',                 /* AKEYCODE_1 */
    '2',                 /* AKEYCODE_2 */
    '3',                 /* AKEYCODE_3 */
    '4',                 /* AKEYCODE_4 */
    '5',                 /* AKEYCODE_5 */
    '6',                 /* AKEYCODE_6 */
    '7',                 /* AKEYCODE_7 */
    '8',                 /* AKEYCODE_8 */
    '9',                 /* AKEYCODE_9 */
    0,                   /* AKEYCODE_STAR */
    0,                   /* AKEYCODE_POUND */
    VK_UP,               /* AKEYCODE_DPAD_UP */
    VK_DOWN,             /* AKEYCODE_DPAD_DOWN */
    VK_LEFT,             /* AKEYCODE_DPAD_LEFT */
    VK_RIGHT,            /* AKEYCODE_DPAD_RIGHT */
    0,                   /* AKEYCODE_DPAD_CENTER */
    0,                   /* AKEYCODE_VOLUME_UP */
    0,                   /* AKEYCODE_VOLUME_DOWN */
    0,                   /* AKEYCODE_POWER */
    0,                   /* AKEYCODE_CAMERA */
    0,                   /* AKEYCODE_CLEAR */
    'A',                 /* AKEYCODE_A */
    'B',                 /* AKEYCODE_B */
    'C',                 /* AKEYCODE_C */
    'D',                 /* AKEYCODE_D */
    'E',                 /* AKEYCODE_E */
    'F',                 /* AKEYCODE_F */
    'G',                 /* AKEYCODE_G */
    'H',                 /* AKEYCODE_H */
    'I',                 /* AKEYCODE_I */
    'J',                 /* AKEYCODE_J */
    'K',                 /* AKEYCODE_K */
    'L',                 /* AKEYCODE_L */
    'M',                 /* AKEYCODE_M */
    'N',                 /* AKEYCODE_N */
    'O',                 /* AKEYCODE_O */
    'P',                 /* AKEYCODE_P */
    'Q',                 /* AKEYCODE_Q */
    'R',                 /* AKEYCODE_R */
    'S',                 /* AKEYCODE_S */
    'T',                 /* AKEYCODE_T */
    'U',                 /* AKEYCODE_U */
    'V',                 /* AKEYCODE_V */
    'W',                 /* AKEYCODE_W */
    'X',                 /* AKEYCODE_X */
    'Y',                 /* AKEYCODE_Y */
    'Z',                 /* AKEYCODE_Z */
    VK_OEM_COMMA,        /* AKEYCODE_COMMA */
    VK_OEM_PERIOD,       /* AKEYCODE_PERIOD */
    VK_LMENU,            /* AKEYCODE_ALT_LEFT */
    VK_RMENU,            /* AKEYCODE_ALT_RIGHT */
    VK_LSHIFT,           /* AKEYCODE_SHIFT_LEFT */
    VK_RSHIFT,           /* AKEYCODE_SHIFT_RIGHT */
    VK_TAB,              /* AKEYCODE_TAB */
    VK_SPACE,            /* AKEYCODE_SPACE */
    0,                   /* AKEYCODE_SYM */
    0,                   /* AKEYCODE_EXPLORER */
    0,                   /* AKEYCODE_ENVELOPE */
    VK_RETURN,           /* AKEYCODE_ENTER */
    VK_BACK,             /* AKEYCODE_DEL */
    VK_OEM_3,            /* AKEYCODE_GRAVE */
    VK_OEM_MINUS,        /* AKEYCODE_MINUS */
    VK_OEM_PLUS,         /* AKEYCODE_EQUALS */
    VK_OEM_4,            /* AKEYCODE_LEFT_BRACKET */
    VK_OEM_6,            /* AKEYCODE_RIGHT_BRACKET */
    VK_OEM_5,            /* AKEYCODE_BACKSLASH */
    VK_OEM_1,            /* AKEYCODE_SEMICOLON */
    VK_OEM_7,            /* AKEYCODE_APOSTROPHE */
    VK_OEM_2,            /* AKEYCODE_SLASH */
    0,                   /* AKEYCODE_AT */
    0,                   /* AKEYCODE_NUM */
    0,                   /* AKEYCODE_HEADSETHOOK */
    0,                   /* AKEYCODE_FOCUS */
    0,                   /* AKEYCODE_PLUS */
    0,                   /* AKEYCODE_MENU */
    0,                   /* AKEYCODE_NOTIFICATION */
    0,                   /* AKEYCODE_SEARCH */
    VK_MEDIA_PLAY_PAUSE, /* AKEYCODE_MEDIA_PLAY_PAUSE */
    VK_MEDIA_STOP,       /* AKEYCODE_MEDIA_STOP */
    VK_MEDIA_NEXT_TRACK, /* AKEYCODE_MEDIA_NEXT */
    VK_MEDIA_PREV_TRACK, /* AKEYCODE_MEDIA_PREVIOUS */
    0,                   /* AKEYCODE_MEDIA_REWIND */
    0,                   /* AKEYCODE_MEDIA_FAST_FORWARD */
    0,                   /* AKEYCODE_MUTE */
    VK_PRIOR,            /* AKEYCODE_PAGE_UP */
    VK_NEXT,             /* AKEYCODE_PAGE_DOWN */
    0,                   /* AKEYCODE_PICTSYMBOLS */
    0,                   /* AKEYCODE_SWITCH_CHARSET */
    0,                   /* AKEYCODE_BUTTON_A */
    0,                   /* AKEYCODE_BUTTON_B */
    0,                   /* AKEYCODE_BUTTON_C */
    0,                   /* AKEYCODE_BUTTON_X */
    0,                   /* AKEYCODE_BUTTON_Y */
    0,                   /* AKEYCODE_BUTTON_Z */
    0,                   /* AKEYCODE_BUTTON_L1 */
    0,                   /* AKEYCODE_BUTTON_R1 */
    0,                   /* AKEYCODE_BUTTON_L2 */
    0,                   /* AKEYCODE_BUTTON_R2 */
    0,                   /* AKEYCODE_BUTTON_THUMBL */
    0,                   /* AKEYCODE_BUTTON_THUMBR */
    0,                   /* AKEYCODE_BUTTON_START */
    0,                   /* AKEYCODE_BUTTON_SELECT */
    0,                   /* AKEYCODE_BUTTON_MODE */
    VK_ESCAPE,           /* AKEYCODE_ESCAPE */
    VK_DELETE,           /* AKEYCODE_FORWARD_DEL */
    VK_LCONTROL,         /* AKEYCODE_CTRL_LEFT */
    VK_RCONTROL,         /* AKEYCODE_CTRL_RIGHT */
    VK_CAPITAL,          /* AKEYCODE_CAPS_LOCK */
    VK_SCROLL,           /* AKEYCODE_SCROLL_LOCK */
    VK_LWIN,             /* AKEYCODE_META_LEFT */
    VK_RWIN,             /* AKEYCODE_META_RIGHT */
    0,                   /* AKEYCODE_FUNCTION */
    0,                   /* AKEYCODE_SYSRQ */
    0,                   /* AKEYCODE_BREAK */
    VK_HOME,             /* AKEYCODE_MOVE_HOME */
    VK_END,              /* AKEYCODE_MOVE_END */
    VK_INSERT,           /* AKEYCODE_INSERT */
    0,                   /* AKEYCODE_FORWARD */
    0,                   /* AKEYCODE_MEDIA_PLAY */
    0,                   /* AKEYCODE_MEDIA_PAUSE */
    0,                   /* AKEYCODE_MEDIA_CLOSE */
    0,                   /* AKEYCODE_MEDIA_EJECT */
    0,                   /* AKEYCODE_MEDIA_RECORD */
    VK_F1,               /* AKEYCODE_F1 */
    VK_F2,               /* AKEYCODE_F2 */
    VK_F3,               /* AKEYCODE_F3 */
    VK_F4,               /* AKEYCODE_F4 */
    VK_F5,               /* AKEYCODE_F5 */
    VK_F6,               /* AKEYCODE_F6 */
    VK_F7,               /* AKEYCODE_F7 */
    VK_F8,               /* AKEYCODE_F8 */
    VK_F9,               /* AKEYCODE_F9 */
    VK_F10,              /* AKEYCODE_F10 */
    VK_F11,              /* AKEYCODE_F11 */
    VK_F12,              /* AKEYCODE_F12 */
    VK_NUMLOCK,          /* AKEYCODE_NUM_LOCK */
    VK_NUMPAD0,          /* AKEYCODE_NUMPAD_0 */
    VK_NUMPAD1,          /* AKEYCODE_NUMPAD_1 */
    VK_NUMPAD2,          /* AKEYCODE_NUMPAD_2 */
    VK_NUMPAD3,          /* AKEYCODE_NUMPAD_3 */
    VK_NUMPAD4,          /* AKEYCODE_NUMPAD_4 */
    VK_NUMPAD5,          /* AKEYCODE_NUMPAD_5 */
    VK_NUMPAD6,          /* AKEYCODE_NUMPAD_6 */
    VK_NUMPAD7,          /* AKEYCODE_NUMPAD_7 */
    VK_NUMPAD8,          /* AKEYCODE_NUMPAD_8 */
    VK_NUMPAD9,          /* AKEYCODE_NUMPAD_9 */
    VK_DIVIDE,           /* AKEYCODE_NUMPAD_DIVIDE */
    VK_MULTIPLY,         /* AKEYCODE_NUMPAD_MULTIPLY */
    VK_SUBTRACT,         /* AKEYCODE_NUMPAD_SUBTRACT */
    VK_ADD,              /* AKEYCODE_NUMPAD_ADD */
    VK_DECIMAL,          /* AKEYCODE_NUMPAD_DOT */
    0,                   /* AKEYCODE_NUMPAD_COMMA */
    0,                   /* AKEYCODE_NUMPAD_ENTER */
    0,                   /* AKEYCODE_NUMPAD_EQUALS */
    0,                   /* AKEYCODE_NUMPAD_LEFT_PAREN */
    0,                   /* AKEYCODE_NUMPAD_RIGHT_PAREN */
    0,                   /* AKEYCODE_VOLUME_MUTE */
    0,                   /* AKEYCODE_INFO */
    0,                   /* AKEYCODE_CHANNEL_UP */
    0,                   /* AKEYCODE_CHANNEL_DOWN */
    0,                   /* AKEYCODE_ZOOM_IN */
    0,                   /* AKEYCODE_ZOOM_OUT */
    0,                   /* AKEYCODE_TV */
    0,                   /* AKEYCODE_WINDOW */
    0,                   /* AKEYCODE_GUIDE */
    0,                   /* AKEYCODE_DVR */
    0,                   /* AKEYCODE_BOOKMARK */
    0,                   /* AKEYCODE_CAPTIONS */
    0,                   /* AKEYCODE_SETTINGS */
    0,                   /* AKEYCODE_TV_POWER */
    0,                   /* AKEYCODE_TV_INPUT */
    0,                   /* AKEYCODE_STB_POWER */
    0,                   /* AKEYCODE_STB_INPUT */
    0,                   /* AKEYCODE_AVR_POWER */
    0,                   /* AKEYCODE_AVR_INPUT */
    0,                   /* AKEYCODE_PROG_RED */
    0,                   /* AKEYCODE_PROG_GREEN */
    0,                   /* AKEYCODE_PROG_YELLOW */
    0,                   /* AKEYCODE_PROG_BLUE */
    0,                   /* AKEYCODE_APP_SWITCH */
    0,                   /* AKEYCODE_BUTTON_1 */
    0,                   /* AKEYCODE_BUTTON_2 */
    0,                   /* AKEYCODE_BUTTON_3 */
    0,                   /* AKEYCODE_BUTTON_4 */
    0,                   /* AKEYCODE_BUTTON_5 */
    0,                   /* AKEYCODE_BUTTON_6 */
    0,                   /* AKEYCODE_BUTTON_7 */
    0,                   /* AKEYCODE_BUTTON_8 */
    0,                   /* AKEYCODE_BUTTON_9 */
    0,                   /* AKEYCODE_BUTTON_10 */
    0,                   /* AKEYCODE_BUTTON_11 */
    0,                   /* AKEYCODE_BUTTON_12 */
    0,                   /* AKEYCODE_BUTTON_13 */
    0,                   /* AKEYCODE_BUTTON_14 */
    0,                   /* AKEYCODE_BUTTON_15 */
    0,                   /* AKEYCODE_BUTTON_16 */
    0,                   /* AKEYCODE_LANGUAGE_SWITCH */
    0,                   /* AKEYCODE_MANNER_MODE */
    0,                   /* AKEYCODE_3D_MODE */
    0,                   /* AKEYCODE_CONTACTS */
    0,                   /* AKEYCODE_CALENDAR */
    0,                   /* AKEYCODE_MUSIC */
    0,                   /* AKEYCODE_CALCULATOR */
    0,                   /* AKEYCODE_ZENKAKU_HANKAKU */
    0,                   /* AKEYCODE_EISU */
    0,                   /* AKEYCODE_MUHENKAN */
    0,                   /* AKEYCODE_HENKAN */
    0,                   /* AKEYCODE_KATAKANA_HIRAGANA */
    0,                   /* AKEYCODE_YEN */
    0,                   /* AKEYCODE_RO */
    VK_KANA,             /* AKEYCODE_KANA */
    0,                   /* AKEYCODE_ASSIST */
};

static const uint16_t vkey_to_scancode[] =
{
    0,     /* 0x00 undefined */
    0,     /* VK_LBUTTON */
    0,     /* VK_RBUTTON */
    0,     /* VK_CANCEL */
    0,     /* VK_MBUTTON */
    0,     /* VK_XBUTTON1 */
    0,     /* VK_XBUTTON2 */
    0,     /* 0x07 undefined */
    0x0e,  /* VK_BACK */
    0x0f,  /* VK_TAB */
    0,     /* 0x0a undefined */
    0,     /* 0x0b undefined */
    0,     /* VK_CLEAR */
    0x1c,  /* VK_RETURN */
    0,     /* 0x0e undefined */
    0,     /* 0x0f undefined */
    0x2a,  /* VK_SHIFT */
    0x1d,  /* VK_CONTROL */
    0x38,  /* VK_MENU */
    0,     /* VK_PAUSE */
    0x3a,  /* VK_CAPITAL */
    0,     /* VK_KANA */
    0,     /* 0x16 undefined */
    0,     /* VK_JUNJA */
    0,     /* VK_FINAL */
    0,     /* VK_HANJA */
    0,     /* 0x1a undefined */
    0x01,  /* VK_ESCAPE */
    0,     /* VK_CONVERT */
    0,     /* VK_NONCONVERT */
    0,     /* VK_ACCEPT */
    0,     /* VK_MODECHANGE */
    0x39,  /* VK_SPACE */
    0x149, /* VK_PRIOR */
    0x151, /* VK_NEXT */
    0x14f, /* VK_END */
    0x147, /* VK_HOME */
    0x14b, /* VK_LEFT */
    0x148, /* VK_UP */
    0x14d, /* VK_RIGHT */
    0x150, /* VK_DOWN */
    0,     /* VK_SELECT */
    0,     /* VK_PRINT */
    0,     /* VK_EXECUTE */
    0,     /* VK_SNAPSHOT */
    0x152, /* VK_INSERT */
    0x153, /* VK_DELETE */
    0,     /* VK_HELP */
    0x0b,  /* VK_0 */
    0x02,  /* VK_1 */
    0x03,  /* VK_2 */
    0x04,  /* VK_3 */
    0x05,  /* VK_4 */
    0x06,  /* VK_5 */
    0x07,  /* VK_6 */
    0x08,  /* VK_7 */
    0x09,  /* VK_8 */
    0x0a,  /* VK_9 */
    0,     /* 0x3a undefined */
    0,     /* 0x3b undefined */
    0,     /* 0x3c undefined */
    0,     /* 0x3d undefined */
    0,     /* 0x3e undefined */
    0,     /* 0x3f undefined */
    0,     /* 0x40 undefined */
    0x1e,  /* VK_A */
    0x30,  /* VK_B */
    0x2e,  /* VK_C */
    0x20,  /* VK_D */
    0x12,  /* VK_E */
    0x21,  /* VK_F */
    0x22,  /* VK_G */
    0x23,  /* VK_H */
    0x17,  /* VK_I */
    0x24,  /* VK_J */
    0x25,  /* VK_K */
    0x26,  /* VK_L */
    0x32,  /* VK_M */
    0x31,  /* VK_N */
    0x18,  /* VK_O */
    0x19,  /* VK_P */
    0x10,  /* VK_Q */
    0x13,  /* VK_R */
    0x1f,  /* VK_S */
    0x14,  /* VK_T */
    0x16,  /* VK_U */
    0x2f,  /* VK_V */
    0x11,  /* VK_W */
    0x2d,  /* VK_X */
    0x15,  /* VK_Y */
    0x2c,  /* VK_Z */
    0,     /* VK_LWIN */
    0,     /* VK_RWIN */
    0,     /* VK_APPS */
    0,     /* 0x5e undefined */
    0,     /* VK_SLEEP */
    0x52,  /* VK_NUMPAD0 */
    0x4f,  /* VK_NUMPAD1 */
    0x50,  /* VK_NUMPAD2 */
    0x51,  /* VK_NUMPAD3 */
    0x4b,  /* VK_NUMPAD4 */
    0x4c,  /* VK_NUMPAD5 */
    0x4d,  /* VK_NUMPAD6 */
    0x47,  /* VK_NUMPAD7 */
    0x48,  /* VK_NUMPAD8 */
    0x49,  /* VK_NUMPAD9 */
    0x37,  /* VK_MULTIPLY */
    0x4e,  /* VK_ADD */
    0x7e,  /* VK_SEPARATOR */
    0x4a,  /* VK_SUBTRACT */
    0x53,  /* VK_DECIMAL */
    0135,  /* VK_DIVIDE */
    0x3b,  /* VK_F1 */
    0x3c,  /* VK_F2 */
    0x3d,  /* VK_F3 */
    0x3e,  /* VK_F4 */
    0x3f,  /* VK_F5 */
    0x40,  /* VK_F6 */
    0x41,  /* VK_F7 */
    0x42,  /* VK_F8 */
    0x43,  /* VK_F9 */
    0x44,  /* VK_F10 */
    0x57,  /* VK_F11 */
    0x58,  /* VK_F12 */
    0x64,  /* VK_F13 */
    0x65,  /* VK_F14 */
    0x66,  /* VK_F15 */
    0x67,  /* VK_F16 */
    0x68,  /* VK_F17 */
    0x69,  /* VK_F18 */
    0x6a,  /* VK_F19 */
    0x6b,  /* VK_F20 */
    0,     /* VK_F21 */
    0,     /* VK_F22 */
    0,     /* VK_F23 */
    0,     /* VK_F24 */
    0,     /* 0x88 undefined */
    0,     /* 0x89 undefined */
    0,     /* 0x8a undefined */
    0,     /* 0x8b undefined */
    0,     /* 0x8c undefined */
    0,     /* 0x8d undefined */
    0,     /* 0x8e undefined */
    0,     /* 0x8f undefined */
    0,     /* VK_NUMLOCK */
    0,     /* VK_SCROLL */
    0x10d, /* VK_OEM_NEC_EQUAL */
    0,     /* VK_OEM_FJ_JISHO */
    0,     /* VK_OEM_FJ_MASSHOU */
    0,     /* VK_OEM_FJ_TOUROKU */
    0,     /* VK_OEM_FJ_LOYA */
    0,     /* VK_OEM_FJ_ROYA */
    0,     /* 0x97 undefined */
    0,     /* 0x98 undefined */
    0,     /* 0x99 undefined */
    0,     /* 0x9a undefined */
    0,     /* 0x9b undefined */
    0,     /* 0x9c undefined */
    0,     /* 0x9d undefined */
    0,     /* 0x9e undefined */
    0,     /* 0x9f undefined */
    0x2a,  /* VK_LSHIFT */
    0x36,  /* VK_RSHIFT */
    0x1d,  /* VK_LCONTROL */
    0x11d, /* VK_RCONTROL */
    0x38,  /* VK_LMENU */
    0x138, /* VK_RMENU */
    0,     /* VK_BROWSER_BACK */
    0,     /* VK_BROWSER_FORWARD */
    0,     /* VK_BROWSER_REFRESH */
    0,     /* VK_BROWSER_STOP */
    0,     /* VK_BROWSER_SEARCH */
    0,     /* VK_BROWSER_FAVORITES */
    0,     /* VK_BROWSER_HOME */
    0x100, /* VK_VOLUME_MUTE */
    0x100, /* VK_VOLUME_DOWN */
    0x100, /* VK_VOLUME_UP */
    0,     /* VK_MEDIA_NEXT_TRACK */
    0,     /* VK_MEDIA_PREV_TRACK */
    0,     /* VK_MEDIA_STOP */
    0,     /* VK_MEDIA_PLAY_PAUSE */
    0,     /* VK_LAUNCH_MAIL */
    0,     /* VK_LAUNCH_MEDIA_SELECT */
    0,     /* VK_LAUNCH_APP1 */
    0,     /* VK_LAUNCH_APP2 */
    0,     /* 0xb8 undefined */
    0,     /* 0xb9 undefined */
    0x27,  /* VK_OEM_1 */
    0x0d,  /* VK_OEM_PLUS */
    0x33,  /* VK_OEM_COMMA */
    0x0c,  /* VK_OEM_MINUS */
    0x34,  /* VK_OEM_PERIOD */
    0x35,  /* VK_OEM_2 */
    0x29,  /* VK_OEM_3 */
    0,     /* 0xc1 undefined */
    0,     /* 0xc2 undefined */
    0,     /* 0xc3 undefined */
    0,     /* 0xc4 undefined */
    0,     /* 0xc5 undefined */
    0,     /* 0xc6 undefined */
    0,     /* 0xc7 undefined */
    0,     /* 0xc8 undefined */
    0,     /* 0xc9 undefined */
    0,     /* 0xca undefined */
    0,     /* 0xcb undefined */
    0,     /* 0xcc undefined */
    0,     /* 0xcd undefined */
    0,     /* 0xce undefined */
    0,     /* 0xcf undefined */
    0,     /* 0xd0 undefined */
    0,     /* 0xd1 undefined */
    0,     /* 0xd2 undefined */
    0,     /* 0xd3 undefined */
    0,     /* 0xd4 undefined */
    0,     /* 0xd5 undefined */
    0,     /* 0xd6 undefined */
    0,     /* 0xd7 undefined */
    0,     /* 0xd8 undefined */
    0,     /* 0xd9 undefined */
    0,     /* 0xda undefined */
    0x1a,  /* VK_OEM_4 */
    0x2b,  /* VK_OEM_5 */
    0x1b,  /* VK_OEM_6 */
    0x28,  /* VK_OEM_7 */
    0,     /* VK_OEM_8 */
    0,     /* 0xe0 undefined */
    0,     /* VK_OEM_AX */
    0x56,  /* VK_OEM_102 */
    0,     /* VK_ICO_HELP */
    0,     /* VK_ICO_00 */
    0,     /* VK_PROCESSKEY */
    0,     /* VK_ICO_CLEAR */
    0,     /* VK_PACKET */
    0,     /* 0xe8 undefined */
    0x71,  /* VK_OEM_RESET */
    0,     /* VK_OEM_JUMP */
    0,     /* VK_OEM_PA1 */
    0,     /* VK_OEM_PA2 */
    0,     /* VK_OEM_PA3 */
    0,     /* VK_OEM_WSCTRL */
    0,     /* VK_OEM_CUSEL */
    0,     /* VK_OEM_ATTN */
    0,     /* VK_OEM_FINISH */
    0,     /* VK_OEM_COPY */
    0,     /* VK_OEM_AUTO */
    0,     /* VK_OEM_ENLW */
    0,     /* VK_OEM_BACKTAB */
    0,     /* VK_ATTN */
    0,     /* VK_CRSEL */
    0,     /* VK_EXSEL */
    0,     /* VK_EREOF */
    0,     /* VK_PLAY */
    0,     /* VK_ZOOM */
    0,     /* VK_NONAME */
    0,     /* VK_PA1 */
    0x59,  /* VK_OEM_CLEAR */
    0,     /* 0xff undefined */
};


/*
 * Port of dlls/wineandroid.drv/keyboard.c keyboard_event → desktop event pipe.
 * Guest process_events reads EVENT_KEYBOARD and NtUserSendHardwareInput(0,…).
 */
static int send_keyboard_event(int32_t hwnd, int action, int keycode, int state)
{
    uint8_t buf[EVENT_DATA_SIZE];
    uint64_t hwnd64 = (uint32_t)hwnd;
    uint32_t evtype = EVENT_KEYBOARD;
    uint32_t lock_state = (uint32_t)state;
    uint32_t input_type = WA_INPUT_KEYBOARD;
    uint16_t vkey;
    uint16_t scan;
    uint32_t dw_flags;
    uint32_t time = 0;
    uint64_t extra = 0;

    if ((unsigned)keycode >= ARRAY_SIZE(keycode_to_vkey) || !keycode_to_vkey[keycode])
        return -1;

    vkey = keycode_to_vkey[keycode];
    if (vkey >= ARRAY_SIZE(vkey_to_scancode)) return -1;
    scan = vkey_to_scancode[vkey];
    dw_flags = (scan & 0x100) ? WA_KEYEVENTF_EXTENDEDKEY : 0;
    if (action == AKEY_EVENT_ACTION_UP) dw_flags |= WA_KEYEVENTF_KEYUP;

    memset(buf, 0, sizeof(buf));
    memcpy(buf + 0, &evtype, 4);
    memcpy(buf + 8, &hwnd64, 8);
    memcpy(buf + 16, &lock_state, 4);
    memcpy(buf + 24, &input_type, 4);
    memcpy(buf + 32, &vkey, 2);
    memcpy(buf + 34, &scan, 2);
    memcpy(buf + 36, &dw_flags, 4);
    memcpy(buf + 40, &time, 4);
    memcpy(buf + 48, &extra, 8);
    LOGI("keyboard hwnd=%08x action=%d keycode=%d vkey=%x scan=%x meta=%x", hwnd, action,
         keycode, vkey, scan, state);
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
        /* HA262AAH / some SF stacks abort on producer BGRA(5). Keep RGBA(1)
         * on the real ANativeWindow; remember guest request only for logging. */
        if (res->args[0] == 5 /* PF_BGRA_8888 */) {
            LOGW("SET_BUFFERS_FORMAT BGRA ignored (keep RGBA); hwnd=%08x", res->hdr.hwnd);
            win->buffer_format = 1;
            ret = 0;
            break;
        }
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

static int ioctl_get_buffer_sock(void *data, size_t in_size, size_t *ret_size, int *reply_fd)
{
    struct ioctl_header *hdr = data;
    struct native_win_data *win;
    int dupfd;

    *ret_size = 0;
    *reply_fd = -1;
    if (in_size < sizeof(*hdr)) return -EINVAL;
    win = get_native_win_data(hdr->hwnd, hdr->opengl);
    if (!win) return -ENOENT;
    if (win->buf_wine_fd < 0) return -EWOULDBLOCK;
    dupfd = dup(win->buf_wine_fd);
    if (dupfd < 0) return -errno;
    *reply_fd = dupfd;
    LOGI("IOCTL_GET_BUFFER_SOCK hwnd=%08x opengl=%d fd=%d", hdr->hwnd, hdr->opengl, dupfd);
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
        case IOCTL_GET_BUFFER_SOCK:
            status = ioctl_get_buffer_sock(buffer, (size_t)ret, &reply_size, &reply_fd);
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
    stop_anw_buf_serve(data);
    data->parent = (struct wine_native_window *)win;
    data->generation++;
    if (data->api)
        data->parent->perform(data->parent, NATIVE_WINDOW_API_CONNECT, data->api);
    data->parent->perform(data->parent, NATIVE_WINDOW_SET_BUFFERS_FORMAT, data->buffer_format);
    data->parent->setSwapInterval(data->parent, data->swap_interval);
    data->parent->query(data->parent, 0, &w); /* NATIVE_WINDOW_WIDTH */
    data->parent->query(data->parent, 1, &h); /* NATIVE_WINDOW_HEIGHT */
    /* OpenGL/client: tip-model host AMPHORA_BUF serve for WSI Present sock I/O. */
    if (opengl)
        start_anw_buf_serve(data);
    send_surface_changed_event(hwnd, opengl ? 1 : 0, (unsigned)w, (unsigned)h);
    pthread_mutex_unlock(&g_lock);
    LOGI("registerSurface hwnd=%08x opengl=%d %dx%d gen=%d buf_fd=%d", hwnd, (int)opengl, w, h,
         data->generation, data->buf_wine_fd);
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
        stop_anw_buf_serve(data);
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

JNIEXPORT jboolean JNICALL
Java_app_amphora_gamesession_wineandroid_WineAndroidNative_nativeSendMotionEvent(
    JNIEnv *env, jclass clazz, jint hwnd, jint action, jint x, jint y, jint state,
    jint vscroll)
{
    int rc;
    (void)env;
    (void)clazz;
    pthread_mutex_lock(&g_lock);
    rc = send_motion_event((int32_t)hwnd, (int)action, (int)x, (int)y, (int)state,
                           (int)vscroll);
    pthread_mutex_unlock(&g_lock);
    if (rc != 0) return JNI_FALSE;
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_app_amphora_gamesession_wineandroid_WineAndroidNative_nativeSendKeyboardEvent(
    JNIEnv *env, jclass clazz, jint hwnd, jint action, jint keycode, jint state)
{
    int rc;
    (void)env;
    (void)clazz;
    pthread_mutex_lock(&g_lock);
    rc = send_keyboard_event((int32_t)hwnd, (int)action, (int)keycode, (int)state);
    pthread_mutex_unlock(&g_lock);
    if (rc != 0) return JNI_FALSE;
    return JNI_TRUE;
}
