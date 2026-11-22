/*
 * Can a process started the way Wine is (through /system/bin/linker64, in the
 * app's sandbox) do what the swapchain in wineandroid.drv needs?
 *
 *   - the platform's libvulkan.so gives it the GPU;
 *   - an AHardwareBuffer it allocates imports as a VkImage it can render to
 *     (VK_ANDROID_external_memory_android_hardware_buffer), for every format
 *     the swapchain will offer;
 *   - "rendering finished" leaves Vulkan as a sync file
 *     (VK_KHR_external_semaphore_fd, SYNC_FD) to hand to SurfaceFlinger;
 *   - a release fence from SurfaceFlinger enters Vulkan the same way
 *     (semaphore and fence import, -1 meaning "already signaled").
 *
 * Build: NDK clang --target=aarch64-linux-android30 -O2 -o probe-vkahb vkahb.c -lvulkan -landroid
 * Run:   see tools/README.md. Prints one line per check, then PASS or FAIL.
 */
#define VK_USE_PLATFORM_ANDROID_KHR
#include <android/hardware_buffer.h>
#include <poll.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <vulkan/vulkan.h>

static int failures;

#define CHECK(cond, ...) \
    do { \
        int ok_ = !!(cond); \
        printf("%s ", ok_ ? "ok  " : "FAIL"); \
        printf(__VA_ARGS__); \
        printf("\n"); \
        if (!ok_) failures++; \
    } while (0)

static const char *const wanted[] = {
    VK_ANDROID_EXTERNAL_MEMORY_ANDROID_HARDWARE_BUFFER_EXTENSION_NAME,
    VK_EXT_QUEUE_FAMILY_FOREIGN_EXTENSION_NAME,
    VK_KHR_EXTERNAL_SEMAPHORE_FD_EXTENSION_NAME,
    VK_KHR_EXTERNAL_FENCE_FD_EXTENSION_NAME,
};

/* Not needed by the swapchain itself; what DXVK / vkd3d-proton look for. */
static const char *const informative[] = {
    VK_KHR_SWAPCHAIN_EXTENSION_NAME,
    "VK_KHR_swapchain_mutable_format",
    "VK_KHR_image_format_list",
    "VK_KHR_present_wait",
    "VK_KHR_present_id",
    "VK_EXT_swapchain_maintenance1",
    "VK_KHR_external_memory_fd",
    "VK_KHR_timeline_semaphore",
    /* How a 32-bit process gets mapped memory below 4 GiB (win32u, WoW64). */
    "VK_EXT_map_memory_placed",
    "VK_KHR_map_memory2",
    "VK_EXT_external_memory_host",
};

static int has_ext(const VkExtensionProperties *props, uint32_t count, const char *name)
{
    for (uint32_t i = 0; i < count; i++)
        if (!strcmp(props[i].extensionName, name)) return 1;
    return 0;
}

static VkPhysicalDevice gpu;
static VkDevice dev;
static VkQueue queue;
static uint32_t queue_family;
static VkCommandPool pool;

/* Extension entry points: the platform library only exports core Vulkan. */
static PFN_vkGetSemaphoreFdKHR get_semaphore_fd;
static PFN_vkImportSemaphoreFdKHR import_semaphore_fd;
static PFN_vkImportFenceFdKHR import_fence_fd;
static PFN_vkGetAndroidHardwareBufferPropertiesANDROID get_ahb_properties;

static uint32_t memory_type(uint32_t bits, VkMemoryPropertyFlags flags)
{
    VkPhysicalDeviceMemoryProperties props;
    vkGetPhysicalDeviceMemoryProperties(gpu, &props);
    for (uint32_t i = 0; i < props.memoryTypeCount; i++)
        if ((bits & (1u << i)) && (props.memoryTypes[i].propertyFlags & flags) == flags) return i;
    return UINT32_MAX;
}

