/* VK_LAYER_AMPHORA_wsi: implicit Vulkan layer giving the imagefs Khronos loader
 * (a Linux build: XCB/Xlib WSI only) VK_KHR_android_surface, so the wineandroid
 * guest runs the ICD chosen in settings (wrapper / Turnip) instead of the platform
 * loader. The loader dlopens the same libamphora_wsi.so Amphora LD_PRELOADs into
 * box64, so the layer shares the helper's proxy windows and AHB swapchain.
 *
 * The loader has no dispatch slot for vkCreateAndroidSurfaceKHR, so surfaces are
 * not created through it: amphora_wsi_create_android_surface (wsi socket) makes
 * one for a layer instance, with handle == the proxy struct amphora_win. The
 * swapchain on it is the AHB-import one (amphora_ahb_sc.inc, wsi-sc socket). The
 * layer answers the surface queries for those handles the way AOSP libvulkan
 * swapchain.cpp does, and passes everything else down unchanged. Manifest:
 * filesDir/wineandroid/vklayer (GuestProgramLauncherComponent).
 */
#include <vulkan/vk_layer.h>

#define VKL_LAYER_NAME "VK_LAYER_AMPHORA_wsi"
#define VKL_MAX_INSTANCES 16
#define VKL_MAX_DEVICES 16

/* ANativeWindow queries beyond the ones amphora_wsi.c names. */
#define VKL_NATIVE_WINDOW_DEFAULT_WIDTH 6
#define VKL_NATIVE_WINDOW_DEFAULT_HEIGHT 7
#define VKL_NATIVE_WINDOW_MAX_BUFFER_COUNT 21

struct vkl_instance {
    void *key; /* dispatch key, shared by the instance and its physical devices */
    VkInstance instance;
    PFN_vkGetInstanceProcAddr gipa; /* next in chain */
    PFN_vkDestroyInstance DestroyInstance;
    PFN_vkDestroySurfaceKHR DestroySurfaceKHR;
    PFN_vkGetPhysicalDeviceSurfaceSupportKHR GetPhysicalDeviceSurfaceSupportKHR;
    PFN_vkGetPhysicalDeviceSurfaceCapabilitiesKHR GetPhysicalDeviceSurfaceCapabilitiesKHR;
    PFN_vkGetPhysicalDeviceSurfaceCapabilities2KHR GetPhysicalDeviceSurfaceCapabilities2KHR;
    PFN_vkGetPhysicalDeviceSurfaceFormatsKHR GetPhysicalDeviceSurfaceFormatsKHR;
    PFN_vkGetPhysicalDeviceSurfaceFormats2KHR GetPhysicalDeviceSurfaceFormats2KHR;
    PFN_vkGetPhysicalDeviceSurfacePresentModesKHR GetPhysicalDeviceSurfacePresentModesKHR;
    PFN_vkGetPhysicalDevicePresentRectanglesKHR GetPhysicalDevicePresentRectanglesKHR;
};

struct vkl_device {
    void *key;
    PFN_vkGetDeviceProcAddr gdpa; /* next in chain */
    PFN_vkDestroyDevice DestroyDevice;
    PFN_vkGetDeviceGroupSurfacePresentModesKHR GetDeviceGroupSurfacePresentModesKHR;
};

static struct vkl_instance g_vkl_instances[VKL_MAX_INSTANCES];
static struct vkl_device g_vkl_devices[VKL_MAX_DEVICES];
static pthread_mutex_t g_vkl_lock = PTHREAD_MUTEX_INITIALIZER;

static void *vkl_key(const void *dispatchable)
{
    return *(void *const *)dispatchable;
}

/* Records are only removed by vkDestroyInstance / vkDestroyDevice, which the app
 * must not race with other calls on the same object. */
static struct vkl_instance *vkl_instance_get(const void *dispatchable)
{
    struct vkl_instance *found = NULL;
    void *key;
    int i;
    if (!dispatchable) return NULL;
    key = vkl_key(dispatchable);
    pthread_mutex_lock(&g_vkl_lock);
    for (i = 0; i < VKL_MAX_INSTANCES; i++) {
        if (g_vkl_instances[i].key == key) { found = &g_vkl_instances[i]; break; }
    }
    pthread_mutex_unlock(&g_vkl_lock);
    return found;
}

