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

        // Win32 句柄导出需要同时提供 VkExportMemoryWin32HandleInfoKHR（访问权限）
        // 否则 vkGetMemoryWin32HandleKHR 会返回 NULL handle（Vulkan 规范要求）
        VkExportMemoryWin32HandleInfoKHR win32Info =
            VkExportMemoryWin32HandleInfoKHR.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_EXPORT_MEMORY_WIN32_HANDLE_INFO_KHR)
                .pAttributes(null)         // 默认安全属性
                .dwAccess(0x1F0003)        // GENERIC_ALL 访问权限
                .name(stack.malloc(2).putShort(0, (short)0)); // 空字符串（UTF-16LE null terminator）

        VkExportMemoryAllocateInfo exportInfo =
            VkExportMemoryAllocateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_EXPORT_MEMORY_ALLOCATE_INFO)
                .handleTypes(VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT_KHR)
                .pNext(win32Info.address()); // 链入 Win32 导出信息

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

    public static boolean patchGLFunctionPointers(org.lwjgl.opengl.GLCapabilities caps) {
        try {
            sun.misc.Unsafe unsafe;
            java.lang.reflect.Field uf = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            uf.setAccessible(true);
            unsafe = (sun.misc.Unsafe) uf.get(null);

            String[] funcs = {
                "glImportMemoryWin32HandleEXT",
                "glCreateMemoryObjectsEXT",
                "glDeleteMemoryObjectsEXT",
                "glTexStorageMem2DEXT",
                "glImportSemaphoreWin32HandleEXT",
                "glGenSemaphoresEXT",
                "glDeleteSemaphoresEXT",
                "glWaitSemaphoreEXT",
            };
            // 先尝试加载 nvoglv64.dll（NVIDIA OpenGL 驱动）
            long hLib = loadNvidiaGL();

            // LWJGL 内部的 FunctionProvider（比手动 wglGetProcAddress 更可靠）
            org.lwjgl.system.FunctionProvider provider = null;
            try { provider = org.lwjgl.opengl.GL.getFunctionProvider(); } catch (Throwable ignored) {}

            int patched = 0;
            for (String name : funcs) {
                try {
                    java.lang.reflect.Field f = caps.getClass().getField(name);
                    f.setAccessible(true);
                    long cur = f.getLong(caps);

                    long ptr = 0;

                    // 1. LWJGL 内部 FunctionProvider（最可靠）
                    if (provider != null) {
                        ptr = provider.getFunctionAddress(name);
                        if (ptr != 0)
                            RTBridgeMod.LOGGER.info("[GLPatch] FunctionProvider {} = 0x{}", name, Long.toHexString(ptr));
                    }

                    // 2. 备用：wglGetProcAddress
                    if (ptr == 0) {
                        ptr = org.lwjgl.opengl.WGL.wglGetProcAddress(name);
                        if (ptr != 0)
                            RTBridgeMod.LOGGER.info("[GLPatch] wglGetProcAddress {} = 0x{}", name, Long.toHexString(ptr));
                    }

                    // 3. 备用：从 DLL 取
                    if (ptr == 0 && hLib != 0) {
                        ptr = getProcAddress(hLib, name);
                        if (ptr != 0)
                            RTBridgeMod.LOGGER.info("[GLPatch] DLL {} = 0x{}", name, Long.toHexString(ptr));
                    }

                    RTBridgeMod.LOGGER.info("[GLPatch] {} cur={} new={}", name, Long.toHexString(cur), Long.toHexString(ptr));

                    if (ptr == 0) {
                        RTBridgeMod.LOGGER.warn("[GLPatch] 找不到函数指针: {}", name);
                        continue;
                    }

                    if (cur == 0) {
                        unsafe.putLong(caps, unsafe.objectFieldOffset(f), ptr);
                        RTBridgeMod.LOGGER.info("[GLPatch] 修补成功: {}", name);
                        patched++;
                    }
                } catch (Throwable e) {
                    RTBridgeMod.LOGGER.warn("[GLPatch] {} 失败: {}", name, e.getMessage());
                }
            }
            RTBridgeMod.LOGGER.info("[GLPatch] 共修补 {} 个函数指针", patched);
            return patched > 0;
        } catch (Throwable e) {
            RTBridgeMod.LOGGER.error("[GLPatch] 修补失败: {}", e.getMessage());
            return false;
        }
    }

    /** 加载 NVIDIA OpenGL 驱动 DLL，返回模块句柄（0=失败） */
    private static long loadNvidiaGL() {
        // 尝试几个常见的 NVIDIA GL DLL 名称
        String[] dlls = {"nvoglv64.dll", "nvoglv32.dll", "opengl32.dll"};
        for (String dll : dlls) {
            try {
                long h = loadLibraryWin32(dll);
                if (h != 0) {
                    RTBridgeMod.LOGGER.info("[GLPatch] 加载 DLL: {}", dll);
                    return h;
                }
            } catch (Throwable ignored) {}
        }
        RTBridgeMod.LOGGER.warn("[GLPatch] 无法加载 NVIDIA GL DLL");
        return 0;
    }

    private static long loadLibraryWin32(String name) throws Exception {
        // 用 JNA 或反射调用 LoadLibraryA
        // 通过 org.lwjgl.system.windows.WindowsLibrary 或直接 JNI
        try {
            Class<?> libClass = Class.forName("org.lwjgl.system.Library");
            // LWJGL 内部有 loadNative 方法
            java.lang.reflect.Method m = libClass.getDeclaredMethod("loadNative",
                Class.class, String.class, String.class, boolean.class);
            m.setAccessible(true);
            // 返回句柄
            Object handle = m.invoke(null, null, null, name, false);
            if (handle != null) {
                java.lang.reflect.Field addrField = handle.getClass().getDeclaredField("address");
                addrField.setAccessible(true);
                return addrField.getLong(handle);
            }
        } catch (Throwable ignored) {}

        // 备用：通过 kernel32 直接调用
        try {
            com.sun.jna.Native.register("kernel32");
        } catch (Throwable ignored) {}

        // 最后备用：用 System.load 后通过 ClassLoader native library 句柄
        return 0;
    }

    private static long getProcAddress(long hLib, String name) {
        // 用 LWJGL 的 JNI 桥直接调用 GetProcAddress
        try {
            // org.lwjgl.system.JNI.callPP(GetProcAddress, hLib, namePtr)
            // 但我们没有 GetProcAddress 的函数指针...
            // 改用 LWJGL 的 MemoryUtil + 反射
            Class<?> libClass = Class.forName("org.lwjgl.system.windows.WindowsUtil");
            java.lang.reflect.Method m = libClass.getDeclaredMethod("getProcAddress", long.class, String.class);
            m.setAccessible(true);
            Object result = m.invoke(null, hLib, name);
            if (result instanceof Long) return (Long) result;
            if (result instanceof Number) return ((Number) result).longValue();
        } catch (Throwable ignored) {}

        // 最简单方案：用 wglGetProcAddress 的 Win32 底层
        // 实际上 wglGetProcAddress 就是从 opengl32.dll 里的 wglGetProcAddress 调的
        // 如果这里也返回 0，说明函数确实不可用或需要 ARB 后缀
        try {
            // 尝试带 ARB/EXT 的变体名，有时候需要
            long ptr = org.lwjgl.opengl.WGL.wglGetProcAddress(name + "ARB");
            if (ptr != 0) return ptr;
            ptr = org.lwjgl.opengl.WGL.wglGetProcAddress(name.replace("EXT", "ARB"));
            if (ptr != 0) return ptr;
        } catch (Throwable ignored) {}

        return 0;
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
        RTBridgeMod.LOGGER.info("[ExternalImage] Win32 HANDLE = 0x{} sizeBytes={}",
            Long.toHexString(handle), this.sizeBytes);

        if (handle == 0) {
            RTBridgeMod.LOGGER.error("[ExternalImage] vkGetMemoryWin32HandleKHR 返回了 NULL HANDLE！");
            RTBridgeMod.LOGGER.error("[ExternalImage] 可能原因：VkMemory 未以 EXPORT 标志分配，或驱动不支持此操作");
            return false;
        }

        glMemObj = glCreateMemoryObjectsEXT();
        RTBridgeMod.LOGGER.info("[ExternalImage] glMemObj={}", glMemObj);

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
