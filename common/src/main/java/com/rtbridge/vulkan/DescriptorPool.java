package com.rtbridge.vulkan;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.LongBuffer;

import static org.lwjgl.vulkan.KHRAccelerationStructure.*;
import static org.lwjgl.vulkan.VK12.*;

/**
 * DescriptorPool — allocates and writes descriptor sets for RT passes.
 *
 * One descriptor set per pass (shadow / reflection / GI).
 * Bindings match RTPipeline's descriptor set layout:
 *   0 = TLAS
 *   1 = storage image (output)
 *   2 = depth sampler
 *   3 = normal sampler
 *   4 = camera UBO
 */
public class DescriptorPool implements AutoCloseable {

    private final VulkanContext ctx;
    private long pool = VK_NULL_HANDLE;

    public DescriptorPool(VulkanContext ctx) {
        this.ctx = ctx;
    }

    public void init(int maxSets) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(4, stack);
            sizes.get(0).type(VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR).descriptorCount(maxSets);
            sizes.get(1).type(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)             .descriptorCount(maxSets);
            sizes.get(2).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)    .descriptorCount(maxSets * 2);
            sizes.get(3).type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)            .descriptorCount(maxSets);

            LongBuffer pPool = stack.mallocLong(1);
            VulkanBuffer.check(vkCreateDescriptorPool(ctx.device,
                VkDescriptorPoolCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO)
                    .maxSets(maxSets)
                    .pPoolSizes(sizes),
                null, pPool), "vkCreateDescriptorPool");
            pool = pPool.get(0);
        }
    }

    /**
     * Allocate + write one descriptor set for a shadow/reflection/GI pass.
     *
     * @param layout   descriptor set layout from RTPipeline
     * @param tlas     VkAccelerationStructureKHR handle
     * @param outImage VkImageView of the RT output image (STORAGE)
     * @param depthView VkImageView of depth GBuffer (SAMPLER)
     * @param normalView VkImageView of normal GBuffer (SAMPLER)
     * @param sampler  VkSampler (nearest, clamp-to-edge)
     * @param cameraUBOBuffer VkBuffer of CameraUBO
     */
    public long allocateAndWrite(long layout, long tlas,
                                  long outImage, long depthView,
                                  long normalView, long sampler,
                                  long cameraUBOBuffer) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // Allocate
            LongBuffer pSet = stack.mallocLong(1);
            VulkanBuffer.check(vkAllocateDescriptorSets(ctx.device,
                VkDescriptorSetAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO)
                    .descriptorPool(pool)
                    .pSetLayouts(stack.longs(layout)),
                pSet), "vkAllocateDescriptorSets");
            long set = pSet.get(0);

            // Write binding 0: TLAS
            VkWriteDescriptorSetAccelerationStructureKHR tlasWrite =
                VkWriteDescriptorSetAccelerationStructureKHR.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET_ACCELERATION_STRUCTURE_KHR)
                    .pAccelerationStructures(stack.longs(tlas));

            // Write binding 1: storage image
            VkDescriptorImageInfo.Buffer storageInfo = VkDescriptorImageInfo.calloc(1, stack)
                .imageView(outImage)
                .imageLayout(VK_IMAGE_LAYOUT_GENERAL);

            // Write bindings 2+3: samplers
            VkDescriptorImageInfo.Buffer depthInfo = VkDescriptorImageInfo.calloc(1, stack)
                .sampler(sampler).imageView(depthView)
                .imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);

            VkDescriptorImageInfo.Buffer normalInfo = VkDescriptorImageInfo.calloc(1, stack)
                .sampler(sampler).imageView(normalView)
                .imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);

            // Write binding 4: UBO
            VkDescriptorBufferInfo.Buffer uboInfo = VkDescriptorBufferInfo.calloc(1, stack)
                .buffer(cameraUBOBuffer).offset(0).range(CameraUBO.SIZE);

            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(5, stack);

            writes.get(0).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                .dstSet(set).dstBinding(0)
                .descriptorType(VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR)
                .descriptorCount(1)
                .pNext(tlasWrite.address());

            writes.get(1).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                .dstSet(set).dstBinding(1)
                .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                .descriptorCount(1)
                .pImageInfo(storageInfo);

            writes.get(2).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                .dstSet(set).dstBinding(2)
                .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                .descriptorCount(1)
                .pImageInfo(depthInfo);

            writes.get(3).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                .dstSet(set).dstBinding(3)
                .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                .descriptorCount(1)
                .pImageInfo(normalInfo);

            writes.get(4).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                .dstSet(set).dstBinding(4)
                .descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                .descriptorCount(1)
                .pBufferInfo(uboInfo);

            vkUpdateDescriptorSets(ctx.device, writes, null);
            return set;
        }
    }

    @Override
    public void close() {
        if (pool != VK_NULL_HANDLE) vkDestroyDescriptorPool(ctx.device, pool, null);
    }
}
