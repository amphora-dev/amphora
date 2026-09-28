/* AHB-import swapchain (Winlator vk_image route).
 * NOT a VkLayer. No GIPA/Negotiate export. No GraphicBuffer.
 * No runtime wine-table / needle / GIPA / GDPA hooks.
 * Wine CreateSwapchain (wineandroid / win32u) calls amphora_ahb_sc_* at
 * compile-time; this file only implements import + register + exports.
 * Winlator steps: dequeue AHB → GetAHBProps → CreateImage(EXTERNAL) →
 * ImportAHB+dedicated alloc → BindImageMemory.
 */
#ifndef VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID
#define VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID 0x00000400
#endif
#ifndef VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO
#define VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO 1000072001
#endif
#ifndef VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO
#define VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO 1000127001
#endif
#ifndef VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_PROPERTIES_ANDROID
#define VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_PROPERTIES_ANDROID 1000129001
#endif
#ifndef VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_FORMAT_PROPERTIES_ANDROID
#define VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_FORMAT_PROPERTIES_ANDROID 1000129002
#endif
#ifndef VK_STRUCTURE_TYPE_IMPORT_ANDROID_HARDWARE_BUFFER_INFO_ANDROID
#define VK_STRUCTURE_TYPE_IMPORT_ANDROID_HARDWARE_BUFFER_INFO_ANDROID 1000129003
#endif
#ifndef VK_STRUCTURE_TYPE_ACQUIRE_NEXT_IMAGE_INFO_KHR
#define VK_STRUCTURE_TYPE_ACQUIRE_NEXT_IMAGE_INFO_KHR 1000189004
#endif

typedef struct VkExternalMemoryImageCreateInfo_ahb {
    VkStructureType sType;
    const void *pNext;
    VkExternalMemoryHandleTypeFlags handleTypes;
} VkExternalMemoryImageCreateInfo_ahb;
typedef struct VkMemoryDedicatedAllocateInfo_ahb {
    VkStructureType sType;
    const void *pNext;
    VkImage image;
    VkBuffer buffer;
} VkMemoryDedicatedAllocateInfo_ahb;
typedef struct VkAndroidHardwareBufferFormatPropertiesANDROID_ahb {
    VkStructureType sType;
    void *pNext;
    VkFormat format;
    uint64_t externalFormat;
    VkFormatFeatureFlags formatFeatures;
    VkComponentMapping samplerYcbcrConversionComponents;
    VkSamplerYcbcrModelConversion suggestedYcbcrModel;
    VkSamplerYcbcrRange suggestedYcbcrRange;
    VkChromaLocation suggestedXChromaOffset;
    VkChromaLocation suggestedYChromaOffset;
} VkAndroidHardwareBufferFormatPropertiesANDROID_ahb;
typedef struct VkAndroidHardwareBufferPropertiesANDROID_ahb {
    VkStructureType sType;
    void *pNext;
    VkDeviceSize allocationSize;
    uint32_t memoryTypeBits;
} VkAndroidHardwareBufferPropertiesANDROID_ahb;
typedef struct VkImportAndroidHardwareBufferInfoANDROID_ahb {
    VkStructureType sType;
    const void *pNext;
    void *buffer;
} VkImportAndroidHardwareBufferInfoANDROID_ahb;
typedef struct VkAcquireNextImageInfoKHR_ahb {
    VkStructureType sType;
    const void *pNext;
    VkSwapchainKHR swapchain;
    uint64_t timeout;
    VkSemaphore semaphore;
    VkFence fence;
    uint32_t deviceMask;
} VkAcquireNextImageInfoKHR_ahb;

typedef VkResult (*PFN_vkGetAndroidHardwareBufferPropertiesANDROID_ahb)(
    VkDevice, const void *, VkAndroidHardwareBufferPropertiesANDROID_ahb *);
typedef VkResult (*PFN_vkAcquireNextImage2KHR_ahb)(VkDevice, const VkAcquireNextImageInfoKHR_ahb *, uint32_t *);

#define AHB_SC_MAX_SURFACES 8
#define AHB_SC_MAX_IMAGES   NB_CACHED_BUFFERS
#define AHB_SC_MAGIC        0x41484253u
#define AHB_SC_MAX_DEVICES  8

/* Image ownership, as in AOSP libvulkan swapchain.cpp: an image is either in the
 * host BufferQueue (cancelled at create, or queued by Present) or dequeued and
 * handed to the app by Acquire. */
enum { AHB_SC_IN_QUEUE = 0, AHB_SC_ACQUIRED = 1 };

#ifndef NATIVE_WINDOW_API_EGL
#define NATIVE_WINDOW_API_EGL 1
#endif
#ifndef NATIVE_WINDOW_SCALING_MODE_SCALE_TO_WINDOW
#define NATIVE_WINDOW_SCALING_MODE_SCALE_TO_WINDOW 1
#endif
/* GRALLOC_USAGE_HW_TEXTURE | HW_RENDER | HW_COMPOSER: what a Vulkan
 * swapchain on Android asks for (colour attachment + sampled + overlay). */
#define AHB_SC_BUFFER_USAGE 0xB00

/* Buffer format for a swapchain format, as AOSP swapchain.cpp
 * GetNativePixelFormat. Without it the queue hands out the SurfaceView's
 * default (8888), the import below takes the image format from that buffer,
 * and a 10-bit swapchain's pixels land in an 8888 buffer (Adreno 8xx picks
 * A2B10G10R10 for D3D8/9). HAL values from android.hardware.graphics.common;
 * 0 = leave the queue default. */
static int ahb_sc_native_format(VkFormat format)
{
    switch (format) {
    case VK_FORMAT_R8G8B8A8_UNORM:
    case VK_FORMAT_R8G8B8A8_SRGB:
        return 0x1; /* RGBA_8888 */
    case VK_FORMAT_R5G6B5_UNORM_PACK16:
        return 0x4; /* RGB_565 */
    case VK_FORMAT_A2B10G10R10_UNORM_PACK32:
        return 0x2B; /* RGBA_1010102 */
    case VK_FORMAT_R16G16B16A16_SFLOAT:
        return 0x16; /* RGBA_FP16 */
    default:
        return 0;
    }
}

