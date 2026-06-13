package com.rtbridge.bvh;

import org.lwjgl.PointerBuffer;
import com.rtbridge.RTBridgeMod;
import com.rtbridge.vulkan.BLASEntry;
import com.rtbridge.vulkan.VulkanBuffer;
import com.rtbridge.vulkan.VulkanContext;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.LongBuffer;
import java.util.Map;
import java.util.concurrent.*;

import static org.lwjgl.vulkan.KHRAccelerationStructure.*;
import static org.lwjgl.vulkan.VK12.*;

/**
 * AsyncBLASBuilder — real Vulkan BLAS construction.
 *
 * Each build runs on a dedicated thread (never the main/GL thread).
 * The caller receives a BLASEntry via callback when the build is done.
 *
 * Build steps per task:
 *   1. Upload vertex + index data via staging buffer → device-local buffer
 *   2. Fill VkAccelerationStructureGeometryKHR
 *   3. Query scratch size via vkGetAccelerationStructureBuildSizesKHR
 *   4. Allocate AS buffer + scratch buffer
 *   5. vkCreateAccelerationStructureKHR
 *   6. vkCmdBuildAccelerationStructuresKHR (one-shot command buffer)
 *   7. vkQueueSubmit + fence wait
 *   8. Query device address, fire callback
 */
public class AsyncBLASBuilder {

    public record BLASTask(
        long      ownerId,
        float[]   vertices,   // packed xyz, null = placeholder AABB
        int[]     indices,
        BLASReadyCallback callback
    ) {}

    @FunctionalInterface
    public interface BLASReadyCallback {
        void onBLASReady(long ownerId, BLASEntry entry);
    }

    // ── State ─────────────────────────────────────────────────────────────────

    private final VulkanContext             ctx;
    private final BlockingQueue<BLASTask>   queue    = new LinkedBlockingQueue<>();
    private final Map<Long, BLASEntry>      built    = new ConcurrentHashMap<>();
    private final ExecutorService           thread;

    private long commandPool = VK_NULL_HANDLE;

    // ── Init ──────────────────────────────────────────────────────────────────