/* 1 signaled, 0 not within the timeout, -1 error. */
static int wait_sync_fd(int fd, int timeout_ms)
{
    struct pollfd pfd = {.fd = fd, .events = POLLIN};
    int ret = poll(&pfd, 1, timeout_ms);
    if (ret < 0 || (pfd.revents & (POLLERR | POLLNVAL))) return -1;
    return ret > 0;
}

struct format_case {
    const char *name;
    uint32_t ahb_format;
    VkFormat vk_format;
    /* The clear color, and the first pixel's bytes it must produce. */
    VkClearColorValue clear;
    uint8_t expect[8];
    uint32_t bytes;
};

static void test_format(const struct format_case *c, uint32_t width, uint32_t height)
{
    AHardwareBuffer_Desc desc = {
        .width = width,
        .height = height,
        .layers = 1,
        .format = c->ahb_format,
        /* What a swapchain image is: rendered to, sampled by the compositor, scanned out by HWC. */
        .usage = AHARDWAREBUFFER_USAGE_GPU_FRAMEBUFFER | AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE |
                 AHARDWAREBUFFER_USAGE_COMPOSER_OVERLAY,
    };
    AHardwareBuffer *ahb = NULL;
    VkImage image = VK_NULL_HANDLE;
    VkDeviceMemory memory = VK_NULL_HANDLE;
    VkBuffer readback = VK_NULL_HANDLE;
    VkDeviceMemory readback_memory = VK_NULL_HANDLE;
    VkSemaphore done = VK_NULL_HANDLE;
    VkCommandBuffer cmd = VK_NULL_HANDLE;
    VkResult res;
    int fd = -1, ret;

    ret = AHardwareBuffer_allocate(&desc, &ahb);
    CHECK(!ret && ahb, "%s: AHardwareBuffer_allocate %ux%u ret %d", c->name, width, height, ret);
    if (ret || !ahb) return;
    AHardwareBuffer_describe(ahb, &desc);

    VkAndroidHardwareBufferFormatPropertiesANDROID format_props = {
        .sType = VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_FORMAT_PROPERTIES_ANDROID};
    VkAndroidHardwareBufferPropertiesANDROID props = {
        .sType = VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_PROPERTIES_ANDROID, .pNext = &format_props};
    res = get_ahb_properties(dev, ahb, &props);
    CHECK(!res && props.allocationSize, "%s: AHB properties res %d size %llu types %#x vk format %d (want %d) stride %u",
          c->name, res, (unsigned long long)props.allocationSize, props.memoryTypeBits, format_props.format,
          c->vk_format, desc.stride);
    if (res) goto out;

    VkExternalMemoryImageCreateInfo external = {
        .sType = VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO,
        .handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID};
    VkImageCreateInfo image_info = {
        .sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO,
        .pNext = &external,
        .imageType = VK_IMAGE_TYPE_2D,
        .format = c->vk_format,
        .extent = {width, height, 1},
        .mipLevels = 1,
        .arrayLayers = 1,
        .samples = VK_SAMPLE_COUNT_1_BIT,
        .tiling = VK_IMAGE_TILING_OPTIMAL,
        /* What DXVK asks of a swapchain image, plus the transfers this test uses. */
        .usage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT |
                 VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT,
        .sharingMode = VK_SHARING_MODE_EXCLUSIVE,
        .initialLayout = VK_IMAGE_LAYOUT_UNDEFINED,
    };
    res = vkCreateImage(dev, &image_info, NULL, &image);
    CHECK(!res, "%s: vkCreateImage (external AHB) res %d", c->name, res);
    if (res) goto out;

    VkMemoryDedicatedAllocateInfo dedicated = {
        .sType = VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO, .image = image};
    VkImportAndroidHardwareBufferInfoANDROID import = {
        .sType = VK_STRUCTURE_TYPE_IMPORT_ANDROID_HARDWARE_BUFFER_INFO_ANDROID, .pNext = &dedicated, .buffer = ahb};
    VkMemoryAllocateInfo alloc = {
        .sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,
        .pNext = &import,
        .allocationSize = props.allocationSize,
        .memoryTypeIndex = memory_type(props.memoryTypeBits, 0),
    };
    res = vkAllocateMemory(dev, &alloc, NULL, &memory);
    CHECK(!res, "%s: import as VkDeviceMemory res %d", c->name, res);
    if (res) goto out;
    res = vkBindImageMemory(dev, image, memory, 0);
    CHECK(!res, "%s: vkBindImageMemory res %d", c->name, res);
    if (res) goto out;

    VkBufferCreateInfo buffer_info = {
        .sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO,
        .size = (VkDeviceSize)width * height * c->bytes,
        .usage = VK_BUFFER_USAGE_TRANSFER_DST_BIT,
    };
    VkMemoryRequirements reqs;
    vkCreateBuffer(dev, &buffer_info, NULL, &readback);
    vkGetBufferMemoryRequirements(dev, readback, &reqs);
    VkMemoryAllocateInfo buffer_alloc = {
        .sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,
        .allocationSize = reqs.size,
        .memoryTypeIndex = memory_type(reqs.memoryTypeBits,
                                       VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT),
    };
    vkAllocateMemory(dev, &buffer_alloc, NULL, &readback_memory);
    vkBindBufferMemory(dev, readback, readback_memory, 0);

    /* The semaphore a present would export: signaled by the submit that finishes the frame. */
    VkExportSemaphoreCreateInfo export_info = {
        .sType = VK_STRUCTURE_TYPE_EXPORT_SEMAPHORE_CREATE_INFO,
        .handleTypes = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT};
    VkSemaphoreCreateInfo semaphore_info = {.sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO, .pNext = &export_info};
    res = vkCreateSemaphore(dev, &semaphore_info, NULL, &done);
    CHECK(!res, "%s: exportable semaphore res %d", c->name, res);
    if (res) goto out;

    VkCommandBufferAllocateInfo cmd_info = {
        .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO,
        .commandPool = pool,
        .level = VK_COMMAND_BUFFER_LEVEL_PRIMARY,
        .commandBufferCount = 1,
    };
    vkAllocateCommandBuffers(dev, &cmd_info, &cmd);
    VkCommandBufferBeginInfo begin = {.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
    vkBeginCommandBuffer(cmd, &begin);
    VkImageSubresourceRange range = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    VkImageMemoryBarrier barrier = {
        .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,
        .dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT,
        .oldLayout = VK_IMAGE_LAYOUT_UNDEFINED,
        .newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
        .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
        .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
        .image = image,
        .subresourceRange = range,
    };
    vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, NULL, 0, NULL,
                         1, &barrier);
    vkCmdClearColorImage(cmd, image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, &c->clear, 1, &range);
    barrier.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    barrier.dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
    barrier.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
    barrier.newLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
    vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, NULL, 0, NULL, 1,
                         &barrier);
    VkBufferImageCopy copy = {
        .imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1},
        .imageExtent = {width, height, 1},
    };
    vkCmdCopyImageToBuffer(cmd, image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, readback, 1, &copy);
    vkEndCommandBuffer(cmd);

    VkSubmitInfo submit = {
        .sType = VK_STRUCTURE_TYPE_SUBMIT_INFO,
        .commandBufferCount = 1,
        .pCommandBuffers = &cmd,
        .signalSemaphoreCount = 1,
        .pSignalSemaphores = &done,
    };
    res = vkQueueSubmit(queue, 1, &submit, VK_NULL_HANDLE);
    CHECK(!res, "%s: vkQueueSubmit res %d", c->name, res);
    if (res) goto out;

    VkSemaphoreGetFdInfoKHR get_fd = {
        .sType = VK_STRUCTURE_TYPE_SEMAPHORE_GET_FD_INFO_KHR,
        .semaphore = done,
        .handleType = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT,
    };
    res = get_semaphore_fd(dev, &get_fd, &fd);
    /* -1 is legal: the work is already done. */
    ret = fd < 0 ? 1 : wait_sync_fd(fd, 2000);
    CHECK(!res && ret == 1, "%s: render-done sync fd res %d fd %d signaled %d", c->name, res, fd, ret);
    vkQueueWaitIdle(queue);

    void *data = NULL;
    vkMapMemory(dev, readback_memory, 0, VK_WHOLE_SIZE, 0, &data);
    if (data) {
        const uint8_t *first = data, *last = first + ((size_t)width * height - 1) * c->bytes;
        int same = !memcmp(first, c->expect, c->bytes) && !memcmp(last, c->expect, c->bytes);
        CHECK(same, "%s: readback first %02x %02x %02x %02x last %02x %02x %02x %02x", c->name, first[0], first[1],
              first[2], first[3], last[0], last[1], last[2], last[3]);
        vkUnmapMemory(dev, readback_memory);
    } else {
        CHECK(0, "%s: map readback buffer", c->name);
    }