struct ahb_sc_surface {
    VkSurfaceKHR surface;
    struct amphora_win *win;
};
struct ahb_sc {
    uint32_t magic;
    VkDevice device;
    VkPhysicalDevice phys;
    VkSurfaceKHR surface;
    struct amphora_win *win;
    uint32_t count;
    VkFormat format;
    VkExtent2D extent;
    VkImage images[AHB_SC_MAX_IMAGES];
    VkDeviceMemory memories[AHB_SC_MAX_IMAGES];
    struct amphora_buf *bufs[AHB_SC_MAX_IMAGES]; /* one ref held per image */
    uint8_t state[AHB_SC_MAX_IMAGES];
    unsigned presents;        /* frames queued to the host; logged at destroy */
    unsigned fenced_presents; /* of those, queued with a render-done fence */
    unsigned acquires;        /* successful acquires */
    unsigned release_fences;  /* of those, with a real compositor release fence */
    unsigned imported_sems;   /* acquire semaphores signalled by SYNC_FD import */
};

static struct ahb_sc_surface g_ahb_surfaces[AHB_SC_MAX_SURFACES];
static int g_ahb_surface_n;
static pthread_mutex_t g_ahb_sc_lock = PTHREAD_MUTEX_INITIALIZER;
static VkInstance g_ahb_instance;

static PFN_vkGetInstanceProcAddr g_real_gipa;
static PFN_vkGetDeviceProcAddr g_real_gdpa;
static PFN_vkCreateSwapchainKHR g_real_CreateSwapchainKHR;
static PFN_vkDestroySwapchainKHR g_real_DestroySwapchainKHR;
static PFN_vkGetSwapchainImagesKHR g_real_GetSwapchainImagesKHR;
static PFN_vkAcquireNextImageKHR g_real_AcquireNextImageKHR;
static PFN_vkAcquireNextImage2KHR_ahb g_real_AcquireNextImage2KHR;
static PFN_vkQueuePresentKHR g_real_QueuePresentKHR;

static struct { VkDevice dev; VkPhysicalDevice phys; } g_ahb_devs[AHB_SC_MAX_DEVICES];
static int g_ahb_dev_n;

static VkResult amphora_vkCreateSwapchainKHR(VkDevice device, const VkSwapchainCreateInfoKHR *pCreateInfo,
                                             const VkAllocationCallbacks *pAllocator, VkSwapchainKHR *pSwapchain);
static void amphora_vkDestroySwapchainKHR(VkDevice device, VkSwapchainKHR swapchain,
                                          const VkAllocationCallbacks *pAllocator);
static VkResult amphora_vkGetSwapchainImagesKHR(VkDevice device, VkSwapchainKHR swapchain,
                                                uint32_t *pSwapchainImageCount, VkImage *pSwapchainImages);
static VkResult amphora_vkAcquireNextImageKHR(VkDevice device, VkSwapchainKHR swapchain, uint64_t timeout,
                                              VkSemaphore semaphore, VkFence fence, uint32_t *pImageIndex);
static VkResult amphora_vkQueuePresentKHR(VkQueue queue, const VkPresentInfoKHR *pPresentInfo);
static VkResult amphora_vkAcquireNextImage2KHR(VkDevice device, const VkAcquireNextImageInfoKHR_ahb *info,
                                               uint32_t *pImageIndex);

static void ahb_resolve_real_vulkan(void)
{
    void *lib;
    if (g_real_gipa && g_real_gdpa) return;
    lib = dlopen("libvulkan.so", RTLD_NOW);
    if (!lib) {
        LOGE("AHB_SC dlopen libvulkan failed: %s", dlerror());
        return;
    }
    if (!g_real_gipa)
        g_real_gipa = (PFN_vkGetInstanceProcAddr)dlsym(lib, "vkGetInstanceProcAddr");
    if (!g_real_gdpa)
        g_real_gdpa = (PFN_vkGetDeviceProcAddr)dlsym(lib, "vkGetDeviceProcAddr");
    LOGI("AHB_SC real gipa=%p gdpa=%p (no hooks)", (void *)g_real_gipa, (void *)g_real_gdpa);
}

static void ahb_sc_register_surface(VkSurfaceKHR surface, struct amphora_win *win)
{
    int i;
    if (!surface || !win) return;
    pthread_mutex_lock(&g_ahb_sc_lock);
    for (i = 0; i < g_ahb_surface_n; i++) {
        if (g_ahb_surfaces[i].surface == surface) {
            g_ahb_surfaces[i].win = win;
            pthread_mutex_unlock(&g_ahb_sc_lock);
            return;
        }
    }
    if (g_ahb_surface_n < AHB_SC_MAX_SURFACES) {
        g_ahb_surfaces[g_ahb_surface_n].surface = surface;
        g_ahb_surfaces[g_ahb_surface_n].win = win;
        g_ahb_surface_n++;
        LOGI("AHB_SC register surface=%p win=%p n=%d", (void *)(uintptr_t)surface, (void *)win, g_ahb_surface_n);
    } else {
        LOGW("AHB_SC surface table full");
    }
    pthread_mutex_unlock(&g_ahb_sc_lock);
}

static struct amphora_win *ahb_sc_win_for_surface(VkSurfaceKHR surface)
{
    int i;
    struct amphora_win *w = NULL;
    pthread_mutex_lock(&g_ahb_sc_lock);
    for (i = 0; i < g_ahb_surface_n; i++) {
        if (g_ahb_surfaces[i].surface == surface) { w = g_ahb_surfaces[i].win; break; }
    }
    pthread_mutex_unlock(&g_ahb_sc_lock);
    return w;
}

static void ahb_sc_stash_device(VkDevice dev, VkPhysicalDevice phys)
{
    int i;
    if (!dev) return;
    pthread_mutex_lock(&g_ahb_sc_lock);
    for (i = 0; i < g_ahb_dev_n; i++) {
        if (g_ahb_devs[i].dev == dev) { g_ahb_devs[i].phys = phys; pthread_mutex_unlock(&g_ahb_sc_lock); return; }
    }
    if (g_ahb_dev_n < AHB_SC_MAX_DEVICES) {
        g_ahb_devs[g_ahb_dev_n].dev = dev;
        g_ahb_devs[g_ahb_dev_n].phys = phys;
        g_ahb_dev_n++;
        LOGI("AHB_SC vkCreateDevice stash dev=%p phys=%p", (void *)dev, (void *)phys);
    }
    pthread_mutex_unlock(&g_ahb_sc_lock);
}

