/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Minimal stand-in for the platform `libandroid.so`, installed into imagefs as
 * `usr/lib/libandroid.so` for the Mali (Leegao) guest ICD.
 *
 * imagefs normally symlinks that name at `/system/lib64/libandroid.so`, which
 * `DT_NEEDED`s libhwui -> libvulkan + libcrypto. Inside the Wine/Box64 process
 * `LD_LIBRARY_PATH` starts with imagefs, so those resolve to the image's
 * Khronos loader and OpenSSL 3 instead of the platform BoringSSL and Vulkan
 * loader, and their eager relocations fail (`android::vkSetCacheDir`,
 * `OpenSSL_add_all_algorithms`). Every dlopen that reaches libandroid dies with
 * it, including `libvulkan_wrapper.so` via libandroid-shmem -- which is why the
 * Vulkan loader found no driver at all and DXVK reported a missing
 * `VK_KHR_surface`.
 *
 * Only ASharedMemory is consumed here (by libandroid-shmem); the wrapper's
 * AHardwareBuffer entry points come from the platform libnativewindow. memfd
 * matches what the platform implementation returns on this API level: an fd
 * that mmaps shared and reports its size through lseek.
 *
 * The AHardwareBuffer symbols below forward to the platform libnativewindow.so
 * (dlopen + dlsym on first use). Box64's wrappedandroid may resolve these NDK
 * symbols to this shim in the host process; GDI and the WSI bridge need real
 * implementations, so a missing symbol is fatal.
 */

#define _GNU_SOURCE

#include <android/log.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <linux/memfd.h>
#include <pthread.h>
#include <stddef.h>
#include <stdint.h>
#include <stdlib.h>
#include <sys/syscall.h>
#include <unistd.h>

#define SHIM_LOG_TAG "AmphoraAndroidShim"
#define SHIM_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, SHIM_LOG_TAG, __VA_ARGS__)

/* ---------------------------------------------------------------------------
 * AHardwareBuffer forwarding (platform libnativewindow.so).
 * Signatures per NDK <android/hardware_buffer.h>; types kept loose here to
 * avoid depending on NDK headers in this translation unit.
 * --------------------------------------------------------------------------- */

struct AHardwareBuffer_shim;
struct ANativeWindowBuffer_shim;
typedef struct AHardwareBuffer_shim AHardwareBuffer_shim;

typedef void (*pfn_ahb_describe)(const AHardwareBuffer_shim *, void *out_desc);
typedef void (*pfn_ahb_acquire)(AHardwareBuffer_shim *);
typedef void (*pfn_ahb_release)(AHardwareBuffer_shim *);
typedef int (*pfn_ahb_lock)(AHardwareBuffer_shim *, uint64_t usage, int32_t fence,
                            const void *rect, void **out_virt);
typedef int (*pfn_ahb_unlock)(AHardwareBuffer_shim *, int32_t *out_fence);
typedef int (*pfn_ahb_recv_handle)(int socket_fd, AHardwareBuffer_shim **out_buffer);
typedef int (*pfn_ahb_send_handle)(int socket_fd, AHardwareBuffer_shim *buffer);
/* Not in the public NDK headers; libnativewindow.so exports it. */
typedef AHardwareBuffer_shim *(*pfn_anwb_get_hardware_buffer)(const struct ANativeWindowBuffer_shim *);

static pfn_ahb_describe fn_ahb_describe;
static pfn_ahb_acquire fn_ahb_acquire;
static pfn_ahb_release fn_ahb_release;
static pfn_ahb_lock fn_ahb_lock;
static pfn_ahb_unlock fn_ahb_unlock;
static pfn_ahb_recv_handle fn_ahb_recv_handle;
static pfn_ahb_send_handle fn_ahb_send_handle;
static pfn_anwb_get_hardware_buffer fn_anwb_get_hardware_buffer;

