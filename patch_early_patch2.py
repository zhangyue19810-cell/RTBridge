f = 'common/src/main/java/com/rtbridge/render/RTRenderer.java'
c = open(f).read()

old = '''    /** GL 线程调用：初始化 GL-Vulkan 共享图像 */
    public void initExternalImagesOnGLThread() {
        if (externalInited || rtImages == null || !rtAvailable.get()) return;
        externalInited = true;
        rtImages.initExternalImages();'''

new = '''    /** GL 线程调用：初始化 GL-Vulkan 共享图像 */
    public void initExternalImagesOnGLThread() {
        if (externalInited || rtImages == null || !rtAvailable.get()) return;
        externalInited = true;

        // 提前做一次 GL 函数指针修补（确保在 ExternalImage 之前生效）
        try {
            var caps = org.lwjgl.opengl.GL.getCapabilities();
            com.rtbridge.vulkan.ExternalImage.patchGLFunctionPointers(caps);
        } catch (Throwable e) {
            RTBridgeMod.LOGGER.warn("[RTRenderer] 提前GL修补失败: {}", e.getMessage());
        }

        rtImages.initExternalImages();'''

c = c.replace(old, new)

# patchGLFunctionPointers 改成 public static
old2 = '    private static boolean patchGLFunctionPointers(org.lwjgl.opengl.GLCapabilities caps) {'
new2 = '    public static boolean patchGLFunctionPointers(org.lwjgl.opengl.GLCapabilities caps) {'
c2 = open('common/src/main/java/com/rtbridge/vulkan/ExternalImage.java').read()
c2 = c2.replace(old2, new2)
open('common/src/main/java/com/rtbridge/vulkan/ExternalImage.java', 'w').write(c2)

open(f, 'w').write(c)
print("Patched RTRenderer:", old not in c)
print("Patched ExternalImage public:", old2 not in c2)
