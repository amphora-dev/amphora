/*
 * Amphora host (ARM64 :session): vkCreateAndroidSurfaceKHR + swapchain +
 * QueuePresent on the real ANativeWindow served for a Wine hwnd.
 * Wine's x86_64 proxy ANW cannot be passed through FEX into aarch64 WSI.
 */
#define VK_USE_PLATFORM_ANDROID_KHR
#include <android/log.h>
#include <android/native_window.h>
#include <dlfcn.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <vulkan/vulkan.h>
#include <vulkan/vulkan_android.h>

#define LOG_TAG "WineAndroidHostVk"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

int wineandroid_host_vk_present(void *anw, int32_t out[4]);

int wineandroid_host_vk_present(void *anw, int32_t out[4])
{
    ANativeWindow *window = (ANativeWindow *)anw;
    void *lib = NULL;
    PFN_vkGetInstanceProcAddr gipa = NULL;
    PFN_vkCreateInstance create_inst = NULL;
    PFN_vkEnumerateInstanceExtensionProperties enum_ext = NULL;
    VkApplicationInfo app;
    VkInstanceCreateInfo ici;
    const char *iext[2];
    VkInstance inst = VK_NULL_HANDLE;
    VkResult r;
    uint32_t pdn = 0, qfn = 0, qfam = 0xffffffffu, i;
    VkPhysicalDevice pd = VK_NULL_HANDLE;
    VkPhysicalDevice *pds = NULL;
    VkQueueFamilyProperties *qfp = NULL;
    VkAndroidSurfaceCreateInfoKHR asci;
    VkSurfaceKHR surface = VK_NULL_HANDLE;
    VkDevice device = VK_NULL_HANDLE;
    VkQueue queue = VK_NULL_HANDLE;
    VkDeviceQueueCreateInfo qci;
    VkDeviceCreateInfo dci;
    const char *dext[1];
    float prio = 1.0f;
    VkBool32 supported = VK_FALSE;
    VkSurfaceCapabilitiesKHR caps;
    uint32_t fmtn = 0, moden = 0, imgn = 0, idx = 0;
    VkSurfaceFormatKHR fmt, *fmts = NULL;
    VkPresentModeKHR mode = VK_PRESENT_MODE_FIFO_KHR;
    VkSwapchainCreateInfoKHR swci;
    VkSwapchainKHR swap = VK_NULL_HANDLE;
    VkImage *images = NULL;
    VkCommandPool pool = VK_NULL_HANDLE;
    VkCommandBuffer cmd = VK_NULL_HANDLE;
    VkCommandPoolCreateInfo pci;
    VkCommandBufferAllocateInfo cai;
    VkCommandBufferBeginInfo bi;
    VkImageMemoryBarrier bar;
    VkSubmitInfo si;
    VkPresentInfoKHR pi;
    VkClearColorValue clear;
    VkImageSubresourceRange range;
    int frames, present_ret = -1;
    PFN_vkDestroyInstance pDestroyInstance = NULL;
    PFN_vkDestroyDevice pDestroyDevice = NULL;
    PFN_vkDestroySurfaceKHR pDestroySurface = NULL;
    PFN_vkDestroySwapchainKHR pDestroySwapchain = NULL;
    PFN_vkDestroyCommandPool pDestroyCommandPool = NULL;

    if (out) { out[0] = -1; out[1] = -1; out[2] = -1; out[3] = 0; }
    if (!window) { LOGE("vk present: null window"); return -1; }

    lib = dlopen("libvulkan.so", RTLD_NOW);
    if (!lib) { LOGE("dlopen libvulkan: %s", dlerror()); return -1; }
    gipa = (PFN_vkGetInstanceProcAddr)dlsym(lib, "vkGetInstanceProcAddr");
    if (!gipa) { LOGE("no vkGetInstanceProcAddr"); dlclose(lib); return -1; }
    create_inst = (PFN_vkCreateInstance)gipa(NULL, "vkCreateInstance");
    enum_ext = (PFN_vkEnumerateInstanceExtensionProperties)gipa(NULL, "vkEnumerateInstanceExtensionProperties");
    if (!create_inst) { LOGE("no vkCreateInstance"); dlclose(lib); return -1; }

    memset(&app, 0, sizeof(app));
    memset(&ici, 0, sizeof(ici));
    app.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    app.pApplicationName = "amphora-host-vk";
    app.apiVersion = VK_API_VERSION_1_0;
    iext[0] = VK_KHR_SURFACE_EXTENSION_NAME;
    iext[1] = VK_KHR_ANDROID_SURFACE_EXTENSION_NAME;
    ici.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    ici.pApplicationInfo = &app;
    ici.enabledExtensionCount = 2;
    ici.ppEnabledExtensionNames = iext;
    r = create_inst(&ici, NULL, &inst);
    LOGI("host vkCreateInstance ret=%d inst=%p", (int)r, (void *)inst);
    if (r != VK_SUCCESS || !inst) { dlclose(lib); return -1; }

#define GPA(name) PFN_##name name = (PFN_##name)gipa(inst, #name)
    GPA(vkEnumeratePhysicalDevices);
    GPA(vkGetPhysicalDeviceQueueFamilyProperties);
    GPA(vkCreateAndroidSurfaceKHR);
    GPA(vkGetPhysicalDeviceSurfaceSupportKHR);
    GPA(vkGetPhysicalDeviceSurfaceCapabilitiesKHR);
    GPA(vkGetPhysicalDeviceSurfaceFormatsKHR);
    GPA(vkGetPhysicalDeviceSurfacePresentModesKHR);
    GPA(vkCreateDevice);
    GPA(vkGetDeviceQueue);
    GPA(vkCreateSwapchainKHR);
    GPA(vkGetSwapchainImagesKHR);
    GPA(vkCreateCommandPool);
    GPA(vkAllocateCommandBuffers);
    GPA(vkResetCommandBuffer);
    GPA(vkBeginCommandBuffer);
    GPA(vkCmdPipelineBarrier);
    GPA(vkCmdClearColorImage);
    GPA(vkEndCommandBuffer);
    GPA(vkQueueSubmit);
    GPA(vkQueueWaitIdle);
    GPA(vkAcquireNextImageKHR);
    GPA(vkQueuePresentKHR);
    GPA(vkDeviceWaitIdle);
#undef GPA
    pDestroyInstance = (PFN_vkDestroyInstance)gipa(inst, "vkDestroyInstance");
    pDestroyDevice = (PFN_vkDestroyDevice)gipa(inst, "vkDestroyDevice");
    pDestroySurface = (PFN_vkDestroySurfaceKHR)gipa(inst, "vkDestroySurfaceKHR");
    pDestroySwapchain = (PFN_vkDestroySwapchainKHR)gipa(inst, "vkDestroySwapchainKHR");
    pDestroyCommandPool = (PFN_vkDestroyCommandPool)gipa(inst, "vkDestroyCommandPool");

    if (!vkCreateAndroidSurfaceKHR) { LOGE("no vkCreateAndroidSurfaceKHR"); goto done; }

    memset(&asci, 0, sizeof(asci));
    asci.sType = VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR;
    asci.window = window;
    r = vkCreateAndroidSurfaceKHR(inst, &asci, NULL, &surface);
    LOGI("host vkCreateAndroidSurfaceKHR ret=%d surface=%p anw=%p", (int)r, (void *)(uintptr_t)surface, (void *)window);
    if (out) out[0] = (int32_t)r;
    if (r != VK_SUCCESS) goto done;

    vkEnumeratePhysicalDevices(inst, &pdn, NULL);
    if (!pdn) { LOGE("no physical devices"); goto done; }
    pds = (VkPhysicalDevice *)calloc(pdn, sizeof(*pds));
    vkEnumeratePhysicalDevices(inst, &pdn, pds);
    pd = pds[0];

    vkGetPhysicalDeviceQueueFamilyProperties(pd, &qfn, NULL);
    qfp = (VkQueueFamilyProperties *)calloc(qfn ? qfn : 1, sizeof(*qfp));
    vkGetPhysicalDeviceQueueFamilyProperties(pd, &qfn, qfp);
    for (i = 0; i < qfn; i++) {
        if (qfp[i].queueFlags & VK_QUEUE_GRAPHICS_BIT) { qfam = i; break; }
    }
    if (qfam == 0xffffffffu) goto done;
    if (vkGetPhysicalDeviceSurfaceSupportKHR) {
        vkGetPhysicalDeviceSurfaceSupportKHR(pd, qfam, surface, &supported);
        LOGI("host surface support qfam=%u supported=%u", qfam, (unsigned)supported);
    }

    memset(&qci, 0, sizeof(qci));
    memset(&dci, 0, sizeof(dci));
    qci.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
    qci.queueFamilyIndex = qfam;
    qci.queueCount = 1;
    qci.pQueuePriorities = &prio;
    dext[0] = VK_KHR_SWAPCHAIN_EXTENSION_NAME;
    dci.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
    dci.queueCreateInfoCount = 1;
    dci.pQueueCreateInfos = &qci;
    dci.enabledExtensionCount = 1;
    dci.ppEnabledExtensionNames = dext;
    r = vkCreateDevice(pd, &dci, NULL, &device);
    LOGI("host vkCreateDevice ret=%d", (int)r);
    if (r != VK_SUCCESS) goto done;
    vkGetDeviceQueue(device, qfam, 0, &queue);

    memset(&caps, 0, sizeof(caps));
    vkGetPhysicalDeviceSurfaceCapabilitiesKHR(pd, surface, &caps);
    vkGetPhysicalDeviceSurfaceFormatsKHR(pd, surface, &fmtn, NULL);
    fmts = (VkSurfaceFormatKHR *)calloc(fmtn ? fmtn : 1, sizeof(*fmts));
    if (fmtn) vkGetPhysicalDeviceSurfaceFormatsKHR(pd, surface, &fmtn, fmts);
    fmt.format = (fmtn && fmts[0].format != VK_FORMAT_UNDEFINED) ? fmts[0].format : VK_FORMAT_R8G8B8A8_UNORM;
    fmt.colorSpace = fmtn ? fmts[0].colorSpace : VK_COLOR_SPACE_SRGB_NONLINEAR_KHR;
    LOGI("host caps %ux%u minImg=%u fmt=%d", caps.currentExtent.width, caps.currentExtent.height,
         caps.minImageCount, (int)fmt.format);

    memset(&swci, 0, sizeof(swci));
    swci.sType = VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR;
    swci.surface = surface;
    swci.minImageCount = caps.minImageCount ? caps.minImageCount : 2;
    if (caps.maxImageCount && swci.minImageCount > caps.maxImageCount)
        swci.minImageCount = caps.maxImageCount;
    swci.imageFormat = fmt.format;
    swci.imageColorSpace = fmt.colorSpace;
    swci.imageExtent = caps.currentExtent;
    if (swci.imageExtent.width == 0xffffffffu || swci.imageExtent.width == 0) {
        int w = ANativeWindow_getWidth(window);
        int h = ANativeWindow_getHeight(window);
        swci.imageExtent.width = w > 0 ? (uint32_t)w : 1280;
        swci.imageExtent.height = h > 0 ? (uint32_t)h : 720;
    }
    swci.imageArrayLayers = 1;
    swci.imageUsage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
    swci.imageSharingMode = VK_SHARING_MODE_EXCLUSIVE;
    swci.preTransform = caps.currentTransform ? caps.currentTransform : VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR;
    if (caps.supportedCompositeAlpha & VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR)
        swci.compositeAlpha = VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR;
    else if (caps.supportedCompositeAlpha & VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR)
        swci.compositeAlpha = VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR;
    else
        swci.compositeAlpha = VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR;
    swci.presentMode = mode;
    swci.clipped = VK_TRUE;
    r = vkCreateSwapchainKHR(device, &swci, NULL, &swap);
    LOGI("host vkCreateSwapchainKHR ret=%d extent=%ux%u", (int)r,
         swci.imageExtent.width, swci.imageExtent.height);
    if (out) out[1] = (int32_t)r;
    if (r != VK_SUCCESS) goto done;

    vkGetSwapchainImagesKHR(device, swap, &imgn, NULL);
    images = (VkImage *)calloc(imgn ? imgn : 1, sizeof(*images));
    vkGetSwapchainImagesKHR(device, swap, &imgn, images);

    memset(&pci, 0, sizeof(pci));
    pci.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
    pci.queueFamilyIndex = qfam;
    pci.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
    vkCreateCommandPool(device, &pci, NULL, &pool);
    memset(&cai, 0, sizeof(cai));
    cai.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    cai.commandPool = pool;
    cai.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    cai.commandBufferCount = 1;
    vkAllocateCommandBuffers(device, &cai, &cmd);

    clear.float32[0] = 0.05f;
    clear.float32[1] = 0.55f;
    clear.float32[2] = 0.25f;
    clear.float32[3] = 1.0f;
    range.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    range.baseMipLevel = 0;
    range.levelCount = 1;
    range.baseArrayLayer = 0;
    range.layerCount = 1;

    for (frames = 0; frames < 3; frames++) {
        r = vkAcquireNextImageKHR(device, swap, UINT64_MAX, VK_NULL_HANDLE, VK_NULL_HANDLE, &idx);
        LOGI("host vkAcquireNextImageKHR frame=%d ret=%d idx=%u", frames, (int)r, idx);
        if (r != VK_SUCCESS && r != VK_SUBOPTIMAL_KHR) break;
        vkResetCommandBuffer(cmd, 0);
        memset(&bi, 0, sizeof(bi));
        bi.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
        bi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
        vkBeginCommandBuffer(cmd, &bi);
        memset(&bar, 0, sizeof(bar));
        bar.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
        bar.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        bar.newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
        bar.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        bar.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        bar.image = images[idx];
        bar.subresourceRange = range;
        bar.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
        vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                             0, 0, NULL, 0, NULL, 1, &bar);
        vkCmdClearColorImage(cmd, images[idx], VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, &clear, 1, &range);
        bar.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
        bar.newLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
        bar.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
        bar.dstAccessMask = 0;
        vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
                             0, 0, NULL, 0, NULL, 1, &bar);
        vkEndCommandBuffer(cmd);
        memset(&si, 0, sizeof(si));
        si.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
        si.commandBufferCount = 1;
        si.pCommandBuffers = &cmd;
        vkQueueSubmit(queue, 1, &si, VK_NULL_HANDLE);
        vkQueueWaitIdle(queue);
        memset(&pi, 0, sizeof(pi));
        pi.sType = VK_STRUCTURE_TYPE_PRESENT_INFO_KHR;
        pi.swapchainCount = 1;
        pi.pSwapchains = &swap;
        pi.pImageIndices = &idx;
        r = vkQueuePresentKHR(queue, &pi);
        LOGI("host vkQueuePresentKHR frame=%d ret=%d", frames, (int)r);
        present_ret = (int)r;
        if (out) { out[2] = (int32_t)r; out[3] = frames + 1; }
        if (r != VK_SUCCESS && r != VK_SUBOPTIMAL_KHR) break;
    }

    /* Hold the last present so a screencap can see the green frame. */
    if (present_ret == 0 || present_ret == 1) usleep(4000000);

done:
    if (device && vkDeviceWaitIdle) vkDeviceWaitIdle(device);
    if (pool && pDestroyCommandPool) pDestroyCommandPool(device, pool, NULL);
    if (swap && pDestroySwapchain) pDestroySwapchain(device, swap, NULL);
    if (surface && pDestroySurface) pDestroySurface(inst, surface, NULL);
    if (device && pDestroyDevice) pDestroyDevice(device, NULL);
    if (inst && pDestroyInstance) pDestroyInstance(inst, NULL);
    free(images);
    free(fmts);
    free(qfp);
    free(pds);
    dlclose(lib);
    LOGI("host vk present done surface=%d swap=%d present=%d",
         out ? out[0] : -1, out ? out[1] : -1, present_ret);
    return present_ret;
}