static void shim_resolve_ahb(void) {
  void *lib;
  const char *names[] = {
      "AHardwareBuffer_describe",       "AHardwareBuffer_acquire",
      "AHardwareBuffer_release",        "AHardwareBuffer_lock",
      "AHardwareBuffer_unlock",         "AHardwareBuffer_recvHandleFromUnixSocket",
      "AHardwareBuffer_sendHandleToUnixSocket",
  };
  void **slots[] = {
      (void **)&fn_ahb_describe, (void **)&fn_ahb_acquire,
      (void **)&fn_ahb_release,  (void **)&fn_ahb_lock,
      (void **)&fn_ahb_unlock,   (void **)&fn_ahb_recv_handle,
      (void **)&fn_ahb_send_handle,
  };
  size_t i;

  lib = dlopen("libnativewindow.so", RTLD_NOW);
  if (!lib) {
    SHIM_LOGE("dlopen libnativewindow.so failed: %s", dlerror());
    abort();
  }
  for (i = 0; i < sizeof(slots) / sizeof(slots[0]); i++) {
    *slots[i] = dlsym(lib, names[i]);
    if (!*slots[i]) {
      SHIM_LOGE("dlsym %s failed: %s", names[i], dlerror());
      abort();
    }
  }
  fn_anwb_get_hardware_buffer = (pfn_anwb_get_hardware_buffer)dlsym(lib, "ANativeWindowBuffer_getHardwareBuffer");
  if (!fn_anwb_get_hardware_buffer) {
    SHIM_LOGE("dlsym ANativeWindowBuffer_getHardwareBuffer failed: %s", dlerror());
    abort();
  }
}

static void shim_ahb_once(void) {
  static pthread_once_t once = PTHREAD_ONCE_INIT;
  pthread_once(&once, shim_resolve_ahb);
}


int ASharedMemory_create(const char *name, size_t size) {
  int fd = (int)syscall(__NR_memfd_create, name != NULL ? name : "SharedMemory",
                        MFD_CLOEXEC | MFD_ALLOW_SEALING);
  if (fd < 0) return -1;
  if (ftruncate(fd, (off_t)size) < 0) {
    close(fd);
    return -1;
  }
  return fd;
}

size_t ASharedMemory_getSize(int fd) {
  off_t size = lseek(fd, 0, SEEK_END);
  return size < 0 ? 0u : (size_t)size;
}

/*
 * Sealing an memfd is one-way, and callers use this only to drop write access
 * on an fd they are about to share. Reporting success keeps libandroid-shmem's
 * shmctl path working; the guest X11 clients never rely on the protection.
 */
int ASharedMemory_setProt(int fd, int prot) {
  (void)fd;
  (void)prot;
  return 0;
}

void AHardwareBuffer_describe(const AHardwareBuffer_shim *buffer, void *out_desc) {
  shim_ahb_once();
  fn_ahb_describe(buffer, out_desc);
}

void AHardwareBuffer_acquire(AHardwareBuffer_shim *buffer) {
  shim_ahb_once();
  fn_ahb_acquire(buffer);
}

void AHardwareBuffer_release(AHardwareBuffer_shim *buffer) {
  shim_ahb_once();
  fn_ahb_release(buffer);
}

int AHardwareBuffer_lock(AHardwareBuffer_shim *buffer, uint64_t usage, int32_t fence,
                         const void *rect, void **out_virtual_addr) {
  shim_ahb_once();
  return fn_ahb_lock(buffer, usage, fence, rect, out_virtual_addr);
}

int AHardwareBuffer_unlock(AHardwareBuffer_shim *buffer, int32_t *out_fence) {
  shim_ahb_once();
  return fn_ahb_unlock(buffer, out_fence);
}

int AHardwareBuffer_recvHandleFromUnixSocket(int socket_fd, AHardwareBuffer_shim **out_buffer) {
  shim_ahb_once();
  return fn_ahb_recv_handle(socket_fd, out_buffer);
}

int AHardwareBuffer_sendHandleToUnixSocket(int socket_fd, AHardwareBuffer_shim *buffer) {
  shim_ahb_once();
  return fn_ahb_send_handle(socket_fd, buffer);
}

AHardwareBuffer_shim *ANativeWindowBuffer_getHardwareBuffer(const struct ANativeWindowBuffer_shim *anwb) {
  shim_ahb_once();
  return fn_anwb_get_hardware_buffer(anwb);
}
