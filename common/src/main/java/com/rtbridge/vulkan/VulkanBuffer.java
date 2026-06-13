package com.rtbridge.vulkan;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.LongBuffer;

import static org.lwjgl.vulkan.VK12.*;
import static org.lwjgl.vulkan.KHRAccelerationStructure.*;

public class VulkanBuffer implements AutoCloseable {

    public final VkDevice device;
    public long buffer  = VK_NULL_HANDLE;
    public long memory  = VK_NULL_HANDLE;
    public long address = 0L;
    public long size;

    public VulkanBuffer(VkDevice device) { this.device = device; }

    public void alloc(VkPhysicalDevice physDevice, long sizeBytes, int usage, int memProps) {
        this.size = sizeBytes;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer pBuf = stack.mallocLong(1);
            check(vkCreateBuffer(device,
                VkBufferCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                    .size(sizeBytes).usage(usage)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE),
                null, pBuf), "vkCreateBuffer");
            buffer = pBuf.get(0);

            VkMemoryRequirements req = VkMemoryRequirements.malloc(stack);
            vkGetBufferMemoryRequirements(device, buffer, req);

            LongBuffer pMem = stack.mallocLong(1);
            check(vkAllocateMemory(device,
                VkMemoryAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                    .allocationSize(req.size())
                    .memoryTypeIndex(findMemType(physDevice, req.memoryTypeBits(), memProps, stack))
                    .pNext(VkMemoryAllocateFlagsInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_FLAGS_INFO)
                        .flags(VK_MEMORY_ALLOCATE_DEVICE_ADDRESS_BIT).address()),
                null, pMem), "vkAllocateMemory");
            memory = pMem.get(0);
            check(vkBindBufferMemory(device, buffer, memory, 0), "vkBindBufferMemory");

            if ((usage & VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT) != 0) {
                address = vkGetBufferDeviceAddress(device,
                    VkBufferDeviceAddressInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_BUFFER_DEVICE_ADDRESS_INFO)
                        .buffer(buffer));
            }
        }
    }

    public static VulkanBuffer staging(VkPhysicalDevice phys, VkDevice dev, long size) {
        VulkanBuffer b = new VulkanBuffer(dev);
        b.alloc(phys, size, VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
            VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
        return b;
    }

    public static VulkanBuffer deviceAS(VkPhysicalDevice phys, VkDevice dev, long size) {
        VulkanBuffer b = new VulkanBuffer(dev);
        b.alloc(phys, size,
            VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR
          | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT
          | VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
          | VK_BUFFER_USAGE_TRANSFER_DST_BIT,
            VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        return b;
    }

    public void upload(float[] data) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer pp = stack.mallocPointer(1);
            vkMapMemory(device, memory, 0, size, 0, pp);
            pp.getByteBuffer(0, (int) size).asFloatBuffer().put(data);
            vkUnmapMemory(device, memory);
        }
    }

    public void upload(int[] data) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer pp = stack.mallocPointer(1);
            vkMapMemory(device, memory, 0, size, 0, pp);
            pp.getByteBuffer(0, (int) size).asIntBuffer().put(data);
            vkUnmapMemory(device, memory);
        }
    }

    private static int findMemType(VkPhysicalDevice phys, int typeBits,
                                   int required, MemoryStack stack) {
        VkPhysicalDeviceMemoryProperties mp = VkPhysicalDeviceMemoryProperties.malloc(stack);
        vkGetPhysicalDeviceMemoryProperties(phys, mp);
        for (int i = 0; i < mp.memoryTypeCount(); i++) {
            if ((typeBits & (1 << i)) != 0
                    && (mp.memoryTypes(i).propertyFlags() & required) == required) return i;
        }
        throw new RuntimeException("No suitable memory type (bits=" + typeBits + " req=" + required + ")");
    }

    public static void check(int res, String op) {
        if (res != VK_SUCCESS) throw new RuntimeException(op + " failed: " + res);
    }

    @Override
    public void close() {
        if (buffer != VK_NULL_HANDLE) vkDestroyBuffer(device, buffer, null);
        if (memory != VK_NULL_HANDLE) vkFreeMemory(device, memory, null);
    }
}