static struct vkl_device *vkl_device_get(const void *dispatchable)
{
    struct vkl_device *found = NULL;
    void *key;
    int i;
    if (!dispatchable) return NULL;
    key = vkl_key(dispatchable);
    pthread_mutex_lock(&g_vkl_lock);
    for (i = 0; i < VKL_MAX_DEVICES; i++) {
        if (g_vkl_devices[i].key == key) { found = &g_vkl_devices[i]; break; }
    }
    pthread_mutex_unlock(&g_vkl_lock);
    return found;
}

static void vkl_instance_add(VkInstance instance, PFN_vkGetInstanceProcAddr gipa)
{
    struct vkl_instance rec;
    int i;
    memset(&rec, 0, sizeof(rec));
    rec.key = vkl_key(instance);
    rec.instance = instance;
    rec.gipa = gipa;
#define VKL_NEXT(name) rec.name = (PFN_vk##name)gipa(instance, "vk" #name)
    VKL_NEXT(DestroyInstance);
    VKL_NEXT(DestroySurfaceKHR);
    VKL_NEXT(GetPhysicalDeviceSurfaceSupportKHR);
    VKL_NEXT(GetPhysicalDeviceSurfaceCapabilitiesKHR);
    VKL_NEXT(GetPhysicalDeviceSurfaceCapabilities2KHR);
    VKL_NEXT(GetPhysicalDeviceSurfaceFormatsKHR);
    VKL_NEXT(GetPhysicalDeviceSurfaceFormats2KHR);
    VKL_NEXT(GetPhysicalDeviceSurfacePresentModesKHR);
    VKL_NEXT(GetPhysicalDevicePresentRectanglesKHR);
#undef VKL_NEXT
    pthread_mutex_lock(&g_vkl_lock);
    for (i = 0; i < VKL_MAX_INSTANCES; i++) {
        if (!g_vkl_instances[i].key) { g_vkl_instances[i] = rec; break; }
    }
    pthread_mutex_unlock(&g_vkl_lock);
    if (i == VKL_MAX_INSTANCES)
        LOGE("%s: instance table full, inst=%p has no Android surfaces", VKL_LAYER_NAME, (void *)instance);
    else
        LOGI("%s: instance %p", VKL_LAYER_NAME, (void *)instance);
}

static void vkl_device_add(VkDevice device, PFN_vkGetDeviceProcAddr gdpa)
{
    struct vkl_device rec;
    int i;
    memset(&rec, 0, sizeof(rec));
    rec.key = vkl_key(device);
    rec.gdpa = gdpa;
    rec.DestroyDevice = (PFN_vkDestroyDevice)gdpa(device, "vkDestroyDevice");
    rec.GetDeviceGroupSurfacePresentModesKHR = (PFN_vkGetDeviceGroupSurfacePresentModesKHR)
        gdpa(device, "vkGetDeviceGroupSurfacePresentModesKHR");
    pthread_mutex_lock(&g_vkl_lock);
    for (i = 0; i < VKL_MAX_DEVICES; i++) {
        if (!g_vkl_devices[i].key) { g_vkl_devices[i] = rec; break; }
    }
    pthread_mutex_unlock(&g_vkl_lock);
    if (i == VKL_MAX_DEVICES)
        LOGE("%s: device table full, dev=%p", VKL_LAYER_NAME, (void *)device);
}

/* ---- surface answers (AOSP libvulkan swapchain.cpp model) ---- */

static struct amphora_win *vkl_surface_win(VkSurfaceKHR surface)
{
    int layer_owned;
    struct amphora_win *win = ahb_sc_win_for_surface(surface, &layer_owned);
    return layer_owned ? win : NULL;
}

/* VK_ERROR_SURFACE_LOST_KHR when the host window is gone (AOSP does the same when
 * a window query fails). */
