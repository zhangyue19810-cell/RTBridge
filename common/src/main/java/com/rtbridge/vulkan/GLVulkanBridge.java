package com.rtbridge.vulkan;

import com.rtbridge.RTBridgeMod;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.LongBuffer;

import org.lwjgl.opengl.EXTMemoryObjectWin32;
import org.lwjgl.opengl.EXTSemaphore;
import org.lwjgl.opengl.EXTSemaphoreWin32;
import static org.lwjgl.opengl.EXTMemoryObject.*;
import static org.lwjgl.opengl.EXTMemoryObjectWin32.*;
import static org.lwjgl.opengl.EXTSemaphore.*;
import static org.lwjgl.opengl.EXTSemaphoreWin32.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.*;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL45.*;
import static org.lwjgl.vulkan.KHRExternalMemory.*;
import static org.lwjgl.vulkan.KHRExternalMemoryWin32.*;
import static org.lwjgl.vulkan.KHRExternalSemaphore.*;
import static org.lwjgl.vulkan.KHRExternalSemaphoreWin32.*;
import static org.lwjgl.vulkan.VK12.*;

/**
 * GLVulkanBridge — 将 Vulkan RT 输出图像共享给 OpenGL 采样。
 *
 * 使用 VK_KHR_external_memory_win32 + GL_EXT_memory_object_win32
 * 将 Vulkan VkImage 导出为 Win32 HANDLE，再导入 OpenGL 纹理。
 *
 * 同步：用 VkSemaphore ↔ GL Semaphore 保证同帧无撕裂。
 *
 * 支持的图像：
 *   shadow     R8_UNORM   → GL_R8
 *   reflection RGBA16F    → GL_RGBA16F
 *   gi         RGBA16F    → GL_RGBA16F
 */
public class GLVulkanBridge implements AutoCloseable {

    private final VulkanContext ctx;
    private final RTImageSet    images;
    private final int width, height;

    // OpenGL 纹理 ID（供 CompositePass 采样）
    public int shadowGLTex     = -1;
    public int reflectionGLTex = -1;
    public int giGLTex         = -1;

    // OpenGL memory object handles
    private int shadowMemObj     = -1;
    private int reflectionMemObj = -1;
    private int giMemObj         = -1;

    // Vulkan → GL 同步信号量
    private long vkSignalSemaphore = VK_NULL_HANDLE; // Vulkan writes → GL reads
    private int  glWaitSemaphore   = -1;

    private boolean ready = false;
    private boolean win32 = false; // OS 是 Windows

    public GLVulkanBridge(VulkanContext ctx, RTImageSet images,
                          int width, int height) {
        this.ctx    = ctx;
        this.images = images;
        this.width  = width;
        this.height = height;
    }

    // ── 初始化（必须在 GL 线程调用）────────────────────────────────────────

    public boolean init() {
        // 检查 GL 扩展
        try {
            GLCapabilities caps = GL.getCapabilities();
            win32 = caps.GL_EXT_memory_object_win32 && caps.GL_EXT_semaphore_win32;
        } catch (Throwable ignored) {}

        if (!win32) {
            RTBridgeMod.LOGGER.warn("[GLVulkanBridge] Win32 共享扩展不可用，使用回退路径");
            return initFallback();
        }

        try {
            if (!exportImages())     return false;
            if (!createSemaphores()) return false;
            ready = true;
            RTBridgeMod.LOGGER.info("[GLVulkanBridge] Win32 共享纹理初始化完成");
            return true;
        } catch (Throwable e) {
            RTBridgeMod.LOGGER.error("[GLVulkanBridge] 初始化失败: {}", e.getMessage());
            return initFallback();
        }
    }

    // ── 回退：CPU 拷贝（性能差但通用）────────────────────────────────────────

    private boolean initFallback() {
        shadowGLTex     = createGLTexture(GL_R8,     width, height);
        reflectionGLTex = createGLTexture(GL_RGBA16F, width, height);
        giGLTex         = createGLTexture(GL_RGBA16F, width, height);
        ready = true;
        RTBridgeMod.LOGGER.info("[GLVulkanBridge] 回退路径：CPU 拷贝纹理");
        return true;
    }

    // ── Win32 共享内存导出 ────────────────────────────────────────────────────

    private boolean exportImages() {
        shadowGLTex     = exportImage(images.shadowImage,
            images.shadowMemory,     GL_R8,      VK_FORMAT_R8_UNORM);
        reflectionGLTex = exportImage(images.reflectionImage,
            images.reflectionMemory, GL_RGBA16F, VK_FORMAT_R16G16B16A16_SFLOAT);
        giGLTex         = exportImage(images.giImage,
            images.giMemory,         GL_RGBA16F, VK_FORMAT_R16G16B16A16_SFLOAT);

        return shadowGLTex >= 0 && reflectionGLTex >= 0 && giGLTex >= 0;
    }