static VkPhysicalDevice ahb_sc_phys_for_device(VkDevice dev)
{
    int i;
    VkPhysicalDevice p = VK_NULL_HANDLE;
    pthread_mutex_lock(&g_ahb_sc_lock);
    for (i = 0; i < g_ahb_dev_n; i++) {
        if (g_ahb_devs[i].dev == dev) { p = g_ahb_devs[i].phys; break; }
    }
    pthread_mutex_unlock(&g_ahb_sc_lock);
    return p;
}

static struct ahb_sc *ahb_sc_from_handle(VkSwapchainKHR sc)
{
    struct ahb_sc *s = (struct ahb_sc *)(uintptr_t)sc;
    if (!s || s->magic != AHB_SC_MAGIC) return NULL;
    return s;
}

static uint32_t ahb_sc_find_mem_type(VkPhysicalDevice phys, uint32_t bits)
{
    PFN_vkGetPhysicalDeviceMemoryProperties getmp = NULL;
    VkPhysicalDeviceMemoryProperties mp;
    uint32_t i, fallback = 0;
    if (!phys) {
        for (i = 0; i < 32; i++) if (bits & (1u << i)) return i;
        return 0;
    }
    ahb_resolve_real_vulkan();
    if (g_real_gipa && g_ahb_instance)
        getmp = (PFN_vkGetPhysicalDeviceMemoryProperties)
            g_real_gipa(g_ahb_instance, "vkGetPhysicalDeviceMemoryProperties");
    if (!getmp) {
        LOGW("AHB_SC no GetPhysicalDeviceMemoryProperties — use lowest bit");
        for (i = 0; i < 32; i++) if (bits & (1u << i)) return i;
        return 0;
    }
    getmp(phys, &mp);
    for (i = 0; i < mp.memoryTypeCount; i++) {
        if ((bits & (1u << i)) == 0) continue;
        if (!fallback) fallback = i;
        if (mp.memoryTypes[i].propertyFlags & VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT)
            return i;
    }
    return fallback;
}

static void ahb_sc_ensure_device_procs(VkDevice device)
{
    ahb_resolve_real_vulkan();
    if (!device || !g_real_gdpa) return;
    if (!g_real_CreateSwapchainKHR)
        g_real_CreateSwapchainKHR = (PFN_vkCreateSwapchainKHR)g_real_gdpa(device, "vkCreateSwapchainKHR");
    if (!g_real_DestroySwapchainKHR)
        g_real_DestroySwapchainKHR = (PFN_vkDestroySwapchainKHR)g_real_gdpa(device, "vkDestroySwapchainKHR");
    if (!g_real_GetSwapchainImagesKHR)
        g_real_GetSwapchainImagesKHR = (PFN_vkGetSwapchainImagesKHR)g_real_gdpa(device, "vkGetSwapchainImagesKHR");
    if (!g_real_AcquireNextImageKHR)
        g_real_AcquireNextImageKHR = (PFN_vkAcquireNextImageKHR)g_real_gdpa(device, "vkAcquireNextImageKHR");
    if (!g_real_AcquireNextImage2KHR)
        g_real_AcquireNextImage2KHR = (PFN_vkAcquireNextImage2KHR_ahb)g_real_gdpa(device, "vkAcquireNextImage2KHR");
    if (!g_real_QueuePresentKHR)
        g_real_QueuePresentKHR = (PFN_vkQueuePresentKHR)g_real_gdpa(device, "vkQueuePresentKHR");
}

static void ahb_sc_ensure_instance_procs(VkInstance inst)
{
    ahb_resolve_real_vulkan();
    if (!g_real_gipa) return;
    if (!inst) return;
    if (!g_ahb_instance) g_ahb_instance = inst;
}