static VkResult vkl_surface_caps(struct amphora_win *win, VkSurfaceCapabilitiesKHR *caps)
{
    int w = 0, h = 0, min_undequeued = 0, max_buffers = 0;
    uint32_t min_images;

    if (win_query(&win->win, VKL_NATIVE_WINDOW_DEFAULT_WIDTH, &w) != 0 ||
        win_query(&win->win, VKL_NATIVE_WINDOW_DEFAULT_HEIGHT, &h) != 0 ||
        win_query(&win->win, NATIVE_WINDOW_MIN_UNDEQUEUED_BUFFERS, &min_undequeued) != 0 ||
        win_query(&win->win, VKL_NATIVE_WINDOW_MAX_BUFFER_COUNT, &max_buffers) != 0)
        return VK_ERROR_SURFACE_LOST_KHR;
    if (w < 1) w = 1;
    if (h < 1) h = 1;
    if (min_undequeued < 1) min_undequeued = 1;
    /* The AHB swapchain imports at most AHB_SC_MAX_IMAGES buffers. */
    if (max_buffers < 1 || max_buffers > AHB_SC_MAX_IMAGES) max_buffers = AHB_SC_MAX_IMAGES;
    min_images = (uint32_t)min_undequeued + 2u;
    if (min_images > (uint32_t)max_buffers) min_images = (uint32_t)max_buffers;

    memset(caps, 0, sizeof(*caps));
    caps->minImageCount = min_images;
    caps->maxImageCount = (uint32_t)max_buffers;
    caps->currentExtent.width = (uint32_t)w;
    caps->currentExtent.height = (uint32_t)h;
    caps->minImageExtent.width = 1;
    caps->minImageExtent.height = 1;
    caps->maxImageExtent.width = w > 4096 ? (uint32_t)w : 4096u;
    caps->maxImageExtent.height = h > 4096 ? (uint32_t)h : 4096u;
    caps->maxImageArrayLayers = 1;
    /* win_query forces TRANSFORM_HINT to identity; the host scales the buffer. */
    caps->supportedTransforms = VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR;
    caps->currentTransform = VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR;
    caps->supportedCompositeAlpha = VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR | VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR;
    caps->supportedUsageFlags = VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT |
                                VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_STORAGE_BIT |
                                VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT |
                                VK_IMAGE_USAGE_INPUT_ATTACHMENT_BIT;
    return VK_SUCCESS;
}

/* AOSP: RGBA8 always, then 565 / FP16 / 1010102 when gralloc can allocate them
 * for GPU render + sample + composition. */
#define VKL_MAX_FORMATS 5
static uint32_t vkl_surface_formats(VkSurfaceFormatKHR *out)
{
    static const struct { VkFormat vk; uint32_t ahb; } optional[] = {
        { VK_FORMAT_R5G6B5_UNORM_PACK16, 0x4 },       /* AHARDWAREBUFFER_FORMAT_R5G6B5_UNORM */
        { VK_FORMAT_R16G16B16A16_SFLOAT, 0x16 },      /* R16G16B16A16_FLOAT */
        { VK_FORMAT_A2B10G10R10_UNORM_PACK32, 0x2b }, /* R10G10B10A2_UNORM */
    };
    static int supported[3] = { -1, -1, -1 };
    uint32_t n = 0, i;

    out[n].format = VK_FORMAT_R8G8B8A8_UNORM;
    out[n++].colorSpace = VK_COLOR_SPACE_SRGB_NONLINEAR_KHR;
    out[n].format = VK_FORMAT_R8G8B8A8_SRGB;
    out[n++].colorSpace = VK_COLOR_SPACE_SRGB_NONLINEAR_KHR;
    amphora_resolve_ahb();
    for (i = 0; i < 3; i++) {
        if (supported[i] < 0) {
            amphora_ahb_desc desc;
            memset(&desc, 0, sizeof(desc));
            desc.width = 1;
            desc.height = 1;
            desc.layers = 1;
            desc.format = optional[i].ahb;
            desc.usage = AHB_SC_BUFFER_USAGE;
            supported[i] = g_ahb_is_supported ? g_ahb_is_supported(&desc) != 0 : 0;
        }
        if (supported[i]) {
            out[n].format = optional[i].vk;
            out[n++].colorSpace = VK_COLOR_SPACE_SRGB_NONLINEAR_KHR;
        }
    }
    return n;
}