out:
    if (fd >= 0) close(fd);
    vkDeviceWaitIdle(dev);
    if (cmd) vkFreeCommandBuffers(dev, pool, 1, &cmd);
    if (done) vkDestroySemaphore(dev, done, NULL);
    if (readback) vkDestroyBuffer(dev, readback, NULL);
    if (readback_memory) vkFreeMemory(dev, readback_memory, NULL);
    if (image) vkDestroyImage(dev, image, NULL);
    if (memory) vkFreeMemory(dev, memory, NULL);
    AHardwareBuffer_release(ahb);
}

/* A release fence reaches the application through the semaphore / fence it passed to acquire. */
static void test_import(void)
{
    VkSemaphoreCreateInfo semaphore_info = {.sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};
    VkFenceCreateInfo fence_info = {.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
    VkSemaphore semaphore;
    VkFence fence;
    VkResult res;

    vkCreateSemaphore(dev, &semaphore_info, NULL, &semaphore);
    vkCreateFence(dev, &fence_info, NULL, &fence);

    VkImportSemaphoreFdInfoKHR import_semaphore = {
        .sType = VK_STRUCTURE_TYPE_IMPORT_SEMAPHORE_FD_INFO_KHR,
        .semaphore = semaphore,
        .flags = VK_SEMAPHORE_IMPORT_TEMPORARY_BIT,
        .handleType = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT,
        .fd = -1,
    };
    res = import_semaphore_fd(dev, &import_semaphore);
    CHECK(!res, "import signaled sync fd into a semaphore res %d", res);

    /* The semaphore must now be waitable by a submit. */
    VkPipelineStageFlags stage = VK_PIPELINE_STAGE_ALL_COMMANDS_BIT;
    VkSubmitInfo submit = {
        .sType = VK_STRUCTURE_TYPE_SUBMIT_INFO,
        .waitSemaphoreCount = 1,
        .pWaitSemaphores = &semaphore,
        .pWaitDstStageMask = &stage,
    };
    res = vkQueueSubmit(queue, 1, &submit, fence);
    if (!res) res = vkWaitForFences(dev, 1, &fence, VK_TRUE, 2000000000ull);
    CHECK(!res, "submit waiting on the imported semaphore res %d", res);
    vkResetFences(dev, 1, &fence);

    VkImportFenceFdInfoKHR import_fence = {
        .sType = VK_STRUCTURE_TYPE_IMPORT_FENCE_FD_INFO_KHR,
        .fence = fence,
        .flags = VK_FENCE_IMPORT_TEMPORARY_BIT,
        .handleType = VK_EXTERNAL_FENCE_HANDLE_TYPE_SYNC_FD_BIT,
        .fd = -1,
    };
    res = import_fence_fd(dev, &import_fence);
    if (!res) res = vkWaitForFences(dev, 1, &fence, VK_TRUE, 0);
    CHECK(!res, "import signaled sync fd into a fence res %d", res);

    vkDeviceWaitIdle(dev);
    vkDestroySemaphore(dev, semaphore, NULL);
    vkDestroyFence(dev, fence, NULL);
}

int main(void)
{
    VkInstance instance;
    VkResult res;
    uint32_t count, version = VK_API_VERSION_1_0;

    setvbuf(stdout, NULL, _IOLBF, 0);
    vkEnumerateInstanceVersion(&version);
    VkApplicationInfo app = {.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO, .apiVersion = VK_API_VERSION_1_1};
    VkInstanceCreateInfo instance_info = {.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO, .pApplicationInfo = &app};
    res = vkCreateInstance(&instance_info, NULL, &instance);
    CHECK(!res, "vkCreateInstance res %d, loader %u.%u.%u", res, VK_VERSION_MAJOR(version), VK_VERSION_MINOR(version),
          VK_VERSION_PATCH(version));
    if (res) goto done;

    count = 0;
    vkEnumerateInstanceExtensionProperties(NULL, &count, NULL);
    VkExtensionProperties *instance_exts = calloc(count ? count : 1, sizeof(*instance_exts));
    vkEnumerateInstanceExtensionProperties(NULL, &count, instance_exts);
    printf("     instance extensions (%u):", count);
    for (uint32_t i = 0; i < count; i++) printf(" %s", instance_exts[i].extensionName);
    printf("\n");

    count = 1;
    res = vkEnumeratePhysicalDevices(instance, &count, &gpu);
    CHECK(res >= 0 && count == 1, "vkEnumeratePhysicalDevices res %d count %u", res, count);
    if (res < 0 || !count) goto done;

    VkPhysicalDeviceProperties gpu_props;
    vkGetPhysicalDeviceProperties(gpu, &gpu_props);
    printf("     %s, Vulkan %u.%u.%u, driver %#x, vendor %#x device %#x\n", gpu_props.deviceName,
           VK_VERSION_MAJOR(gpu_props.apiVersion), VK_VERSION_MINOR(gpu_props.apiVersion),
           VK_VERSION_PATCH(gpu_props.apiVersion), gpu_props.driverVersion, gpu_props.vendorID, gpu_props.deviceID);

    count = 0;
    vkEnumerateDeviceExtensionProperties(gpu, NULL, &count, NULL);
    VkExtensionProperties *exts = calloc(count ? count : 1, sizeof(*exts));
    vkEnumerateDeviceExtensionProperties(gpu, NULL, &count, exts);
    printf("     %u device extensions\n", count);
    for (size_t i = 0; i < sizeof(wanted) / sizeof(wanted[0]); i++)
        CHECK(has_ext(exts, count, wanted[i]), "device extension %s", wanted[i]);
    for (size_t i = 0; i < sizeof(informative) / sizeof(informative[0]); i++)
        printf("     %s: %s\n", informative[i], has_ext(exts, count, informative[i]) ? "yes" : "no");

    uint32_t family_count = 0;
    vkGetPhysicalDeviceQueueFamilyProperties(gpu, &family_count, NULL);
    VkQueueFamilyProperties *families = calloc(family_count ? family_count : 1, sizeof(*families));
    vkGetPhysicalDeviceQueueFamilyProperties(gpu, &family_count, families);
    queue_family = UINT32_MAX;
    for (uint32_t i = 0; i < family_count; i++)
        if (families[i].queueFlags & VK_QUEUE_GRAPHICS_BIT) { queue_family = i; break; }
    CHECK(queue_family != UINT32_MAX, "graphics queue family %u of %u", queue_family, family_count);
    if (queue_family == UINT32_MAX) goto done;

    float priority = 1.0f;
    VkDeviceQueueCreateInfo queue_info = {
        .sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO,
        .queueFamilyIndex = queue_family,
        .queueCount = 1,
        .pQueuePriorities = &priority,
    };
    VkDeviceCreateInfo device_info = {
        .sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO,
        .queueCreateInfoCount = 1,
        .pQueueCreateInfos = &queue_info,
        .enabledExtensionCount = sizeof(wanted) / sizeof(wanted[0]),
        .ppEnabledExtensionNames = wanted,
    };
    res = vkCreateDevice(gpu, &device_info, NULL, &dev);
    CHECK(!res, "vkCreateDevice with the swapchain's extensions res %d", res);
    if (res) goto done;
    vkGetDeviceQueue(dev, queue_family, 0, &queue);
    get_semaphore_fd = (PFN_vkGetSemaphoreFdKHR)vkGetDeviceProcAddr(dev, "vkGetSemaphoreFdKHR");
    import_semaphore_fd = (PFN_vkImportSemaphoreFdKHR)vkGetDeviceProcAddr(dev, "vkImportSemaphoreFdKHR");
    import_fence_fd = (PFN_vkImportFenceFdKHR)vkGetDeviceProcAddr(dev, "vkImportFenceFdKHR");
    get_ahb_properties = (PFN_vkGetAndroidHardwareBufferPropertiesANDROID)vkGetDeviceProcAddr(
        dev, "vkGetAndroidHardwareBufferPropertiesANDROID");
    CHECK(get_semaphore_fd && import_semaphore_fd && import_fence_fd && get_ahb_properties,
          "extension entry points %p %p %p %p", (void *)get_semaphore_fd, (void *)import_semaphore_fd,
          (void *)import_fence_fd, (void *)get_ahb_properties);
    if (!get_semaphore_fd || !import_semaphore_fd || !import_fence_fd || !get_ahb_properties) goto done;
    VkCommandPoolCreateInfo pool_info = {
        .sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO, .queueFamilyIndex = queue_family};
    vkCreateCommandPool(dev, &pool_info, NULL, &pool);

    /* Can "finished rendering" be exported as a sync file at all? */
    VkPhysicalDeviceExternalSemaphoreInfo semaphore_query = {
        .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_EXTERNAL_SEMAPHORE_INFO,
        .handleType = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT};
    VkExternalSemaphoreProperties semaphore_props = {.sType = VK_STRUCTURE_TYPE_EXTERNAL_SEMAPHORE_PROPERTIES};
    vkGetPhysicalDeviceExternalSemaphoreProperties(gpu, &semaphore_query, &semaphore_props);
    CHECK((semaphore_props.externalSemaphoreFeatures & 3) == 3, "SYNC_FD semaphores: features %#x (export 1, import 2)",
          semaphore_props.externalSemaphoreFeatures);

    static const struct format_case cases[] = {
        {"RGBA8 unorm", AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_R8G8B8A8_UNORM,
         {.float32 = {1.0f, 0.0f, 0.0f, 1.0f}}, {0xff, 0x00, 0x00, 0xff}, 4},
        /* The same buffer format: sRGB is a property of the view, the compositor sees bytes. */
        {"RGBA8 srgb", AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_R8G8B8A8_SRGB,
         {.float32 = {1.0f, 0.0f, 0.0f, 1.0f}}, {0xff, 0x00, 0x00, 0xff}, 4},
        {"A2B10G10R10", AHARDWAREBUFFER_FORMAT_R10G10B10A2_UNORM, VK_FORMAT_A2B10G10R10_UNORM_PACK32,
         {.float32 = {1.0f, 0.0f, 0.0f, 1.0f}}, {0xff, 0x03, 0x00, 0xc0}, 4},
        {"RGBA16F", AHARDWAREBUFFER_FORMAT_R16G16B16A16_FLOAT, VK_FORMAT_R16G16B16A16_SFLOAT,
         {.float32 = {1.0f, 0.0f, 0.0f, 1.0f}}, {0x00, 0x3c, 0x00, 0x00, 0x00, 0x00, 0x00, 0x3c}, 8},
        {"RGB565", AHARDWAREBUFFER_FORMAT_R5G6B5_UNORM, VK_FORMAT_R5G6B5_UNORM_PACK16,
         {.float32 = {1.0f, 0.0f, 0.0f, 1.0f}}, {0x00, 0xf8}, 2},
    };
    for (size_t i = 0; i < sizeof(cases) / sizeof(cases[0]); i++) test_format(&cases[i], 1280, 720);
    /* A size that is not a multiple of anything, as a resized window would be. */
    test_format(&cases[0], 1283, 721);

    test_import();

done:
    printf("%s (%d failed)\n", failures ? "FAIL" : "PASS", failures);
    return failures ? 1 : 0;
}