static VkResult ahb_sc_import_one(VkDevice device, VkPhysicalDevice phys,
                                  struct amphora_buf *buf, VkFormat format, VkExtent2D extent,
                                  VkImageUsageFlags usage,
                                  VkSharingMode sharing, uint32_t qcount, const uint32_t *qindices,
                                  VkImage *out_image, VkDeviceMemory *out_mem)
{
    PFN_vkCreateImage create_image;
    PFN_vkGetAndroidHardwareBufferPropertiesANDROID_ahb get_ahb_props;
    PFN_vkAllocateMemory alloc_mem;
    PFN_vkBindImageMemory bind_mem;
    PFN_vkDestroyImage destroy_image;
    PFN_vkFreeMemory free_mem;
    VkExternalMemoryImageCreateInfo_ahb ext;
    VkImageCreateInfo ici;
    VkAndroidHardwareBufferFormatPropertiesANDROID_ahb fmt;
    VkAndroidHardwareBufferPropertiesANDROID_ahb props;
    VkImportAndroidHardwareBufferInfoANDROID_ahb import_info;
    VkMemoryDedicatedAllocateInfo_ahb dedicated;
    VkMemoryAllocateInfo mai;
    VkImage image = VK_NULL_HANDLE;
    VkDeviceMemory memory = VK_NULL_HANDLE;
    VkFormat img_fmt = format;
    VkResult r;

    if (!buf || !buf->ahb) {
        LOGW("AHB_SC import: no ahb on buf id=%d", buf ? buf->buffer_id : -1);
        return VK_ERROR_INITIALIZATION_FAILED;
    }
    ahb_resolve_real_vulkan();
    if (!g_real_gdpa) return VK_ERROR_INITIALIZATION_FAILED;

    create_image = (PFN_vkCreateImage)g_real_gdpa(device, "vkCreateImage");
    get_ahb_props = (PFN_vkGetAndroidHardwareBufferPropertiesANDROID_ahb)
        g_real_gdpa(device, "vkGetAndroidHardwareBufferPropertiesANDROID");
    if (!get_ahb_props && g_real_gipa && g_ahb_instance)
        get_ahb_props = (PFN_vkGetAndroidHardwareBufferPropertiesANDROID_ahb)
            g_real_gipa(g_ahb_instance, "vkGetAndroidHardwareBufferPropertiesANDROID");
    if (!get_ahb_props && g_real_gipa)
        get_ahb_props = (PFN_vkGetAndroidHardwareBufferPropertiesANDROID_ahb)
            g_real_gipa(VK_NULL_HANDLE, "vkGetAndroidHardwareBufferPropertiesANDROID");
    alloc_mem = (PFN_vkAllocateMemory)g_real_gdpa(device, "vkAllocateMemory");
    bind_mem = (PFN_vkBindImageMemory)g_real_gdpa(device, "vkBindImageMemory");
    destroy_image = (PFN_vkDestroyImage)g_real_gdpa(device, "vkDestroyImage");
    free_mem = (PFN_vkFreeMemory)g_real_gdpa(device, "vkFreeMemory");
    if (!create_image || !get_ahb_props || !alloc_mem || !bind_mem) {
        LOGW("AHB_SC import: missing device procs create=%p props=%p alloc=%p bind=%p",
             (void *)create_image, (void *)get_ahb_props, (void *)alloc_mem, (void *)bind_mem);
        return VK_ERROR_EXTENSION_NOT_PRESENT;
    }

    memset(&fmt, 0, sizeof(fmt));
    fmt.sType = (VkStructureType)VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_FORMAT_PROPERTIES_ANDROID;
    memset(&props, 0, sizeof(props));
    props.sType = (VkStructureType)VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_PROPERTIES_ANDROID;
    props.pNext = &fmt;
    r = get_ahb_props(device, buf->ahb, &props);
    if (r != VK_SUCCESS) {
        LOGW("AHB_SC GetAHBProperties failed res=%d", (int)r);
        return r;
    }
    if (fmt.format != VK_FORMAT_UNDEFINED)
        img_fmt = fmt.format;
    LOGI("AHB_SC GetAHBProperties size=%llu typeBits=0x%x vkFormat=%d (scFormat=%d) id=%d",
         (unsigned long long)props.allocationSize, props.memoryTypeBits,
         (int)fmt.format, (int)format, buf->buffer_id);

    memset(&ext, 0, sizeof(ext));
    ext.sType = (VkStructureType)VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO;
    ext.handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID;

    memset(&ici, 0, sizeof(ici));
    ici.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    ici.pNext = &ext;
    ici.imageType = VK_IMAGE_TYPE_2D;
    ici.format = img_fmt;
    ici.extent.width = extent.width;
    ici.extent.height = extent.height;
    ici.extent.depth = 1;
    ici.mipLevels = 1;
    ici.arrayLayers = 1;
    ici.samples = VK_SAMPLE_COUNT_1_BIT;
    ici.tiling = VK_IMAGE_TILING_OPTIMAL;
    ici.usage = usage;
    ici.sharingMode = sharing;
    ici.queueFamilyIndexCount = qcount;
    ici.pQueueFamilyIndices = qindices;
    ici.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;

    r = create_image(device, &ici, NULL, &image);
    if (r != VK_SUCCESS) {
        LOGW("AHB_SC vkCreateImage failed res=%d fmt=%d", (int)r, (int)img_fmt);
        return r;
    }

    memset(&import_info, 0, sizeof(import_info));
    import_info.sType = (VkStructureType)VK_STRUCTURE_TYPE_IMPORT_ANDROID_HARDWARE_BUFFER_INFO_ANDROID;
    import_info.buffer = buf->ahb;

    memset(&dedicated, 0, sizeof(dedicated));
    dedicated.sType = (VkStructureType)VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO;
    dedicated.pNext = &import_info;
    dedicated.image = image;

    memset(&mai, 0, sizeof(mai));
    mai.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    mai.pNext = &dedicated;
    mai.allocationSize = props.allocationSize;
    mai.memoryTypeIndex = ahb_sc_find_mem_type(phys, props.memoryTypeBits);

    r = alloc_mem(device, &mai, NULL, &memory);
    if (r != VK_SUCCESS) {
        LOGW("AHB_SC vkAllocateMemory import failed res=%d size=%llu typeBits=0x%x typeIdx=%u",
             (int)r, (unsigned long long)props.allocationSize, props.memoryTypeBits, mai.memoryTypeIndex);
        if (destroy_image) destroy_image(device, image, NULL);
        return r;
    }
    r = bind_mem(device, image, memory, 0);
    if (r != VK_SUCCESS) {
        LOGW("AHB_SC vkBindImageMemory failed res=%d", (int)r);
        if (free_mem) free_mem(device, memory, NULL);
        if (destroy_image) destroy_image(device, image, NULL);
        return r;
    }
    *out_image = image;
    *out_mem = memory;
    LOGI("AHB_SC import ok id=%d ahb=%p image=%p mem=%p size=%llu fmt=%d",
         buf->buffer_id, buf->ahb, (void *)(uintptr_t)image, (void *)(uintptr_t)memory,
         (unsigned long long)props.allocationSize, (int)img_fmt);
    return VK_SUCCESS;
}

static void ahb_sc_destroy_obj(struct ahb_sc *sc)
{
    PFN_vkDestroyImage destroy_image;
    PFN_vkFreeMemory free_mem;
    uint32_t i;
    if (!sc) return;
    ahb_resolve_real_vulkan();
    destroy_image = g_real_gdpa ? (PFN_vkDestroyImage)g_real_gdpa(sc->device, "vkDestroyImage") : NULL;
    free_mem = g_real_gdpa ? (PFN_vkFreeMemory)g_real_gdpa(sc->device, "vkFreeMemory") : NULL;
    for (i = 0; i < sc->count; i++) {
        if (sc->images[i] && destroy_image)
            destroy_image(sc->device, sc->images[i], NULL);
        if (sc->memories[i] && free_mem)
            free_mem(sc->device, sc->memories[i], NULL);
        if (sc->bufs[i]) {
            /* Images the app still holds go back to the queue; queued ones are
             * already the queue's. */
            if (sc->win && sc->state[i] == AHB_SC_ACQUIRED)
                win_cancel(&sc->win->win, &sc->bufs[i]->buffer, -1);
            amphora_buf_release(sc->bufs[i]);
            sc->bufs[i] = NULL;
        }
        sc->images[i] = VK_NULL_HANDLE;
        sc->memories[i] = VK_NULL_HANDLE;
        sc->state[i] = AHB_SC_IN_QUEUE;
    }
    sc->magic = 0;
    free(sc);
}

