/*
 * amphora-vkprobe: guest-side Vulkan capability probe.
 *
 * Runs inside Wine (box64 + winevulkan -> VK_LAYER_AMPHORA_wsi -> wrapper /
 * Turnip / System ICD) and dumps, as one JSON object between
 * VKPROBE_JSON_BEGIN / VKPROBE_JSON_END markers, exactly what a DXVK adapter
 * check can observe. stdout is freopen'd to RESULT_PATH inside the prefix
 * because guest stdout never reaches the unix fd in a headless launch:
 *
 *   - vkCreateInstance result with DXVK's WSI extension list
 *     (VK_KHR_surface + VK_KHR_win32_surface) and, if that fails, with no
 *     extensions;
 *   - vkEnumeratePhysicalDevices;
 *   - vkGetPhysicalDeviceProperties2 (driver properties in the pNext chain);
 *   - vkGetPhysicalDeviceFeatures2 with the pNext chain DXVK queries
 *     (core vk11-vk14 + every extension feature struct in its feature list,
 *     each chained only when the extension is advertised, matching
 *     DxvkDeviceCapabilities::initSupportedFeatures);
 *   - vkEnumerateDeviceExtensionProperties;
 *   - vkGetPhysicalDeviceQueueFamilyProperties;
 *   - VkPhysicalDeviceLimits.
 *
 * vulkan-1.dll is resolved through LoadLibrary/GetProcAddress so the binary
 * needs no import library at build time. Build with scripts/guest-vkprobe/
 * build.sh (mingw-w64 + Vulkan headers).
 */

#define _CRT_SECURE_NO_WARNINGS
#include <stddef.h>
#include <stdio.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <windows.h>
#include <vulkan/vulkan.h>

static PFN_vkGetInstanceProcAddr gipa;

/* Result file, inside the container prefix. GuestVulkanProbe reads
 * <prefix>/drive_c/amphora-vkprobe.json while the session runs. */
#define RESULT_PATH "C:\\amphora-vkprobe.json"

/* --then handshake: the host drops this file once the final (or provisional)
 * DXVK links are in place. Bounded wait — missing go still launches. */
#define GO_PATH "C:\\amphora-vkprobe.go"
#define GO_TIMEOUT_MS 20000
#define GO_POLL_MS 200

#define VKFUNC(name) static PFN_##name p##name
VKFUNC(vkCreateInstance);
VKFUNC(vkEnumerateInstanceExtensionProperties);
VKFUNC(vkEnumeratePhysicalDevices);
VKFUNC(vkGetPhysicalDeviceProperties2);
VKFUNC(vkGetPhysicalDeviceFeatures2);
VKFUNC(vkGetPhysicalDeviceQueueFamilyProperties);
VKFUNC(vkEnumerateDeviceExtensionProperties);
#undef VKFUNC

static void load_core(VkInstance inst)
{
#define L(n) p##n = (PFN_##n)gipa(inst, #n)
    L(vkEnumerateInstanceExtensionProperties);
    L(vkEnumeratePhysicalDevices);
    L(vkGetPhysicalDeviceProperties2);
    L(vkGetPhysicalDeviceFeatures2);
    L(vkGetPhysicalDeviceQueueFamilyProperties);
    L(vkEnumerateDeviceExtensionProperties);
#undef L
}

/* ---------- tiny JSON emitter ---------- */

static void js_str(const char *s)
{
    putchar('"');
    for (; *s; s++) {
        unsigned char c = (unsigned char)*s;
        if (c == '"' || c == '\\') { putchar('\\'); putchar(c); }
        else if (c >= 0x20 && c < 0x7f) putchar(c);
        else printf("\\u%04x", c);
    }
    putchar('"');
}

static void js_ver(uint32_t v)
{
    printf("\"%u.%u.%u\"", VK_VERSION_MAJOR(v), VK_VERSION_MINOR(v), VK_VERSION_PATCH(v));
}

/* ---------- feature-struct emission ---------- */

typedef struct {
    const char *name;
    uint32_t offset; /* byte offset of a VkBool32 field */
} FieldDef;

typedef struct {
    const char *tag;
    const void *s;
    const FieldDef *fields;
    uint32_t nfields;
} StructDef;

#define FLDF(s, f) { #f, (uint32_t)offsetof(s, f) }
#define COUNT(a) (sizeof(a) / sizeof((a)[0]))

static void emit_fields(const StructDef *d)
{
    uint32_t i;
    js_str(d->tag);
    fputs(":{", stdout);
    for (i = 0; i < d->nfields; i++) {
        const FieldDef *f = &d->fields[i];
        VkBool32 v = *(const VkBool32 *)((const char *)d->s + f->offset);
        if (i) putchar(',');
        js_str(f->name);
        printf(":%u", v ? 1u : 0u);
    }
    putchar('}');
}

/*
 * Extension feature structs are chained only when the extension is
 * advertised, exactly like DxvkDeviceCapabilities::initSupportedFeatures
 * (unsupported-extension structs would read back zero anyway, but gating
 * keeps the query itself legal on drivers that reject unknown sTypes).
 * Returns 1 when chained.
 */
static int has_ext(const VkExtensionProperties *exts, uint32_t n, const char *name)
{
    uint32_t i;
    for (i = 0; i < n; i++)
        if (!strncmp(exts[i].extensionName, name, VK_MAX_EXTENSION_NAME_SIZE))
            return 1;
    return 0;
}

#define CHAIN_ST(dst, stype) do { \
        memset(&dst, 0, sizeof(dst)); \
        dst.sType = stype; \
        dst.pNext = chain; \
        chain = &dst; \
    } while (0)

/* field tables, one per struct tag */
#define F_VK11(s) \
    FLDF(s, storageBuffer16BitAccess), FLDF(s, uniformAndStorageBuffer16BitAccess), \
    FLDF(s, storagePushConstant16), FLDF(s, storageInputOutput16), \
    FLDF(s, multiview), FLDF(s, multiviewGeometryShader), \
    FLDF(s, multiviewTessellationShader), FLDF(s, variablePointersStorageBuffer), \
    FLDF(s, variablePointers), FLDF(s, protectedMemory), \
    FLDF(s, samplerYcbcrConversion), FLDF(s, shaderDrawParameters)