/* AOSP lists FIFO and MAILBOX; the AHB swapchain maps any non-FIFO mode to swap
 * interval 0 (the queue replaces the pending buffer), which is MAILBOX. */
static const VkPresentModeKHR g_vkl_present_modes[] = {
    VK_PRESENT_MODE_FIFO_KHR,
    VK_PRESENT_MODE_MAILBOX_KHR,
};

static VkResult vkl_fill(uint32_t *count, void *out, const void *src, uint32_t n, size_t elem)
{
    uint32_t c;
    if (!out) {
        *count = n;
        return VK_SUCCESS;
    }
    c = *count < n ? *count : n;
    memcpy(out, src, (size_t)c * elem);
    *count = c;
    return c < n ? VK_INCOMPLETE : VK_SUCCESS;
}


/* ---- instance-level entry points ---- */

static VKAPI_ATTR void VKAPI_CALL vkl_DestroySurfaceKHR(VkInstance instance, VkSurfaceKHR surface,
                                                        const VkAllocationCallbacks *pAllocator)
{
    struct amphora_win *win = ahb_sc_unregister_layer_surface(surface);
    struct vkl_instance *inst;
    if (win) {
        int dret = win_perform(&win->win, NATIVE_WINDOW_API_DISCONNECT, NATIVE_WINDOW_API_EGL);
        LOGI("%s: destroy surface=%p disconnect=%d", VKL_LAYER_NAME, (void *)(uintptr_t)surface, dret);
        win->win.common.decRef(&win->win.common);
        return;
    }
    inst = vkl_instance_get(instance);
    if (inst && inst->DestroySurfaceKHR) inst->DestroySurfaceKHR(instance, surface, pAllocator);
}

static VKAPI_ATTR VkResult VKAPI_CALL vkl_GetPhysicalDeviceSurfaceSupportKHR(
    VkPhysicalDevice physicalDevice, uint32_t queueFamilyIndex, VkSurfaceKHR surface, VkBool32 *pSupported)
{
    struct vkl_instance *inst;
    if (vkl_surface_win(surface)) {
        *pSupported = VK_TRUE;
        return VK_SUCCESS;
    }
    inst = vkl_instance_get(physicalDevice);
    if (!inst || !inst->GetPhysicalDeviceSurfaceSupportKHR) return VK_ERROR_SURFACE_LOST_KHR;
    return inst->GetPhysicalDeviceSurfaceSupportKHR(physicalDevice, queueFamilyIndex, surface, pSupported);
}

static VKAPI_ATTR VkResult VKAPI_CALL vkl_GetPhysicalDeviceSurfaceCapabilitiesKHR(
    VkPhysicalDevice physicalDevice, VkSurfaceKHR surface, VkSurfaceCapabilitiesKHR *pCaps)
{
    struct amphora_win *win = vkl_surface_win(surface);
    struct vkl_instance *inst;
    if (win) return vkl_surface_caps(win, pCaps);
    inst = vkl_instance_get(physicalDevice);
    if (!inst || !inst->GetPhysicalDeviceSurfaceCapabilitiesKHR) return VK_ERROR_SURFACE_LOST_KHR;
    return inst->GetPhysicalDeviceSurfaceCapabilitiesKHR(physicalDevice, surface, pCaps);
}