static VkResult amphora_vkCreateSwapchainKHR(
    VkDevice device, const VkSwapchainCreateInfoKHR *pCreateInfo,
    const VkAllocationCallbacks *pAllocator, VkSwapchainKHR *pSwapchain)
{
    struct amphora_win *win;
    struct ahb_sc *sc = NULL;
    uint32_t want, i;
    VkResult r;
    int swap_interval;

    ahb_sc_ensure_device_procs(device);
    if (!pCreateInfo || !pSwapchain)
        return VK_ERROR_INITIALIZATION_FAILED;

    LOGI("AHB_SC CreateSwapchain enter device=%p surface=%p (source-path, no hooks)",
         (void *)device, (void *)(uintptr_t)pCreateInfo->surface);

    win = ahb_sc_win_for_surface(pCreateInfo->surface);
    if (!win) {
        LOGW("AHB_SC CreateSwapchain no registered surface=%p — ICD fallback createSC=%p",
             (void *)(uintptr_t)pCreateInfo->surface, (void *)g_real_CreateSwapchainKHR);
        if (!g_real_CreateSwapchainKHR)
            return VK_ERROR_INITIALIZATION_FAILED;
        return g_real_CreateSwapchainKHR(device, pCreateInfo, pAllocator, pSwapchain);
    }

    /* Same model as AOSP libvulkan swapchain.cpp: every BufferQueue slot is one
     * swapchain image. Create dequeues them all once to import, then cancels
     * them back; Acquire dequeues, Present queues. */
    if (win->sc_used) {
        /* The queue only lets a producer dequeue every slot before its first
         * queue; reconnecting orphans the previous swapchain's buffers so all
         * slots can be dequeued again. The host bumps its buffer generation. */
        int dret = win_perform(&win->win, NATIVE_WINDOW_API_DISCONNECT, NATIVE_WINDOW_API_EGL);
        int cret = win_perform(&win->win, NATIVE_WINDOW_API_CONNECT, NATIVE_WINDOW_API_EGL);
        LOGI("AHB_SC reconnect window disconnect=%d connect=%d", dret, cret);
    }
    win->sc_used = 1;

    /* FIFO blocks on the queue (vsync); MAILBOX/IMMEDIATE replace the pending
     * buffer. Set before querying MIN_UNDEQUEUED: async mode needs one more. */
    swap_interval = (pCreateInfo->presentMode == VK_PRESENT_MODE_FIFO_KHR ||
                     pCreateInfo->presentMode == VK_PRESENT_MODE_FIFO_RELAXED_KHR) ? 1 : 0;
    {
        int sret = win_set_swap(&win->win, swap_interval);
        if (sret != 0)
            LOGW("AHB_SC setSwapInterval(%d) ret=%d", swap_interval, sret);
    }

    /* Configure the queue as AOSP does (connect resets these): buffers exactly
     * the swapchain extent, since an AHB import needs the image and buffer to
     * match; scaled to the Surface when the view is a different size (resize in
     * flight); render-target usage so the gralloc layout suits the GPU. */
    {
        int native_format = ahb_sc_native_format(pCreateInfo->imageFormat);
        int uret = win_perform(&win->win, NATIVE_WINDOW_SET_USAGE, AHB_SC_BUFFER_USAGE);
        int fret = native_format
            ? win_perform(&win->win, NATIVE_WINDOW_SET_BUFFERS_FORMAT, native_format) : 0;
        int dret = win_perform(&win->win, NATIVE_WINDOW_SET_BUFFERS_DIMENSIONS,
                               (int)pCreateInfo->imageExtent.width,
                               (int)pCreateInfo->imageExtent.height);
        int sret = win_perform(&win->win, NATIVE_WINDOW_SET_SCALING_MODE,
                               NATIVE_WINDOW_SCALING_MODE_SCALE_TO_WINDOW);
        if (!native_format)
            LOGW("AHB_SC no buffer format for vkFormat=%d, keeping the queue default",
                 (int)pCreateInfo->imageFormat);
        LOGI("AHB_SC configure usage=0x%x ret=%d format=%d->0x%x ret=%d dims=%ux%u ret=%d "
             "scale-to-window ret=%d",
             AHB_SC_BUFFER_USAGE, uret, (int)pCreateInfo->imageFormat, native_format, fret,
             pCreateInfo->imageExtent.width, pCreateInfo->imageExtent.height, dret, sret);
    }

    int min_undequeued = 1;
    if (win_query(&win->win, NATIVE_WINDOW_MIN_UNDEQUEUED_BUFFERS, &min_undequeued) != 0 || min_undequeued < 1)
        min_undequeued = 1;

    /* Surface caps report minImageCount = MIN_UNDEQUEUED + 2 (AOSP); honour a
     * larger app request. With every slot imported, a dequeue can only return
     * one of our images. */
    want = pCreateInfo->minImageCount;
    if (want < (uint32_t)min_undequeued + 2u) want = (uint32_t)min_undequeued + 2u;
    if (want > AHB_SC_MAX_IMAGES) {
        LOGW("AHB_SC minImageCount=%u exceeds %d images — clamped",
             pCreateInfo->minImageCount, AHB_SC_MAX_IMAGES);
        want = AHB_SC_MAX_IMAGES;
    }

    {
        int set_ret = win_perform(&win->win, NATIVE_WINDOW_SET_BUFFER_COUNT, (int)want);
        LOGI("AHB_SC SET_BUFFER_COUNT images=%u (app min=%u min_undeq=%d present_mode=%d swap=%d) ret=%d",
             want, pCreateInfo->minImageCount, min_undequeued, (int)pCreateInfo->presentMode,
             swap_interval, set_ret);
    }

    sc = calloc(1, sizeof(*sc));
    if (!sc) return VK_ERROR_OUT_OF_HOST_MEMORY;
    sc->magic = AHB_SC_MAGIC;
    sc->device = device;
    sc->phys = ahb_sc_phys_for_device(device);
    sc->surface = pCreateInfo->surface;
    sc->win = win;
    sc->format = pCreateInfo->imageFormat;
    sc->extent = pCreateInfo->imageExtent;

    for (i = 0; i < want; i++) {
        struct ANativeWindowBuffer *anwb = NULL;
        struct amphora_buf *buf;
        /* Import-only dequeue: no fence out-param, the host waits on it. */
        if (win_dequeue(&win->win, &anwb, NULL) != 0 || !anwb) {
            LOGW("AHB_SC dequeue[%u] failed", i);
            r = VK_ERROR_INITIALIZATION_FAILED;
            goto fail;
        }
        buf = (struct amphora_buf *)anwb;
        if (!buf->ahb) {
            LOGW("AHB_SC dequeue[%u] id=%d has no AHB — cannot import", i, buf->buffer_id);
            win_cancel(&win->win, anwb, -1);
            r = VK_ERROR_INITIALIZATION_FAILED;
            goto fail;
        }
        r = ahb_sc_import_one(device, sc->phys, buf, sc->format, sc->extent,
                              pCreateInfo->imageUsage,
                              pCreateInfo->imageSharingMode,
                              pCreateInfo->queueFamilyIndexCount,
                              pCreateInfo->pQueueFamilyIndices,
                              &sc->images[i], &sc->memories[i]);
        if (r != VK_SUCCESS) {
            LOGW("AHB_SC import[%u] failed res=%d — fall back to ICD CreateSwapchain", i, (int)r);
            win_cancel(&win->win, anwb, -1);
            goto fallback;
        }
        buf_inc(&buf->buffer.common);
        sc->bufs[i] = buf;
        sc->state[i] = AHB_SC_ACQUIRED; /* dequeued; cancelled below */
        sc->count++;
    }

    /* Return every image to the queue; Acquire dequeues them one by one. */
    for (i = 0; i < sc->count; i++) {
        int cret = win_cancel(&win->win, &sc->bufs[i]->buffer, -1);
        sc->state[i] = AHB_SC_IN_QUEUE;
        LOGI("AHB_SC create cancel idx=%u id=%d ret=%d", i, sc->bufs[i]->buffer_id, cret);
    }

    *pSwapchain = (VkSwapchainKHR)(uintptr_t)sc;
    LOGI("AHB_SC create images=%u import=ok format=%d extent=%ux%u surface=%p sc=%p",
         sc->count, (int)sc->format, sc->extent.width, sc->extent.height,
         (void *)(uintptr_t)pCreateInfo->surface, (void *)sc);
    return VK_SUCCESS;

fallback:
    ahb_sc_destroy_obj(sc);
    sc = NULL;
    if (!g_real_CreateSwapchainKHR) {
        LOGW("AHB_SC fallback: no real CreateSwapchain");
        return VK_ERROR_INITIALIZATION_FAILED;
    }
    LOGW("AHB_SC create images fallback ICD path");
    return g_real_CreateSwapchainKHR(device, pCreateInfo, pAllocator, pSwapchain);

fail:
    ahb_sc_destroy_obj(sc);
    return r;
}

