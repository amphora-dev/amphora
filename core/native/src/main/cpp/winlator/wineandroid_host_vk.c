/*
 * Amphora host (ARM64 :session): vkCreateAndroidSurfaceKHR + swapchain +
 * QueuePresent on the real ANativeWindow served for a Wine hwnd.
 * Wine's x86_64 proxy ANW cannot be passed through FEX into aarch64 WSI.
 *
 * Draws a tiny RGB triangle (gl_VertexIndex VS/FS) so screencap shows more
 * than a solid clear on the dedicated Amphora client ANW.
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

static const uint32_t tri_vert_spv[] = {
    0x07230203, 0x00010000, 0x000d000a, 0x0000003a, 0x00000000, 0x00020011,
    0x00000001, 0x0006000b, 0x00000001, 0x4c534c47, 0x6474732e, 0x3035342e,
    0x00000000, 0x0003000e, 0x00000000, 0x00000001, 0x0008000f, 0x00000000,
    0x00000004, 0x6e69616d, 0x00000000, 0x00000026, 0x0000002a, 0x00000035,
    0x00030003, 0x00000002, 0x000001c2, 0x000a0004, 0x475f4c47, 0x4c474f4f,
    0x70635f45, 0x74735f70, 0x5f656c79, 0x656e696c, 0x7269645f, 0x69746365,
    0x00006576, 0x00080004, 0x475f4c47, 0x4c474f4f, 0x6e695f45, 0x64756c63,
    0x69645f65, 0x74636572, 0x00657669, 0x00040005, 0x00000004, 0x6e69616d,
    0x00000000, 0x00050005, 0x0000000c, 0x69736f70, 0x6e6f6974, 0x00000073,
    0x00040005, 0x00000018, 0x6f6c6f63, 0x00007372, 0x00060005, 0x00000024,
    0x505f6c67, 0x65567265, 0x78657472, 0x00000000, 0x00060006, 0x00000024,
    0x00000000, 0x505f6c67, 0x7469736f, 0x006e6f69, 0x00070006, 0x00000024,
    0x00000001, 0x505f6c67, 0x746e696f, 0x657a6953, 0x00000000, 0x00070006,
    0x00000024, 0x00000002, 0x435f6c67, 0x4470696c, 0x61747369, 0x0065636e,
    0x00070006, 0x00000024, 0x00000003, 0x435f6c67, 0x446c6c75, 0x61747369,
    0x0065636e, 0x00030005, 0x00000026, 0x00000000, 0x00060005, 0x0000002a,
    0x565f6c67, 0x65747265, 0x646e4978, 0x00007865, 0x00050005, 0x00000035,
    0x67617266, 0x6f6c6f43, 0x00000072, 0x00050048, 0x00000024, 0x00000000,
    0x0000000b, 0x00000000, 0x00050048, 0x00000024, 0x00000001, 0x0000000b,
    0x00000001, 0x00050048, 0x00000024, 0x00000002, 0x0000000b, 0x00000003,
    0x00050048, 0x00000024, 0x00000003, 0x0000000b, 0x00000004, 0x00030047,
    0x00000024, 0x00000002, 0x00040047, 0x0000002a, 0x0000000b, 0x0000002a,
    0x00040047, 0x00000035, 0x0000001e, 0x00000000, 0x00020013, 0x00000002,
    0x00030021, 0x00000003, 0x00000002, 0x00030016, 0x00000006, 0x00000020,
    0x00040017, 0x00000007, 0x00000006, 0x00000002, 0x00040015, 0x00000008,
    0x00000020, 0x00000000, 0x0004002b, 0x00000008, 0x00000009, 0x00000003,
    0x0004001c, 0x0000000a, 0x00000007, 0x00000009, 0x00040020, 0x0000000b,
    0x00000006, 0x0000000a, 0x0004003b, 0x0000000b, 0x0000000c, 0x00000006,
    0x0004002b, 0x00000006, 0x0000000d, 0x00000000, 0x0004002b, 0x00000006,
    0x0000000e, 0xbf19999a, 0x0005002c, 0x00000007, 0x0000000f, 0x0000000d,
    0x0000000e, 0x0004002b, 0x00000006, 0x00000010, 0x3f19999a, 0x0004002b,
    0x00000006, 0x00000011, 0x3f000000, 0x0005002c, 0x00000007, 0x00000012,
    0x00000010, 0x00000011, 0x0005002c, 0x00000007, 0x00000013, 0x0000000e,
    0x00000011, 0x0006002c, 0x0000000a, 0x00000014, 0x0000000f, 0x00000012,
    0x00000013, 0x00040017, 0x00000015, 0x00000006, 0x00000003, 0x0004001c,
    0x00000016, 0x00000015, 0x00000009, 0x00040020, 0x00000017, 0x00000006,
    0x00000016, 0x0004003b, 0x00000017, 0x00000018, 0x00000006, 0x0004002b,
    0x00000006, 0x00000019, 0x3f800000, 0x0004002b, 0x00000006, 0x0000001a,
    0x3e19999a, 0x0006002c, 0x00000015, 0x0000001b, 0x00000019, 0x0000001a,
    0x0000001a, 0x0006002c, 0x00000015, 0x0000001c, 0x0000001a, 0x00000019,
    0x0000001a, 0x0004002b, 0x00000006, 0x0000001d, 0x3e4ccccd, 0x0004002b,
    0x00000006, 0x0000001e, 0x3eb33333, 0x0006002c, 0x00000015, 0x0000001f,
    0x0000001d, 0x0000001e, 0x00000019, 0x0006002c, 0x00000016, 0x00000020,
    0x0000001b, 0x0000001c, 0x0000001f, 0x00040017, 0x00000021, 0x00000006,
    0x00000004, 0x0004002b, 0x00000008, 0x00000022, 0x00000001, 0x0004001c,
    0x00000023, 0x00000006, 0x00000022, 0x0006001e, 0x00000024, 0x00000021,
    0x00000006, 0x00000023, 0x00000023, 0x00040020, 0x00000025, 0x00000003,
    0x00000024, 0x0004003b, 0x00000025, 0x00000026, 0x00000003, 0x00040015,
    0x00000027, 0x00000020, 0x00000001, 0x0004002b, 0x00000027, 0x00000028,
    0x00000000, 0x00040020, 0x00000029, 0x00000001, 0x00000027, 0x0004003b,
    0x00000029, 0x0000002a, 0x00000001, 0x00040020, 0x0000002c, 0x00000006,
    0x00000007, 0x00040020, 0x00000032, 0x00000003, 0x00000021, 0x00040020,
    0x00000034, 0x00000003, 0x00000015, 0x0004003b, 0x00000034, 0x00000035,
    0x00000003, 0x00040020, 0x00000037, 0x00000006, 0x00000015, 0x00050036,
    0x00000002, 0x00000004, 0x00000000, 0x00000003, 0x000200f8, 0x00000005,
    0x0003003e, 0x0000000c, 0x00000014, 0x0003003e, 0x00000018, 0x00000020,
    0x0004003d, 0x00000027, 0x0000002b, 0x0000002a, 0x00050041, 0x0000002c,
    0x0000002d, 0x0000000c, 0x0000002b, 0x0004003d, 0x00000007, 0x0000002e,
    0x0000002d, 0x00050051, 0x00000006, 0x0000002f, 0x0000002e, 0x00000000,
    0x00050051, 0x00000006, 0x00000030, 0x0000002e, 0x00000001, 0x00070050,
    0x00000021, 0x00000031, 0x0000002f, 0x00000030, 0x0000000d, 0x00000019,
    0x00050041, 0x00000032, 0x00000033, 0x00000026, 0x00000028, 0x0003003e,
    0x00000033, 0x00000031, 0x0004003d, 0x00000027, 0x00000036, 0x0000002a,
    0x00050041, 0x00000037, 0x00000038, 0x00000018, 0x00000036, 0x0004003d,
    0x00000015, 0x00000039, 0x00000038, 0x0003003e, 0x00000035, 0x00000039,
    0x000100fd, 0x00010038,
};
static const uint32_t tri_frag_spv[] = {
    0x07230203, 0x00010000, 0x000d000a, 0x00000013, 0x00000000, 0x00020011,
    0x00000001, 0x0006000b, 0x00000001, 0x4c534c47, 0x6474732e, 0x3035342e,
    0x00000000, 0x0003000e, 0x00000000, 0x00000001, 0x0007000f, 0x00000004,
    0x00000004, 0x6e69616d, 0x00000000, 0x00000009, 0x0000000c, 0x00030010,
    0x00000004, 0x00000007, 0x00030003, 0x00000002, 0x000001c2, 0x000a0004,
    0x475f4c47, 0x4c474f4f, 0x70635f45, 0x74735f70, 0x5f656c79, 0x656e696c,
    0x7269645f, 0x69746365, 0x00006576, 0x00080004, 0x475f4c47, 0x4c474f4f,
    0x6e695f45, 0x64756c63, 0x69645f65, 0x74636572, 0x00657669, 0x00040005,
    0x00000004, 0x6e69616d, 0x00000000, 0x00050005, 0x00000009, 0x4374756f,
    0x726f6c6f, 0x00000000, 0x00050005, 0x0000000c, 0x67617266, 0x6f6c6f43,
    0x00000072, 0x00040047, 0x00000009, 0x0000001e, 0x00000000, 0x00040047,
    0x0000000c, 0x0000001e, 0x00000000, 0x00020013, 0x00000002, 0x00030021,
    0x00000003, 0x00000002, 0x00030016, 0x00000006, 0x00000020, 0x00040017,
    0x00000007, 0x00000006, 0x00000004, 0x00040020, 0x00000008, 0x00000003,
    0x00000007, 0x0004003b, 0x00000008, 0x00000009, 0x00000003, 0x00040017,
    0x0000000a, 0x00000006, 0x00000003, 0x00040020, 0x0000000b, 0x00000001,
    0x0000000a, 0x0004003b, 0x0000000b, 0x0000000c, 0x00000001, 0x0004002b,
    0x00000006, 0x0000000e, 0x3f800000, 0x00050036, 0x00000002, 0x00000004,
    0x00000000, 0x00000003, 0x000200f8, 0x00000005, 0x0004003d, 0x0000000a,
    0x0000000d, 0x0000000c, 0x00050051, 0x00000006, 0x0000000f, 0x0000000d,
    0x00000000, 0x00050051, 0x00000006, 0x00000010, 0x0000000d, 0x00000001,
    0x00050051, 0x00000006, 0x00000011, 0x0000000d, 0x00000002, 0x00070050,
    0x00000007, 0x00000012, 0x0000000f, 0x00000010, 0x00000011, 0x0000000e,
    0x0003003e, 0x00000009, 0x00000012, 0x000100fd, 0x00010038,
};

int wineandroid_host_vk_present(void *anw, int32_t out[4]);

int wineandroid_host_vk_present(void *anw, int32_t out[4])
{
    ANativeWindow *window = (ANativeWindow *)anw;
    void *lib = NULL;
    PFN_vkGetInstanceProcAddr gipa = NULL;
    PFN_vkCreateInstance create_inst = NULL;
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
    uint32_t fmtn = 0, imgn = 0, idx = 0;
    VkSurfaceFormatKHR fmt, *fmts = NULL;
    VkPresentModeKHR mode = VK_PRESENT_MODE_FIFO_KHR;
    VkSwapchainCreateInfoKHR swci;
    VkSwapchainKHR swap = VK_NULL_HANDLE;
    VkImage *images = NULL;
    VkImageView *views = NULL;
    VkFramebuffer *fbs = NULL;
    VkCommandPool pool = VK_NULL_HANDLE;
    VkCommandBuffer cmd = VK_NULL_HANDLE;
    VkRenderPass rp = VK_NULL_HANDLE;
    VkShaderModule vs = VK_NULL_HANDLE, fs = VK_NULL_HANDLE;
    VkPipelineLayout pl = VK_NULL_HANDLE;
    VkPipeline pipe = VK_NULL_HANDLE;
    VkCommandPoolCreateInfo pci;
    VkCommandBufferAllocateInfo cai;
    VkCommandBufferBeginInfo bi;
    VkSubmitInfo si;
    VkPresentInfoKHR pi;
    VkAttachmentDescription att;
    VkAttachmentReference attref;
    VkSubpassDescription sub;
    VkSubpassDependency dep;
    VkRenderPassCreateInfo rpci;
    VkShaderModuleCreateInfo smci;
    VkPipelineShaderStageCreateInfo stages[2];
    VkPipelineVertexInputStateCreateInfo vi;
    VkPipelineInputAssemblyStateCreateInfo ia;
    VkPipelineViewportStateCreateInfo vp;
    VkPipelineRasterizationStateCreateInfo rs;
    VkPipelineMultisampleStateCreateInfo ms;
    VkPipelineColorBlendAttachmentState cba;
    VkPipelineColorBlendStateCreateInfo cb;
    VkPipelineLayoutCreateInfo plci;
    VkGraphicsPipelineCreateInfo gpci;
    VkViewport viewport;
    VkRect2D scissor;
    VkRenderPassBeginInfo rpbi;
    VkClearValue clear;
    int frames, present_ret = -1;
    PFN_vkDestroyInstance pDestroyInstance = NULL;
    PFN_vkDestroyDevice pDestroyDevice = NULL;
    PFN_vkDestroySurfaceKHR pDestroySurface = NULL;
    PFN_vkDestroySwapchainKHR pDestroySwapchain = NULL;
    PFN_vkDestroyCommandPool pDestroyCommandPool = NULL;
    PFN_vkDestroyRenderPass pDestroyRenderPass = NULL;
    PFN_vkDestroyShaderModule pDestroyShaderModule = NULL;
    PFN_vkDestroyPipelineLayout pDestroyPipelineLayout = NULL;
    PFN_vkDestroyPipeline pDestroyPipeline = NULL;
    PFN_vkDestroyImageView pDestroyImageView = NULL;
    PFN_vkDestroyFramebuffer pDestroyFramebuffer = NULL;

    if (out) { out[0] = -1; out[1] = -1; out[2] = -1; out[3] = 0; }
    if (!window) { LOGE("vk present: null window"); return -1; }

    lib = dlopen("libvulkan.so", RTLD_NOW);
    if (!lib) { LOGE("dlopen libvulkan: %s", dlerror()); return -1; }
    gipa = (PFN_vkGetInstanceProcAddr)dlsym(lib, "vkGetInstanceProcAddr");
    if (!gipa) { LOGE("no vkGetInstanceProcAddr"); dlclose(lib); return -1; }
    create_inst = (PFN_vkCreateInstance)gipa(NULL, "vkCreateInstance");
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
    GPA(vkCreateDevice);
    GPA(vkGetDeviceQueue);
    GPA(vkCreateSwapchainKHR);
    GPA(vkGetSwapchainImagesKHR);
    GPA(vkCreateCommandPool);
    GPA(vkAllocateCommandBuffers);
    GPA(vkResetCommandBuffer);
    GPA(vkBeginCommandBuffer);
    GPA(vkEndCommandBuffer);
    GPA(vkCmdBeginRenderPass);
    GPA(vkCmdEndRenderPass);
    GPA(vkCmdBindPipeline);
    GPA(vkCmdSetViewport);
    GPA(vkCmdSetScissor);
    GPA(vkCmdDraw);
    GPA(vkCreateRenderPass);
    GPA(vkCreateShaderModule);
    GPA(vkCreatePipelineLayout);
    GPA(vkCreateGraphicsPipelines);
    GPA(vkCreateImageView);
    GPA(vkCreateFramebuffer);
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
    pDestroyRenderPass = (PFN_vkDestroyRenderPass)gipa(inst, "vkDestroyRenderPass");
    pDestroyShaderModule = (PFN_vkDestroyShaderModule)gipa(inst, "vkDestroyShaderModule");
    pDestroyPipelineLayout = (PFN_vkDestroyPipelineLayout)gipa(inst, "vkDestroyPipelineLayout");
    pDestroyPipeline = (PFN_vkDestroyPipeline)gipa(inst, "vkDestroyPipeline");
    pDestroyImageView = (PFN_vkDestroyImageView)gipa(inst, "vkDestroyImageView");
    pDestroyFramebuffer = (PFN_vkDestroyFramebuffer)gipa(inst, "vkDestroyFramebuffer");

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
    swci.imageUsage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
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
    views = (VkImageView *)calloc(imgn ? imgn : 1, sizeof(*views));
    fbs = (VkFramebuffer *)calloc(imgn ? imgn : 1, sizeof(*fbs));
    vkGetSwapchainImagesKHR(device, swap, &imgn, images);

    memset(&att, 0, sizeof(att));
    att.format = fmt.format;
    att.samples = VK_SAMPLE_COUNT_1_BIT;
    att.loadOp = VK_ATTACHMENT_LOAD_OP_CLEAR;
    att.storeOp = VK_ATTACHMENT_STORE_OP_STORE;
    att.stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
    att.stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
    att.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    att.finalLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
    memset(&attref, 0, sizeof(attref));
    attref.attachment = 0;
    attref.layout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
    memset(&sub, 0, sizeof(sub));
    sub.pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS;
    sub.colorAttachmentCount = 1;
    sub.pColorAttachments = &attref;
    memset(&dep, 0, sizeof(dep));
    dep.srcSubpass = VK_SUBPASS_EXTERNAL;
    dep.dstSubpass = 0;
    dep.srcStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    dep.dstStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    dep.dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
    memset(&rpci, 0, sizeof(rpci));
    rpci.sType = VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO;
    rpci.attachmentCount = 1;
    rpci.pAttachments = &att;
    rpci.subpassCount = 1;
    rpci.pSubpasses = &sub;
    rpci.dependencyCount = 1;
    rpci.pDependencies = &dep;
    r = vkCreateRenderPass(device, &rpci, NULL, &rp);
    LOGI("host vkCreateRenderPass ret=%d", (int)r);
    if (r != VK_SUCCESS) goto done;

    memset(&smci, 0, sizeof(smci));
    smci.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
    smci.codeSize = sizeof(tri_vert_spv);
    smci.pCode = tri_vert_spv;
    r = vkCreateShaderModule(device, &smci, NULL, &vs);
    if (r != VK_SUCCESS) { LOGE("vs module %d", (int)r); goto done; }
    smci.codeSize = sizeof(tri_frag_spv);
    smci.pCode = tri_frag_spv;
    r = vkCreateShaderModule(device, &smci, NULL, &fs);
    if (r != VK_SUCCESS) { LOGE("fs module %d", (int)r); goto done; }

    memset(&plci, 0, sizeof(plci));
    plci.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
    r = vkCreatePipelineLayout(device, &plci, NULL, &pl);
    if (r != VK_SUCCESS) goto done;

    memset(stages, 0, sizeof(stages));
    stages[0].sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    stages[0].stage = VK_SHADER_STAGE_VERTEX_BIT;
    stages[0].module = vs;
    stages[0].pName = "main";
    stages[1].sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    stages[1].stage = VK_SHADER_STAGE_FRAGMENT_BIT;
    stages[1].module = fs;
    stages[1].pName = "main";

    memset(&vi, 0, sizeof(vi));
    vi.sType = VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO;
    memset(&ia, 0, sizeof(ia));
    ia.sType = VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO;
    ia.topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
    memset(&vp, 0, sizeof(vp));
    vp.sType = VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO;
    vp.viewportCount = 1;
    vp.scissorCount = 1;
    memset(&rs, 0, sizeof(rs));
    rs.sType = VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO;
    rs.polygonMode = VK_POLYGON_MODE_FILL;
    rs.cullMode = VK_CULL_MODE_NONE;
    rs.frontFace = VK_FRONT_FACE_COUNTER_CLOCKWISE;
    rs.lineWidth = 1.0f;
    memset(&ms, 0, sizeof(ms));
    ms.sType = VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO;
    ms.rasterizationSamples = VK_SAMPLE_COUNT_1_BIT;
    memset(&cba, 0, sizeof(cba));
    cba.colorWriteMask = VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT |
                         VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT;
    memset(&cb, 0, sizeof(cb));
    cb.sType = VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO;
    cb.attachmentCount = 1;
    cb.pAttachments = &cba;

    memset(&gpci, 0, sizeof(gpci));
    gpci.sType = VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO;
    gpci.stageCount = 2;
    gpci.pStages = stages;
    gpci.pVertexInputState = &vi;
    gpci.pInputAssemblyState = &ia;
    gpci.pViewportState = &vp;
    gpci.pRasterizationState = &rs;
    gpci.pMultisampleState = &ms;
    gpci.pColorBlendState = &cb;
    gpci.layout = pl;
    gpci.renderPass = rp;
    gpci.subpass = 0;
    {
        static const VkDynamicState dyn[2] = { VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR };
        VkPipelineDynamicStateCreateInfo dynci;
        memset(&dynci, 0, sizeof(dynci));
        dynci.sType = VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO;
        dynci.dynamicStateCount = 2;
        dynci.pDynamicStates = dyn;
        gpci.pDynamicState = &dynci;
        r = vkCreateGraphicsPipelines(device, VK_NULL_HANDLE, 1, &gpci, NULL, &pipe);
    }
    LOGI("host vkCreateGraphicsPipelines ret=%d", (int)r);
    if (r != VK_SUCCESS) goto done;

    for (i = 0; i < imgn; i++) {
        VkImageViewCreateInfo ivci;
        VkFramebufferCreateInfo fbci;
        memset(&ivci, 0, sizeof(ivci));
        ivci.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
        ivci.image = images[i];
        ivci.viewType = VK_IMAGE_VIEW_TYPE_2D;
        ivci.format = fmt.format;
        ivci.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        ivci.subresourceRange.levelCount = 1;
        ivci.subresourceRange.layerCount = 1;
        r = vkCreateImageView(device, &ivci, NULL, &views[i]);
        if (r != VK_SUCCESS) { LOGE("imageView[%u]=%d", i, (int)r); goto done; }
        memset(&fbci, 0, sizeof(fbci));
        fbci.sType = VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO;
        fbci.renderPass = rp;
        fbci.attachmentCount = 1;
        fbci.pAttachments = &views[i];
        fbci.width = swci.imageExtent.width;
        fbci.height = swci.imageExtent.height;
        fbci.layers = 1;
        r = vkCreateFramebuffer(device, &fbci, NULL, &fbs[i]);
        if (r != VK_SUCCESS) { LOGE("framebuffer[%u]=%d", i, (int)r); goto done; }
    }

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

    /* Dark clear so RGB triangle is obvious vs prior solid-green smoke. */
    memset(&clear, 0, sizeof(clear));
    clear.color.float32[0] = 0.08f;
    clear.color.float32[1] = 0.08f;
    clear.color.float32[2] = 0.12f;
    clear.color.float32[3] = 1.0f;

    memset(&viewport, 0, sizeof(viewport));
    viewport.width = (float)swci.imageExtent.width;
    viewport.height = (float)swci.imageExtent.height;
    viewport.minDepth = 0.0f;
    viewport.maxDepth = 1.0f;
    memset(&scissor, 0, sizeof(scissor));
    scissor.extent = swci.imageExtent;

    for (frames = 0; frames < 3; frames++) {
        r = vkAcquireNextImageKHR(device, swap, UINT64_MAX, VK_NULL_HANDLE, VK_NULL_HANDLE, &idx);
        LOGI("host vkAcquireNextImageKHR frame=%d ret=%d idx=%u", frames, (int)r, idx);
        if (r != VK_SUCCESS && r != VK_SUBOPTIMAL_KHR) break;
        vkResetCommandBuffer(cmd, 0);
        memset(&bi, 0, sizeof(bi));
        bi.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
        bi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
        vkBeginCommandBuffer(cmd, &bi);
        memset(&rpbi, 0, sizeof(rpbi));
        rpbi.sType = VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO;
        rpbi.renderPass = rp;
        rpbi.framebuffer = fbs[idx];
        rpbi.renderArea.extent = swci.imageExtent;
        rpbi.clearValueCount = 1;
        rpbi.pClearValues = &clear;
        vkCmdBeginRenderPass(cmd, &rpbi, VK_SUBPASS_CONTENTS_INLINE);
        vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipe);
        vkCmdSetViewport(cmd, 0, 1, &viewport);
        vkCmdSetScissor(cmd, 0, 1, &scissor);
        vkCmdDraw(cmd, 3, 1, 0, 0);
        vkCmdEndRenderPass(cmd);
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
        LOGI("host vkQueuePresentKHR frame=%d ret=%d (tri)", frames, (int)r);
        present_ret = (int)r;
        if (out) { out[2] = (int32_t)r; out[3] = frames + 1; }
        if (r != VK_SUCCESS && r != VK_SUBOPTIMAL_KHR) break;
    }

    /* Hold last present for screencap (RGB triangle on dark clear). */
    if (present_ret == 0 || present_ret == 1) usleep(4000000);

