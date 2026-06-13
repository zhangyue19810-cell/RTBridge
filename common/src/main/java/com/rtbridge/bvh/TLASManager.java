package com.rtbridge.bvh;

import com.rtbridge.RTBridgeMod;
import com.rtbridge.vulkan.BLASEntry;
import com.rtbridge.vulkan.VulkanBuffer;
import com.rtbridge.vulkan.VulkanContext;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.lwjgl.vulkan.KHRAccelerationStructure.*;
import static org.lwjgl.vulkan.VK12.*;

/**
 * TLASManager — real Vulkan TLAS build + transform-only update.
 *
 * Two paths:
 *   rebuild()         — full vkCmdBuildAccelerationStructuresKHR (new instance added/removed)
 *   updateTransforms()— vkCmdBuildAccelerationStructuresKHR with UPDATE flag (transform-only)
 *
 * Instance layout per VkAccelerationStructureInstanceKHR (64 bytes):
 *   transform         : 12× float (3×4 row-major)
 *   instanceCustomIndex: 24 bits
 *   mask              :  8 bits
 *   instanceShaderBindingTableRecordOffset: 24 bits
 *   flags             :  8 bits
 *   accelerationStructureReference: 64 bits (device address)
 */
public class TLASManager implements AutoCloseable {

    private static final int INSTANCE_STRIDE = 64; // bytes per VkAccelerationStructureInstanceKHR

    private final VulkanContext ctx;

    // Ordered map: ownerId → (blasDeviceAddress, transform)
    private final LinkedHashMap<Long, InstanceData> instances = new LinkedHashMap<>();
    private record InstanceData(long blasDevAddr, Matrix4f transform, int customIndex) {}

    // Vulkan objects
    private long   tlasHandle   = VK_NULL_HANDLE;
    private long   tlasBuffer   = VK_NULL_HANDLE;
    private long   tlasMemory   = VK_NULL_HANDLE;
    private long   instanceBuffer   = VK_NULL_HANDLE; // host-visible instance data
    private long   instanceMemory   = VK_NULL_HANDLE;
    private long   instanceDevAddr  = 0L;
    private long   scratchBuffer    = VK_NULL_HANDLE;
    private long   scratchMemory    = VK_NULL_HANDLE;

    private long commandPool = VK_NULL_HANDLE;

    // ── Init ──────────────────────────────────────────────────────────────────