static void amphora_vkDestroySwapchainKHR(
    VkDevice device, VkSwapchainKHR swapchain, const VkAllocationCallbacks *pAllocator)
{
    struct ahb_sc *sc = ahb_sc_from_handle(swapchain);
    ahb_sc_ensure_device_procs(device);
    if (!sc) {
        if (g_real_DestroySwapchainKHR)
            g_real_DestroySwapchainKHR(device, swapchain, pAllocator);
        return;
    }
    LOGI("AHB_SC destroy sc=%p images=%u presents=%u fenced=%u acquires=%u release_fences=%u imported=%u",
         (void *)sc, sc->count, sc->presents, sc->fenced_presents, sc->acquires, sc->release_fences,
         sc->imported_sems);
    ahb_sc_destroy_obj(sc);
}

static VkResult amphora_vkGetSwapchainImagesKHR(
    VkDevice device, VkSwapchainKHR swapchain, uint32_t *pSwapchainImageCount, VkImage *pSwapchainImages)
{
    struct ahb_sc *sc = ahb_sc_from_handle(swapchain);
    uint32_t i;
    if (!sc) {
        ahb_sc_ensure_device_procs(device);
        if (!g_real_GetSwapchainImagesKHR) return VK_ERROR_INITIALIZATION_FAILED;
        return g_real_GetSwapchainImagesKHR(device, swapchain, pSwapchainImageCount, pSwapchainImages);
    }
    if (!pSwapchainImageCount) return VK_ERROR_INITIALIZATION_FAILED;
    if (!pSwapchainImages) {
        *pSwapchainImageCount = sc->count;
        return VK_SUCCESS;
    }
    if (*pSwapchainImageCount < sc->count) {
        uint32_t n = *pSwapchainImageCount;
        for (i = 0; i < n; i++) pSwapchainImages[i] = sc->images[i];
        *pSwapchainImageCount = sc->count;
        return VK_INCOMPLETE;
    }
    for (i = 0; i < sc->count; i++) pSwapchainImages[i] = sc->images[i];
    *pSwapchainImageCount = sc->count;
    return VK_SUCCESS;
}

/* Make the app's acquire semaphore / fence wait on the compositor's release
 * fence by importing it as a temporary SYNC_FD payload (fd -1 = already
 * released), as AOSP libvulkan / Mesa WSI do. No queue submit, so this is safe
 * on the IPC thread. Returns AHB_SC_ACQ_SEM / AHB_SC_ACQ_FENCE for what it
 * signalled; release_fd is not consumed. */
enum { AHB_SC_ACQ_SEM = 1, AHB_SC_ACQ_FENCE = 2 };