static VKAPI_ATTR VkResult VKAPI_CALL vkl_GetPhysicalDeviceSurfaceCapabilities2KHR(
    VkPhysicalDevice physicalDevice, const VkPhysicalDeviceSurfaceInfo2KHR *pInfo,
    VkSurfaceCapabilities2KHR *pCaps)
{
    struct amphora_win *win = pInfo ? vkl_surface_win(pInfo->surface) : NULL;
    struct vkl_instance *inst;
    const VkBaseInStructure *in;
    VkBaseOutStructure *out;
    VkPresentModeKHR mode = VK_PRESENT_MODE_FIFO_KHR;
    VkResult r;

    if (!win) {
        inst = vkl_instance_get(physicalDevice);
        if (!inst || !inst->GetPhysicalDeviceSurfaceCapabilities2KHR) return VK_ERROR_SURFACE_LOST_KHR;
        return inst->GetPhysicalDeviceSurfaceCapabilities2KHR(physicalDevice, pInfo, pCaps);
    }
    if ((r = vkl_surface_caps(win, &pCaps->surfaceCapabilities)) != VK_SUCCESS) return r;
    for (in = (const VkBaseInStructure *)pInfo->pNext; in; in = in->pNext) {
        if (in->sType == VK_STRUCTURE_TYPE_SURFACE_PRESENT_MODE_EXT)
            mode = ((const VkSurfacePresentModeEXT *)in)->presentMode;
    }
    for (out = (VkBaseOutStructure *)pCaps->pNext; out; out = out->pNext) {
        switch ((int)out->sType) {
        case VK_STRUCTURE_TYPE_SURFACE_PRESENT_MODE_COMPATIBILITY_EXT: {
            /* No present-mode switching on a live swapchain: only the mode itself. */
            VkSurfacePresentModeCompatibilityEXT *compat = (VkSurfacePresentModeCompatibilityEXT *)out;
            if (!compat->pPresentModes) {
                compat->presentModeCount = 1;
            } else if (compat->presentModeCount) {
                compat->pPresentModes[0] = mode;
                compat->presentModeCount = 1;
            }
            break;
        }
        case VK_STRUCTURE_TYPE_SURFACE_PRESENT_SCALING_CAPABILITIES_EXT: {
            VkSurfacePresentScalingCapabilitiesEXT *scaling = (VkSurfacePresentScalingCapabilitiesEXT *)out;
            scaling->supportedPresentScaling = 0;
            scaling->supportedPresentGravityX = 0;
            scaling->supportedPresentGravityY = 0;
            scaling->minScaledImageExtent = pCaps->surfaceCapabilities.minImageExtent;
            scaling->maxScaledImageExtent = pCaps->surfaceCapabilities.maxImageExtent;
            break;
        }
        case VK_STRUCTURE_TYPE_SURFACE_PROTECTED_CAPABILITIES_KHR:
            ((VkSurfaceProtectedCapabilitiesKHR *)out)->supportsProtected = VK_FALSE;
            break;
        case VK_STRUCTURE_TYPE_SHARED_PRESENT_SURFACE_CAPABILITIES_KHR:
            ((VkSharedPresentSurfaceCapabilitiesKHR *)out)->sharedPresentSupportedUsageFlags = 0;
            break;
        default:
            break;
        }
    }
    return VK_SUCCESS;
}

static VKAPI_ATTR VkResult VKAPI_CALL vkl_GetPhysicalDeviceSurfaceFormatsKHR(
    VkPhysicalDevice physicalDevice, VkSurfaceKHR surface, uint32_t *pCount, VkSurfaceFormatKHR *pFormats)
{
    VkSurfaceFormatKHR formats[VKL_MAX_FORMATS];
    struct vkl_instance *inst;
    if (vkl_surface_win(surface))
        return vkl_fill(pCount, pFormats, formats, vkl_surface_formats(formats), sizeof(formats[0]));
    inst = vkl_instance_get(physicalDevice);
    if (!inst || !inst->GetPhysicalDeviceSurfaceFormatsKHR) return VK_ERROR_SURFACE_LOST_KHR;
    return inst->GetPhysicalDeviceSurfaceFormatsKHR(physicalDevice, surface, pCount, pFormats);
}

