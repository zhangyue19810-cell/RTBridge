package com.rtbridge.vulkan;

import com.rtbridge.RTBridgeMod;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;

import static org.lwjgl.vulkan.KHRRayTracingPipeline.*;
import static org.lwjgl.vulkan.VK12.*;

/**
 * ShaderBindingTable — allocates and fills the SBT for one RT pipeline.
 *
 * Layout:
 *   [ raygen region ][ miss region ][ hit region ]
 *
 * Each region is aligned to shaderGroupBaseAlignment.
 * Each entry is shaderGroupHandleSize (typically 32 bytes) padded to
 * shaderGroupHandleAlignment.
 */
public class ShaderBindingTable implements AutoCloseable {

    public final VulkanContext ctx;

    private VulkanBuffer sbtBuffer;

    // VkStridedDeviceAddressRegionKHR fields for vkCmdTraceRaysKHR
    public long raygenDeviceAddress;
    public long raygenStride;
    public long raygenSize;

    public long missDeviceAddress;
    public long missStride;
    public long missSize;

    public long hitDeviceAddress;
    public long hitStride;
    public long hitSize;

    public long callableDeviceAddress = 0L;
    public long callableStride        = 0L;
    public long callableSize          = 0L;

    public ShaderBindingTable(VulkanContext ctx) {
        this.ctx = ctx;
    }

    // ── Build ─────────────────────────────────────────────────────────────────

    public void build(RTPipeline pipeline) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceRayTracingPipelinePropertiesKHR props = ctx.rtProps;

            int handleSize      = props.shaderGroupHandleSize();
            int handleAlignment = props.shaderGroupHandleAlignment();
            int baseAlignment   = props.shaderGroupBaseAlignment();

            // Aligned handle size
            int handleSizeAligned = align(handleSize, handleAlignment);

            // Region sizes (one entry each for raygen, miss, hit)
            int raygenRegionSize = align(handleSizeAligned, baseAlignment);
            int missRegionSize   = align(handleSizeAligned, baseAlignment);
            int hitRegionSize    = align(handleSizeAligned, baseAlignment);
            int totalSize        = raygenRegionSize + missRegionSize + hitRegionSize;

            // Get shader group handles from the pipeline
            ByteBuffer handles = stack.malloc(handleSize * RTPipeline.GROUP_COUNT);
            VulkanBuffer.check(
                vkGetRayTracingShaderGroupHandlesKHR(ctx.device,
                    pipeline.pipeline, 0, RTPipeline.GROUP_COUNT,
                    handles),
                "vkGetRayTracingShaderGroupHandlesKHR");

            // Allocate host-visible SBT buffer
            sbtBuffer = new VulkanBuffer(ctx.device);
            sbtBuffer.alloc(ctx.physDevice, totalSize,
                VK_BUFFER_USAGE_SHADER_BINDING_TABLE_BIT_KHR
              | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT
              | VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT
              | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);

            // Map and write handles
            org.lwjgl.PointerBuffer pp = stack.mallocPointer(1);
            vkMapMemory(ctx.device, sbtBuffer.memory, 0, totalSize, 0, pp);
            ByteBuffer mapped = pp.getByteBuffer(0, totalSize);

            // Write raygen handle at offset 0
            writeHandle(mapped, handles, RTPipeline.GROUP_RAYGEN,
                0, handleSize);

            // Write miss handle at raygenRegionSize
            writeHandle(mapped, handles, RTPipeline.GROUP_MISS,
                raygenRegionSize, handleSize);

            // Write hit handle at raygenRegionSize + missRegionSize
            writeHandle(mapped, handles, RTPipeline.GROUP_ANYHIT,
                raygenRegionSize + missRegionSize, handleSize);

            vkUnmapMemory(ctx.device, sbtBuffer.memory);

            // Fill device address regions
            long base = sbtBuffer.address;
            raygenDeviceAddress = base;
            raygenStride        = raygenRegionSize;
            raygenSize          = raygenRegionSize;

            missDeviceAddress   = base + raygenRegionSize;
            missStride          = handleSizeAligned;
            missSize            = missRegionSize;

            hitDeviceAddress    = base + raygenRegionSize + missRegionSize;
            hitStride           = handleSizeAligned;
            hitSize             = hitRegionSize;

            RTBridgeMod.LOGGER.info("[SBT] Built: raygen=0x{} miss=0x{} hit=0x{}",
                Long.toHexString(raygenDeviceAddress),
                Long.toHexString(missDeviceAddress),
                Long.toHexString(hitDeviceAddress));

        } catch (Exception e) {
            RTBridgeMod.LOGGER.error("[SBT] Build failed", e);
        }
    }

    private void writeHandle(ByteBuffer dst, ByteBuffer src,
                              int groupIndex, int dstOffset, int handleSize) {
        int srcOffset = groupIndex * handleSize;
        for (int i = 0; i < handleSize; i++) {
            dst.put(dstOffset + i, src.get(srcOffset + i));
        }
    }

    private static int align(int value, int alignment) {
        return (value + alignment - 1) & ~(alignment - 1);
    }

    @Override
    public void close() {
        if (sbtBuffer != null) sbtBuffer.close();
    }
}