static uint32_t ahb_sc_import_release(VkDevice device, VkSemaphore semaphore, VkFence fence, int release_fd)
{
    static int warned_sem, warned_fence;
    PFN_vkImportSemaphoreFdKHR import_sem = NULL;
    PFN_vkImportFenceFdKHR import_fence = NULL;
    uint32_t handled = 0;
    VkResult r;

    ahb_resolve_real_vulkan();
    if (g_real_gdpa) {
        import_sem = (PFN_vkImportSemaphoreFdKHR)g_real_gdpa(device, "vkImportSemaphoreFdKHR");
        import_fence = (PFN_vkImportFenceFdKHR)g_real_gdpa(device, "vkImportFenceFdKHR");
    }
    if (semaphore) {
        VkImportSemaphoreFdInfoKHR info;
        memset(&info, 0, sizeof(info));
        info.sType = VK_STRUCTURE_TYPE_IMPORT_SEMAPHORE_FD_INFO_KHR;
        info.semaphore = semaphore;
        info.flags = VK_SEMAPHORE_IMPORT_TEMPORARY_BIT;
        info.handleType = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT;
        info.fd = release_fd >= 0 ? dup(release_fd) : -1;
        r = import_sem ? import_sem(device, &info) : VK_ERROR_EXTENSION_NOT_PRESENT;
        if (r == VK_SUCCESS) {
            handled |= AHB_SC_ACQ_SEM; /* the driver owns info.fd now */
        } else {
            if (info.fd >= 0) close(info.fd);
            if (!warned_sem++)
                LOGW("AHB_SC acquire: semaphore SYNC_FD import res=%d fn=%p — deferred signal",
                     (int)r, (void *)import_sem);
        }
    }
    if (fence) {
        VkImportFenceFdInfoKHR info;
        memset(&info, 0, sizeof(info));
        info.sType = VK_STRUCTURE_TYPE_IMPORT_FENCE_FD_INFO_KHR;
        info.fence = fence;
        info.flags = VK_FENCE_IMPORT_TEMPORARY_BIT;
        info.handleType = VK_EXTERNAL_FENCE_HANDLE_TYPE_SYNC_FD_BIT;
        info.fd = release_fd >= 0 ? dup(release_fd) : -1;
        r = import_fence ? import_fence(device, &info) : VK_ERROR_EXTENSION_NOT_PRESENT;
        if (r == VK_SUCCESS) {
            handled |= AHB_SC_ACQ_FENCE;
        } else {
            if (info.fd >= 0) close(info.fd);
            if (!warned_fence++)
                LOGW("AHB_SC acquire: fence SYNC_FD import res=%d fn=%p — deferred signal",
                     (int)r, (void *)import_fence);
        }
    }
    return handled;
}

/* import: the caller (wsi-sc op 8) signals nothing itself for what we report
 * handled. Anything left unhandled is signalled later by win32u's deferred
 * empty submit, which does not wait on the release, so wait for it here. */
static VkResult ahb_sc_acquire(
    VkDevice device, VkSwapchainKHR swapchain, uint64_t timeout,
    VkSemaphore semaphore, VkFence fence, uint32_t *pImageIndex, int import, uint32_t *out_handled)
{
    struct ahb_sc *sc = ahb_sc_from_handle(swapchain);
    struct ANativeWindowBuffer *anwb = NULL;
    uint32_t idx, handled = 0;
    int dret, dfence = -1;
    static unsigned acquire_n;
    if (out_handled) *out_handled = 0;
    if (!sc) {
        ahb_sc_ensure_device_procs(device);
        if (!g_real_AcquireNextImageKHR) return VK_ERROR_INITIALIZATION_FAILED;
        return g_real_AcquireNextImageKHR(device, swapchain, timeout, semaphore, fence, pImageIndex);
    }
    if (!pImageIndex) return VK_ERROR_INITIALIZATION_FAILED;

    /* Blocks in the host until SurfaceFlinger hands a buffer back. The app never
     * holds more than images - minImageCount + 1, so this only waits on the
     * compositor, never on our own Present. The release fence comes back too. */
    dret = win_dequeue(&sc->win->win, &anwb, &dfence);
    if (dret != 0 || !anwb) {
        LOGW("AHB_SC acquire dequeue ret=%d timeout=%llu", dret, (unsigned long long)timeout);
        if (dret == -ETIMEDOUT || dret == -EAGAIN || dret == -EBUSY)
            return timeout ? VK_TIMEOUT : VK_NOT_READY;
        return VK_ERROR_OUT_OF_DATE_KHR;
    }
    for (idx = 0; idx < sc->count; idx++)
        if (sc->bufs[idx] && &sc->bufs[idx]->buffer == anwb) break;
    if (idx == sc->count) {
        /* Host generation changed (surface resized/recreated): not ours. */
        LOGW("AHB_SC acquire dequeued unknown buffer id=%d — OUT_OF_DATE",
             ((struct amphora_buf *)anwb)->buffer_id);
        win_cancel(&sc->win->win, anwb, dfence);
        return VK_ERROR_OUT_OF_DATE_KHR;
    }
    if (sc->state[idx] == AHB_SC_ACQUIRED)
        LOGW("AHB_SC acquire idx=%u was already acquired", idx);
    sc->state[idx] = AHB_SC_ACQUIRED;
    *pImageIndex = idx;

    if (import)
        handled = ahb_sc_import_release(device, semaphore, fence, dfence);
    if (((semaphore && !(handled & AHB_SC_ACQ_SEM)) || (fence && !(handled & AHB_SC_ACQ_FENCE))) && dfence >= 0)
        amphora_poll_fence(dfence);
    sc->acquires++;
    if (dfence >= 0) sc->release_fences++;
    if (handled & AHB_SC_ACQ_SEM) sc->imported_sems++;
    if (amphora_log_sample(&acquire_n))
        LOGI("AHB_SC acquire idx=%u release_fd=%d sem=%p fence=%p handled=0x%x import=%d n=%u",
             idx, dfence, (void *)(uintptr_t)semaphore, (void *)(uintptr_t)fence, handled, import,
             acquire_n);
    if (dfence >= 0) close(dfence);
    if (out_handled) *out_handled = handled;
    return VK_SUCCESS;
}

static VkResult amphora_vkAcquireNextImageKHR(
    VkDevice device, VkSwapchainKHR swapchain, uint64_t timeout,
    VkSemaphore semaphore, VkFence fence, uint32_t *pImageIndex)
{
    return ahb_sc_acquire(device, swapchain, timeout, semaphore, fence, pImageIndex, 0, NULL);
}

static VkResult amphora_vkAcquireNextImage2KHR(VkDevice device, const VkAcquireNextImageInfoKHR_ahb *info,
                                               uint32_t *pImageIndex)
{
    if (!info) return VK_ERROR_INITIALIZATION_FAILED;
    if (ahb_sc_from_handle(info->swapchain))
        return amphora_vkAcquireNextImageKHR(device, info->swapchain, info->timeout,
                                             info->semaphore, info->fence, pImageIndex);
    ahb_sc_ensure_device_procs(device);
    if (!g_real_AcquireNextImage2KHR) {
        return amphora_vkAcquireNextImageKHR(device, info->swapchain, info->timeout,
                                             info->semaphore, info->fence, pImageIndex);
    }
    return g_real_AcquireNextImage2KHR(device, info, pImageIndex);
}

/* Present wait-sems are consumed in win32u on the DXVK queue thread. */
static VkResult ahb_sc_wait_present_semaphores(VkQueue queue, const VkPresentInfoKHR *info)
{
    (void)queue;
    (void)info;
    return VK_SUCCESS;
}

