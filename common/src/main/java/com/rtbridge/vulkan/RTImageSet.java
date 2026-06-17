package com.rtbridge.vulkan;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.vulkan.VK12.*;

import com.rtbridge.RTBridgeMod;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.LongBuffer;

/**
 * RTImageSet — allocates the three RT output images:
 *   shadow     R8_UNORM   (shadow factor 0-1)
 *   reflection RGBA16F    (reflection radiance)
 *   gi         RGBA16F    (indirect illumination)
 */
public class RTImageSet implements AutoCloseable {

    public ExternalImage shadowExt = null;
    public ExternalImage reflectionExt = null;
    public ExternalImage giExt = null;
    public boolean externalReady = false;

    public final VulkanContext ctx;
    public int width, height;

    public long shadowImage = VK_NULL_HANDLE;
    public long shadowMemory = VK_NULL_HANDLE;
    public long shadowView = VK_NULL_HANDLE;

    public long reflectionImage = VK_NULL_HANDLE;
    public long reflectionMemory = VK_NULL_HANDLE;
    public long reflectionView = VK_NULL_HANDLE;

    public long giImage = VK_NULL_HANDLE;
    public long giMemory = VK_NULL_HANDLE;
    public long giView = VK_NULL_HANDLE;

    public RTImageSet(VulkanContext ctx) {
        this.ctx = ctx;
    }

    public void allocate(int width, int height) {
        this.width = width;
        this.height = height;

        try (MemoryStack stack = MemoryStack.stackPush()) {

            long[] sh = createImage(VK_FORMAT_R8G8B8A8_UNORM, width, height, stack);
            shadowImage = sh[0];
            shadowMemory = sh[1];
            shadowView = createView(shadowImage, VK_FORMAT_R8G8B8A8_UNORM,
                    VK_IMAGE_ASPECT_COLOR_BIT, stack);

            long[] ref = createImage(VK_FORMAT_R16G16B16A16_SFLOAT, width, height, stack);
            reflectionImage = ref[0];
            reflectionMemory = ref[1];
            reflectionView = createView(reflectionImage,
                    VK_FORMAT_R16G16B16A16_SFLOAT, VK_IMAGE_ASPECT_COLOR_BIT, stack);

            long[] gi = createImage(VK_FORMAT_R16G16B16A16_SFLOAT, width, height, stack);
            giImage = gi[0];
            giMemory = gi[1];
            giView = createView(giImage,
                    VK_FORMAT_R16G16B16A16_SFLOAT, VK_IMAGE_ASPECT_COLOR_BIT, stack);

            RTBridgeMod.LOGGER.info("[RTImageSet] Allocated {}x{}", width, height);
        }
        allocateShadowStaging();
    }

    // ── CPU 回读 staging buffer（持久映射）─────────────────────────────────────
    public long shadowStagingBuffer = VK_NULL_HANDLE;
    public long shadowStagingMemory = VK_NULL_HANDLE;
    public long shadowStagingPtr    = 0L;
    public long shadowStagingSize   = 0L;

    private void allocateShadowStaging() {
        try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
            shadowStagingSize = (long) width * height * 4; // RGBA8

            java.nio.LongBuffer pBuf = stack.mallocLong(1);
            VulkanBuffer.check(vkCreateBuffer(ctx.device,
                VkBufferCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                    .size(shadowStagingSize)
                    .usage(VK_BUFFER_USAGE_TRANSFER_DST_BIT)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE),
                null, pBuf), "vkCreateBuffer shadowStaging");
            shadowStagingBuffer = pBuf.get(0);

            VkMemoryRequirements req = VkMemoryRequirements.malloc(stack);
            vkGetBufferMemoryRequirements(ctx.device, shadowStagingBuffer, req);

            int memType = -1;
            VkPhysicalDeviceMemoryProperties mp = VkPhysicalDeviceMemoryProperties.malloc(stack);
            vkGetPhysicalDeviceMemoryProperties(ctx.physDevice, mp);
            for (int i = 0; i < mp.memoryTypeCount(); i++) {
                if ((req.memoryTypeBits() & (1 << i)) != 0
                    && (mp.memoryTypes(i).propertyFlags()
                        & (VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT))
                       == (VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)) {
                    memType = i; break;
                }
            }

