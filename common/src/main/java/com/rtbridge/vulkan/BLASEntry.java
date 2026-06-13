package com.rtbridge.vulkan;

/**
 * Holds Vulkan handles for one built BLAS.
 * Freed explicitly by AsyncBLASBuilder.destroy(ownerId).
 */
public class BLASEntry {
    public long asHandle      = 0L;  // VkAccelerationStructureKHR
    public long asBuffer      = 0L;  // VkBuffer backing the AS
    public long asMemory      = 0L;  // VkDeviceMemory
    public long deviceAddress = 0L;  // queried via vkGetAccelerationStructureDeviceAddressKHR
    public final long ownerId;

    public BLASEntry(long ownerId) { this.ownerId = ownerId; }
}
