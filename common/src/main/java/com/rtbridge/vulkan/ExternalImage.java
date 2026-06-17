package com.rtbridge.vulkan;
import org.lwjgl.opengl.EXTMemoryObjectWin32;

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
 *
 * 创建流程：
 *   1. Vulkan 创建 VkImage（带 VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT）
 *   2. 导出 Win32 HANDLE
 *   3. OpenGL 通过 EXT_memory_object_win32 导入，创建 GL 纹理
 *
 * 使用：
 *   - Vulkan RT pass 写入 vkImage（layout = GENERAL）
 *   - 内存屏障后 GL 采样 glTexId（layout = SHADER_READ_ONLY）
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
    public final long sizeBytes;

    // ── 构造 ─────────────────────────────────────────────────────────────────

    public ExternalImage(VulkanContext ctx,
                         int width, int height,
                         int vkFormat, int glFormat, int glInternalFormat) {
        this.ctx             = ctx;
        this.width           = width;
        this.height          = height;
        this.vkFormat        = vkFormat;
        this.glFormat        = glFormat;
        this.glInternalFormat= glInternalFormat;
        this.sizeBytes       = computeSize(vkFormat, width, height);
    }

    // ── 初始化（GL 线程调用）──────────────────────────────────────────────────

    public boolean init() {
        RTBridgeMod.LOGGER.info("[ExternalImage] init begin {}x{}", width, height);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            if (!createVulkanImage(stack))  return false;
            if (!exportToGL(stack))         return false;
            RTBridgeMod.LOGGER.info("[ExternalImage] 共享图像创建: {}x{} glTex={}",
                width, height, glTexId);
            return true;
        } catch (Throwable e) {
            RTBridgeMod.LOGGER.error("[ExternalImage] 初始化失败", e); // 打印完整堆栈
            return false;
        }
    }

    // ── Vulkan 图像创建 ───────────────────────────────────────────────────────

    private boolean createVulkanImage(MemoryStack stack) {
        RTBridgeMod.LOGGER.info("[ExternalImage] createVulkanImage begin");
        // 声明外部内存导出
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

        // 查询内存需求
        VkMemoryRequirements req = VkMemoryRequirements.malloc(stack);
        vkGetImageMemoryRequirements(ctx.device, vkImage, req);

        // 分配可导出的内存
        VkExportMemoryAllocateInfo exportInfo =
            VkExportMemoryAllocateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_EXPORT_MEMORY_ALLOCATE_INFO)
                .handleTypes(VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT_KHR);

        LongBuffer pMem = stack.mallocLong(1);
        VulkanBuffer.check(vkAllocateMemory(ctx.device,
            VkMemoryAllocateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                .allocationSize(req.size())
                .memoryTypeIndex(findDeviceLocalMemType(req.memoryTypeBits(), stack))
                .pNext(exportInfo.address()),
            null, pMem), "vkAllocateMemory external");
        vkMemory = pMem.get(0);

        VulkanBuffer.check(vkBindImageMemory(ctx.device, vkImage, vkMemory, 0),
            "vkBindImageMemory external");

        // 创建 ImageView
        LongBuffer pView = stack.mallocLong(1);
        VulkanBuffer.check(vkCreateImageView(ctx.device,
            VkImageViewCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
                .image(vkImage)
                .viewType(VK_IMAGE_VIEW_TYPE_2D)
                .format(vkFormat)
                .subresourceRange(r -> r
                    .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .levelCount(1).layerCount(1)),
            null, pView), "vkCreateImageView external");
        vkView = pView.get(0);

        return true;
    }

    // ── 导出到 OpenGL ─────────────────────────────────────────────────────────

    private boolean exportToGL(MemoryStack stack) {
        RTBridgeMod.LOGGER.info("[ExternalImage] exportToGL begin");
        // 诊断：打印所有相关 GL 扩展支持情况
        try {
            var caps = org.lwjgl.opengl.GL.getCapabilities();
            RTBridgeMod.LOGGER.info("[GLDiag] GL_EXT_memory_object={}", caps.GL_EXT_memory_object);
            RTBridgeMod.LOGGER.info("[GLDiag] GL_EXT_memory_object_win32={}", caps.GL_EXT_memory_object_win32);
            RTBridgeMod.LOGGER.info("[GLDiag] GL_EXT_semaphore={}", caps.GL_EXT_semaphore);
            RTBridgeMod.LOGGER.info("[GLDiag] GL_EXT_semaphore_win32={}", caps.GL_EXT_semaphore_win32);
            RTBridgeMod.LOGGER.info("[GLDiag] OpenGL version={}", org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_VERSION));

            String allExt = org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_EXTENSIONS);
            if (allExt != null) {
                for (String e : allExt.split(" ")) {
                    if (e.contains("memory_object") || e.contains("semaphore") || e.contains("win32")) {
                        RTBridgeMod.LOGGER.info("[GLDiag] 驱动支持扩展: {}", e);
                    }
                }
            } else {
                int count = org.lwjgl.opengl.GL30.glGetInteger(org.lwjgl.opengl.GL30.GL_NUM_EXTENSIONS);
                RTBridgeMod.LOGGER.info("[GLDiag] Core profile, 扩展总数={}", count);
                for (int i = 0; i < count; i++) {
                    String e = org.lwjgl.opengl.GL30.glGetStringi(org.lwjgl.opengl.GL11.GL_EXTENSIONS, i);
                    if (e != null && (e.contains("memory_object") || e.contains("semaphore") || e.contains("win32"))) {
                        RTBridgeMod.LOGGER.info("[GLDiag] 驱动支持扩展: {}", e);
                    }
                }
            }

            if (!caps.GL_EXT_memory_object_win32 || !caps.GL_EXT_memory_object) {
                RTBridgeMod.LOGGER.warn("[ExternalImage] GL_EXT_memory_object_win32 不可用，跳过 GL-Vulkan interop");
                return false;
            }
        } catch (Throwable e) {
            RTBridgeMod.LOGGER.warn("[ExternalImage] GL 能力检查失败: {}", e.getMessage(), e);
            return false;
        }

        // 获取 Win32 HANDLE
        PointerBuffer pHandle = stack.mallocPointer(1);
        VulkanBuffer.check(vkGetMemoryWin32HandleKHR(ctx.device,
            VkMemoryGetWin32HandleInfoKHR.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_MEMORY_GET_WIN32_HANDLE_INFO_KHR)
                .memory(vkMemory)
                .handleType(VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT_KHR),
            pHandle), "vkGetMemoryWin32HandleKHR");
        long handle = pHandle.get(0);

        // GL: 创建 memory object 并导入
        glMemObj = glCreateMemoryObjectsEXT();
        RTBridgeMod.LOGGER.info("[ExternalImage] glCreateMemoryObjectsEXT ok glMemObj={}", glMemObj);
        glImportMemoryWin32HandleEXT(glMemObj, sizeBytes,
            EXTMemoryObjectWin32.GL_HANDLE_TYPE_OPAQUE_WIN32_EXT, handle);

        // GL: 创建纹理并绑定到 memory object
        glTexId = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, glTexId);
        glTexStorageMem2DEXT(GL_TEXTURE_2D, 1, glInternalFormat,
            width, height, glMemObj, 0);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glBindTexture(GL_TEXTURE_2D, 0);

        // 验证纹理确实有效
        if (!org.lwjgl.opengl.GL11.glIsTexture(glTexId)) {
            RTBridgeMod.LOGGER.error("[ExternalImage] GL 纹理无效，Win32 interop 失败");
            glTexId = -1;
            return false;
        }
        return true;
    }

    // ── 同步信号量 ────────────────────────────────────────────────────────────

    /**
     * 创建 Vulkan→GL 同步信号量对。
     * Vulkan 写完后发信号，GL 等到信号再采样。
     */
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

    // ── 工具 ─────────────────────────────────────────────────────────────────

    private int findDeviceLocalMemType(int typeBits, MemoryStack stack) {
        VkPhysicalDeviceMemoryProperties mp =
            VkPhysicalDeviceMemoryProperties.malloc(stack);
        vkGetPhysicalDeviceMemoryProperties(ctx.physDevice, mp);
        for (int i = 0; i < mp.memoryTypeCount(); i++) {
            if ((typeBits & (1 << i)) != 0
                && (mp.memoryTypes(i).propertyFlags()
                    & VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT) != 0) return i;
        }
        throw new RuntimeException("找不到设备本地内存类型");
    }

    private static long computeSize(int vkFormat, int w, int h) {
        return switch (vkFormat) {
            case VK_FORMAT_R8G8B8A8_UNORM      -> (long) w * h * 4;
            case VK_FORMAT_R16G16B16A16_SFLOAT -> (long) w * h * 8;
            case VK_FORMAT_R32G32B32A32_SFLOAT -> (long) w * h * 16;
            default                            -> (long) w * h * 4;
        };
    }

    // ── 常量（KHR 扩展里的，避免 import 歧义）────────────────────────────────

    private static final int VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT_KHR  = 0x00000001;
    private static final int VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_WIN32_BIT_KHR = 0x00000001;

    // ── 清理 ─────────────────────────────────────────────────────────────────

    @Override
    public void close() {
        if (glTexId  >= 0) { glDeleteTextures(glTexId);           glTexId  = -1; }
        if (glMemObj >= 0) { glDeleteMemoryObjectsEXT(glMemObj);  glMemObj = -1; }
        if (vkView   != VK_NULL_HANDLE) vkDestroyImageView(ctx.device, vkView,   null);
        if (vkImage  != VK_NULL_HANDLE) vkDestroyImage(ctx.device, vkImage,      null);
        if (vkMemory != VK_NULL_HANDLE) vkFreeMemory(ctx.device, vkMemory,       null);
    }
}