            java.nio.LongBuffer pMem = stack.mallocLong(1);
            VulkanBuffer.check(vkAllocateMemory(ctx.device,
                VkMemoryAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                    .allocationSize(req.size())
                    .memoryTypeIndex(memType),
                null, pMem), "vkAllocateMemory shadowStaging");
            shadowStagingMemory = pMem.get(0);
            vkBindBufferMemory(ctx.device, shadowStagingBuffer, shadowStagingMemory, 0);

            // 持久映射
            org.lwjgl.PointerBuffer pData = stack.mallocPointer(1);
            vkMapMemory(ctx.device, shadowStagingMemory, 0, shadowStagingSize, 0, pData);
            shadowStagingPtr = pData.get(0);

            RTBridgeMod.LOGGER.info("[RTImageSet] Shadow staging buffer 就绪 {}KB",
                shadowStagingSize / 1024);
        } catch (Throwable e) {
            RTBridgeMod.LOGGER.error("[RTImageSet] Shadow staging 分配失败: {}", e.getMessage());
        }
    }

    public void initExternalImages() {
        try {
            shadowExt = new ExternalImage(ctx, width, height,
                    VK_FORMAT_R8G8B8A8_UNORM, GL_RED, GL_R8);

            reflectionExt = new ExternalImage(ctx, width, height,
                    VK_FORMAT_R16G16B16A16_SFLOAT, GL_RGBA, GL_RGBA16F);

            giExt = new ExternalImage(ctx, width, height,
                    VK_FORMAT_R16G16B16A16_SFLOAT, GL_RGBA, GL_RGBA16F);

            if (shadowExt.init() && reflectionExt.init() && giExt.init()) {
                externalReady = true;
                RTBridgeMod.LOGGER.info("[RTImageSet] GL-Vulkan 共享图像就绪");
            }

        } catch (Throwable e) {
            RTBridgeMod.LOGGER.warn("[RTImageSet] GL-Vulkan interop 失败: {}",
                    e.getMessage());
            externalReady = false;
        }
    }

    public int getShadowGLTex() {
        return externalReady ? shadowExt.glTexId : -1;
    }

    public int getReflectionGLTex() {
        return externalReady ? reflectionExt.glTexId : -1;
    }

    public int getGIGLTex() {
        return externalReady ? giExt.glTexId : -1;
    }

    public long getShadowVkView() {
        return externalReady ? shadowExt.vkView : shadowView;
    }

    public long getReflectionVkView() {
        return externalReady ? reflectionExt.vkView : reflectionView;
    }

    public long getGIVkView() {
        return externalReady ? giExt.vkView : giView;
    }

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
        return new long[]{pImg.get(0), pMem.get(0)};
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
                    && (mp.memoryTypes(i).propertyFlags() & props) == props) {
                return i;
            }
        }
        throw new RuntimeException("No suitable image memory type");
    }

    @Override
    public void close() {
        if (shadowView != VK_NULL_HANDLE) vkDestroyImageView(ctx.device, shadowView, null);
        if (reflectionView != VK_NULL_HANDLE) vkDestroyImageView(ctx.device, reflectionView, null);
        if (giView != VK_NULL_HANDLE) vkDestroyImageView(ctx.device, giView, null);

        if (shadowImage != VK_NULL_HANDLE) vkDestroyImage(ctx.device, shadowImage, null);
        if (reflectionImage != VK_NULL_HANDLE) vkDestroyImage(ctx.device, reflectionImage, null);
        if (giImage != VK_NULL_HANDLE) vkDestroyImage(ctx.device, giImage, null);

        if (shadowMemory != VK_NULL_HANDLE) vkFreeMemory(ctx.device, shadowMemory, null);
        if (reflectionMemory != VK_NULL_HANDLE) vkFreeMemory(ctx.device, reflectionMemory, null);
        if (giMemory != VK_NULL_HANDLE) vkFreeMemory(ctx.device, giMemory, null);
    }
}