#define F_VK12(s) \
    FLDF(s, samplerMirrorClampToEdge), FLDF(s, drawIndirectCount), \
    FLDF(s, storageBuffer8BitAccess), FLDF(s, uniformAndStorageBuffer8BitAccess), \
    FLDF(s, storagePushConstant8), FLDF(s, shaderBufferInt64Atomics), \
    FLDF(s, shaderSharedInt64Atomics), FLDF(s, shaderFloat16), FLDF(s, shaderInt8), \
    FLDF(s, descriptorIndexing), FLDF(s, shaderInputAttachmentArrayDynamicIndexing), \
    FLDF(s, shaderUniformTexelBufferArrayDynamicIndexing), \
    FLDF(s, shaderStorageTexelBufferArrayDynamicIndexing), \
    FLDF(s, shaderUniformBufferArrayNonUniformIndexing), \
    FLDF(s, shaderSampledImageArrayNonUniformIndexing), \
    FLDF(s, shaderStorageBufferArrayNonUniformIndexing), \
    FLDF(s, shaderStorageImageArrayNonUniformIndexing), \
    FLDF(s, shaderInputAttachmentArrayNonUniformIndexing), \
    FLDF(s, shaderUniformTexelBufferArrayNonUniformIndexing), \
    FLDF(s, shaderStorageTexelBufferArrayNonUniformIndexing), \
    FLDF(s, descriptorBindingUniformBufferUpdateAfterBind), \
    FLDF(s, descriptorBindingSampledImageUpdateAfterBind), \
    FLDF(s, descriptorBindingStorageImageUpdateAfterBind), \
    FLDF(s, descriptorBindingStorageBufferUpdateAfterBind), \
    FLDF(s, descriptorBindingUniformTexelBufferUpdateAfterBind), \
    FLDF(s, descriptorBindingStorageTexelBufferUpdateAfterBind), \
    FLDF(s, descriptorBindingUpdateUnusedWhilePending), \
    FLDF(s, descriptorBindingPartiallyBound), \
    FLDF(s, descriptorBindingVariableDescriptorCount), \
    FLDF(s, runtimeDescriptorArray), FLDF(s, samplerFilterMinmax), \
    FLDF(s, scalarBlockLayout), FLDF(s, imagelessFramebuffer), \
    FLDF(s, uniformBufferStandardLayout), FLDF(s, shaderSubgroupExtendedTypes), \
    FLDF(s, separateDepthStencilLayouts), FLDF(s, hostQueryReset), \
    FLDF(s, timelineSemaphore), FLDF(s, bufferDeviceAddress), \
    FLDF(s, bufferDeviceAddressCaptureReplay), \
    FLDF(s, bufferDeviceAddressMultiDevice), \
    FLDF(s, vulkanMemoryModel), FLDF(s, vulkanMemoryModelDeviceScope), \
    FLDF(s, vulkanMemoryModelAvailabilityVisibilityChains), \
    FLDF(s, shaderOutputViewportIndex), FLDF(s, shaderOutputLayer), \
    FLDF(s, subgroupBroadcastDynamicId)

#define F_VK13(s) \
    FLDF(s, robustImageAccess), FLDF(s, inlineUniformBlock), \
    FLDF(s, descriptorBindingInlineUniformBlockUpdateAfterBind), \
    FLDF(s, pipelineCreationCacheControl), FLDF(s, privateData), \
    FLDF(s, shaderDemoteToHelperInvocation), FLDF(s, shaderTerminateInvocation), \
    FLDF(s, subgroupSizeControl), FLDF(s, computeFullSubgroups), \
    FLDF(s, synchronization2), FLDF(s, textureCompressionASTC_HDR), \
    FLDF(s, shaderZeroInitializeWorkgroupMemory), FLDF(s, dynamicRendering), \
    FLDF(s, shaderIntegerDotProduct), FLDF(s, maintenance4)

#define F_VK14(s) \
    FLDF(s, globalPriorityQuery), FLDF(s, shaderSubgroupRotate), \
    FLDF(s, shaderSubgroupRotateClustered), FLDF(s, shaderFloatControls2), \
    FLDF(s, shaderExpectAssume), FLDF(s, rectangularLines), \
    FLDF(s, bresenhamLines), FLDF(s, smoothLines), FLDF(s, stippledRectangularLines), \
    FLDF(s, stippledBresenhamLines), FLDF(s, stippledSmoothLines), \
    FLDF(s, vertexAttributeInstanceRateDivisor), \
    FLDF(s, vertexAttributeInstanceRateZeroDivisor), \
    FLDF(s, indexTypeUint8), FLDF(s, dynamicRenderingLocalRead), \
    FLDF(s, maintenance5), FLDF(s, maintenance6), FLDF(s, pipelineProtectedAccess), \
    FLDF(s, pipelineRobustness), FLDF(s, hostImageCopy), \
    FLDF(s, pushDescriptor)

#define F_CORE(s) \
    FLDF(s, robustBufferAccess), FLDF(s, fullDrawIndexUint32), \
    FLDF(s, imageCubeArray), FLDF(s, independentBlend), FLDF(s, geometryShader), \
    FLDF(s, tessellationShader), FLDF(s, sampleRateShading), FLDF(s, dualSrcBlend), \
    FLDF(s, logicOp), FLDF(s, multiDrawIndirect), FLDF(s, drawIndirectFirstInstance), \
    FLDF(s, depthClamp), FLDF(s, depthBiasClamp), FLDF(s, fillModeNonSolid), \
    FLDF(s, depthBounds), FLDF(s, wideLines), FLDF(s, largePoints), \
    FLDF(s, alphaToOne), FLDF(s, multiViewport), FLDF(s, samplerAnisotropy), \
    FLDF(s, textureCompressionETC2), FLDF(s, textureCompressionASTC_LDR), \
    FLDF(s, textureCompressionBC), FLDF(s, occlusionQueryPrecise), \
    FLDF(s, pipelineStatisticsQuery), FLDF(s, vertexPipelineStoresAndAtomics), \
    FLDF(s, fragmentStoresAndAtomics), FLDF(s, shaderTessellationAndGeometryPointSize), \
    FLDF(s, shaderImageGatherExtended), FLDF(s, shaderStorageImageExtendedFormats), \
    FLDF(s, shaderStorageImageMultisample), FLDF(s, shaderStorageImageReadWithoutFormat), \
    FLDF(s, shaderStorageImageWriteWithoutFormat), \
    FLDF(s, shaderUniformBufferArrayDynamicIndexing), \
    FLDF(s, shaderSampledImageArrayDynamicIndexing), \
    FLDF(s, shaderStorageBufferArrayDynamicIndexing), \
    FLDF(s, shaderStorageImageArrayDynamicIndexing), FLDF(s, shaderClipDistance), \
    FLDF(s, shaderCullDistance), FLDF(s, shaderFloat64), FLDF(s, shaderInt64), \
    FLDF(s, shaderInt16), FLDF(s, shaderResourceResidency), \
    FLDF(s, shaderResourceMinLod), FLDF(s, sparseBinding), \
    FLDF(s, sparseResidencyBuffer), FLDF(s, sparseResidencyImage2D), \
    FLDF(s, sparseResidencyImage3D), FLDF(s, sparseResidency2Samples), \
    FLDF(s, sparseResidency4Samples), FLDF(s, sparseResidency8Samples), \
    FLDF(s, sparseResidency16Samples), FLDF(s, sparseResidencyAliased), \
    FLDF(s, variableMultisampleRate), FLDF(s, inheritedQueries)

#define F_AFLL(s) FLDF(s, attachmentFeedbackLoopLayout)
#define F_BCS(s) FLDF(s, borderColorSwizzle), FLDF(s, borderColorSwizzleFromImage)
#define F_CBC(s) FLDF(s, customBorderColors), FLDF(s, customBorderColorWithoutFormat)
#define F_DCE(s) FLDF(s, depthClipEnable)
#define F_DBC(s) FLDF(s, depthBiasControl), \
    FLDF(s, leastRepresentableValueForceUnormRepresentation), \
    FLDF(s, floatRepresentation), FLDF(s, depthBiasExact)
