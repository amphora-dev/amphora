/*
 * Vulkan smoke test for Wine on Android (M3, 12 §2.3). One source, three builds:
 *   aarch64-w64-mingw32-clang -O2 -o vktest-arm64.exe vktest.c -luser32
 *   x86_64-w64-mingw32-clang  -O2 -o vktest64.exe     vktest.c -luser32
 *   i686-w64-mingw32-clang    -O2 -o vktest32.exe     vktest.c -luser32
 * (-I a Vulkan-Headers include directory; vulkan-1.dll is loaded at run time,
 * so no import library is needed. See build.sh.)
 *
 *   vktest [-q]                      off screen: draw a triangle through a real
 *                                    pipeline into an image and read it back.
 *                                    Ends with a message box unless -q.
 *   vktest --window [--frames N] [--mode fifo|mailbox|immediate] [--size WxH]
 *                                    on screen: a window, a surface, a swapchain,
 *                                    N frames (default 600, 0 = until closed) of a
 *                                    spinning triangle, then the frame rate.
 *
 * Every step prints a line; exit code 0 = all passed.
 */
#define VK_NO_PROTOTYPES
#define VK_USE_PLATFORM_WIN32_KHR
#include <windows.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <vulkan/vulkan.h>

#include "shaders.h"

static int failures;
static char report[8192];

static void check(int ok, const char *fmt, ...)
{
    char line[512];
    size_t used = strlen(report);
    va_list args;

    va_start(args, fmt);
    vsnprintf(line, sizeof(line), fmt, args);
    va_end(args);
    printf("%s %s\n", ok ? "ok  " : "FAIL", line);
    fflush(stdout);
    snprintf(report + used, sizeof(report) - used, "%s  %s\n", ok ? "ok" : "FAIL", line);
    if (!ok) failures++;
}

static void note(const char *fmt, ...)
{
    char line[512];
    size_t used = strlen(report);
    va_list args;

    va_start(args, fmt);
    vsnprintf(line, sizeof(line), fmt, args);
    va_end(args);
    printf("     %s\n", line);
    fflush(stdout);
    snprintf(report + used, sizeof(report) - used, "%s\n", line);
}

#define INSTANCE_FUNCS(X) \
    X(vkDestroyInstance) \
    X(vkEnumeratePhysicalDevices) \
    X(vkGetPhysicalDeviceProperties) \
    X(vkGetPhysicalDeviceQueueFamilyProperties) \
    X(vkGetPhysicalDeviceMemoryProperties) \
    X(vkEnumerateDeviceExtensionProperties) \
    X(vkCreateDevice) \
    X(vkGetDeviceProcAddr)

#define SURFACE_FUNCS(X) \
    X(vkCreateWin32SurfaceKHR) \
    X(vkDestroySurfaceKHR) \
    X(vkGetPhysicalDeviceSurfaceSupportKHR) \
    X(vkGetPhysicalDeviceSurfaceCapabilitiesKHR) \
    X(vkGetPhysicalDeviceSurfaceFormatsKHR) \
    X(vkGetPhysicalDeviceSurfacePresentModesKHR)

#define DEVICE_FUNCS(X) \
    X(vkDestroyDevice) \
    X(vkGetDeviceQueue) \
    X(vkDeviceWaitIdle) \
    X(vkCreateCommandPool) \
    X(vkDestroyCommandPool) \
    X(vkAllocateCommandBuffers) \
    X(vkBeginCommandBuffer) \
    X(vkEndCommandBuffer) \
    X(vkResetCommandBuffer) \
    X(vkCreateImage) \
    X(vkDestroyImage) \
    X(vkGetImageMemoryRequirements) \
    X(vkCreateBuffer) \
    X(vkDestroyBuffer) \
    X(vkGetBufferMemoryRequirements) \
    X(vkAllocateMemory) \
    X(vkFreeMemory) \
    X(vkBindImageMemory) \
    X(vkBindBufferMemory) \
    X(vkMapMemory) \
    X(vkUnmapMemory) \
    X(vkCreateImageView) \
    X(vkDestroyImageView) \
    X(vkCreateRenderPass) \
    X(vkDestroyRenderPass) \
    X(vkCreateFramebuffer) \
    X(vkDestroyFramebuffer) \
    X(vkCreateShaderModule) \
    X(vkDestroyShaderModule) \
    X(vkCreatePipelineLayout) \
    X(vkDestroyPipelineLayout) \
    X(vkCreateGraphicsPipelines) \
    X(vkDestroyPipeline) \
    X(vkCmdBeginRenderPass) \
    X(vkCmdEndRenderPass) \
    X(vkCmdBindPipeline) \
    X(vkCmdSetViewport) \
    X(vkCmdSetScissor) \
    X(vkCmdPushConstants) \
    X(vkCmdDraw) \
    X(vkCmdCopyImageToBuffer) \
    X(vkQueueSubmit) \
    X(vkQueueWaitIdle) \
    X(vkCreateFence) \
    X(vkDestroyFence) \
    X(vkWaitForFences) \
    X(vkResetFences) \
    X(vkCreateSemaphore) \
    X(vkDestroySemaphore)

#define SWAPCHAIN_FUNCS(X) \
    X(vkCreateSwapchainKHR) \
    X(vkDestroySwapchainKHR) \
    X(vkGetSwapchainImagesKHR) \
    X(vkAcquireNextImageKHR) \
    X(vkQueuePresentKHR)