    public TLASManager(VulkanContext ctx) {
        this.ctx = ctx;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer pPool = stack.mallocLong(1);
            vkCreateCommandPool(ctx.device,
                VkCommandPoolCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO)
                    .queueFamilyIndex(ctx.computeQueueFamily)
                    .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT),
                null, pPool);
            commandPool = pPool.get(0);
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public void addInstance(long ownerId, BLASEntry blas, Matrix4f transform) {
        instances.put(ownerId, new InstanceData(blas.deviceAddress, new Matrix4f(transform),
            instances.size()));
    }

    public void removeInstance(long ownerId) {
        instances.remove(ownerId);
    }

    /** MOVE/ROTATE — only patch the transform, keep everything else. */
    public void updateTransform(long ownerId, Matrix4f transform) {
        InstanceData existing = instances.get(ownerId);
        if (existing == null) return;
        instances.put(ownerId, new InstanceData(existing.blasDevAddr(),
            new Matrix4f(transform), existing.customIndex()));
    }

    // ── Full rebuild ──────────────────────────────────────────────────────────

    public void rebuild() {
        if (instances.isEmpty()) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ensureInstanceBuffer(stack);
            writeInstanceData();
            buildTLAS(false, stack);
            RTBridgeMod.LOGGER.debug("[TLAS] Full rebuild — {} instances", instances.size());
        }
    }

    // ── Transform-only update ─────────────────────────────────────────────────

    public void updateTransforms() {
        if (tlasHandle == VK_NULL_HANDLE || instances.isEmpty()) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            writeInstanceData(); // patch transforms in instance buffer
            buildTLAS(true, stack); // UPDATE=true: fast path
            RTBridgeMod.LOGGER.debug("[TLAS] Transform update");
        }
    }

    // ── Instance buffer write ─────────────────────────────────────────────────

    /**
     * Write all instances into the host-visible instance buffer.
     * Format: VkAccelerationStructureInstanceKHR (64 bytes each).
     */
    private void writeInstanceData() {
        // Map
        try (MemoryStack stack = MemoryStack.stackPush()) {
            org.lwjgl.PointerBuffer pp = stack.mallocPointer(1);
            vkMapMemory(ctx.device, instanceMemory, 0,
                (long) instances.size() * INSTANCE_STRIDE, 0, pp);
            ByteBuffer mapped = pp.getByteBuffer(0, instances.size() * INSTANCE_STRIDE);

            int idx = 0;
            for (InstanceData inst : instances.values()) {
                int base = idx * INSTANCE_STRIDE;

                // 3×4 row-major transform (drop last row)
                float[] m = new float[16];
                inst.transform().get(m);
                // Row 0
                mapped.putFloat(base +  0, m[0]);
                mapped.putFloat(base +  4, m[4]);
                mapped.putFloat(base +  8, m[8]);
                mapped.putFloat(base + 12, m[12]);
                // Row 1
                mapped.putFloat(base + 16, m[1]);
                mapped.putFloat(base + 20, m[5]);
                mapped.putFloat(base + 24, m[9]);
                mapped.putFloat(base + 28, m[13]);
                // Row 2
                mapped.putFloat(base + 32, m[2]);
                mapped.putFloat(base + 36, m[6]);
                mapped.putFloat(base + 40, m[10]);
                mapped.putFloat(base + 44, m[14]);

                // instanceCustomIndex (24 bits) | mask (8 bits) packed
                int customAndMask = (inst.customIndex() & 0xFFFFFF) | (0xFF << 24);
                mapped.putInt(base + 48, customAndMask);

                // shaderBindingOffset (24 bits) | flags (8 bits)
                mapped.putInt(base + 52, 0);

                // BLAS device address
                mapped.putLong(base + 56, inst.blasDevAddr());
                idx++;
            }
            vkUnmapMemory(ctx.device, instanceMemory);
        }
    }

    // ── TLAS build ────────────────────────────────────────────────────────────

    private void buildTLAS(boolean update, MemoryStack stack) {
        VkAccelerationStructureGeometryInstancesDataKHR instData =
            VkAccelerationStructureGeometryInstancesDataKHR.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_GEOMETRY_INSTANCES_DATA_KHR)
                .data(VkDeviceOrHostAddressConstKHR.calloc(stack).deviceAddress(instanceDevAddr));

        VkAccelerationStructureGeometryKHR.Buffer geomBuf =
            VkAccelerationStructureGeometryKHR.calloc(1, stack)
                .sType(VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_GEOMETRY_KHR)
                .geometryType(VK_GEOMETRY_TYPE_INSTANCES_KHR);
        geomBuf.get(0).geometry().instances(instData);

        VkAccelerationStructureBuildGeometryInfoKHR buildInfo =
            VkAccelerationStructureBuildGeometryInfoKHR.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_BUILD_GEOMETRY_INFO_KHR)
                .type(VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR)
                .flags(VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR
                     | VK_BUILD_ACCELERATION_STRUCTURE_ALLOW_UPDATE_BIT_KHR)
                .pGeometries(geomBuf)
                .mode(update
                    ? VK_BUILD_ACCELERATION_STRUCTURE_MODE_UPDATE_KHR
                    : VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR);

        int instCount = instances.size();

        if (!update) {
            // Requery sizes (instance count may have changed)
            VkAccelerationStructureBuildSizesInfoKHR sizes =
                VkAccelerationStructureBuildSizesInfoKHR.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_BUILD_SIZES_INFO_KHR);
            vkGetAccelerationStructureBuildSizesKHR(ctx.device,
                VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR,
                buildInfo, stack.ints(instCount), sizes);
            reallocTLAS(sizes, stack);
        }

        buildInfo
            .srcAccelerationStructure(update ? tlasHandle : VK_NULL_HANDLE)
            .dstAccelerationStructure(tlasHandle)
            .scratchData(VkDeviceOrHostAddressKHR.calloc(stack).deviceAddress(
                getDeviceAddress(scratchBuffer)));

        VkAccelerationStructureBuildRangeInfoKHR.Buffer range =
            VkAccelerationStructureBuildRangeInfoKHR.calloc(1, stack)
                .primitiveCount(instCount);

        submitCmds(buildInfo, range, stack);
    }

    private void reallocTLAS(VkAccelerationStructureBuildSizesInfoKHR sizes, MemoryStack stack) {
        // Free old
        if (tlasHandle != VK_NULL_HANDLE) vkDestroyAccelerationStructureKHR(ctx.device, tlasHandle, null);
        if (tlasBuffer != VK_NULL_HANDLE) vkDestroyBuffer(ctx.device, tlasBuffer, null);
        if (tlasMemory != VK_NULL_HANDLE) vkFreeMemory(ctx.device, tlasMemory, null);
        if (scratchBuffer != VK_NULL_HANDLE) vkDestroyBuffer(ctx.device, scratchBuffer, null);
        if (scratchMemory != VK_NULL_HANDLE) vkFreeMemory(ctx.device, scratchMemory, null);

        VulkanBuffer asBuf = new VulkanBuffer(ctx.device);
        asBuf.alloc(ctx.physDevice, sizes.accelerationStructureSize(),
            VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR
          | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT,
            VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        tlasBuffer = asBuf.buffer; tlasMemory = asBuf.memory;

        VulkanBuffer scratchBuf = new VulkanBuffer(ctx.device);
        scratchBuf.alloc(ctx.physDevice, sizes.buildScratchSize(),
            VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT,
            VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        scratchBuffer = scratchBuf.buffer; scratchMemory = scratchBuf.memory;

        LongBuffer pAS = stack.mallocLong(1);
        vkCreateAccelerationStructureKHR(ctx.device,
            VkAccelerationStructureCreateInfoKHR.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_CREATE_INFO_KHR)
                .buffer(tlasBuffer)
                .size(sizes.accelerationStructureSize())
                .type(VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR),
            null, pAS);
        tlasHandle = pAS.get(0);
    }

    private void ensureInstanceBuffer(MemoryStack stack) {
        long needed = (long) instances.size() * INSTANCE_STRIDE;
        // Free and reallocate if too small
        if (instanceBuffer != VK_NULL_HANDLE) {
            vkDestroyBuffer(ctx.device, instanceBuffer, null);
            vkFreeMemory(ctx.device, instanceMemory, null);
        }
        VulkanBuffer ib = new VulkanBuffer(ctx.device);
        ib.alloc(ctx.physDevice, needed,
            VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR
          | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT,
            VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
        instanceBuffer = ib.buffer;
        instanceMemory = ib.memory;
        instanceDevAddr = ib.address;
    }

    private long getDeviceAddress(long buffer) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            return vkGetBufferDeviceAddress(ctx.device,
                VkBufferDeviceAddressInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_BUFFER_DEVICE_ADDRESS_INFO)
                    .buffer(buffer));
        }
    }

    // ── Command submit helpers ────────────────────────────────────────────────

    private void submitCmds(VkAccelerationStructureBuildGeometryInfoKHR buildInfo,
                             VkAccelerationStructureBuildRangeInfoKHR.Buffer range,
                             MemoryStack stack) {
        org.lwjgl.PointerBuffer pCmd = stack.mallocPointer(1);
        vkAllocateCommandBuffers(ctx.device,
            VkCommandBufferAllocateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
                .commandPool(commandPool)
                .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                .commandBufferCount(1), pCmd);
        VkCommandBuffer cmd = new VkCommandBuffer(pCmd.get(0), ctx.device);

        vkBeginCommandBuffer(cmd,
            VkCommandBufferBeginInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
                .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT));

        VkAccelerationStructureBuildGeometryInfoKHR.Buffer buildInfoBuf =
                VkAccelerationStructureBuildGeometryInfoKHR.calloc(1, stack);
            buildInfoBuf.put(0, buildInfo);
            vkCmdBuildAccelerationStructuresKHR(cmd, buildInfoBuf, stack.pointers(range.address()));

        vkEndCommandBuffer(cmd);

        LongBuffer pFence = stack.mallocLong(1);
        vkCreateFence(ctx.device,
            VkFenceCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_FENCE_CREATE_INFO),
            null, pFence);
        vkQueueSubmit(ctx.computeQueue,
            VkSubmitInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_SUBMIT_INFO)
                .pCommandBuffers(stack.pointers(cmd)),
            pFence.get(0));
        vkWaitForFences(ctx.device, pFence, true, Long.MAX_VALUE);
        vkDestroyFence(ctx.device, pFence.get(0), null);
        vkFreeCommandBuffers(ctx.device, commandPool, stack.pointers(cmd));
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public long getTLASHandle() { return tlasHandle; }
    public int  instanceCount() { return instances.size(); }

    // ── Cleanup ───────────────────────────────────────────────────────────────

    @Override
    public void close() {
        if (tlasHandle     != VK_NULL_HANDLE) vkDestroyAccelerationStructureKHR(ctx.device, tlasHandle, null);
        if (tlasBuffer     != VK_NULL_HANDLE) vkDestroyBuffer(ctx.device, tlasBuffer, null);
        if (tlasMemory     != VK_NULL_HANDLE) vkFreeMemory(ctx.device, tlasMemory, null);
        if (instanceBuffer != VK_NULL_HANDLE) vkDestroyBuffer(ctx.device, instanceBuffer, null);
        if (instanceMemory != VK_NULL_HANDLE) vkFreeMemory(ctx.device, instanceMemory, null);
        if (scratchBuffer  != VK_NULL_HANDLE) vkDestroyBuffer(ctx.device, scratchBuffer, null);
        if (scratchMemory  != VK_NULL_HANDLE) vkFreeMemory(ctx.device, scratchMemory, null);
        if (commandPool    != VK_NULL_HANDLE) vkDestroyCommandPool(ctx.device, commandPool, null);
    }
}