#define F_DBUF(s) FLDF(s, descriptorBuffer), FLDF(s, descriptorBufferCaptureReplay), \
    FLDF(s, descriptorBufferImageLayoutIgnored), \
    FLDF(s, descriptorBufferPushDescriptors)
#define F_DHEAP(s) FLDF(s, descriptorHeap), FLDF(s, descriptorHeapCaptureReplay)
#define F_DRUA(s) FLDF(s, dynamicRenderingUnusedAttachments)
#define F_EDS3(s) FLDF(s, extendedDynamicState3TessellationDomainOrigin), \
    FLDF(s, extendedDynamicState3DepthClampEnable), \
    FLDF(s, extendedDynamicState3PolygonMode), \
    FLDF(s, extendedDynamicState3RasterizationSamples), \
    FLDF(s, extendedDynamicState3SampleMask), \
    FLDF(s, extendedDynamicState3AlphaToCoverageEnable), \
    FLDF(s, extendedDynamicState3AlphaToOneEnable), \
    FLDF(s, extendedDynamicState3LogicOpEnable), \
    FLDF(s, extendedDynamicState3ColorBlendEnable), \
    FLDF(s, extendedDynamicState3ColorBlendEquation), \
    FLDF(s, extendedDynamicState3ColorWriteMask), \
    FLDF(s, extendedDynamicState3RasterizationStream), \
    FLDF(s, extendedDynamicState3ConservativeRasterizationMode), \
    FLDF(s, extendedDynamicState3ExtraPrimitiveOverestimationSize), \
    FLDF(s, extendedDynamicState3DepthClipEnable), \
    FLDF(s, extendedDynamicState3SampleLocationsEnable), \
    FLDF(s, extendedDynamicState3ColorBlendAdvanced), \
    FLDF(s, extendedDynamicState3ProvokingVertexMode), \
    FLDF(s, extendedDynamicState3LineRasterizationMode), \
    FLDF(s, extendedDynamicState3LineStippleEnable), \
    FLDF(s, extendedDynamicState3DepthClipNegativeOneToOne), \
    FLDF(s, extendedDynamicState3ViewportWScalingEnable), \
    FLDF(s, extendedDynamicState3ViewportSwizzle), \
    FLDF(s, extendedDynamicState3CoverageToColorEnable), \
    FLDF(s, extendedDynamicState3CoverageToColorLocation), \
    FLDF(s, extendedDynamicState3CoverageModulationMode), \
    FLDF(s, extendedDynamicState3CoverageModulationTableEnable), \
    FLDF(s, extendedDynamicState3CoverageModulationTable), \
    FLDF(s, extendedDynamicState3CoverageReductionMode), \
    FLDF(s, extendedDynamicState3RepresentativeFragmentTestEnable), \
    FLDF(s, extendedDynamicState3ShadingRateImageEnable)
#define F_FSI(s) FLDF(s, fragmentShaderSampleInterlock), \
    FLDF(s, fragmentShaderPixelInterlock), \
    FLDF(s, fragmentShaderShadingRateInterlock)
#define F_GPL(s) FLDF(s, graphicsPipelineLibrary)
#define F_LINE(s) FLDF(s, rectangularLines), FLDF(s, bresenhamLines), \
    FLDF(s, smoothLines), FLDF(s, stippledRectangularLines), \
    FLDF(s, stippledBresenhamLines), FLDF(s, stippledSmoothLines)
#define F_MEMP(s) FLDF(s, memoryPriority)
#define F_MD(s) FLDF(s, multiDraw)
#define F_NSCM(s) FLDF(s, nonSeamlessCubeMap)
#define F_PDLM(s) FLDF(s, pageableDeviceLocalMemory)
#define F_PID(s) FLDF(s, presentId)
#define F_PW(s) FLDF(s, presentWait)
#define F_PID2(s) FLDF(s, presentId2)
#define F_PW2(s) FLDF(s, presentWait2)
#define F_PTLR(s) FLDF(s, primitiveTopologyListRestart), \
    FLDF(s, primitiveTopologyPatchListRestart)
#define F_R2(s) FLDF(s, robustBufferAccess2), FLDF(s, robustImageAccess2), \
    FLDF(s, nullDescriptor)
#define F_SC(s) FLDF(s, shaderSubgroupClock), FLDF(s, shaderDeviceClock)
#define F_SMI(s) FLDF(s, shaderModuleIdentifier)
#define F_SO(s) FLDF(s, shaderObject)
#define F_SWM(s) FLDF(s, swapchainMaintenance1)
#define F_TF(s) FLDF(s, transformFeedback), FLDF(s, geometryStreams)
#define F_VAD(s) FLDF(s, vertexAttributeInstanceRateDivisor), \
    FLDF(s, vertexAttributeInstanceRateZeroDivisor)
#define F_FAULT(s) FLDF(s, deviceFault), FLDF(s, deviceFaultVendorBinary)
#define F_DRLR(s) FLDF(s, dynamicRenderingLocalRead)
#define F_M5(s) FLDF(s, maintenance5)
#define F_M6(s) FLDF(s, maintenance6)
#define F_M7(s) FLDF(s, maintenance7)
#define F_M8(s) FLDF(s, maintenance8)
#define F_M9(s) FLDF(s, maintenance9)
#define F_M10(s) FLDF(s, maintenance10)
#define F_M11(s) FLDF(s, maintenance11)
#define F_SFC2(s) FLDF(s, shaderFloatControls2)
#define F_SSUCF(s) FLDF(s, shaderSubgroupUniformControlFlow)
#define F_SUP(s) FLDF(s, shaderUntypedPointers)
#define F_UIL(s) FLDF(s, unifiedImageLayouts)
#define F_RAC(s) FLDF(s, shaderRawAccessChains)
#define F_HIC(s) FLDF(s, hostImageCopy)

/* extension feature structs, allocated once (only chained if ext present) */
#define DECL(st, tag, fields) \
    static st s_##tag; \
    static const FieldDef f_##tag[] = { fields(st) }