static VKAPI_ATTR VkResult VKAPI_CALL vkl_GetPhysicalDeviceSurfaceFormats2KHR(
    VkPhysicalDevice physicalDevice, const VkPhysicalDeviceSurfaceInfo2KHR *pInfo, uint32_t *pCount,
    VkSurfaceFormat2KHR *pFormats)
{
    VkSurfaceFormatKHR formats[VKL_MAX_FORMATS];
    struct vkl_instance *inst;
    uint32_t n, c, i;

    if (!pInfo || !vkl_surface_win(pInfo->surface)) {
        inst = vkl_instance_get(physicalDevice);
        if (!inst || !inst->GetPhysicalDeviceSurfaceFormats2KHR) return VK_ERROR_SURFACE_LOST_KHR;
        return inst->GetPhysicalDeviceSurfaceFormats2KHR(physicalDevice, pInfo, pCount, pFormats);
    }
    n = vkl_surface_formats(formats);
    if (!pFormats) {
        *pCount = n;
        return VK_SUCCESS;
    }
    c = *pCount < n ? *pCount : n;
    for (i = 0; i < c; i++) {
        VkBaseOutStructure *out;
        pFormats[i].surfaceFormat = formats[i];
        for (out = (VkBaseOutStructure *)pFormats[i].pNext; out; out = out->pNext) {
            if ((int)out->sType == VK_STRUCTURE_TYPE_IMAGE_COMPRESSION_PROPERTIES_EXT) {
                VkImageCompressionPropertiesEXT *comp = (VkImageCompressionPropertiesEXT *)out;
                comp->imageCompressionFlags = VK_IMAGE_COMPRESSION_DEFAULT_EXT;
                comp->imageCompressionFixedRateFlags = 0;
            }
        }
    }
    *pCount = c;
    return c < n ? VK_INCOMPLETE : VK_SUCCESS;
}

static VKAPI_ATTR VkResult VKAPI_CALL vkl_GetPhysicalDeviceSurfacePresentModesKHR(
    VkPhysicalDevice physicalDevice, VkSurfaceKHR surface, uint32_t *pCount, VkPresentModeKHR *pModes)
{
    struct vkl_instance *inst;
    if (vkl_surface_win(surface))
        return vkl_fill(pCount, pModes, g_vkl_present_modes,
                        (uint32_t)(sizeof(g_vkl_present_modes) / sizeof(g_vkl_present_modes[0])),
                        sizeof(g_vkl_present_modes[0]));
    inst = vkl_instance_get(physicalDevice);
    if (!inst || !inst->GetPhysicalDeviceSurfacePresentModesKHR) return VK_ERROR_SURFACE_LOST_KHR;
    return inst->GetPhysicalDeviceSurfacePresentModesKHR(physicalDevice, surface, pCount, pModes);
}

static VKAPI_ATTR VkResult VKAPI_CALL vkl_GetPhysicalDevicePresentRectanglesKHR(
    VkPhysicalDevice physicalDevice, VkSurfaceKHR surface, uint32_t *pCount, VkRect2D *pRects)
{
    struct amphora_win *win = vkl_surface_win(surface);
    struct vkl_instance *inst;
    if (win) {
        VkSurfaceCapabilitiesKHR caps;
        VkRect2D rect;
        VkResult r = vkl_surface_caps(win, &caps);
        if (r != VK_SUCCESS) return r;
        rect.offset.x = 0;
        rect.offset.y = 0;
        rect.extent = caps.currentExtent;
        return vkl_fill(pCount, pRects, &rect, 1, sizeof(rect));
    }
    inst = vkl_instance_get(physicalDevice);
    if (!inst || !inst->GetPhysicalDevicePresentRectanglesKHR) return VK_ERROR_SURFACE_LOST_KHR;
    return inst->GetPhysicalDevicePresentRectanglesKHR(physicalDevice, surface, pCount, pRects);
}