#define DECLARE(name) static PFN_##name name;
static PFN_vkGetInstanceProcAddr vkGetInstanceProcAddr;
static PFN_vkCreateInstance vkCreateInstance;
static PFN_vkEnumerateInstanceExtensionProperties vkEnumerateInstanceExtensionProperties;
INSTANCE_FUNCS(DECLARE)
SURFACE_FUNCS(DECLARE)
DEVICE_FUNCS(DECLARE)
SWAPCHAIN_FUNCS(DECLARE)
#undef DECLARE

static VkInstance instance;
static VkPhysicalDevice gpu;
static VkDevice device;
static VkQueue queue;
static uint32_t queue_family;
static VkCommandPool pool;
static VkCommandBuffer cmd;
static VkPipelineLayout layout;
static VkShaderModule vert, frag;

struct push
{
    float angle, aspect, tint;
};

static const char *arch(void)
{
#if defined(__aarch64__) || defined(__arm64ec__)
    return "arm64";
#elif defined(__x86_64__)
    return "x86_64";
#else
    return "i386";
#endif
}

static int missing(const char *name)
{
    check(0, "%s not found", name);
    return 0;
}

static int has_extension(const VkExtensionProperties *props, uint32_t count, const char *name)
{
    for (uint32_t i = 0; i < count; i++)
        if (!strcmp(props[i].extensionName, name)) return 1;
    return 0;
}

static uint32_t memory_type(uint32_t bits, VkMemoryPropertyFlags flags)
{
    VkPhysicalDeviceMemoryProperties props;

    vkGetPhysicalDeviceMemoryProperties(gpu, &props);
    for (uint32_t i = 0; i < props.memoryTypeCount; i++)
        if ((bits & (1u << i)) && (props.memoryTypes[i].propertyFlags & flags) == flags) return i;
    return 0;
}