DECL(VkPhysicalDeviceAttachmentFeedbackLoopLayoutFeaturesEXT, extAttachmentFeedbackLoopLayout, F_AFLL);
DECL(VkPhysicalDeviceBorderColorSwizzleFeaturesEXT, extBorderColorSwizzle, F_BCS);
DECL(VkPhysicalDeviceCustomBorderColorFeaturesEXT, extCustomBorderColor, F_CBC);
DECL(VkPhysicalDeviceDepthClipEnableFeaturesEXT, extDepthClipEnable, F_DCE);
DECL(VkPhysicalDeviceDepthBiasControlFeaturesEXT, extDepthBiasControl, F_DBC);
DECL(VkPhysicalDeviceDescriptorBufferFeaturesEXT, extDescriptorBuffer, F_DBUF);
DECL(VkPhysicalDeviceDescriptorHeapFeaturesEXT, extDescriptorHeap, F_DHEAP);
DECL(VkPhysicalDeviceDynamicRenderingUnusedAttachmentsFeaturesEXT, extDynamicRenderingUnusedAttachments, F_DRUA);
DECL(VkPhysicalDeviceExtendedDynamicState3FeaturesEXT, extExtendedDynamicState3, F_EDS3);
DECL(VkPhysicalDeviceFragmentShaderInterlockFeaturesEXT, extFragmentShaderInterlock, F_FSI);
DECL(VkPhysicalDeviceGraphicsPipelineLibraryFeaturesEXT, extGraphicsPipelineLibrary, F_GPL);
DECL(VkPhysicalDeviceLineRasterizationFeaturesKHR, extLineRasterization, F_LINE);
DECL(VkPhysicalDeviceMemoryPriorityFeaturesEXT, extMemoryPriority, F_MEMP);
DECL(VkPhysicalDeviceMultiDrawFeaturesEXT, extMultiDraw, F_MD);
DECL(VkPhysicalDeviceNonSeamlessCubeMapFeaturesEXT, extNonSeamlessCubeMap, F_NSCM);
DECL(VkPhysicalDevicePageableDeviceLocalMemoryFeaturesEXT, extPageableDeviceLocalMemory, F_PDLM);
DECL(VkPhysicalDevicePresentIdFeaturesKHR, khrPresentId, F_PID);
DECL(VkPhysicalDevicePresentId2FeaturesKHR, khrPresentId2, F_PID2);
DECL(VkPhysicalDevicePresentWaitFeaturesKHR, khrPresentWait, F_PW);
DECL(VkPhysicalDevicePresentWait2FeaturesKHR, khrPresentWait2, F_PW2);
DECL(VkPhysicalDevicePrimitiveTopologyListRestartFeaturesEXT, extPrimitiveTopologyListRestart, F_PTLR);
DECL(VkPhysicalDeviceRobustness2FeaturesEXT, extRobustness2, F_R2);
DECL(VkPhysicalDeviceShaderClockFeaturesKHR, khrShaderClock, F_SC);
DECL(VkPhysicalDeviceShaderModuleIdentifierFeaturesEXT, extShaderModuleIdentifier, F_SMI);
DECL(VkPhysicalDeviceShaderObjectFeaturesEXT, extShaderObject, F_SO);
DECL(VkPhysicalDeviceSwapchainMaintenance1FeaturesKHR, khrSwapchainMaintenance1, F_SWM);
DECL(VkPhysicalDeviceSwapchainMaintenance1FeaturesEXT, extSwapchainMaintenance1, F_SWM);
DECL(VkPhysicalDeviceTransformFeedbackFeaturesEXT, extTransformFeedback, F_TF);
DECL(VkPhysicalDeviceVertexAttributeDivisorFeaturesEXT, extVertexAttributeDivisor, F_VAD);
DECL(VkPhysicalDeviceFaultFeaturesEXT, extDeviceFault, F_FAULT);
DECL(VkPhysicalDeviceDynamicRenderingLocalReadFeaturesKHR, khrDynamicRenderingLocalRead, F_DRLR);
DECL(VkPhysicalDeviceMaintenance5FeaturesKHR, khrMaintenance5, F_M5);
DECL(VkPhysicalDeviceMaintenance6FeaturesKHR, khrMaintenance6, F_M6);
DECL(VkPhysicalDeviceMaintenance7FeaturesKHR, khrMaintenance7, F_M7);
DECL(VkPhysicalDeviceMaintenance8FeaturesKHR, khrMaintenance8, F_M8);
DECL(VkPhysicalDeviceMaintenance9FeaturesKHR, khrMaintenance9, F_M9);
DECL(VkPhysicalDeviceMaintenance10FeaturesKHR, khrMaintenance10, F_M10);
DECL(VkPhysicalDeviceMaintenance11FeaturesKHR, khrMaintenance11, F_M11);
DECL(VkPhysicalDeviceShaderFloatControls2FeaturesKHR, khrShaderFloatControls2, F_SFC2);
DECL(VkPhysicalDeviceShaderSubgroupUniformControlFlowFeaturesKHR, khrShaderSubgroupUniformControlFlow, F_SSUCF);
DECL(VkPhysicalDeviceShaderUntypedPointersFeaturesKHR, khrShaderUntypedPointers, F_SUP);
DECL(VkPhysicalDeviceUnifiedImageLayoutsFeaturesKHR, khrUnifiedImageLayouts, F_UIL);
DECL(VkPhysicalDeviceRawAccessChainsFeaturesNV, nvRawAccessChains, F_RAC);
DECL(VkPhysicalDeviceHostImageCopyFeaturesEXT, extHostImageCopy, F_HIC);
#define F_IU8(s) FLDF(s, indexTypeUint8)
DECL(VkPhysicalDeviceIndexTypeUint8FeaturesEXT, extIndexTypeUint8, F_IU8);

static VkPhysicalDeviceVulkan11Features s_vk11;
static const FieldDef f_vk11[] = { F_VK11(VkPhysicalDeviceVulkan11Features) };
static VkPhysicalDeviceVulkan12Features s_vk12;
static const FieldDef f_vk12[] = { F_VK12(VkPhysicalDeviceVulkan12Features) };
static VkPhysicalDeviceVulkan13Features s_vk13;
static const FieldDef f_vk13[] = { F_VK13(VkPhysicalDeviceVulkan13Features) };
static VkPhysicalDeviceVulkan14Features s_vk14;
static const FieldDef f_vk14[] = { F_VK14(VkPhysicalDeviceVulkan14Features) };
static VkPhysicalDeviceFeatures2 s_core;
static const FieldDef f_core[] = { F_CORE(VkPhysicalDeviceFeatures) };

/* ---------- limits ---------- */

#define LIM(u) printf(",\"" #u "\":%llu", (unsigned long long)l->u)
#define LIMf(u) printf(",\"" #u "\":%.9g", (double)l->u)
#define LIMa2(u) printf(",\"" #u "\":[%llu,%llu]", \
    (unsigned long long)l->u[0], (unsigned long long)l->u[1])
#define LIMa3u(u) printf(",\"" #u "\":[%u,%u,%u]", l->u[0], l->u[1], l->u[2])
#define LIMa2f(u) printf(",\"" #u "\":[%.9g,%.9g]", (double)l->u[0], (double)l->u[1])