done:
    if (device && vkDeviceWaitIdle) vkDeviceWaitIdle(device);
    if (fbs && pDestroyFramebuffer) {
        for (i = 0; i < imgn; i++) if (fbs[i]) pDestroyFramebuffer(device, fbs[i], NULL);
    }
    if (views && pDestroyImageView) {
        for (i = 0; i < imgn; i++) if (views[i]) pDestroyImageView(device, views[i], NULL);
    }
    if (pipe && pDestroyPipeline) pDestroyPipeline(device, pipe, NULL);
    if (pl && pDestroyPipelineLayout) pDestroyPipelineLayout(device, pl, NULL);
    if (vs && pDestroyShaderModule) pDestroyShaderModule(device, vs, NULL);
    if (fs && pDestroyShaderModule) pDestroyShaderModule(device, fs, NULL);
    if (rp && pDestroyRenderPass) pDestroyRenderPass(device, rp, NULL);
    if (pool && pDestroyCommandPool) pDestroyCommandPool(device, pool, NULL);
    if (swap && pDestroySwapchain) pDestroySwapchain(device, swap, NULL);
    if (surface && pDestroySurface) pDestroySurface(inst, surface, NULL);
    if (device && pDestroyDevice) pDestroyDevice(device, NULL);
    if (inst && pDestroyInstance) pDestroyInstance(inst, NULL);
    free(fbs);
    free(views);
    free(images);
    free(fmts);
    free(qfp);
    free(pds);
    dlclose(lib);
    LOGI("host vk present done surface=%d swap=%d present=%d",
         out ? out[0] : -1, out ? out[1] : -1, present_ret);
    return present_ret;
}