static VKAPI_ATTR VkResult VKAPI_CALL vkl_CreateInstance(const VkInstanceCreateInfo *pCreateInfo,
                                                         const VkAllocationCallbacks *pAllocator,
                                                         VkInstance *pInstance)
{
    VkLayerInstanceCreateInfo *link = (VkLayerInstanceCreateInfo *)pCreateInfo->pNext;
    PFN_vkGetInstanceProcAddr next_gipa;
    PFN_vkCreateInstance next_create;
    VkInstanceCreateInfo info = *pCreateInfo;
    const char **names = NULL;
    uint32_t i, n = 0;
    VkResult r;

    while (link && !(link->sType == VK_STRUCTURE_TYPE_LOADER_INSTANCE_CREATE_INFO &&
                     link->function == VK_LAYER_LINK_INFO))
        link = (VkLayerInstanceCreateInfo *)link->pNext;
    if (!link || !link->u.pLayerInfo) {
        LOGE("%s: vkCreateInstance without loader link info", VKL_LAYER_NAME);
        return VK_ERROR_INITIALIZATION_FAILED;
    }
    next_gipa = link->u.pLayerInfo->pfnNextGetInstanceProcAddr;
    link->u.pLayerInfo = link->u.pLayerInfo->pNext;
    next_create = (PFN_vkCreateInstance)next_gipa(VK_NULL_HANDLE, "vkCreateInstance");
    if (!next_create) return VK_ERROR_INITIALIZATION_FAILED;

    /* VK_KHR_android_surface is implemented here; the ICD below does not have it. */
    if (pCreateInfo->enabledExtensionCount) {
        names = malloc(sizeof(*names) * pCreateInfo->enabledExtensionCount);
        if (!names) return VK_ERROR_OUT_OF_HOST_MEMORY;
        for (i = 0; i < pCreateInfo->enabledExtensionCount; i++) {
            if (strcmp(pCreateInfo->ppEnabledExtensionNames[i], "VK_KHR_android_surface"))
                names[n++] = pCreateInfo->ppEnabledExtensionNames[i];
        }
        info.enabledExtensionCount = n;
        info.ppEnabledExtensionNames = names;
    }
    r = next_create(&info, pAllocator, pInstance);
    free(names);
    if (r == VK_SUCCESS) vkl_instance_add(*pInstance, next_gipa);
    return r;
}

static VKAPI_ATTR void VKAPI_CALL vkl_DestroyInstance(VkInstance instance, const VkAllocationCallbacks *pAllocator)
{
    struct vkl_instance *inst = vkl_instance_get(instance);
    PFN_vkDestroyInstance destroy;
    if (!inst) return;
    destroy = inst->DestroyInstance;
    pthread_mutex_lock(&g_vkl_lock);
    memset(inst, 0, sizeof(*inst));
    pthread_mutex_unlock(&g_vkl_lock);
    if (destroy) destroy(instance, pAllocator);
}

static VKAPI_ATTR VkResult VKAPI_CALL vkl_CreateDevice(VkPhysicalDevice physicalDevice,
                                                       const VkDeviceCreateInfo *pCreateInfo,
                                                       const VkAllocationCallbacks *pAllocator, VkDevice *pDevice)
{
    VkLayerDeviceCreateInfo *link = (VkLayerDeviceCreateInfo *)pCreateInfo->pNext;
    struct vkl_instance *inst = vkl_instance_get(physicalDevice);
    PFN_vkGetInstanceProcAddr next_gipa;
    PFN_vkGetDeviceProcAddr next_gdpa;
    PFN_vkCreateDevice next_create;
    VkResult r;

    while (link && !(link->sType == VK_STRUCTURE_TYPE_LOADER_DEVICE_CREATE_INFO &&
                     link->function == VK_LAYER_LINK_INFO))
        link = (VkLayerDeviceCreateInfo *)link->pNext;
    if (!link || !link->u.pLayerInfo || !inst) {
        LOGE("%s: vkCreateDevice without loader link info / instance", VKL_LAYER_NAME);
        return VK_ERROR_INITIALIZATION_FAILED;
    }
    next_gipa = link->u.pLayerInfo->pfnNextGetInstanceProcAddr;
    next_gdpa = link->u.pLayerInfo->pfnNextGetDeviceProcAddr;
    link->u.pLayerInfo = link->u.pLayerInfo->pNext;
    next_create = (PFN_vkCreateDevice)next_gipa(inst->instance, "vkCreateDevice");
    if (!next_create) return VK_ERROR_INITIALIZATION_FAILED;
    r = next_create(physicalDevice, pCreateInfo, pAllocator, pDevice);
    if (r == VK_SUCCESS) vkl_device_add(*pDevice, next_gdpa);
    return r;
}

static VKAPI_ATTR void VKAPI_CALL vkl_DestroyDevice(VkDevice device, const VkAllocationCallbacks *pAllocator)
{
    struct vkl_device *dev = vkl_device_get(device);
    PFN_vkDestroyDevice destroy;
    if (!dev) return;
    destroy = dev->DestroyDevice;
    pthread_mutex_lock(&g_vkl_lock);
    memset(dev, 0, sizeof(*dev));
    pthread_mutex_unlock(&g_vkl_lock);
    if (destroy) destroy(device, pAllocator);
}

