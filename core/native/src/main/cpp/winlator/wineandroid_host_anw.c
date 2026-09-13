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
#include <errno.h>
#include <jni.h>
#include <pthread.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>

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
    }

    memset(&msg, 0, sizeof(msg));
    iov.iov_base = &hdr;
    iov.iov_len = sizeof(hdr);
    msg.msg_iov = &iov;
    msg.msg_iovlen = 1;

    if (nh && nh->numFds > 0) {
        struct cmsghdr *cmsg;
        size_t fdbytes = sizeof(int) * (size_t)nh->numFds;
        if (fdbytes > sizeof(control) - sizeof(struct cmsghdr)) return -1;
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
            return -1;
        }
        break;
    }
    /* Ints as a follow-up write so wine can recvmsg(hdr+fds) then read(ints). */
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
                if (cmd == CMD_QUEUE)
                    ret = ctx->win->queueBuffer(ctx->win, ctx->buffers[buffer_id], -1);
                else
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
    struct serve_ctx *ctx = (struct serve_ctx *)(intptr_t)servePtr;
    (void)env;
    (void)clazz;
    if (ctx) ctx->generation++;
}

JNIEXPORT jlong JNICALL
Java_app_amphora_gamesession_wineandroid_WineAndroidNative_nativeStartBufferServe(
    JNIEnv *env, jclass clazz, jlong windowHandle, jint hwnd, jint sockFd)
{
    struct serve_ctx *ctx;
    pthread_t th;
    int i;
    (void)env;
    (void)clazz;
    if (!windowHandle || sockFd < 0) return 0;

    ctx = calloc(1, sizeof(*ctx));
    if (!ctx) {
        close(sockFd);
        return 0;
    }
    ctx->win = (struct wine_native_window *)(intptr_t)windowHandle;
    ctx->sock = sockFd;
    ctx->hwnd = hwnd;
    ctx->generation = 1;
    for (i = 0; i < NB_BUFFERS; i++) ctx->buffer_lru[i] = -1;

    /* serve thread owns one ref; caller may release its copy */
    ANativeWindow_acquire((ANativeWindow *)ctx->win);

    if (pthread_create(&th, NULL, serve_thread, ctx)) {
        LOGE("pthread_create failed for hwnd=%08x", hwnd);
        ANativeWindow_release((ANativeWindow *)ctx->win);
        close(sockFd);
        free(ctx);
        return 0;
    }
    pthread_detach(th);
    return (jlong)(intptr_t)ctx;
}

JNIEXPORT void JNICALL
Java_app_amphora_gamesession_wineandroid_WineAndroidNative_nativeStopBufferServe(
    JNIEnv *env, jclass clazz, jlong servePtr, jint sockFd)
{
    struct serve_ctx *ctx = (struct serve_ctx *)(intptr_t)servePtr;
    int32_t cmd = CMD_STOP;
    int fd = sockFd;
    (void)env;
    (void)clazz;
    if (ctx && ctx->sock >= 0) fd = ctx->sock;
    if (fd >= 0) {
        write_full(fd, &cmd, sizeof(cmd));
        /* serve thread closes ctx->sock on exit; shutdown to unblock read */
        shutdown(fd, SHUT_RDWR);
    }
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