static void emit_limits(const VkPhysicalDeviceLimits *l)
{
    fputs(",\"limits\":{", stdout);
    printf("\"maxImageDimension1D\":%llu", (unsigned long long)l->maxImageDimension1D);
    LIM(maxImageDimension2D); LIM(maxImageDimension3D); LIM(maxImageDimensionCube);
    LIM(maxImageArrayLayers); LIM(maxTexelBufferElements); LIM(maxUniformBufferRange);
    LIM(maxStorageBufferRange); LIM(maxPushConstantsSize); LIM(maxMemoryAllocationCount);
    LIM(maxSamplerAllocationCount); LIM(bufferImageGranularity); LIM(sparseAddressSpaceSize);
    LIM(maxBoundDescriptorSets); LIM(maxPerStageDescriptorSamplers);
    LIM(maxPerStageDescriptorUniformBuffers); LIM(maxPerStageDescriptorStorageBuffers);
    LIM(maxPerStageDescriptorSampledImages); LIM(maxPerStageDescriptorStorageImages);
    LIM(maxPerStageDescriptorInputAttachments); LIM(maxPerStageResources);
    LIM(maxDescriptorSetSamplers); LIM(maxDescriptorSetUniformBuffers);
    LIM(maxDescriptorSetUniformBuffersDynamic); LIM(maxDescriptorSetStorageBuffers);
    LIM(maxDescriptorSetStorageBuffersDynamic); LIM(maxDescriptorSetSampledImages);
    LIM(maxDescriptorSetStorageImages); LIM(maxDescriptorSetInputAttachments);
    LIM(maxVertexInputAttributes); LIM(maxVertexInputBindings);
    LIM(maxVertexInputAttributeOffset); LIM(maxVertexInputBindingStride);
    LIM(maxVertexOutputComponents); LIM(maxTessellationGenerationLevel);
    LIM(maxTessellationPatchSize); LIM(maxTessellationControlPerVertexInputComponents);
    LIM(maxTessellationControlPerVertexOutputComponents);
    LIM(maxTessellationControlPerPatchOutputComponents);
    LIM(maxTessellationControlTotalOutputComponents);
    LIM(maxTessellationEvaluationInputComponents); LIM(maxTessellationEvaluationOutputComponents);
    LIM(maxGeometryShaderInvocations); LIM(maxGeometryInputComponents);
    LIM(maxGeometryOutputComponents); LIM(maxGeometryOutputVertices);
    LIM(maxGeometryTotalOutputComponents); LIM(maxFragmentInputComponents);
    LIM(maxFragmentOutputAttachments); LIM(maxFragmentDualSrcAttachments);
    LIM(maxFragmentCombinedOutputResources); LIM(maxComputeSharedMemorySize);
    LIMa3u(maxComputeWorkGroupCount); LIM(maxComputeWorkGroupInvocations);
    LIMa3u(maxComputeWorkGroupSize);
    LIM(subPixelPrecisionBits); LIM(subTexelPrecisionBits); LIM(mipmapPrecisionBits);
    LIM(maxDrawIndexedIndexValue); LIM(maxDrawIndirectCount); LIMf(maxSamplerLodBias);
    LIMf(maxSamplerAnisotropy); LIM(maxViewports); LIMa2(maxViewportDimensions);
    LIMa2f(viewportBoundsRange); LIM(viewportSubPixelBits); LIM(minMemoryMapAlignment);
    LIM(minTexelBufferOffsetAlignment); LIM(minUniformBufferOffsetAlignment);
    LIM(minStorageBufferOffsetAlignment); LIM(minTexelOffset); LIM(maxTexelOffset);
    LIM(minTexelGatherOffset); LIM(maxTexelGatherOffset); LIMf(minInterpolationOffset);
    LIMf(maxInterpolationOffset); LIM(subPixelInterpolationOffsetBits);
    LIM(maxFramebufferWidth); LIM(maxFramebufferHeight);
    LIM(maxFramebufferLayers); LIM(framebufferColorSampleCounts);
    LIM(framebufferDepthSampleCounts); LIM(framebufferStencilSampleCounts);
    LIM(framebufferNoAttachmentsSampleCounts); LIM(maxColorAttachments);
    LIM(sampledImageColorSampleCounts); LIM(sampledImageIntegerSampleCounts);
    LIM(sampledImageDepthSampleCounts); LIM(sampledImageStencilSampleCounts);
    LIM(storageImageSampleCounts); LIM(maxSampleMaskWords);
    LIM(timestampComputeAndGraphics); LIMf(timestampPeriod);
    LIM(maxClipDistances); LIM(maxCullDistances); LIM(maxCombinedClipAndCullDistances);
    LIM(discreteQueuePriorities); LIMf(pointSizeGranularity); LIMf(lineWidthGranularity);
    LIM(strictLines); LIM(standardSampleLocations); LIM(optimalBufferCopyOffsetAlignment);
    LIM(optimalBufferCopyRowPitchAlignment); LIM(nonCoherentAtomSize);
    LIMa2f(pointSizeRange); LIMa2f(lineWidthRange);
    fputs("}", stdout);
}

/* ---------- device probe ---------- */

