f = 'common/src/main/java/com/rtbridge/vulkan/ExternalImage.java'
c = open(f).read()

old = '''    private boolean exportToGL(MemoryStack stack) {
        // 诊断：打印所有相关 GL 扩展支持情况
        try {
            var caps = org.lwjgl.opengl.GL.getCapabilities();'''

new = '''    /**
     * 用 wglGetProcAddress + Unsafe 手动解析并修补 LWJGL 函数指针。
     * LWJGL 3.3.3 在某些 NVIDIA 驱动下会报告扩展可用但函数指针为 null（已知 bug），
     * 通过直接调用 wglGetProcAddress 绕过 LWJGL 的惰性解析。
     */
    private static boolean patchGLFunctionPointers(org.lwjgl.opengl.GLCapabilities caps) {
        try {
            sun.misc.Unsafe unsafe = getUnsafe();

            // 要修补的函数列表（名称 → GLCapabilities 字段名）
            String[] funcs = {
                "glImportMemoryWin32HandleEXT",
                "glCreateMemoryObjectsEXT",
                "glDeleteMemoryObjectsEXT",
                "glTexStorageMem2DEXT",
                "glImportSemaphoreWin32HandleEXT",
                "glGenSemaphoresEXT",
                "glDeleteSemaphoresEXT",
                "glWaitSemaphoreEXT",
                "glSignalSemaphoreEXT",
            };

            int patched = 0;
            for (String name : funcs) {
                try {
                    java.lang.reflect.Field f = caps.getClass().getField(name);
                    f.setAccessible(true);
                    long currentPtr = f.getLong(caps);
                    if (currentPtr != 0) continue; // 已经有值，不用修补

                    // 通过 wglGetProcAddress 获取真实函数指针
                    long newPtr = org.lwjgl.opengl.WGL.wglGetProcAddress(name);
                    if (newPtr == 0) {
                        RTBridgeMod.LOGGER.warn("[GLPatch] wglGetProcAddress({}) 返回 0", name);
                        continue;
                    }

                    long fieldOffset = unsafe.objectFieldOffset(f);
                    unsafe.putLong(caps, fieldOffset, newPtr);
                    RTBridgeMod.LOGGER.info("[GLPatch] 修补函数指针 {} = 0x{}", name, Long.toHexString(newPtr));
                    patched++;
                } catch (Throwable e) {
                    RTBridgeMod.LOGGER.warn("[GLPatch] 修补失败 {}: {}", name, e.getMessage());
                }
            }
            RTBridgeMod.LOGGER.info("[GLPatch] 共修补 {} 个函数指针", patched);
            return true;
        } catch (Throwable e) {
            RTBridgeMod.LOGGER.error("[GLPatch] Unsafe 修补失败: {}", e.getMessage());
            return false;
        }
    }

    private static sun.misc.Unsafe getUnsafe() throws Exception {
        java.lang.reflect.Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        return (sun.misc.Unsafe) f.get(null);
    }

    private static boolean patchApplied = false;

    private boolean exportToGL(MemoryStack stack) {
        // 诊断：打印所有相关 GL 扩展支持情况
        try {
            var caps = org.lwjgl.opengl.GL.getCapabilities();'''

c = c.replace(old, new)

# 在能力检查通过后，调用修补
old2 = '''            if (!caps.GL_EXT_memory_object_win32 || !caps.GL_EXT_memory_object) {
                RTBridgeMod.LOGGER.warn("[ExternalImage] GL_EXT_memory_object_win32 不可用，跳过 GL-Vulkan interop");
                return false;
            }
        } catch (Throwable e) {
            RTBridgeMod.LOGGER.warn("[ExternalImage] GL 能力检查失败: {}", e.getMessage(), e);
            return false;
        }'''

new2 = '''            if (!caps.GL_EXT_memory_object_win32 || !caps.GL_EXT_memory_object) {
                RTBridgeMod.LOGGER.warn("[ExternalImage] GL_EXT_memory_object_win32 不可用，跳过 GL-Vulkan interop");
                return false;
            }

            // 修补 LWJGL 函数指针（只做一次）
            if (!patchApplied) {
                patchApplied = patchGLFunctionPointers(caps);
            }
        } catch (Throwable e) {
            RTBridgeMod.LOGGER.warn("[ExternalImage] GL 能力检查失败: {}", e.getMessage(), e);
            return false;
        }'''

c = c.replace(old2, new2)
open(f, 'w').write(c)
print("Patched:", old not in c, old2 not in c)