static VKAPI_ATTR VkResult VKAPI_CALL vkl_GetDeviceGroupSurfacePresentModesKHR(
    VkDevice device, VkSurfaceKHR surface, VkDeviceGroupPresentModeFlagsKHR *pModes)
{
    struct vkl_device *dev;
    if (vkl_surface_win(surface)) {
        *pModes = VK_DEVICE_GROUP_PRESENT_MODE_LOCAL_BIT_KHR;
        return VK_SUCCESS;
    }
    dev = vkl_device_get(device);
    if (!dev || !dev->GetDeviceGroupSurfacePresentModesKHR) return VK_ERROR_SURFACE_LOST_KHR;
    return dev->GetDeviceGroupSurfacePresentModesKHR(device, surface, pModes);
}

static VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL vkl_GetDeviceProcAddr(VkDevice device, const char *pName)
{
    struct vkl_device *dev;
    if (!pName) return NULL;
    if (!strcmp(pName, "vkGetDeviceProcAddr")) return (PFN_vkVoidFunction)vkl_GetDeviceProcAddr;
    dev = vkl_device_get(device);
    if (!dev) return NULL;
    if (!strcmp(pName, "vkDestroyDevice")) return (PFN_vkVoidFunction)vkl_DestroyDevice;
    if (!strcmp(pName, "vkGetDeviceGroupSurfacePresentModesKHR"))
        return dev->GetDeviceGroupSurfacePresentModesKHR
            ? (PFN_vkVoidFunction)vkl_GetDeviceGroupSurfacePresentModesKHR : NULL;
    return dev->gdpa(device, pName);
}

static VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL vkl_GetInstanceProcAddr(VkInstance instance, const char *pName)
{
    static const struct { const char *name; PFN_vkVoidFunction fn; } own[] = {
#define VKL_OWN(name) { "vk" #name, (PFN_vkVoidFunction)vkl_##name }
        VKL_OWN(GetInstanceProcAddr),
        VKL_OWN(CreateInstance),
        VKL_OWN(DestroyInstance),
        VKL_OWN(CreateDevice),
        VKL_OWN(GetDeviceProcAddr),
        VKL_OWN(DestroySurfaceKHR),
        VKL_OWN(GetPhysicalDeviceSurfaceSupportKHR),
        VKL_OWN(GetPhysicalDeviceSurfaceCapabilitiesKHR),
        VKL_OWN(GetPhysicalDeviceSurfaceCapabilities2KHR),
        VKL_OWN(GetPhysicalDeviceSurfaceFormatsKHR),
        VKL_OWN(GetPhysicalDeviceSurfaceFormats2KHR),
        VKL_OWN(GetPhysicalDeviceSurfacePresentModesKHR),
        VKL_OWN(GetPhysicalDevicePresentRectanglesKHR),
#undef VKL_OWN
    };
    struct vkl_instance *inst;
    size_t i;
    if (!pName) return NULL;
    for (i = 0; i < sizeof(own) / sizeof(own[0]); i++) {
        if (!strcmp(pName, own[i].name)) return own[i].fn;
    }
    inst = vkl_instance_get(instance);
    return inst ? inst->gipa(instance, pName) : NULL;
}

/* The only export the loader needs (manifest interface version 2). */
VKAPI_ATTR VkResult VKAPI_CALL vkNegotiateLoaderLayerInterfaceVersion(VkNegotiateLayerInterface *pVersionStruct)
{
    if (!pVersionStruct || pVersionStruct->sType != LAYER_NEGOTIATE_INTERFACE_STRUCT ||
        pVersionStruct->loaderLayerInterfaceVersion < 2)
        return VK_ERROR_INITIALIZATION_FAILED;
    pVersionStruct->loaderLayerInterfaceVersion = 2;
    pVersionStruct->pfnGetInstanceProcAddr = vkl_GetInstanceProcAddr;
    pVersionStruct->pfnGetDeviceProcAddr = vkl_GetDeviceProcAddr;
    pVersionStruct->pfnGetPhysicalDeviceProcAddr = NULL;
    LOGI("%s: negotiated loader interface 2", VKL_LAYER_NAME);
    return VK_SUCCESS;
}
