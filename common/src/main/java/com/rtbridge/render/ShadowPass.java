package com.rtbridge.render;

import com.rtbridge.RTBridgeMod;
import com.rtbridge.bvh.TLASManager;
import com.rtbridge.vulkan.*;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.LongBuffer;

import static org.lwjgl.vulkan.KHRAccelerationStructure.*;
import static org.lwjgl.vulkan.KHRRayTracingPipeline.*;
import static org.lwjgl.vulkan.VK12.*;

/**
 * ShadowPass — 完整的 shadow ray tracing pass。
 *
 * 流程：
 *   1. 确保 TLAS 是最新的
 *   2. 更新 CameraUBO（invView, invProj, lightDir）
 *   3. 更新 DescriptorSet（绑定 TLAS + shadow image + GBuffer）
 *   4. vkCmdBindPipeline
 *   5. vkCmdBindDescriptorSets
 *   6. vkCmdTraceRaysKHR（width × height × 1）
 *   7. barrier：shadow image GENERAL → SHADER_READ_ONLY
 */
public class ShadowPass implements AutoCloseable {

    private final VulkanContext    ctx;
    private final TLASManager      tlas;
    private final RTImageSet       images;
    private final CameraUBO        cameraUBO;
    private final DescriptorPool   descPool;
    private final RTCommandBuffer  cmdBuf;

    private RTPipeline          pipeline;
    private ShaderBindingTable  sbt;
    private long                descriptorSet = VK_NULL_HANDLE;
    private long                sampler       = VK_NULL_HANDLE;

    private boolean ready = false;
    private int width, height;

    // 临时深度/法线贴图（从 OpenGL GBuffer 共享过来）
    // 初期使用占位 1×1 白色贴图
    private long placeholderView = VK_NULL_HANDLE;
    private long placeholderMem  = VK_NULL_HANDLE;
    private long placeholderImg  = VK_NULL_HANDLE;

    public ShadowPass(VulkanContext ctx, TLASManager tlas,
                      RTImageSet images, int width, int height) {
        this.ctx      = ctx;
        this.tlas     = tlas;
        this.images   = images;
        this.width    = width;
        this.height   = height;
        this.cameraUBO = new CameraUBO(ctx);
        this.descPool  = new DescriptorPool(ctx);
        this.cmdBuf    = new RTCommandBuffer(ctx);
    }

    public boolean init() {
        try {
            // 1. 编译 pipeline
            pipeline = new RTPipeline(ctx);
            if (!pipeline.build("shadow")) return false;

            // 2. SBT
            sbt = new ShaderBindingTable(ctx);
            sbt.build(pipeline);

            // 3. Sampler
            sampler = createSampler();

            // 4. 占位 1×1 图像（depth / normal GBuffer 还未接入）
            createPlaceholder();

            // 5. Descriptor pool + set
            descPool.init(1);
            descriptorSet = descPool.allocateAndWrite(
                pipeline.descriptorSetLayout,
                tlas.getTLASHandle(),
                images.shadowView,
                placeholderView, // depth
                placeholderView, // normal
                sampler,
                cameraUBO.bufferHandle()
            );

            ready = true;
            RTBridgeMod.LOGGER.info("[ShadowPass] 初始化完成 {}×{}", width, height);
            return true;
        } catch (Exception e) {
            RTBridgeMod.LOGGER.error("[ShadowPass] 初始化失败", e);
            return false;
        }
    }

    // ── 每帧 dispatch ─────────────────────────────────────────────────────────