static void emit_device(VkPhysicalDevice dev)
{
    VkPhysicalDeviceDriverProperties drv = { VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DRIVER_PROPERTIES };
    VkPhysicalDeviceVulkan11Properties p11 = { VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_1_PROPERTIES };
    VkPhysicalDeviceVulkan12Properties p12 = { VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_PROPERTIES };
    VkPhysicalDeviceVulkan13Properties p13 = { VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_3_PROPERTIES };
    VkPhysicalDeviceProperties2 props = { VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2 };
    props.pNext = &drv;
    drv.pNext = &p11;
    p11.pNext = &p12;
    p12.pNext = &p13;
    pvkGetPhysicalDeviceProperties2(dev, &props);

    uint32_t extCount = 0;
    VkExtensionProperties *exts = NULL;
    pvkEnumerateDeviceExtensionProperties(dev, NULL, &extCount, NULL);
    exts = extCount ? malloc(extCount * sizeof(*exts)) : NULL;
    if (exts)
        pvkEnumerateDeviceExtensionProperties(dev, NULL, &extCount, exts);

    /* pNext feature chain: core vk11-vk14 gated on apiVersion, extension
     * structs gated on the advertised extension set -- same selection as
     * DxvkDeviceCapabilities::initSupportedFeatures. */
    void *chain = NULL;
    StructDef defs[64];
    uint32_t ndef = 0;
#define CHAIN(nm, st, stype) do { \
        if (ndef >= COUNT(defs)) break; \
        CHAIN_ST(s_##nm, stype); \
        defs[ndef].tag = #nm; defs[ndef].s = &s_##nm; \
        defs[ndef].fields = f_##nm; defs[ndef].nfields = COUNT(f_##nm); \
        ndef++; \
    } while (0)

    uint32_t api = props.properties.apiVersion;
    if (api >= VK_API_VERSION_1_4) CHAIN(vk14, s_vk14, VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_4_FEATURES);
    if (api >= VK_API_VERSION_1_3) CHAIN(vk13, s_vk13, VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_3_FEATURES);
    if (api >= VK_API_VERSION_1_2) CHAIN(vk12, s_vk12, VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES);
    CHAIN(vk11, s_vk11, VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_1_FEATURES);

#define CHAIN_EXT(nm, extname, stype) do { \
        if (ndef < COUNT(defs) && has_ext(exts, extCount, extname)) { \
            CHAIN_ST(s_##nm, stype); \
            defs[ndef].tag = #nm; defs[ndef].s = &s_##nm; \
            defs[ndef].fields = f_##nm; defs[ndef].nfields = COUNT(f_##nm); \
            ndef++; \
        } \
    } while (0)

    CHAIN_EXT(extAttachmentFeedbackLoopLayout, VK_EXT_ATTACHMENT_FEEDBACK_LOOP_LAYOUT_EXTENSION_NAME, VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_ATTACHMENT_FEEDBACK_LOOP_LAYOUT_FEATURES_EXT);
    CHAIN_EXT(extBorderColorSwizzle, VK_EXT_BORDER_COLOR_SWIZZLE_EXTENSION_NAME, VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_BORDER_COLOR_SWIZZLE_FEATURES_EXT);
    CHAIN_EXT(extCustomBorderColor, VK_EXT_CUSTOM_BORDER_COLOR_EXTENSION_NAME, VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_CUSTOM_BORDER_COLOR_FEATURES_EXT);
    CHAIN_EXT(extDepthClipEnable, VK_EXT_DEPTH_CLIP_ENABLE_EXTENSION_NAME, VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DEPTH_CLIP_ENABLE_FEATURES_EXT);
    CHAIN_EXT(extDepthBiasControl, VK_EXT_DEPTH_BIAS_CONTROL_EXTENSION_NAME, VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DEPTH_BIAS_CONTROL_FEATURES_EXT);
    CHAIN_EXT(extDescriptorBuffer, VK_EXT_DESCRIPTOR_BUFFER_EXTENSION_NAME, VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DESCRIPTOR_BUFFER_FEATURES_EXT);
    CHAIN_EXT(extDescriptorHeap, VK_EXT_DESCRIPTOR_HEAP_EXTENSION_NAME, VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DESCRIPTOR_HEAP_FEATURES_EXT);
    CHAIN_EXT(extDynamicRenderingUnusedAttachments, VK_EXT_DYNAMIC_RENDERING_UNUSED_ATTACHMENTS_EXTENSION_NAME, VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DYNAMIC_RENDERING_UNUSED_ATTACHMENTS_FEATURES_EXT);
    CHAIN_EXT(extExtendedDynamicState3, VK_EXT_EXTENDED_DYNAMIC_STATE_3_EXTENSION_NAME, VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_EXTENDED_DYNAMIC_STATE_3_FEATURES_EXT);
    CHAIN_EXT(extFragmentShaderInterlock, VK_EXT_FRAGMENT_SHADER_INTERLOCK_EXTENSION_NAME, VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FRAGMENT_SHADER_INTERLOCK_FEATURES_EXT);
    CHAIN_EXT(extGraphicsPipelineLibrary, VK_EXT_GRAPHICS_PIPELINE_LIBRARY_EXTENSION_NAME, VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_GRAPHICS_PIPELINE_LIBRARY_FEATURES_EXT);
    CHAIN_EXT(extLineRasterization, VK_EXT_LINE_RASTERIZATION_EXTENSION_NAME, VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_LINE_RASTERIZATION_FEATURES_KHR);
    CHAIN_EXT(extMemoryPriority, VK_EXT_MEMORY_PRIORITY_EXTENSION_NAME, VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MEMORY_PRIORITY_FEATURES_EXT);
    CHAIN_EXT(extMultiDraw, VK_EXT_MULTI_DRAW_EXTENSION_NAME, VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MULTI_DRAW_FEATURES_EXT);
    CHAIN_EXT(extNonSeamlessCubeMap, VK_EXT_NON_SEAMLESS_CUBE_MAP_EXTENSION_NAME, VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_NON_SEAMLESS_CUBE_MAP_FEATURES_EXT);
    CHAIN_EXT(extPageableDeviceLocalMemory, VK_EXT_PAGEABLE_DEVICE_LOCAL_MEMORY_EXTENSION_NAME, VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PAGEABLE_DEVICE_LOCAL_MEMORY_FEATURES_EXT);
    CHAIN_EXT(khrPresentId, VK_KHR_PRESENT_ID_EXTENSION_NAME, VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PRESENT_ID_FEATURES_KHR);
    CHAIN_EXT(khrPresentId2, VK_KHR_PRESENT_ID_2_EXTENSION_NAME, VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PRESENT_ID_2_FEATURES_KHR);
    CHAIN_EXT(khrPresentWait, VK_KHR_PRESENT_WAIT_EXTENSION_NAME, VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PRESENT_WAIT_FEATURES_KHR);
    CHAIN_EXT(khrPresentWait2, VK_KHR_PRESENT_WAIT_2_EXTENSION_NAME, VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PRESENT_WAIT_2_FEATURES_KHR);
    CHAIN_EXT(extPrimitiveTopologyListRestart, VK_EXT_PRIMITIVE_TOPOLOGY_LIST_RESTART_EXTENSION_NAME, VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PRIMITIVE_TOPOLOGY_LIST_RESTART_FEATURES_EXT);
    CHAIN_EXT(extRobustness2, VK_EXT_ROBUSTNESS_2_EXTENSION_NAME, VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_ROBUSTNESS_2_FEATURES_EXT);
    CHAIN_EXT(khrShaderClock, VK_KHR_SHADER_CLOCK_EXTENSION_NAME, VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SHADER_CLOCK_FEATURES_KHR);
    CHAIN_EXT(extShaderModuleIdentifier, VK_EXT_SHADER_MODULE_IDENTIFIER_EXTENSION_NAME, VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SHADER_MODULE_IDENTIFIER_FEATURES_EXT);
    CHAIN_EXT(extShaderObject, VK_EXT_SHADER_OBJECT_EXTENSION_NAME, VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SHADER_OBJECT_FEATURES_EXT);
    CHAIN_EXT(extSwapchainMaintenance1, VK_EXT_SWAPCHAIN_MAINTENANCE_1_EXTENSION_NAME, VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SWAPCHAIN_MAINTENANCE_1_FEATURES_EXT);
    CHAIN_EXT(extTransformFeedback, VK_EXT_TRANSFORM_FEEDBACK_EXTENSION_NAME, VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_TRANSFORM_FEEDBACK_FEATURES_EXT);
    CHAIN_EXT(extVertexAttributeDivisor, VK_EXT_VERTEX_ATTRIBUTE_DIVISOR_EXTENSION_NAME, VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VERTEX_ATTRIBUTE_DIVISOR_FEATURES_EXT);
    /* VK_KHR_device_fault was renamed VK_EXT_device_fault; either name may appear. */
    if (has_ext(exts, extCount, VK_KHR_DEVICE_FAULT_EXTENSION_NAME) ||
        has_ext(exts, extCount, VK_EXT_DEVICE_FAULT_EXTENSION_NAME)) {
        CHAIN_ST(s_extDeviceFault, VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FAULT_FEATURES_EXT);
        defs[ndef].tag = "extDeviceFault"; defs[ndef].s = &s_extDeviceFault;
        defs[ndef].fields = f_extDeviceFault; defs[ndef].nfields = COUNT(f_extDeviceFault);
        ndef++;
    }
    CHAIN_EXT(khrDynamicRenderingLocalRead, VK_KHR_DYNAMIC_RENDERING_LOCAL_READ_EXTENSION_NAME, VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DYNAMIC_RENDERING_LOCAL_READ_FEATURES_KHR);
    CHAIN_EXT(khrMaintenance5, VK_KHR_MAINTENANCE_5_EXTENSION_NAME, VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MAINTENANCE_5_FEATURES_KHR);
    CHAIN_EXT(khrMaintenance6, VK_KHR_MAINTENANCE_6_EXTENSION_NAME, VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MAINTENANCE_6_FEATURES_KHR);
    CHAIN_EXT(khrMaintenance7, VK_KHR_MAINTENANCE_7_EXTENSION_NAME, VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MAINTENANCE_7_FEATURES_KHR);
    CHAIN_EXT(khrMaintenance8, VK_KHR_MAINTENANCE_8_EXTENSION_NAME, VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MAINTENANCE_8_FEATURES_KHR);
    CHAIN_EXT(khrMaintenance9, VK_KHR_MAINTENANCE_9_EXTENSION_NAME, VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MAINTENANCE_9_FEATURES_KHR);
    CHAIN_EXT(khrMaintenance10, VK_KHR_MAINTENANCE_10_EXTENSION_NAME, VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MAINTENANCE_10_FEATURES_KHR);
    CHAIN_EXT(khrMaintenance11, VK_KHR_MAINTENANCE_11_EXTENSION_NAME, VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MAINTENANCE_11_FEATURES_KHR);
    CHAIN_EXT(khrShaderFloatControls2, VK_KHR_SHADER_FLOAT_CONTROLS_2_EXTENSION_NAME, VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SHADER_FLOAT_CONTROLS_2_FEATURES_KHR);
    CHAIN_EXT(khrShaderSubgroupUniformControlFlow, VK_KHR_SHADER_SUBGROUP_UNIFORM_CONTROL_FLOW_EXTENSION_NAME, VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SHADER_SUBGROUP_UNIFORM_CONTROL_FLOW_FEATURES_KHR);
    CHAIN_EXT(khrShaderUntypedPointers, VK_KHR_SHADER_UNTYPED_POINTERS_EXTENSION_NAME, VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SHADER_UNTYPED_POINTERS_FEATURES_KHR);
    CHAIN_EXT(khrUnifiedImageLayouts, VK_KHR_UNIFIED_IMAGE_LAYOUTS_EXTENSION_NAME, VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_UNIFIED_IMAGE_LAYOUTS_FEATURES_KHR);
    CHAIN_EXT(khrSwapchainMaintenance1, VK_KHR_SWAPCHAIN_MAINTENANCE_1_EXTENSION_NAME, VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SWAPCHAIN_MAINTENANCE_1_FEATURES_KHR);
    CHAIN_EXT(nvRawAccessChains, VK_NV_RAW_ACCESS_CHAINS_EXTENSION_NAME, VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAW_ACCESS_CHAINS_FEATURES_NV);
    CHAIN_EXT(extHostImageCopy, VK_EXT_HOST_IMAGE_COPY_EXTENSION_NAME, VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_HOST_IMAGE_COPY_FEATURES_EXT);
    CHAIN_EXT(extIndexTypeUint8, VK_EXT_INDEX_TYPE_UINT8_EXTENSION_NAME, VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_INDEX_TYPE_UINT8_FEATURES_EXT);

    memset(&s_core, 0, sizeof(s_core));
    s_core.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2;
    s_core.pNext = chain;
    pvkGetPhysicalDeviceFeatures2(dev, &s_core);
    /* vkGetPhysicalDeviceFeatures2 fills only the head + chained structs;
     * core.features lives at s_core.features. */
    {
        static const StructDef coredef = { "core", &s_core.features, f_core, COUNT(f_core) };
        memmove(&defs[1], defs, sizeof(defs[0]) * ndef);
        defs[0] = coredef;
        ndef++;
    }

    uint32_t qcount = 0;
    pvkGetPhysicalDeviceQueueFamilyProperties(dev, &qcount, NULL);
    VkQueueFamilyProperties *queues = qcount ? malloc(qcount * sizeof(*queues)) : NULL;
    if (queues)
        pvkGetPhysicalDeviceQueueFamilyProperties(dev, &qcount, queues);

    /* ---- emit ---- */
    putchar('{');
    fputs("\"deviceName\":", stdout); js_str(props.properties.deviceName);
    fputs(",\"apiVersion\":", stdout); printf("%u", api);
    fputs(",\"apiVersionText\":", stdout); js_ver(api);
    fputs(",\"driverVersion\":", stdout); printf("%u", props.properties.driverVersion);
    fputs(",\"vendorID\":", stdout); printf("%u", props.properties.vendorID);
    fputs(",\"deviceID\":", stdout); printf("%u", props.properties.deviceID);
    fputs(",\"deviceType\":", stdout); printf("%u", (unsigned)props.properties.deviceType);
    fputs(",\"driver\":{\"id\":", stdout); printf("%u", (unsigned)drv.driverID);
    fputs(",\"name\":", stdout); js_str(drv.driverName);
    fputs(",\"info\":", stdout); js_str(drv.driverInfo);
    fputs(",\"conformance\":", stdout);
    printf("\"%u.%u.%u.%u\"", drv.conformanceVersion.major, drv.conformanceVersion.minor,
        drv.conformanceVersion.subminor, drv.conformanceVersion.patch);
    fputs("}", stdout);
    fputs(",\"vk11Props\":{\"subgroupSize\":", stdout);
    printf("%u,\"subgroupSupportedOperations\":%u,\"subgroupSupportedStages\":%u}",
        p11.subgroupSize, (unsigned)p11.subgroupSupportedOperations,
        (unsigned)p11.subgroupSupportedStages);
    fputs(",\"vk13Props\":{\"maxSubgroupSize\":", stdout);
    printf("%u,\"maxInlineUniformBlockSize\":%u}",
        p13.maxSubgroupSize, (unsigned)p13.maxInlineUniformBlockSize);
    emit_limits(&props.properties.limits);
    fputs(",\"features\":{", stdout);
    for (uint32_t i = 0; i < ndef; i++) {
        if (i) putchar(',');
        emit_fields(&defs[i]);
    }
    fputs("}", stdout);
    fputs(",\"extensions\":[", stdout);
    for (uint32_t i = 0; i < extCount; i++) {
        if (i) putchar(',');
        js_str(exts[i].extensionName);
    }
    fputs("]", stdout);
    fputs(",\"queueFamilies\":[", stdout);
    for (uint32_t i = 0; i < qcount; i++) {
        if (i) putchar(',');
        printf("{\"flags\":%u,\"count\":%u}", (unsigned)queues[i].queueFlags, queues[i].queueCount);
    }
    fputs("]", stdout);
    putchar('}');

    free(queues);
    free(exts);
}

