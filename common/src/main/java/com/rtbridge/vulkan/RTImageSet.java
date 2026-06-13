package com.rtbridge.vulkan;

import com.rtbridge.RTBridgeMod;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.LongBuffer;

import static org.lwjgl.vulkan.VK12.*;

/**
 * RTImageSet — allocates the three RT output images:
 *   shadow     R8_UNORM   (shadow factor 0-1)
 *   reflection RGBA16F    (reflection radiance)
 *   gi         RGBA16F    (indirect illumination)
 *
 * Also creates VkImageView and VkSampler for each,
 * so CompositePass can sample them as GL textures via
 * VK_KHR_external_memory interop (TODO: interop bridge).
 */
public class RTImageSet implements AutoCloseable {

    public final VulkanContext ctx;
    public int width, height;

    // Shadow
    public long shadowImage      = VK_NULL_HANDLE;
    public long shadowMemory     = VK_NULL_HANDLE;
    public long shadowView       = VK_NULL_HANDLE;

    // Reflection
    public long reflectionImage  = VK_NULL_HANDLE;
    public long reflectionMemory = VK_NULL_HANDLE;
    public long reflectionView   = VK_NULL_HANDLE;

    // GI
    public long giImage          = VK_NULL_HANDLE;
    public long giMemory         = VK_NULL_HANDLE;
    public long giView           = VK_NULL_HANDLE;

    public RTImageSet(VulkanContext ctx) {
        this.ctx = ctx;
    }

    public void allocate(int width, int height) {
        this.width  = width;
        this.height = height;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long[] sh = createImage(VK_FORMAT_R8_UNORM,  width, height, stack);
            shadowImage = sh[0]; shadowMemory = sh[1];
            shadowView  = createView(shadowImage, VK_FORMAT_R8_UNORM,
                VK_IMAGE_ASPECT_COLOR_BIT, stack);

            long[] ref = createImage(VK_FORMAT_R16G16B16A16_SFLOAT, width, height, stack);
            reflectionImage = ref[0]; reflectionMemory = ref[1];
            reflectionView  = createView(reflectionImage,
                VK_FORMAT_R16G16B16A16_SFLOAT, VK_IMAGE_ASPECT_COLOR_BIT, stack);

            long[] gi = createImage(VK_FORMAT_R16G16B16A16_SFLOAT, width, height, stack);
            giImage = gi[0]; giMemory = gi[1];
            giView  = createView(giImage,
                VK_FORMAT_R16G16B16A16_SFLOAT, VK_IMAGE_ASPECT_COLOR_BIT, stack);

            RTBridgeMod.LOGGER.info("[RTImageSet] Allocated {}x{}", width, height);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private long[] createImage(int format, int w, int h, MemoryStack stack) {
        LongBuffer pImg = stack.mallocLong(1);
        VulkanBuffer.check(vkCreateImage(ctx.device,
            VkImageCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO)
                .imageType(VK_IMAGE_TYPE_2D)
                .format(format)
                .extent(e -> e.width(w).height(h).depth(1))
                .mipLevels(1)
                .arrayLayers(1)
                .samples(VK_SAMPLE_COUNT_1_BIT)
                .tiling(VK_IMAGE_TILING_OPTIMAL)
                .usage(VK_IMAGE_USAGE_STORAGE_BIT
                     | VK_IMAGE_USAGE_SAMPLED_BIT
                     | VK_IMAGE_USAGE_TRANSFER_SRC_BIT)
                .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED),
            null, pImg), "vkCreateImage");

        VkMemoryRequirements req = VkMemoryRequirements.malloc(stack);
        vkGetImageMemoryRequirements(ctx.device, pImg.get(0), req);

        LongBuffer pMem = stack.mallocLong(1);
        VulkanBuffer.check(vkAllocateMemory(ctx.device,
            VkMemoryAllocateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                .allocationSize(req.size())
                .memoryTypeIndex(findMemType(req.memoryTypeBits(),
                    VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, stack)),
            null, pMem), "vkAllocateMemory image");

        vkBindImageMemory(ctx.device, pImg.get(0), pMem.get(0), 0);
        return new long[]{ pImg.get(0), pMem.get(0) };
    }

    private long createView(long image, int format, int aspectMask, MemoryStack stack) {
        LongBuffer pView = stack.mallocLong(1);
        VulkanBuffer.check(vkCreateImageView(ctx.device,
            VkImageViewCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
                .image(image)
                .viewType(VK_IMAGE_VIEW_TYPE_2D)
                .format(format)
                .subresourceRange(r -> r
                    .aspectMask(aspectMask)
                    .levelCount(1)
                    .layerCount(1)),
            null, pView), "vkCreateImageView");
        return pView.get(0);
    }

    private int findMemType(int typeBits, int props, MemoryStack stack) {
        VkPhysicalDeviceMemoryProperties mp =
            VkPhysicalDeviceMemoryProperties.malloc(stack);
        vkGetPhysicalDeviceMemoryProperties(ctx.physDevice, mp);
        for (int i = 0; i < mp.memoryTypeCount(); i++) {
            if ((typeBits & (1 << i)) != 0
                && (mp.memoryTypes(i).propertyFlags() & props) == props) return i;
        }
        throw new RuntimeException("No suitable image memory type");
    }

    @Override
    public void close() {
        if (shadowView      != VK_NULL_HANDLE) vkDestroyImageView(ctx.device, shadowView,      null);
        if (reflectionView  != VK_NULL_HANDLE) vkDestroyImageView(ctx.device, reflectionView,  null);
        if (giView          != VK_NULL_HANDLE) vkDestroyImageView(ctx.device, giView,          null);
        if (shadowImage     != VK_NULL_HANDLE) vkDestroyImage(ctx.device, shadowImage,         null);
        if (reflectionImage != VK_NULL_HANDLE) vkDestroyImage(ctx.device, reflectionImage,     null);
        if (giImage         != VK_NULL_HANDLE) vkDestroyImage(ctx.device, giImage,             null);
        if (shadowMemory    != VK_NULL_HANDLE) vkFreeMemory(ctx.device, shadowMemory,          null);
        if (reflectionMemory!= VK_NULL_HANDLE) vkFreeMemory(ctx.device, reflectionMemory,      null);
        if (giMemory        != VK_NULL_HANDLE) vkFreeMemory(ctx.device, giMemory,              null);
    }
}