/* fence: sync fd that signals when the frame is rendered (-1 = already done,
 * because win32u QueueWaitIdle'd or the export reported it signalled). Always
 * consumed. */
static VkResult ahb_sc_queue_present(VkQueue queue, const VkPresentInfoKHR *pPresentInfo, int fence)
{
    uint32_t i;
    int any_ours = 0, any_theirs = 0;
    VkResult last = VK_SUCCESS;

    if (!pPresentInfo) {
        if (fence >= 0) close(fence);
        return VK_ERROR_INITIALIZATION_FAILED;
    }

    for (i = 0; i < pPresentInfo->swapchainCount; i++) {
        if (ahb_sc_from_handle(pPresentInfo->pSwapchains[i])) any_ours = 1;
        else any_theirs = 1;
    }

    if (any_ours)
        ahb_sc_wait_present_semaphores(queue, pPresentInfo);

    if (any_theirs && !any_ours) {
        /* win32u dropped the wait semaphores; the ICD swapchain only sees the fence. */
        amphora_wait_present_fence(fence);
        ahb_resolve_real_vulkan();
        if (!g_real_QueuePresentKHR && g_real_gdpa && g_ahb_dev_n > 0)
            g_real_QueuePresentKHR = (PFN_vkQueuePresentKHR)
                g_real_gdpa(g_ahb_devs[0].dev, "vkQueuePresentKHR");
        if (g_real_QueuePresentKHR)
            return g_real_QueuePresentKHR(queue, pPresentInfo);
        return VK_ERROR_INITIALIZATION_FAILED;
    }

    for (i = 0; i < pPresentInfo->swapchainCount; i++) {
        struct ahb_sc *sc = ahb_sc_from_handle(pPresentInfo->pSwapchains[i]);
        uint32_t idx = pPresentInfo->pImageIndices[i];
        int qret;
        if (!sc) {
            LOGW("AHB_SC QueuePresent mixed non-ours sc=%p — skipped",
                 (void *)(uintptr_t)pPresentInfo->pSwapchains[i]);
            if (pPresentInfo->pResults) pPresentInfo->pResults[i] = VK_ERROR_SURFACE_LOST_KHR;
            last = VK_ERROR_SURFACE_LOST_KHR;
            continue;
        }
        if (idx >= sc->count || !sc->bufs[idx]) {
            LOGW("AHB_SC QueuePresent bad idx=%u count=%u", idx, sc->count);
            if (pPresentInfo->pResults) pPresentInfo->pResults[i] = VK_ERROR_OUT_OF_DATE_KHR;
            last = VK_ERROR_OUT_OF_DATE_KHR;
            continue;
        }
        if (sc->state[idx] != AHB_SC_ACQUIRED) {
            LOGW("AHB_SC QueuePresent idx=%u not acquired", idx);
            if (pPresentInfo->pResults) pPresentInfo->pResults[i] = VK_ERROR_OUT_OF_DATE_KHR;
            last = VK_ERROR_OUT_OF_DATE_KHR;
            continue;
        }
        /* win_queue takes its own copy of the fence to the host (queueBuffer
         * waits on it) and runs the q50/q100 readback sampler. */
        qret = win_queue(&sc->win->win, &sc->bufs[idx]->buffer, fence >= 0 ? dup(fence) : -1);
        sc->state[idx] = AHB_SC_IN_QUEUE;
        if (qret != 0) {
            LOGW("AHB_SC QueuePresent queue idx=%u ret=%d", idx, qret);
            if (pPresentInfo->pResults) pPresentInfo->pResults[i] = VK_ERROR_OUT_OF_DATE_KHR;
            last = VK_ERROR_OUT_OF_DATE_KHR;
        } else {
            sc->presents++;
            if (fence >= 0) sc->fenced_presents++;
            if (pPresentInfo->pResults) pPresentInfo->pResults[i] = VK_SUCCESS;
        }
    }
    if (fence >= 0) close(fence);
    return last;
}

static VkResult amphora_vkQueuePresentKHR(VkQueue queue, const VkPresentInfoKHR *pPresentInfo)
{
    return ahb_sc_queue_present(queue, pPresentInfo, -1);
}

/* Exported so wineandroid / same-SO callers can invoke without a layer. */
void amphora_ahb_sc_stash_device(uint64_t device, uint64_t phys)
{
    ahb_sc_stash_device((VkDevice)(uintptr_t)device, (VkPhysicalDevice)(uintptr_t)phys);
}

VkResult amphora_ahb_sc_create_swapchain(VkDevice device, const VkSwapchainCreateInfoKHR *info,
                                         const VkAllocationCallbacks *alloc, VkSwapchainKHR *out)
{
    return amphora_vkCreateSwapchainKHR(device, info, alloc, out);
}
void amphora_ahb_sc_destroy_swapchain(VkDevice device, VkSwapchainKHR sc, const VkAllocationCallbacks *alloc)
{
    amphora_vkDestroySwapchainKHR(device, sc, alloc);
}
VkResult amphora_ahb_sc_get_images(VkDevice device, VkSwapchainKHR sc, uint32_t *count, VkImage *images)
{
    return amphora_vkGetSwapchainImagesKHR(device, sc, count, images);
}
VkResult amphora_ahb_sc_acquire(VkDevice device, VkSwapchainKHR sc, uint64_t timeout,
                                VkSemaphore sem, VkFence fence, uint32_t *idx)
{
    return amphora_vkAcquireNextImageKHR(device, sc, timeout, sem, fence, idx);
}
VkResult amphora_ahb_sc_acquire_import(VkDevice device, VkSwapchainKHR sc, uint64_t timeout,
                                       VkSemaphore sem, VkFence fence, uint32_t *idx, uint32_t *handled)
{
    return ahb_sc_acquire(device, sc, timeout, sem, fence, idx, 1, handled);
}
VkResult amphora_ahb_sc_present(VkQueue queue, const VkPresentInfoKHR *info)
{
    return amphora_vkQueuePresentKHR(queue, info);
}
VkResult amphora_ahb_sc_present_fence(VkQueue queue, const VkPresentInfoKHR *info, int fence)
{
    return ahb_sc_queue_present(queue, info, fence);
}
void amphora_ahb_sc_register(uint64_t surface, void *win)
{
    ahb_sc_register_surface((VkSurfaceKHR)(uintptr_t)surface, (struct amphora_win *)win);
}