    private int exportImage(long vkImage, long vkMemory, int glFormat, int vkFormat) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // 1. 获取 Win32 HANDLE
            org.lwjgl.PointerBuffer pHandle = stack.mallocPointer(1);
            VkMemoryGetWin32HandleInfoKHR info = VkMemoryGetWin32HandleInfoKHR.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_MEMORY_GET_WIN32_HANDLE_INFO_KHR)
                .memory(vkMemory)
                .handleType(0x00000001);
            int res = vkGetMemoryWin32HandleKHR(ctx.device, info, pHandle);
            if (res != VK_SUCCESS) {
                RTBridgeMod.LOGGER.error("[GLVulkanBridge] vkGetMemoryWin32HandleKHR 失败: {}", res);
                return -1;
            }
            long win32Handle = pHandle.get(0); // HANDLE

            // 2. 创建 GL memory object
            int memObj = glCreateMemoryObjectsEXT();

            // 查询图像内存大小
            VkMemoryRequirements req = VkMemoryRequirements.malloc(stack);
            vkGetImageMemoryRequirements(ctx.device, vkImage, req);

            glImportMemoryWin32HandleEXT(memObj, req.size(),
                EXTMemoryObjectWin32.GL_HANDLE_TYPE_OPAQUE_WIN32_EXT, win32Handle);

            // 3. 创建 GL 纹理并绑定到 memory object
            int tex = glGenTextures();
            glBindTexture(GL_TEXTURE_2D, tex);
            glTexStorageMem2DEXT(GL_TEXTURE_2D, 1, glFormat,
                width, height, memObj, 0);

            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
            glBindTexture(GL_TEXTURE_2D, 0);

            return tex;
        }
    }

    // ── 信号量创建 ────────────────────────────────────────────────────────────

    private boolean createSemaphores() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // Vulkan 信号量
            VkExportSemaphoreWin32HandleInfoKHR exportWin32 =
                VkExportSemaphoreWin32HandleInfoKHR.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_EXPORT_SEMAPHORE_WIN32_HANDLE_INFO_KHR)
                    .dwAccess(0x1F0003); // GENERIC_ALL

            VkExportSemaphoreCreateInfo exportInfo =
                VkExportSemaphoreCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_EXPORT_SEMAPHORE_CREATE_INFO)
                    .handleTypes(0x00000001)
                    .pNext(exportWin32.address());

            LongBuffer pSem = stack.mallocLong(1);
            vkCreateSemaphore(ctx.device,
                VkSemaphoreCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO)
                    .pNext(exportInfo.address()),
                null, pSem);
            vkSignalSemaphore = pSem.get(0);

            // 获取 Win32 HANDLE
            org.lwjgl.PointerBuffer pHandle = stack.mallocPointer(1);
            vkGetSemaphoreWin32HandleKHR(ctx.device,
                VkSemaphoreGetWin32HandleInfoKHR.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SEMAPHORE_GET_WIN32_HANDLE_INFO_KHR)
                    .semaphore(vkSignalSemaphore)
                    .handleType(0x00000001),
                pHandle);

            // 导入到 GL
            glWaitSemaphore = glGenSemaphoresEXT();
            glImportSemaphoreWin32HandleEXT(glWaitSemaphore,
                EXTSemaphoreWin32.GL_HANDLE_TYPE_OPAQUE_WIN32_EXT, pHandle.get(0));

            return true;
        }
    }

    // ── 每帧同步：等待 Vulkan 写完再让 GL 读 ─────────────────────────────────

    public void waitForVulkanWrites() {
        if (!ready || !win32 || glWaitSemaphore < 0) return;

        // GL 等待 Vulkan 信号量，然后做 layout transition
        int[] textures = {shadowGLTex, reflectionGLTex, giGLTex};
        int[] layouts  = {
            EXTSemaphore.GL_LAYOUT_SHADER_READ_ONLY_EXT,
            EXTSemaphore.GL_LAYOUT_SHADER_READ_ONLY_EXT,
            EXTSemaphore.GL_LAYOUT_SHADER_READ_ONLY_EXT
        };
        // EXTSemaphore.glWaitSemaphoreEXT(semaphore, buffers[], srcLayouts[], dstLayouts[])
        // buffers = empty, srcLayouts = per-texture current layout, dstLayouts = target layout
        int[] srcLayouts = {
            EXTSemaphore.GL_LAYOUT_GENERAL_EXT,
            EXTSemaphore.GL_LAYOUT_GENERAL_EXT,
            EXTSemaphore.GL_LAYOUT_GENERAL_EXT
        };
        glWaitSemaphoreEXT(glWaitSemaphore, new int[0], srcLayouts, layouts);
    }

    // ── 工具方法 ──────────────────────────────────────────────────────────────

    private int createGLTexture(int format, int w, int h) {
        int tex = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, tex);
        glTexImage2D(GL_TEXTURE_2D, 0, format, w, h, 0,
            format == GL_R8 ? GL_RED : GL_RGBA,
            format == GL_R8 ? GL_UNSIGNED_BYTE : GL_HALF_FLOAT,
            (java.nio.ByteBuffer) null);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glBindTexture(GL_TEXTURE_2D, 0);
        return tex;
    }

    public boolean isReady() { return ready; }

    @Override
    public void close() {
        if (shadowGLTex     >= 0) glDeleteTextures(shadowGLTex);
        if (reflectionGLTex >= 0) glDeleteTextures(reflectionGLTex);
        if (giGLTex         >= 0) glDeleteTextures(giGLTex);
        if (shadowMemObj    >= 0) glDeleteMemoryObjectsEXT(shadowMemObj);
        if (reflectionMemObj>= 0) glDeleteMemoryObjectsEXT(reflectionMemObj);
        if (giMemObj        >= 0) glDeleteMemoryObjectsEXT(giMemObj);
        if (glWaitSemaphore >= 0) glDeleteSemaphoresEXT(glWaitSemaphore);
        if (vkSignalSemaphore != VK_NULL_HANDLE)
            vkDestroySemaphore(ctx.device, vkSignalSemaphore, null);
    }
}