/* Runs on a big explicit stack — see main(). */
static int probe_main(void)
{
    static const char *wsi_exts[] = { "VK_KHR_surface", "VK_KHR_win32_surface" };
    HMODULE mod = LoadLibraryA("vulkan-1");
    if (!mod) {
        fprintf(stderr, "vkprobe: LoadLibrary(vulkan-1) failed gle=%lu\n", GetLastError());
        printf("VKPROBE_JSON_BEGIN\n{\"vkprobe\":1,\"error\":\"vulkan-1.dll not found\"}\nVKPROBE_JSON_END\n");
        return 1;
    }
    gipa = (PFN_vkGetInstanceProcAddr)GetProcAddress(mod, "vkGetInstanceProcAddr");
    load_core(NULL);

    /* guest-visible instance extension list (through winevulkan) */
    uint32_t instExtCount = 0;
    VkExtensionProperties *instExts = NULL;
    if (pvkEnumerateInstanceExtensionProperties) {
        pvkEnumerateInstanceExtensionProperties(NULL, &instExtCount, NULL);
        instExts = instExtCount ? malloc(instExtCount * sizeof(*instExts)) : NULL;
        if (instExts)
            pvkEnumerateInstanceExtensionProperties(NULL, &instExtCount, instExts);
    }

    /* Instance the way DXVK's WSI does it on Windows: KHR_surface +
     * KHR_win32_surface. If that fails, retry bare so device data is still
     * reported; the failure itself is recorded either way. */
    VkApplicationInfo app = { VK_STRUCTURE_TYPE_APPLICATION_INFO };
    app.pApplicationName = "amphora-vkprobe";
    /* Same apiVersion DXVK reports in VkApplicationInfo (dxvk_instance.h:
     * DxvkVulkanApiVersion = VK_API_VERSION_1_3). */
    app.apiVersion = VK_API_VERSION_1_3;
    VkInstanceCreateInfo ci = { VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO };
    ci.pApplicationInfo = &app;
    ci.enabledExtensionCount = 2;
    ci.ppEnabledExtensionNames = wsi_exts;
    pvkCreateInstance = (PFN_vkCreateInstance)gipa(NULL, "vkCreateInstance");
    VkInstance inst = VK_NULL_HANDLE;
    VkResult surfRes = pvkCreateInstance ? pvkCreateInstance(&ci, NULL, &inst) : VK_ERROR_INITIALIZATION_FAILED;
    VkResult instRes = surfRes;
    if (instRes != VK_SUCCESS) {
        ci.enabledExtensionCount = 0;
        ci.ppEnabledExtensionNames = NULL;
        instRes = pvkCreateInstance(&ci, NULL, &inst);
    }
    if (instRes != VK_SUCCESS || inst == VK_NULL_HANDLE) {
        printf("VKPROBE_JSON_BEGIN\n{\"vkprobe\":1,\"surfaceInstanceCreate\":%d,\"instanceCreate\":%d,\"devices\":[]}\nVKPROBE_JSON_END\n",
            (int)surfRes, (int)instRes);
        return 1;
    }
    load_core(inst);

    uint32_t devCount = 0;
    VkPhysicalDevice *devs = NULL;
    VkResult enumRes = pvkEnumeratePhysicalDevices(inst, &devCount, NULL);
    devs = devCount ? malloc(devCount * sizeof(*devs)) : NULL;
    if (devs && enumRes == VK_SUCCESS)
        enumRes = pvkEnumeratePhysicalDevices(inst, &devCount, devs);

    fputs("VKPROBE_JSON_BEGIN\n", stdout);
    fputs("{\"vkprobe\":1", stdout);
    fputs(",\"surfaceInstanceCreate\":", stdout); printf("%d", (int)surfRes);
    fputs(",\"instanceCreate\":", stdout); printf("%d", (int)instRes);
    fputs(",\"instanceExtensions\":[", stdout);
    for (uint32_t i = 0; i < instExtCount; i++) {
        if (i) putchar(',');
        js_str(instExts[i].extensionName);
    }
    fputs("]", stdout);
    fputs(",\"enumerateDevices\":", stdout); printf("%d", (int)enumRes);
    fputs(",\"devices\":[", stdout);
    for (uint32_t i = 0; i < devCount; i++) {
        if (i) putchar(',');
        emit_device(devs[i]);
    }
    fputs("]}\nVKPROBE_JSON_END\n", stdout);
    fflush(stdout);

    free(devs);
    free(instExts);
    return 0;
}

