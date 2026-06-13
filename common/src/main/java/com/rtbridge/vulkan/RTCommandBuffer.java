package com.rtbridge.vulkan;

import com.rtbridge.RTBridgeMod;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.LongBuffer;

import static org.lwjgl.vulkan.VK12.*;

/**
 * RTCommandBuffer — 每帧复用的 RT 命令缓冲。
 * 使用 RESET_COMMAND_BUFFER_BIT，每帧重录。
 */
public class RTCommandBuffer implements AutoCloseable {

    public final VulkanContext ctx;

    private long commandPool   = VK_NULL_HANDLE;
    private long commandBuffer = VK_NULL_HANDLE;
    private long fence         = VK_NULL_HANDLE;

    public RTCommandBuffer(VulkanContext ctx) {
        this.ctx = ctx;
        init();
    }

    private void init() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // Command pool
            LongBuffer pPool = stack.mallocLong(1);
            VulkanBuffer.check(vkCreateCommandPool(ctx.device,
                VkCommandPoolCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO)
                    .queueFamilyIndex(ctx.computeQueueFamily)
                    .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT),
                null, pPool), "vkCreateCommandPool RT");
            commandPool = pPool.get(0);

            // Command buffer
            PointerBuffer pCmd = stack.mallocPointer(1);
            VulkanBuffer.check(vkAllocateCommandBuffers(ctx.device,
                VkCommandBufferAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
                    .commandPool(commandPool)
                    .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                    .commandBufferCount(1),
                pCmd), "vkAllocateCommandBuffers RT");
            commandBuffer = pCmd.get(0);

            // Fence (signaled = ready)
            LongBuffer pFence = stack.mallocLong(1);
            VulkanBuffer.check(vkCreateFence(ctx.device,
                VkFenceCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_FENCE_CREATE_INFO)
                    .flags(VK_FENCE_CREATE_SIGNALED_BIT),
                null, pFence), "vkCreateFence RT");
            fence = pFence.get(0);

            RTBridgeMod.LOGGER.info("[RTCommandBuffer] 初始化完成");
        }
    }

    // ── Frame recording ───────────────────────────────────────────────────────

    /** 等待上一帧完成，重置并开始录制 */
    public VkCommandBuffer begin() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // 等待上一帧 fence
            vkWaitForFences(ctx.device, stack.longs(fence), true, Long.MAX_VALUE);
            vkResetFences(ctx.device, stack.longs(fence));

            // 重置命令缓冲
            vkResetCommandBuffer(new VkCommandBuffer(commandBuffer, ctx.device), 0);

            // 开始录制
            VulkanBuffer.check(vkBeginCommandBuffer(
                new VkCommandBuffer(commandBuffer, ctx.device),
                VkCommandBufferBeginInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
                    .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)),
                "vkBeginCommandBuffer RT");

            return new VkCommandBuffer(commandBuffer, ctx.device);
        }
    }

    /** 结束录制并提交，fence 会在完成时触发 */
    public void submitAndSignal() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandBuffer cmd = new VkCommandBuffer(commandBuffer, ctx.device);
            VulkanBuffer.check(vkEndCommandBuffer(cmd), "vkEndCommandBuffer RT");

            PointerBuffer pCmd = stack.pointers(cmd);
            VulkanBuffer.check(vkQueueSubmit(ctx.computeQueue,
                VkSubmitInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SUBMIT_INFO)
                    .pCommandBuffers(pCmd),
                fence), "vkQueueSubmit RT");
        }
    }

    /** 等待当前帧完成（同步路径，调试用） */
    public void waitIdle() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            vkWaitForFences(ctx.device, stack.longs(fence), true, Long.MAX_VALUE);
        }
    }

    @Override
    public void close() {
        if (fence         != VK_NULL_HANDLE) vkDestroyFence(ctx.device, fence, null);
        if (commandPool   != VK_NULL_HANDLE) vkDestroyCommandPool(ctx.device, commandPool, null);
    }
}