    public void dispatch(Matrix4f invView, Matrix4f invProj, Vector3f lightDir) {
        if (!ready || tlas.getTLASHandle() == VK_NULL_HANDLE) return;

        // 更新 CameraUBO
        cameraUBO.update(invView, invProj, lightDir);

        // 录制命令
        VkCommandBuffer cmd = cmdBuf.begin();

        // Barrier: shadow image UNDEFINED/SHADER_READ → GENERAL（可写入）
        imageBarrier(cmd,
            images.shadowImage,
            VK_IMAGE_LAYOUT_UNDEFINED,
            VK_IMAGE_LAYOUT_GENERAL,
            0,
            VK_ACCESS_SHADER_WRITE_BIT,
            VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
            VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR);

        // Bind RT pipeline
        vkCmdBindPipeline(cmd,
            VK_PIPELINE_BIND_POINT_RAY_TRACING_KHR,
            pipeline.pipeline);

        // Bind descriptor set
        try (MemoryStack stack = MemoryStack.stackPush()) {
            vkCmdBindDescriptorSets(cmd,
                VK_PIPELINE_BIND_POINT_RAY_TRACING_KHR,
                pipeline.pipelineLayout,
                0,
                stack.longs(descriptorSet),
                null);

            // vkCmdTraceRaysKHR
            VkStridedDeviceAddressRegionKHR raygen = VkStridedDeviceAddressRegionKHR.calloc(stack)
                .deviceAddress(sbt.raygenDeviceAddress)
                .stride(sbt.raygenStride)
                .size(sbt.raygenSize);

            VkStridedDeviceAddressRegionKHR miss = VkStridedDeviceAddressRegionKHR.calloc(stack)
                .deviceAddress(sbt.missDeviceAddress)
                .stride(sbt.missStride)
                .size(sbt.missSize);

            VkStridedDeviceAddressRegionKHR hit = VkStridedDeviceAddressRegionKHR.calloc(stack)
                .deviceAddress(sbt.hitDeviceAddress)
                .stride(sbt.hitStride)
                .size(sbt.hitSize);

            VkStridedDeviceAddressRegionKHR callable = VkStridedDeviceAddressRegionKHR.calloc(stack)
                .deviceAddress(0).stride(0).size(0);

            vkCmdTraceRaysKHR(cmd,
                raygen, miss, hit, callable,
                width, height, 1);
        }

        // Barrier: shadow image GENERAL → SHADER_READ_ONLY（供 CompositePass 采样）
        imageBarrier(cmd,
            images.shadowImage,
            VK_IMAGE_LAYOUT_GENERAL,
            VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
            VK_ACCESS_SHADER_WRITE_BIT,
            VK_ACCESS_SHADER_READ_BIT,
            VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
            VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT);

        cmdBuf.submitAndSignal();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void imageBarrier(VkCommandBuffer cmd, long image,
                               int oldLayout, int newLayout,
                               int srcAccess, int dstAccess,
                               int srcStage, int dstStage) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack)
                .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                .oldLayout(oldLayout)
                .newLayout(newLayout)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .image(image)
                .srcAccessMask(srcAccess)
                .dstAccessMask(dstAccess);
            barrier.subresourceRange()
                .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                .levelCount(1)
                .layerCount(1);

            vkCmdPipelineBarrier(cmd, srcStage, dstStage,
                0, null, null, barrier);
        }
    }

    private long createSampler() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer pSampler = stack.mallocLong(1);
            VulkanBuffer.check(vkCreateSampler(ctx.device,
                VkSamplerCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO)
                    .magFilter(VK_FILTER_NEAREST)
                    .minFilter(VK_FILTER_NEAREST)
                    .addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .maxLod(0),
                null, pSampler), "vkCreateSampler");
            return pSampler.get(0);
        }
    }

    private void createPlaceholder() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // 1×1 R8_UNORM 白色占位图
            LongBuffer pImg = stack.mallocLong(1);
            vkCreateImage(ctx.device,
                VkImageCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO)
                    .imageType(VK_IMAGE_TYPE_2D)
                    .format(VK_FORMAT_R8_UNORM)
                    .extent(e -> e.width(1).height(1).depth(1))
                    .mipLevels(1).arrayLayers(1)
                    .samples(VK_SAMPLE_COUNT_1_BIT)
                    .tiling(VK_IMAGE_TILING_OPTIMAL)
                    .usage(VK_IMAGE_USAGE_SAMPLED_BIT)
                    .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED),
                null, pImg);
            placeholderImg = pImg.get(0);

            VkMemoryRequirements req = VkMemoryRequirements.malloc(stack);
            vkGetImageMemoryRequirements(ctx.device, placeholderImg, req);

            LongBuffer pMem = stack.mallocLong(1);
            vkAllocateMemory(ctx.device,
                VkMemoryAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                    .allocationSize(req.size())
                    .memoryTypeIndex(0),
                null, pMem);
            placeholderMem = pMem.get(0);
            vkBindImageMemory(ctx.device, placeholderImg, placeholderMem, 0);

            LongBuffer pView = stack.mallocLong(1);
            vkCreateImageView(ctx.device,
                VkImageViewCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
                    .image(placeholderImg)
                    .viewType(VK_IMAGE_VIEW_TYPE_2D)
                    .format(VK_FORMAT_R8_UNORM)
                    .subresourceRange(r -> r
                        .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                        .levelCount(1).layerCount(1)),
                null, pView);
            placeholderView = pView.get(0);
        }
    }

    @Override
    public void close() {
        if (sampler        != VK_NULL_HANDLE) vkDestroySampler(ctx.device, sampler, null);
        if (placeholderView!= VK_NULL_HANDLE) vkDestroyImageView(ctx.device, placeholderView, null);
        if (placeholderImg != VK_NULL_HANDLE) vkDestroyImage(ctx.device, placeholderImg, null);
        if (placeholderMem != VK_NULL_HANDLE) vkFreeMemory(ctx.device, placeholderMem, null);
        if (pipeline       != null) pipeline.close();
        if (sbt            != null) sbt.close();
        if (descPool       != null) descPool.close();
        if (cameraUBO      != null) cameraUBO.close();
        if (cmdBuf         != null) cmdBuf.close();
    }
}
