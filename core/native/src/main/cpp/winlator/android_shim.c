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
 */

#define _GNU_SOURCE

#include <fcntl.h>
#include <linux/memfd.h>
#include <stddef.h>
#include <sys/syscall.h>
#include <unistd.h>

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