static int init_instance(int window)
{
    static const char *const surface_extensions[] = {VK_KHR_SURFACE_EXTENSION_NAME,
                                                     VK_KHR_WIN32_SURFACE_EXTENSION_NAME};
    VkApplicationInfo app = {.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO,
                             .pApplicationName = "vktest",
                             .apiVersion = VK_API_VERSION_1_1};
    VkInstanceCreateInfo info = {.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO, .pApplicationInfo = &app};
    VkExtensionProperties *props;
    uint32_t count = 0;
    HMODULE lib;
    VkResult res;

    lib = LoadLibraryA("vulkan-1.dll");
    check(lib != NULL, "LoadLibrary vulkan-1.dll (error %lu)", lib ? 0 : GetLastError());
    if (!lib) return 0;
    vkGetInstanceProcAddr = (PFN_vkGetInstanceProcAddr)(void *)GetProcAddress(lib, "vkGetInstanceProcAddr");
    if (!vkGetInstanceProcAddr) return missing("vkGetInstanceProcAddr");
    vkCreateInstance = (PFN_vkCreateInstance)vkGetInstanceProcAddr(NULL, "vkCreateInstance");
    vkEnumerateInstanceExtensionProperties =
        (PFN_vkEnumerateInstanceExtensionProperties)vkGetInstanceProcAddr(NULL, "vkEnumerateInstanceExtensionProperties");
    if (!vkCreateInstance || !vkEnumerateInstanceExtensionProperties) return missing("vkCreateInstance");

    vkEnumerateInstanceExtensionProperties(NULL, &count, NULL);
    props = calloc(count ? count : 1, sizeof(*props));
    vkEnumerateInstanceExtensionProperties(NULL, &count, props);
    note("%u instance extensions, VK_KHR_win32_surface: %s", count,
         has_extension(props, count, VK_KHR_WIN32_SURFACE_EXTENSION_NAME) ? "yes" : "no");
    if (window)
    {
        check(has_extension(props, count, VK_KHR_WIN32_SURFACE_EXTENSION_NAME), "VK_KHR_win32_surface is offered");
        info.enabledExtensionCount = 2;
        info.ppEnabledExtensionNames = surface_extensions;
    }
    free(props);

    res = vkCreateInstance(&info, NULL, &instance);
    check(!res, "vkCreateInstance res %d", res);
    if (res) return 0;

#define LOAD(name) \
    if (!(name = (PFN_##name)vkGetInstanceProcAddr(instance, #name))) return missing(#name);
    INSTANCE_FUNCS(LOAD)
    if (window) { SURFACE_FUNCS(LOAD) }
#undef LOAD
    return 1;
}

static int init_device(int window)
{
    static const char *const swapchain_extension[] = {VK_KHR_SWAPCHAIN_EXTENSION_NAME};
    VkPhysicalDeviceProperties props;
    VkQueueFamilyProperties families[16];
    VkExtensionProperties *extensions;
    uint32_t count = 1, family_count = 16;
    float priority = 1.0f;
    VkResult res;

    res = vkEnumeratePhysicalDevices(instance, &count, &gpu);
    check(res >= 0 && count >= 1, "vkEnumeratePhysicalDevices res %d count %u", res, count);
    if (res < 0 || !count) return 0;

    vkGetPhysicalDeviceProperties(gpu, &props);
    note("%s, Vulkan %u.%u.%u, vendor %#x device %#x", props.deviceName, VK_VERSION_MAJOR(props.apiVersion),
         VK_VERSION_MINOR(props.apiVersion), VK_VERSION_PATCH(props.apiVersion), props.vendorID, props.deviceID);

    count = 0;
    vkEnumerateDeviceExtensionProperties(gpu, NULL, &count, NULL);
    extensions = calloc(count ? count : 1, sizeof(*extensions));
    vkEnumerateDeviceExtensionProperties(gpu, NULL, &count, extensions);
    note("%u device extensions, VK_KHR_swapchain: %s", count,
         has_extension(extensions, count, VK_KHR_SWAPCHAIN_EXTENSION_NAME) ? "yes" : "no");
    if (window) check(has_extension(extensions, count, VK_KHR_SWAPCHAIN_EXTENSION_NAME), "VK_KHR_swapchain is offered");
    free(extensions);

    vkGetPhysicalDeviceQueueFamilyProperties(gpu, &family_count, families);
    queue_family = UINT32_MAX;
    for (uint32_t i = 0; i < family_count; i++)
        if (families[i].queueFlags & VK_QUEUE_GRAPHICS_BIT) { queue_family = i; break; }
    check(queue_family != UINT32_MAX, "graphics queue family %u of %u", queue_family, family_count);
    if (queue_family == UINT32_MAX) return 0;

    VkDeviceQueueCreateInfo queue_info = {.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO,
                                          .queueFamilyIndex = queue_family,
                                          .queueCount = 1,
                                          .pQueuePriorities = &priority};
    VkDeviceCreateInfo info = {.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO,
                               .queueCreateInfoCount = 1,
                               .pQueueCreateInfos = &queue_info,
                               .enabledExtensionCount = window ? 1 : 0,
                               .ppEnabledExtensionNames = swapchain_extension};
    res = vkCreateDevice(gpu, &info, NULL, &device);
    check(!res, "vkCreateDevice res %d", res);
    if (res) return 0;

#define LOAD(name) \
    if (!(name = (PFN_##name)vkGetDeviceProcAddr(device, #name))) return missing(#name);
    DEVICE_FUNCS(LOAD)
    if (window) { SWAPCHAIN_FUNCS(LOAD) }
#undef LOAD

    vkGetDeviceQueue(device, queue_family, 0, &queue);
    VkCommandPoolCreateInfo pool_info = {.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO,
                                         .flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT,
                                         .queueFamilyIndex = queue_family};
    res = vkCreateCommandPool(device, &pool_info, NULL, &pool);
    VkCommandBufferAllocateInfo cmd_info = {.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO,
                                            .commandPool = pool,
                                            .level = VK_COMMAND_BUFFER_LEVEL_PRIMARY,
                                            .commandBufferCount = 1};
    if (!res) res = vkAllocateCommandBuffers(device, &cmd_info, &cmd);

    VkShaderModuleCreateInfo vert_info = {.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO,
                                          .codeSize = sizeof(triangle_vert),
                                          .pCode = triangle_vert};
    VkShaderModuleCreateInfo frag_info = {.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO,
                                          .codeSize = sizeof(triangle_frag),
                                          .pCode = triangle_frag};
    if (!res) res = vkCreateShaderModule(device, &vert_info, NULL, &vert);
    if (!res) res = vkCreateShaderModule(device, &frag_info, NULL, &frag);

    VkPushConstantRange range = {VK_SHADER_STAGE_VERTEX_BIT, 0, sizeof(struct push)};
    VkPipelineLayoutCreateInfo layout_info = {.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO,
                                              .pushConstantRangeCount = 1,
                                              .pPushConstantRanges = &range};
    if (!res) res = vkCreatePipelineLayout(device, &layout_info, NULL, &layout);
    check(!res, "command pool, shader modules, pipeline layout res %d", res);
    return !res;
}

/* A render pass with one color attachment and the triangle pipeline for it. */
static int create_pipeline(VkFormat format, VkImageLayout final_layout, VkRenderPass *pass, VkPipeline *pipeline)
{
    VkAttachmentDescription attachment = {.format = format,
                                          .samples = VK_SAMPLE_COUNT_1_BIT,
                                          .loadOp = VK_ATTACHMENT_LOAD_OP_CLEAR,
                                          .storeOp = VK_ATTACHMENT_STORE_OP_STORE,
                                          .stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE,
                                          .stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE,
                                          .initialLayout = VK_IMAGE_LAYOUT_UNDEFINED,
                                          .finalLayout = final_layout};
    VkAttachmentReference reference = {0, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL};
    VkSubpassDescription subpass = {.pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS,
                                    .colorAttachmentCount = 1,
                                    .pColorAttachments = &reference};
    VkSubpassDependency dependency = {.srcSubpass = VK_SUBPASS_EXTERNAL,
                                      .dstSubpass = 0,
                                      .srcStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
                                      .dstStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
                                      .dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT};
    VkRenderPassCreateInfo pass_info = {.sType = VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO,
                                        .attachmentCount = 1,
                                        .pAttachments = &attachment,
                                        .subpassCount = 1,
                                        .pSubpasses = &subpass,
                                        .dependencyCount = 1,
                                        .pDependencies = &dependency};
    VkResult res = vkCreateRenderPass(device, &pass_info, NULL, pass);
    if (res) { check(0, "vkCreateRenderPass res %d", res); return 0; }

    VkPipelineShaderStageCreateInfo stages[2] = {
        {.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO,
         .stage = VK_SHADER_STAGE_VERTEX_BIT, .module = vert, .pName = "main"},
        {.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO,
         .stage = VK_SHADER_STAGE_FRAGMENT_BIT, .module = frag, .pName = "main"},
    };
    VkPipelineVertexInputStateCreateInfo vertex_input = {
        .sType = VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO};
    VkPipelineInputAssemblyStateCreateInfo assembly = {
        .sType = VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO,
        .topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST};
    VkPipelineViewportStateCreateInfo viewport = {.sType = VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO,
                                                  .viewportCount = 1,
                                                  .scissorCount = 1};
    VkPipelineRasterizationStateCreateInfo raster = {
        .sType = VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO,
        .polygonMode = VK_POLYGON_MODE_FILL,
        .cullMode = VK_CULL_MODE_NONE,
        .lineWidth = 1.0f};
    VkPipelineMultisampleStateCreateInfo multisample = {
        .sType = VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO,
        .rasterizationSamples = VK_SAMPLE_COUNT_1_BIT};
    VkPipelineColorBlendAttachmentState blend_attachment = {.colorWriteMask = 0xf};
    VkPipelineColorBlendStateCreateInfo blend = {.sType = VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO,
                                                 .attachmentCount = 1,
                                                 .pAttachments = &blend_attachment};
    VkDynamicState dynamic_states[] = {VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR};
    VkPipelineDynamicStateCreateInfo dynamic = {.sType = VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO,
                                                .dynamicStateCount = 2,
                                                .pDynamicStates = dynamic_states};
    VkGraphicsPipelineCreateInfo info = {.sType = VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO,
                                         .stageCount = 2,
                                         .pStages = stages,
                                         .pVertexInputState = &vertex_input,
                                         .pInputAssemblyState = &assembly,
                                         .pViewportState = &viewport,
                                         .pRasterizationState = &raster,
                                         .pMultisampleState = &multisample,
                                         .pColorBlendState = &blend,
                                         .pDynamicState = &dynamic,
                                         .layout = layout,
                                         .renderPass = *pass};
    res = vkCreateGraphicsPipelines(device, VK_NULL_HANDLE, 1, &info, NULL, pipeline);
    if (res) { check(0, "vkCreateGraphicsPipelines res %d", res); return 0; }
    return 1;
}

static void record_frame(VkRenderPass pass, VkPipeline pipeline, VkFramebuffer framebuffer, uint32_t width,
                         uint32_t height, const VkClearValue *clear, const struct push *push)
{
    VkCommandBufferBeginInfo begin = {.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
    VkRenderPassBeginInfo pass_begin = {.sType = VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO,
                                        .renderPass = pass,
                                        .framebuffer = framebuffer,
                                        .renderArea = {{0, 0}, {width, height}},
                                        .clearValueCount = 1,
                                        .pClearValues = clear};
    VkViewport viewport = {0, 0, (float)width, (float)height, 0, 1};
    VkRect2D scissor = {{0, 0}, {width, height}};

    vkBeginCommandBuffer(cmd, &begin);
    vkCmdBeginRenderPass(cmd, &pass_begin, VK_SUBPASS_CONTENTS_INLINE);
    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline);
    vkCmdSetViewport(cmd, 0, 1, &viewport);
    vkCmdSetScissor(cmd, 0, 1, &scissor);
    vkCmdPushConstants(cmd, layout, VK_SHADER_STAGE_VERTEX_BIT, 0, sizeof(*push), push);
    vkCmdDraw(cmd, 3, 1, 0, 0);
    vkCmdEndRenderPass(cmd);
}

static int run_offscreen(void)
{
    enum { SIZE = 256 };
    VkImage image = VK_NULL_HANDLE;
    VkDeviceMemory image_memory = VK_NULL_HANDLE, buffer_memory = VK_NULL_HANDLE;
    VkImageView view = VK_NULL_HANDLE;
    VkFramebuffer framebuffer = VK_NULL_HANDLE;
    VkBuffer buffer = VK_NULL_HANDLE;
    VkRenderPass pass = VK_NULL_HANDLE;
    VkPipeline pipeline = VK_NULL_HANDLE;
    VkFence fence = VK_NULL_HANDLE;
    VkMemoryRequirements reqs;
    VkResult res;
    void *data = NULL;

    VkImageCreateInfo image_info = {.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO,
                                    .imageType = VK_IMAGE_TYPE_2D,
                                    .format = VK_FORMAT_R8G8B8A8_UNORM,
                                    .extent = {SIZE, SIZE, 1},
                                    .mipLevels = 1,
                                    .arrayLayers = 1,
                                    .samples = VK_SAMPLE_COUNT_1_BIT,
                                    .tiling = VK_IMAGE_TILING_OPTIMAL,
                                    .usage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT};
    res = vkCreateImage(device, &image_info, NULL, &image);
    if (!res)
    {
        vkGetImageMemoryRequirements(device, image, &reqs);
        VkMemoryAllocateInfo alloc = {.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,
                                      .allocationSize = reqs.size,
                                      .memoryTypeIndex = memory_type(reqs.memoryTypeBits, 0)};
        res = vkAllocateMemory(device, &alloc, NULL, &image_memory);
    }
    if (!res) res = vkBindImageMemory(device, image, image_memory, 0);
    VkImageViewCreateInfo view_info = {.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO,
                                       .image = image,
                                       .viewType = VK_IMAGE_VIEW_TYPE_2D,
                                       .format = VK_FORMAT_R8G8B8A8_UNORM,
                                       .subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1}};
    if (!res) res = vkCreateImageView(device, &view_info, NULL, &view);
    check(!res, "color image %dx%d and view res %d", SIZE, SIZE, res);
    if (res) goto out;

    VkBufferCreateInfo buffer_info = {.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO,
                                      .size = SIZE * SIZE * 4,
                                      .usage = VK_BUFFER_USAGE_TRANSFER_DST_BIT};
    res = vkCreateBuffer(device, &buffer_info, NULL, &buffer);
    if (!res)
    {
        vkGetBufferMemoryRequirements(device, buffer, &reqs);
        VkMemoryAllocateInfo alloc = {
            .sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,
            .allocationSize = reqs.size,
            .memoryTypeIndex = memory_type(reqs.memoryTypeBits, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT |
                                                                    VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)};
        res = vkAllocateMemory(device, &alloc, NULL, &buffer_memory);
    }
    if (!res) res = vkBindBufferMemory(device, buffer, buffer_memory, 0);
    /* Mapping is where a 32-bit process differs most: host memory must land below 4 GiB. */
    if (!res) res = vkMapMemory(device, buffer_memory, 0, VK_WHOLE_SIZE, 0, &data);
    check(!res && data, "readback buffer mapped at %p res %d", data, res);
    if (res) goto out;

    if (!create_pipeline(VK_FORMAT_R8G8B8A8_UNORM, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, &pass, &pipeline)) goto out;
    check(1, "render pass and graphics pipeline (SPIR-V compiled by the driver)");

    VkFramebufferCreateInfo framebuffer_info = {.sType = VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO,
                                                .renderPass = pass,
                                                .attachmentCount = 1,
                                                .pAttachments = &view,
                                                .width = SIZE,
                                                .height = SIZE,
                                                .layers = 1};
    res = vkCreateFramebuffer(device, &framebuffer_info, NULL, &framebuffer);
    VkFenceCreateInfo fence_info = {.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
    if (!res) res = vkCreateFence(device, &fence_info, NULL, &fence);
    if (res) { check(0, "framebuffer / fence res %d", res); goto out; }

    VkClearValue clear = {.color = {.float32 = {0.0f, 0.0f, 1.0f, 1.0f}}};
    struct push push = {0.0f, 1.0f, 0.0f};
    record_frame(pass, pipeline, framebuffer, SIZE, SIZE, &clear, &push);
    VkBufferImageCopy copy = {.imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1},
                              .imageExtent = {SIZE, SIZE, 1}};
    vkCmdCopyImageToBuffer(cmd, image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, buffer, 1, &copy);
    vkEndCommandBuffer(cmd);

    VkSubmitInfo submit = {.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO, .commandBufferCount = 1, .pCommandBuffers = &cmd};
    res = vkQueueSubmit(queue, 1, &submit, fence);
    if (!res) res = vkWaitForFences(device, 1, &fence, VK_TRUE, 5000000000ull);
    check(!res, "submit and wait res %d", res);
    if (res) goto out;

    {
        const uint8_t *pixels = data;
        const uint8_t *corner = pixels, *center = pixels + (SIZE / 2 * SIZE + SIZE / 2) * 4;
        unsigned inside = 0;

        for (unsigned i = 0; i < SIZE * SIZE; i++)
            if (pixels[i * 4 + 1] == 0xff && !pixels[i * 4 + 2]) inside++;
        check(corner[0] == 0 && corner[1] == 0 && corner[2] == 0xff && corner[3] == 0xff,
              "corner is the clear color: %02x %02x %02x %02x", corner[0], corner[1], corner[2], corner[3]);
        check(center[0] == 0 && center[1] == 0xff && center[2] == 0 && center[3] == 0xff,
              "center is the triangle: %02x %02x %02x %02x", center[0], center[1], center[2], center[3]);
        /* Base 1.4 and height 1.4 in clip space: area 0.98 of the 4 the image spans, 16056 of its pixels. */
        check(inside > 15500 && inside < 16600, "triangle covers %u pixels (expected about 16056)", inside);
    }

out:
    if (device) vkDeviceWaitIdle(device);
    if (fence) vkDestroyFence(device, fence, NULL);
    if (framebuffer) vkDestroyFramebuffer(device, framebuffer, NULL);
    if (pipeline) vkDestroyPipeline(device, pipeline, NULL);
    if (pass) vkDestroyRenderPass(device, pass, NULL);
    if (data) vkUnmapMemory(device, buffer_memory);
    if (buffer) vkDestroyBuffer(device, buffer, NULL);
    if (buffer_memory) vkFreeMemory(device, buffer_memory, NULL);
    if (view) vkDestroyImageView(device, view, NULL);
    if (image) vkDestroyImage(device, image, NULL);
    if (image_memory) vkFreeMemory(device, image_memory, NULL);
    return !failures;
}

/* On screen. */

static int closed;

static LRESULT CALLBACK window_proc(HWND hwnd, UINT msg, WPARAM wparam, LPARAM lparam)
{
    switch (msg)
    {
    case WM_CLOSE:
    case WM_DESTROY:
        closed = 1;
        return 0;
    case WM_KEYDOWN:
        if (wparam == VK_ESCAPE) closed = 1;
        return 0;
    }
    return DefWindowProcA(hwnd, msg, wparam, lparam);
}

struct swapchain
{
    VkSwapchainKHR handle;
    VkFormat format;
    VkExtent2D extent;
    uint32_t count;
    VkImage images[16];
    VkImageView views[16];
    VkFramebuffer framebuffers[16];
};

static const char *mode_name(VkPresentModeKHR mode)
{
    switch (mode)
    {
    case VK_PRESENT_MODE_IMMEDIATE_KHR: return "immediate";
    case VK_PRESENT_MODE_MAILBOX_KHR: return "mailbox";
    case VK_PRESENT_MODE_FIFO_KHR: return "fifo";
    case VK_PRESENT_MODE_FIFO_RELAXED_KHR: return "fifo_relaxed";
    default: return "other";
    }
}

static void destroy_swapchain_views(struct swapchain *sc)
{
    for (uint32_t i = 0; i < sc->count; i++)
    {
        if (sc->framebuffers[i]) vkDestroyFramebuffer(device, sc->framebuffers[i], NULL);
        if (sc->views[i]) vkDestroyImageView(device, sc->views[i], NULL);
        sc->framebuffers[i] = VK_NULL_HANDLE;
        sc->views[i] = VK_NULL_HANDLE;
    }
    sc->count = 0;
}

/* (Re)creates the swapchain for the surface's current size. 1 ok, 0 failed, -1 nothing to draw (zero size). */
static int create_swapchain(VkSurfaceKHR surface, VkSurfaceFormatKHR format, VkPresentModeKHR mode,
                            VkRenderPass pass, struct swapchain *sc, int verbose)
{
    VkSurfaceCapabilitiesKHR caps;
    VkSwapchainKHR old = sc->handle;
    VkResult res;

    res = vkGetPhysicalDeviceSurfaceCapabilitiesKHR(gpu, surface, &caps);
    if (res) { check(0, "vkGetPhysicalDeviceSurfaceCapabilitiesKHR res %d", res); return 0; }
    if (verbose)
        note("surface: extent %ux%u (min %ux%u max %ux%u), images %u..%u, transform %#x, alpha %#x, usage %#x",
             caps.currentExtent.width, caps.currentExtent.height, caps.minImageExtent.width,
             caps.minImageExtent.height, caps.maxImageExtent.width, caps.maxImageExtent.height, caps.minImageCount,
             caps.maxImageCount, caps.currentTransform, caps.supportedCompositeAlpha, caps.supportedUsageFlags);
    if (!caps.currentExtent.width || !caps.currentExtent.height) return -1;

    vkDeviceWaitIdle(device);
    destroy_swapchain_views(sc);

    VkSwapchainCreateInfoKHR info = {
        .sType = VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR,
        .surface = surface,
        .minImageCount = caps.minImageCount + 1,
        .imageFormat = format.format,
        .imageColorSpace = format.colorSpace,
        .imageExtent = caps.currentExtent,
        .imageArrayLayers = 1,
        .imageUsage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT,
        .imageSharingMode = VK_SHARING_MODE_EXCLUSIVE,
        .preTransform = caps.currentTransform,
        .compositeAlpha = (caps.supportedCompositeAlpha & VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR)
                              ? VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR
                              : VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR,
        .presentMode = mode,
        .clipped = VK_TRUE,
        .oldSwapchain = old,
    };
    if (caps.maxImageCount && info.minImageCount > caps.maxImageCount) info.minImageCount = caps.maxImageCount;
    res = vkCreateSwapchainKHR(device, &info, NULL, &sc->handle);
    if (old) vkDestroySwapchainKHR(device, old, NULL);
    if (res) { sc->handle = VK_NULL_HANDLE; check(0, "vkCreateSwapchainKHR res %d", res); return 0; }

    sc->format = format.format;
    sc->extent = caps.currentExtent;
    sc->count = 16;
    res = vkGetSwapchainImagesKHR(device, sc->handle, &sc->count, sc->images);
    if (res < 0) { sc->count = 0; check(0, "vkGetSwapchainImagesKHR res %d", res); return 0; }

    for (uint32_t i = 0; i < sc->count; i++)
    {
        VkImageViewCreateInfo view_info = {.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO,
                                           .image = sc->images[i],
                                           .viewType = VK_IMAGE_VIEW_TYPE_2D,
                                           .format = format.format,
                                           .subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1}};
        res = vkCreateImageView(device, &view_info, NULL, &sc->views[i]);
        VkFramebufferCreateInfo framebuffer_info = {.sType = VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO,
                                                    .renderPass = pass,
                                                    .attachmentCount = 1,
                                                    .pAttachments = &sc->views[i],
                                                    .width = sc->extent.width,
                                                    .height = sc->extent.height,
                                                    .layers = 1};
        if (!res) res = vkCreateFramebuffer(device, &framebuffer_info, NULL, &sc->framebuffers[i]);
        if (res) { check(0, "swapchain image %u view / framebuffer res %d", i, res); return 0; }
    }
    if (verbose)
        check(1, "swapchain %ux%u, %u images, format %d, %s", sc->extent.width, sc->extent.height, sc->count,
              sc->format, mode_name(mode));
    return 1;
}

static int run_window(uint32_t frames, const char *want_mode, int width, int height)
{
    WNDCLASSA wc = {.lpfnWndProc = window_proc,
                    .hInstance = GetModuleHandleA(NULL),
                    .hCursor = LoadCursorA(NULL, (const char *)IDC_ARROW),
                    .lpszClassName = "vktest"};
    RECT rect = {0, 0, width, height};
    VkSurfaceKHR surface = VK_NULL_HANDLE;
    VkSurfaceFormatKHR formats[64], format;
    VkPresentModeKHR modes[16], mode = VK_PRESENT_MODE_FIFO_KHR;
    VkRenderPass pass = VK_NULL_HANDLE;
    VkPipeline pipeline = VK_NULL_HANDLE;
    VkSemaphore acquired = VK_NULL_HANDLE, rendered = VK_NULL_HANDLE;
    VkFence fence = VK_NULL_HANDLE;
    struct swapchain sc = {0};
    uint32_t count, done = 0, recreated = 0;
    LARGE_INTEGER freq, start, now, last;
    VkBool32 supported = VK_FALSE;
    char title[128];
    VkResult res;
    HWND hwnd;
    int ret;

    RegisterClassA(&wc);
    AdjustWindowRect(&rect, WS_OVERLAPPEDWINDOW, FALSE);
    hwnd = CreateWindowExA(0, "vktest", "vktest", WS_OVERLAPPEDWINDOW | WS_VISIBLE, 60, 40, rect.right - rect.left,
                           rect.bottom - rect.top, NULL, NULL, wc.hInstance, NULL);
    check(hwnd != NULL, "window %dx%d client (error %lu)", width, height, hwnd ? 0 : GetLastError());
    if (!hwnd) return 0;

    VkWin32SurfaceCreateInfoKHR surface_info = {.sType = VK_STRUCTURE_TYPE_WIN32_SURFACE_CREATE_INFO_KHR,
                                                .hinstance = wc.hInstance,
                                                .hwnd = hwnd};
    res = vkCreateWin32SurfaceKHR(instance, &surface_info, NULL, &surface);
    check(!res, "vkCreateWin32SurfaceKHR res %d", res);
    if (res) return 0;

    res = vkGetPhysicalDeviceSurfaceSupportKHR(gpu, queue_family, surface, &supported);
    check(!res && supported, "queue family %u can present res %d", queue_family, res);

    count = 64;
    res = vkGetPhysicalDeviceSurfaceFormatsKHR(gpu, surface, &count, formats);
    check(res >= 0 && count, "surface formats res %d count %u", res, count);
    if (res < 0 || !count) goto out;
    format = formats[0];
    for (uint32_t i = 0; i < count; i++)
    {
        note("  format %d color space %d", formats[i].format, formats[i].colorSpace);
        if (formats[i].format == VK_FORMAT_B8G8R8A8_UNORM || formats[i].format == VK_FORMAT_R8G8B8A8_UNORM)
            if (format.format != VK_FORMAT_B8G8R8A8_UNORM && format.format != VK_FORMAT_R8G8B8A8_UNORM)
                format = formats[i];
    }

    count = 16;
    res = vkGetPhysicalDeviceSurfacePresentModesKHR(gpu, surface, &count, modes);
    check(res >= 0 && count, "present modes res %d count %u", res, count);
    if (res < 0 || !count) goto out;
    for (uint32_t i = 0; i < count; i++)
    {
        note("  present mode %s", mode_name(modes[i]));
        if (want_mode && !strcmp(want_mode, mode_name(modes[i]))) mode = modes[i];
    }
    if (want_mode) check(!strcmp(want_mode, mode_name(mode)), "present mode %s is offered", want_mode);

    if (!create_pipeline(format.format, VK_IMAGE_LAYOUT_PRESENT_SRC_KHR, &pass, &pipeline)) goto out;
    ret = create_swapchain(surface, format, mode, pass, &sc, 1);
    if (!ret) goto out;

    VkSemaphoreCreateInfo semaphore_info = {.sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};
    VkFenceCreateInfo fence_info = {.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
    vkCreateSemaphore(device, &semaphore_info, NULL, &acquired);
    vkCreateSemaphore(device, &semaphore_info, NULL, &rendered);
    vkCreateFence(device, &fence_info, NULL, &fence);

    QueryPerformanceFrequency(&freq);
    QueryPerformanceCounter(&start);
    last = start;
    while (!closed && (!frames || done < frames))
    {
        uint32_t index = 0, since;
        MSG msg;

        while (PeekMessageA(&msg, NULL, 0, 0, PM_REMOVE))
        {
            TranslateMessage(&msg);
            DispatchMessageA(&msg);
        }
        if (closed) break;
        if (ret < 0)
        {
            /* Minimized: nothing to draw into. */
            Sleep(50);
            ret = create_swapchain(surface, format, mode, pass, &sc, 0);
            if (!ret) break;
            continue;
        }

        res = vkAcquireNextImageKHR(device, sc.handle, 2000000000ull, acquired, VK_NULL_HANDLE, &index);
        if (res == VK_ERROR_OUT_OF_DATE_KHR)
        {
            recreated++;
            if (!(ret = create_swapchain(surface, format, mode, pass, &sc, 0))) break;
            continue;
        }
        if (res != VK_SUCCESS && res != VK_SUBOPTIMAL_KHR)
        {
            check(0, "vkAcquireNextImageKHR res %d at frame %u", res, done);
            break;
        }

        QueryPerformanceCounter(&now);
        double t = (double)(now.QuadPart - start.QuadPart) / (double)freq.QuadPart;
        VkClearValue clear = {.color = {.float32 = {0.05f, 0.05f, 0.12f, 1.0f}}};
        struct push push = {(float)t * 1.5f, (float)sc.extent.height / (float)sc.extent.width, 1.0f};
        record_frame(pass, pipeline, sc.framebuffers[index], sc.extent.width, sc.extent.height, &clear, &push);
        vkEndCommandBuffer(cmd);

        VkPipelineStageFlags stage = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
        VkSubmitInfo submit = {.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO,
                               .waitSemaphoreCount = 1,
                               .pWaitSemaphores = &acquired,
                               .pWaitDstStageMask = &stage,
                               .commandBufferCount = 1,
                               .pCommandBuffers = &cmd,
                               .signalSemaphoreCount = 1,
                               .pSignalSemaphores = &rendered};
        res = vkQueueSubmit(queue, 1, &submit, fence);
        if (res) { check(0, "vkQueueSubmit res %d at frame %u", res, done); break; }

        VkPresentInfoKHR present = {.sType = VK_STRUCTURE_TYPE_PRESENT_INFO_KHR,
                                    .waitSemaphoreCount = 1,
                                    .pWaitSemaphores = &rendered,
                                    .swapchainCount = 1,
                                    .pSwapchains = &sc.handle,
                                    .pImageIndices = &index};
        res = vkQueuePresentKHR(queue, &present);
        /* The command buffer is reused next frame. */
        vkWaitForFences(device, 1, &fence, VK_TRUE, 5000000000ull);
        vkResetFences(device, 1, &fence);
        if (res == VK_ERROR_OUT_OF_DATE_KHR || res == VK_SUBOPTIMAL_KHR)
        {
            recreated++;
            if (!(ret = create_swapchain(surface, format, mode, pass, &sc, 0))) break;
        }
        else if (res)
        {
            check(0, "vkQueuePresentKHR res %d at frame %u", res, done);
            break;
        }
        done++;

        since = (uint32_t)((now.QuadPart - last.QuadPart) * 1000 / freq.QuadPart);
        if (since >= 1000)
        {
            snprintf(title, sizeof(title), "vktest (%s) %ux%u %s frame %u", arch(), sc.extent.width,
                     sc.extent.height, mode_name(mode), done);
            SetWindowTextA(hwnd, title);
            last = now;
        }
    }

    QueryPerformanceCounter(&now);
    {
        double seconds = (double)(now.QuadPart - start.QuadPart) / (double)freq.QuadPart;
        check(done > 0 && (!frames || done == frames || closed), "%u frames in %.2f s = %.1f fps (%s), swapchain recreated %u times",
              done, seconds, seconds > 0 ? done / seconds : 0.0, mode_name(mode), recreated);
    }

out:
    if (device) vkDeviceWaitIdle(device);
    if (fence) vkDestroyFence(device, fence, NULL);
    if (acquired) vkDestroySemaphore(device, acquired, NULL);
    if (rendered) vkDestroySemaphore(device, rendered, NULL);
    destroy_swapchain_views(&sc);
    if (sc.handle) vkDestroySwapchainKHR(device, sc.handle, NULL);
    if (pipeline) vkDestroyPipeline(device, pipeline, NULL);
    if (pass) vkDestroyRenderPass(device, pass, NULL);
    if (surface) vkDestroySurfaceKHR(instance, surface, NULL);
    DestroyWindow(hwnd);
    return !failures;
}

int main(int argc, char **argv)
{
    int quiet = 0, window = 0, width = 640, height = 480;
    uint32_t frames = 600;
    const char *mode = NULL;

    for (int i = 1; i < argc; i++)
    {
        if (!strcmp(argv[i], "-q")) quiet = 1;
        else if (!strcmp(argv[i], "--window")) window = 1;
        else if (!strcmp(argv[i], "--frames") && i + 1 < argc) frames = (uint32_t)atoi(argv[++i]);
        else if (!strcmp(argv[i], "--mode") && i + 1 < argc) mode = argv[++i];
        else if (!strcmp(argv[i], "--size") && i + 1 < argc) sscanf(argv[++i], "%dx%d", &width, &height);
    }

    note("vktest %s, %s", arch(), window ? "on screen" : "off screen");
    if (init_instance(window) && init_device(window))
    {
        if (window) run_window(frames, mode, width, height);
        else run_offscreen();
    }

    if (device)
    {
        vkDeviceWaitIdle(device);
        if (layout) vkDestroyPipelineLayout(device, layout, NULL);
        if (vert) vkDestroyShaderModule(device, vert, NULL);
        if (frag) vkDestroyShaderModule(device, frag, NULL);
        if (pool) vkDestroyCommandPool(device, pool, NULL);
        vkDestroyDevice(device, NULL);
    }
    if (instance) vkDestroyInstance(instance, NULL);

    note("%s (%d failed)", failures ? "FAIL" : "PASS", failures);
    if (!quiet && !window)
        MessageBoxA(NULL, report, failures ? "vktest: FAIL" : "vktest: PASS", MB_OK | (failures ? MB_ICONERROR : MB_ICONINFORMATION));
    return failures ? 1 : 0;
}