static DWORD WINAPI probe_thread(void *arg)
{
    return (DWORD)probe_main();
}

int main(int argc, char **argv)
{
    /* The report goes to a file in the prefix: the host watches for it while
     * the session runs, and guest stdout is not a reliable channel under the
     * desktop launcher. freopen keeps every printf below unchanged. */
    freopen(RESULT_PATH, "w", stdout);

    /* Wine runs host calls on the calling thread's stack, and sizes the
     * process's initial stack from RLIMIT_STACK at exec. An app-launched
     * process inherits Android's unlimited RLIMIT_STACK, which leaves wine
     * with a small initial stack — and the wrapper ICD's vkCreateInstance
     * overflows it (seh: "Syscall stack overrun", abort in
     * winevulkan/loader.c). Run the probe on a thread with an explicit
     * 16 MiB stack so the host call always has room. */
    HANDLE th = CreateThread(NULL, 16u << 20, probe_thread, NULL, 0, NULL);
    DWORD code = 1;
    if (th) {
        WaitForSingleObject(th, INFINITE);
        GetExitCodeThread(th, &code);
        CloseHandle(th);
    } else {
        fprintf(stderr, "vkprobe: CreateThread failed gle=%lu; running inline\n",
            GetLastError());
        code = (DWORD)probe_main();
    }
    fflush(stdout);
    fclose(stdout);

    /* --then <exe> [args...]: the probe ran as the session's launch command.
     * Hand the report window back to the host (it decides the DXVK build and
     * relinks the prefix), then spawn the real game and exit: the session
     * lives on through the explorer desktop the game is attached to, and
     * nothing reads this process's exit code. The gate is bounded — if the
     * host never writes the go file the game still starts on the provisional
     * links. */
    int then = -1;
    for (int i = 1; i < argc; i++) {
        if (!strcmp(argv[i], "--then") && i + 1 < argc) {
            then = i + 1;
            break;
        }
    }
    if (then < 0)
        return (int)code;

    DWORD deadline = GetTickCount() + GO_TIMEOUT_MS;
    while (GetFileAttributesA(GO_PATH) == INVALID_FILE_ATTRIBUTES &&
           GetTickCount() < deadline)
        Sleep(GO_POLL_MS);

    /* Command line for CreateProcess: "exe" then each arg verbatim (paths
     * arrive pre-sanitized host-side). */
    size_t len = strlen(argv[then]) + 3;
    for (int i = then + 1; i < argc; i++)
        len += strlen(argv[i]) + 3;
    char *cmd = malloc(len);
    if (!cmd)
        return (int)code;
    char *p = cmd;
    p += sprintf(p, "\"%s\"", argv[then]);
    for (int i = then + 1; i < argc; i++)
        p += sprintf(p, " \"%s\"", argv[i]);

    STARTUPINFOA si;
    PROCESS_INFORMATION pi;
    memset(&si, 0, sizeof si);
    si.cb = sizeof si;
    memset(&pi, 0, sizeof pi);
    if (!CreateProcessA(NULL, cmd, NULL, NULL, FALSE, 0, NULL, NULL, &si, &pi)) {
        fprintf(stderr, "vkprobe: CreateProcess(%s) failed gle=%lu\n", cmd,
            GetLastError());
        free(cmd);
        return (int)code;
    }
    free(cmd);
    CloseHandle(pi.hThread);
    CloseHandle(pi.hProcess);
    return 0;
}