    public AsyncBLASBuilder(VulkanContext ctx) {
        this.ctx = ctx;
        this.thread = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "RTBridge-BLASBuilder");
            t.setDaemon(true);
            return t;
        });
        thread.submit(this::init);
        thread.submit(this::buildLoop);
    }

    private void init() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandPoolCreateInfo ci = VkCommandPoolCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO)
                .queueFamilyIndex(ctx.computeQueueFamily)
                .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT);
            LongBuffer pPool = stack.mallocLong(1);
            check(vkCreateCommandPool(ctx.device, ci, null, pPool), "vkCreateCommandPool");
            commandPool = pPool.get(0);
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public void submitShip(long shipId, float[] verts, int[] idx, BLASReadyCallback cb) {
        queue.add(new BLASTask(shipId, verts, idx, cb));
    }

    public void submitChunk(long chunkKey, float[] verts, int[] idx, BLASReadyCallback cb) {
        queue.add(new BLASTask(chunkKey, verts, idx, cb));
    }

    /** Placeholder: tiny 1×1×1 AABB, built immediately as a cheap stand-in. */
    public void submitPlaceholder(long ownerId, BLASReadyCallback cb) {
        queue.add(new BLASTask(ownerId, null, null, cb));
    }

    public void destroy(long ownerId) {
        BLASEntry e = built.remove(ownerId);
        if (e != null) freeBLAS(e);
    }

    // ── Build loop ────────────────────────────────────────────────────────────

    private void buildLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                BLASTask task = queue.take();
                BLASEntry entry = task.vertices() == null
                    ? buildPlaceholder(task.ownerId())
                    : buildGeometry(task);
                if (entry != null) {
                    built.put(task.ownerId(), entry);
                    task.callback().onBLASReady(task.ownerId(), entry);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                RTBridgeMod.LOGGER.error("[BLASBuilder] Build failed", e);
            }
        }
    }

    // ── Geometry BLAS ─────────────────────────────────────────────────────────

    private BLASEntry buildGeometry(BLASTask task) {
        try (MemoryStack stack = MemoryStack.stackPush()) {

            int vertCount  = task.vertices().length / 3;
            int primCount  = task.indices().length  / 3;
            long vbSize    = (long) task.vertices().length * Float.BYTES;
            long ibSize    = (long) task.indices().length  * Integer.BYTES;

            // 1. Upload geometry to device-local buffers via staging
            VulkanBuffer vbStage = VulkanBuffer.staging(ctx.physDevice, ctx.device, vbSize);
            VulkanBuffer ibStage = VulkanBuffer.staging(ctx.physDevice, ctx.device, ibSize);
            vbStage.upload(task.vertices());
            ibStage.upload(task.indices());

            VulkanBuffer vb = VulkanBuffer.deviceAS(ctx.physDevice, ctx.device, vbSize);
            VulkanBuffer ib = VulkanBuffer.deviceAS(ctx.physDevice, ctx.device, ibSize);

            // Copy staging → device (one-shot cmd buffer)
            copyBuffer(vbStage.buffer, vb.buffer, vbSize);
            copyBuffer(ibStage.buffer, ib.buffer, ibSize);
            vbStage.close(); ibStage.close();

            // 2. Geometry descriptor
            VkAccelerationStructureGeometryTrianglesDataKHR triData =
                VkAccelerationStructureGeometryTrianglesDataKHR.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_GEOMETRY_TRIANGLES_DATA_KHR)
                    .vertexFormat(VK_FORMAT_R32G32B32_SFLOAT)
                    .vertexData(VkDeviceOrHostAddressConstKHR.calloc(stack).deviceAddress(vb.address))
                    .vertexStride(3L * Float.BYTES)
                    .maxVertex(vertCount - 1)
                    .indexType(VK_INDEX_TYPE_UINT32)
                    .indexData(VkDeviceOrHostAddressConstKHR.calloc(stack).deviceAddress(ib.address));

            VkAccelerationStructureGeometryKHR.Buffer geomBuf =
                VkAccelerationStructureGeometryKHR.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_GEOMETRY_KHR)
                    .geometryType(VK_GEOMETRY_TYPE_TRIANGLES_KHR)
                    .flags(VK_GEOMETRY_OPAQUE_BIT_KHR);
            geomBuf.get(0).geometry().triangles(triData);

            // 3. Query sizes
            VkAccelerationStructureBuildGeometryInfoKHR buildInfo =
                VkAccelerationStructureBuildGeometryInfoKHR.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_BUILD_GEOMETRY_INFO_KHR)
                    .type(VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR)
                    .flags(VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR)
                    .pGeometries(geomBuf);

            VkAccelerationStructureBuildSizesInfoKHR sizes =
                VkAccelerationStructureBuildSizesInfoKHR.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_BUILD_SIZES_INFO_KHR);

            vkGetAccelerationStructureBuildSizesKHR(
                ctx.device,
                VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR,
                buildInfo,
                stack.ints(primCount),
                sizes);

            // 4. Allocate AS buffer + scratch
            VulkanBuffer asBuffer = new VulkanBuffer(ctx.device);
            asBuffer.alloc(ctx.physDevice, sizes.accelerationStructureSize(),
                VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR
              | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT,
                VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);

            VulkanBuffer scratch = new VulkanBuffer(ctx.device);
            scratch.alloc(ctx.physDevice, sizes.buildScratchSize(),
                VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
              | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT,
                VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);

            // 5. Create AS handle
            LongBuffer pAS = stack.mallocLong(1);
            VkAccelerationStructureCreateInfoKHR asCI =
                VkAccelerationStructureCreateInfoKHR.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_CREATE_INFO_KHR)
                    .buffer(asBuffer.buffer)
                    .size(sizes.accelerationStructureSize())
                    .type(VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR);
            check(vkCreateAccelerationStructureKHR(ctx.device, asCI, null, pAS),
                "vkCreateAccelerationStructureKHR BLAS");
            long asHandle = pAS.get(0);

            // 6. Build on GPU
            buildInfo
                .dstAccelerationStructure(asHandle)
                .scratchData(VkDeviceOrHostAddressKHR.calloc(stack).deviceAddress(scratch.address));

            VkAccelerationStructureBuildRangeInfoKHR.Buffer rangeInfo =
                VkAccelerationStructureBuildRangeInfoKHR.calloc(1, stack)
                    .primitiveCount(primCount)
                    .primitiveOffset(0)
                    .firstVertex(0)
                    .transformOffset(0);

            submitBuildCmds(buildInfo, rangeInfo, stack);
            scratch.close();

            // 7. Query device address
            VkAccelerationStructureDeviceAddressInfoKHR addrInfo =
                VkAccelerationStructureDeviceAddressInfoKHR.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_DEVICE_ADDRESS_INFO_KHR)
                    .accelerationStructure(asHandle);
            long devAddr = vkGetAccelerationStructureDeviceAddressKHR(ctx.device, addrInfo);

            BLASEntry entry = new BLASEntry(task.ownerId());
            entry.asHandle    = asHandle;
            entry.asBuffer    = asBuffer.buffer;
            entry.asMemory    = asBuffer.memory;
            entry.deviceAddress = devAddr;

            RTBridgeMod.LOGGER.debug("[BLASBuilder] Geometry BLAS built for owner {}, addr=0x{:x}",
                task.ownerId(), devAddr);
            return entry;

        } catch (Exception e) {
            RTBridgeMod.LOGGER.error("[BLASBuilder] buildGeometry failed for {}", task.ownerId(), e);
            return null;
        }
    }

    // ── Placeholder AABB BLAS ─────────────────────────────────────────────────

    private BLASEntry buildPlaceholder(long ownerId) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // Upload a 1×1×1 AABB centered at origin
            float[] aabbData = { -0.5f, -0.5f, -0.5f, 0.5f, 0.5f, 0.5f };
            long aabbSize = (long) aabbData.length * Float.BYTES;

            VulkanBuffer stageBuf = VulkanBuffer.staging(ctx.physDevice, ctx.device, aabbSize);
            stageBuf.upload(aabbData);
            VulkanBuffer aabbBuf  = VulkanBuffer.deviceAS(ctx.physDevice, ctx.device, aabbSize);
            copyBuffer(stageBuf.buffer, aabbBuf.buffer, aabbSize);
            stageBuf.close();

            VkAccelerationStructureGeometryAabbsDataKHR aabbsData =
                VkAccelerationStructureGeometryAabbsDataKHR.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_GEOMETRY_AABBS_DATA_KHR)
                    .data(VkDeviceOrHostAddressConstKHR.calloc(stack).deviceAddress(aabbBuf.address))
                    .stride(6L * Float.BYTES);

            VkAccelerationStructureGeometryKHR.Buffer geomBuf =
                VkAccelerationStructureGeometryKHR.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_GEOMETRY_KHR)
                    .geometryType(VK_GEOMETRY_TYPE_AABBS_KHR);
            geomBuf.get(0).geometry().aabbs(aabbsData);

            VkAccelerationStructureBuildGeometryInfoKHR buildInfo =
                VkAccelerationStructureBuildGeometryInfoKHR.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_BUILD_GEOMETRY_INFO_KHR)
                    .type(VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR)
                    .flags(VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_BUILD_BIT_KHR)
                    .pGeometries(geomBuf);

            VkAccelerationStructureBuildSizesInfoKHR sizes =
                VkAccelerationStructureBuildSizesInfoKHR.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_BUILD_SIZES_INFO_KHR);
            vkGetAccelerationStructureBuildSizesKHR(ctx.device,
                VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR,
                buildInfo, stack.ints(1), sizes);

            VulkanBuffer asBuffer = new VulkanBuffer(ctx.device);
            asBuffer.alloc(ctx.physDevice, sizes.accelerationStructureSize(),
                VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR
              | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT,
                VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);

            VulkanBuffer scratch = new VulkanBuffer(ctx.device);
            scratch.alloc(ctx.physDevice, sizes.buildScratchSize(),
                VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT,
                VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);

            LongBuffer pAS = stack.mallocLong(1);
            vkCreateAccelerationStructureKHR(ctx.device,
                VkAccelerationStructureCreateInfoKHR.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_CREATE_INFO_KHR)
                    .buffer(asBuffer.buffer)
                    .size(sizes.accelerationStructureSize())
                    .type(VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR),
                null, pAS);
            long asHandle = pAS.get(0);

            buildInfo.dstAccelerationStructure(asHandle)
                     .scratchData(VkDeviceOrHostAddressKHR.calloc(stack).deviceAddress(scratch.address));
            VkAccelerationStructureBuildRangeInfoKHR.Buffer range =
                VkAccelerationStructureBuildRangeInfoKHR.calloc(1, stack)
                    .primitiveCount(1);
            submitBuildCmds(buildInfo, range, stack);
            scratch.close();

            long devAddr = vkGetAccelerationStructureDeviceAddressKHR(ctx.device,
                VkAccelerationStructureDeviceAddressInfoKHR.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_DEVICE_ADDRESS_INFO_KHR)
                    .accelerationStructure(asHandle));

            BLASEntry entry = new BLASEntry(ownerId);
            entry.asHandle = asHandle;
            entry.asBuffer = asBuffer.buffer;
            entry.asMemory = asBuffer.memory;
            entry.deviceAddress = devAddr;
            return entry;
        }
    }

    // ── Command helpers ───────────────────────────────────────────────────────

    private void submitBuildCmds(VkAccelerationStructureBuildGeometryInfoKHR buildInfo,
                                  VkAccelerationStructureBuildRangeInfoKHR.Buffer range,
                                  MemoryStack stack) {
        long cmdBuf = allocCmdBuffer(stack);
        beginCmdBuffer(cmdBuf, stack);

        // PointerBuffer of range pointers (one per geometry)
        PointerBuffer ppRanges = stack.pointers(range.address());
        VkAccelerationStructureBuildGeometryInfoKHR.Buffer buildBuf =
            VkAccelerationStructureBuildGeometryInfoKHR.calloc(1, stack);
            buildBuf.put(0, buildInfo);
            vkCmdBuildAccelerationStructuresKHR(
                new VkCommandBuffer(cmdBuf, ctx.device),
                buildBuf, ppRanges);

        // Barrier: AS build → shader read (VK 1.2 pipeline barrier)
        VkMemoryBarrier.Buffer memBarrier = VkMemoryBarrier.calloc(1, stack)
            .sType(VK_STRUCTURE_TYPE_MEMORY_BARRIER)
            .srcAccessMask(VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR)
            .dstAccessMask(VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR);
        vkCmdPipelineBarrier(new VkCommandBuffer(cmdBuf, ctx.device),
            VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
            VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
            0, memBarrier, null, null);

        submitAndWait(cmdBuf, stack);
    }

    private long allocCmdBuffer(MemoryStack stack) {
        org.lwjgl.PointerBuffer pCmd = stack.mallocPointer(1);
        check(vkAllocateCommandBuffers(ctx.device,
            VkCommandBufferAllocateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
                .commandPool(commandPool)
                .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                .commandBufferCount(1),
            pCmd), "vkAllocateCommandBuffers");
        return pCmd.get(0);
    }

    private void beginCmdBuffer(long cmdBuf, MemoryStack stack) {
        check(vkBeginCommandBuffer(
            new VkCommandBuffer(cmdBuf, ctx.device),
            VkCommandBufferBeginInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
                .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)),
            "vkBeginCommandBuffer");
    }

    private void submitAndWait(long cmdBuf, MemoryStack stack) {
        VkCommandBuffer vkCmd = new VkCommandBuffer(cmdBuf, ctx.device);
        check(vkEndCommandBuffer(vkCmd), "vkEndCommandBuffer");

        LongBuffer pFence = stack.mallocLong(1);
        check(vkCreateFence(ctx.device,
            VkFenceCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_FENCE_CREATE_INFO),
            null, pFence), "vkCreateFence");
        long fence = pFence.get(0);

        PointerBuffer pCmds = stack.pointers(vkCmd);
        check(vkQueueSubmit(ctx.computeQueue,
            VkSubmitInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_SUBMIT_INFO)
                .pCommandBuffers(pCmds),
            fence), "vkQueueSubmit");

        vkWaitForFences(ctx.device, pFence, true, Long.MAX_VALUE);
        vkDestroyFence(ctx.device, fence, null);
        vkFreeCommandBuffers(ctx.device, commandPool, pCmds);
    }

    private void copyBuffer(long src, long dst, long size) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long cmd = allocCmdBuffer(stack);
            beginCmdBuffer(cmd, stack);
            vkCmdCopyBuffer(new VkCommandBuffer(cmd, ctx.device), src, dst,
                VkBufferCopy.calloc(1, stack).size(size));
            submitAndWait(cmd, stack);
        }
    }

    private void freeBLAS(BLASEntry e) {
        if (e.asHandle != 0) vkDestroyAccelerationStructureKHR(ctx.device, e.asHandle, null);
        if (e.asBuffer != 0) vkDestroyBuffer(ctx.device, e.asBuffer, null);
        if (e.asMemory != 0) vkFreeMemory(ctx.device, e.asMemory, null);
    }

    private static void check(int res, String op) {
        if (res != VK_SUCCESS) throw new RuntimeException(op + " failed: " + res);
    }

    public void shutdown() {
        thread.shutdown();
        built.values().forEach(this::freeBLAS);
        if (commandPool != VK_NULL_HANDLE)
            vkDestroyCommandPool(ctx.device, commandPool, null);
    }
}
