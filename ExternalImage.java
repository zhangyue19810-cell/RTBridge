package com.rtbridge.vulkan;

import com.rtbridge.RTBridgeMod;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.LongBuffer;

import static org.lwjgl.opengl.EXTMemoryObject.*;
import static org.lwjgl.opengl.EXTMemoryObjectWin32.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.*;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.vulkan.KHRExternalMemory.*;
import static org.lwjgl.vulkan.KHRExternalMemoryWin32.*;
import static org.lwjgl.vulkan.VK12.*;

/**
 * ExternalImage — 一张同时被 Vulkan 和 OpenGL 访问的图像。
 */
public class ExternalImage implements AutoCloseable {

    public final VulkanContext ctx;

    // Vulkan 资源
    public long vkImage  = VK_NULL_HANDLE;
    public long vkMemory = VK_NULL_HANDLE;
    public long vkView   = VK_NULL_HANDLE;
    public long deviceAddress = 0;

    // OpenGL 资源
    public int glMemObj = -1;
    public int glTexId  = -1;

    // 图像属性
    public final int  vkFormat;
    public final int  glFormat;
    public final int  glInternalFormat;
    public final int  width, height;

    public long sizeBytes = 0;

    public ExternalImage(VulkanContext ctx,
                         int width, int height,
                         int vkFormat, int glFormat, int glInternalFormat) {
        this.ctx              = ctx;
        this.width            = width;
        this.height           = height;
        this.vkFormat         = vkFormat;
        this.glFormat         = glFormat;
        this.glInternalFormat = glInternalFormat;
    }

    public boolean init() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            if (!createVulkanImage(stack)) return false;
            if (!exportToGL(stack)) return false;

            RTBridgeMod.LOGGER.info(
                "[ExternalImage] 共享图像创建: {}x{} glTex={} size={}B",
                width, height, glTexId, sizeBytes
            );
            return true;
        } catch (Throwable e) {
            RTBridgeMod.LOGGER.error("[ExternalImage] 初始化失败: {}", e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    private boolean createVulkanImage(MemoryStack stack) {
        VkExternalMemoryImageCreateInfo extMemInfo =
            VkExternalMemoryImageCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO)
                .handleTypes(VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT_KHR);

        LongBuffer pImage = stack.mallocLong(1);

        VulkanBuffer.check(vkCreateImage(ctx.device,
            VkImageCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO)
                .imageType(VK_IMAGE_TYPE_2D)
                .format(vkFormat)
                .extent(e -> e.width(width).height(height).depth(1))
                .mipLevels(1).arrayLayers(1)
                .samples(VK_SAMPLE_COUNT_1_BIT)
                .tiling(VK_IMAGE_TILING_OPTIMAL)
                .usage(VK_IMAGE_USAGE_STORAGE_BIT
                     | VK_IMAGE_USAGE_SAMPLED_BIT
                     | VK_IMAGE_USAGE_TRANSFER_SRC_BIT)
                .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
                .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED)
                .pNext(extMemInfo.address()),
            null, pImage), "vkCreateImage external");

        vkImage = pImage.get(0);

        VkMemoryRequirements req = VkMemoryRequirements.malloc(stack);
        vkGetImageMemoryRequirements(ctx.device, vkImage, req);

        this.sizeBytes = req.size();

        VkExportMemoryAllocateInfo exportInfo =
            VkExportMemoryAllocateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_EXPORT_MEMORY_ALLOCATE_INFO)
                .handleTypes(VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT_KHR);

        LongBuffer pMem = stack.mallocLong(1);

        VulkanBuffer.check(vkAllocateMemory(ctx.device,
            VkMemoryAllocateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                .allocationSize(this.sizeBytes)
                .memoryTypeIndex(findDeviceLocalMemType(req.memoryTypeBits(), stack))
                .pNext(exportInfo.address()),
            null, pMem), "vkAllocateMemory external");

        vkMemory = pMem.get(0);

        VulkanBuffer.check(vkBindImageMemory(ctx.device, vkImage, vkMemory, 0),
            "vkBindImageMemory external");

        LongBuffer pView = stack.mallocLong(1);

        VulkanBuffer.check(vkCreateImageView(ctx.device,
            VkImageViewCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
                .image(vkImage)
                .viewType(VK_IMAGE_VIEW_TYPE_2D)
                .format(vkFormat)
                .subresourceRange(r -> r
                    .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .levelCount(1)
                    .layerCount(1)),
            null, pView), "vkCreateImageView external");

        vkView = pView.get(0);

        return true;
    }

    private boolean exportToGL(MemoryStack stack) {
        PointerBuffer pHandle = stack.mallocPointer(1);

        VulkanBuffer.check(vkGetMemoryWin32HandleKHR(ctx.device,
            VkMemoryGetWin32HandleInfoKHR.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_MEMORY_GET_WIN32_HANDLE_INFO_KHR)
                .memory(vkMemory)
                .handleType(VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT_KHR),
            pHandle), "vkGetMemoryWin32HandleKHR");

        long handle = pHandle.get(0);

        glMemObj = glCreateMemoryObjectsEXT();

        glImportMemoryWin32HandleEXT(glMemObj, this.sizeBytes,
            GL_HANDLE_TYPE_OPAQUE_WIN32_EXT, handle);

        glTexId = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, glTexId);

        glTexStorageMem2DEXT(GL_TEXTURE_2D, 1, glInternalFormat,
            width, height, glMemObj, 0);

        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

        glBindTexture(GL_TEXTURE_2D, 0);

        if (!glIsTexture(glTexId)) {
            RTBridgeMod.LOGGER.error("[ExternalImage] GL 纹理无效，Win32 interop 失败");
            glTexId = -1;
            return false;
        }

        return true;
    }

    public long createVkSemaphore(MemoryStack stack) {
        VkExportSemaphoreCreateInfo exportInfo =
            VkExportSemaphoreCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_EXPORT_SEMAPHORE_CREATE_INFO)
                .handleTypes(VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_WIN32_BIT_KHR);

        LongBuffer pSem = stack.mallocLong(1);

        vkCreateSemaphore(ctx.device,
            VkSemaphoreCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO)
                .pNext(exportInfo.address()),
            null, pSem);

        return pSem.get(0);
    }

    private int findDeviceLocalMemType(int typeBits, MemoryStack stack) {
        VkPhysicalDeviceMemoryProperties mp =
            VkPhysicalDeviceMemoryProperties.malloc(stack);

        vkGetPhysicalDeviceMemoryProperties(ctx.physDevice, mp);

        for (int i = 0; i < mp.memoryTypeCount(); i++) {
            if ((typeBits & (1 << i)) != 0 &&
                (mp.memoryTypes(i).propertyFlags()
                    & VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT) != 0) {
                return i;
            }
        }

        throw new RuntimeException("找不到设备本地内存类型");
    }

    private static final int VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT_KHR = 0x00000001;
    private static final int VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_WIN32_BIT_KHR = 0x00000001;

    @Override
    public void close() {
        if (glTexId >= 0) {
            glDeleteTextures(glTexId);
            glTexId = -1;
        }
        if (glMemObj >= 0) {
            glDeleteMemoryObjectsEXT(glMemObj);
            glMemObj = -1;
        }
        if (vkView != VK_NULL_HANDLE)
            vkDestroyImageView(ctx.device, vkView, null);

        if (vkImage != VK_NULL_HANDLE)
            vkDestroyImage(ctx.device, vkImage, null);

        if (vkMemory != VK_NULL_HANDLE)
            vkFreeMemory(ctx.device, vkMemory, null);
    }
}
