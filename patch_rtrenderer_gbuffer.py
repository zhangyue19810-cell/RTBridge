f = 'common/src/main/java/com/rtbridge/render/RTRenderer.java'
c = open(f).read()

old = '''    private int createPlainGLTexture(int w, int h) {'''

new = '''    /**
     * GL 线程调用：把 MC 真实深度/法线 CPU 回读数据拷贝进 Vulkan staging buffer。
     * 这些数据将在下次 ShadowPass.dispatch() 时被拷贝进 gDepthImage/gNormalImage。
     */
    public void uploadGBufferToVulkan(java.nio.ByteBuffer depthData, java.nio.ByteBuffer normalData) {
        if (rtImages == null) return;
        try {
            if (depthData != null && rtImages.gDepthUploadPtr != 0) {
                long copySize = Math.min(depthData.remaining(), rtImages.gDepthUploadSize);
                org.lwjgl.system.MemoryUtil.memCopy(
                    org.lwjgl.system.MemoryUtil.memAddress(depthData),
                    rtImages.gDepthUploadPtr, copySize);
            }
            if (normalData != null && rtImages.gNormalUploadPtr != 0) {
                long copySize = Math.min(normalData.remaining(), rtImages.gNormalUploadSize);
                org.lwjgl.system.MemoryUtil.memCopy(
                    org.lwjgl.system.MemoryUtil.memAddress(normalData),
                    rtImages.gNormalUploadPtr, copySize);
            }
        } catch (Throwable e) {
            RTBridgeMod.LOGGER.error("[RTRenderer] GBuffer 上传失败: {}", e.getMessage());
        }
    }

    private int createPlainGLTexture(int w, int h) {'''

c = c.replace(old, new)
open(f, 'w').write(c)
print("Patched:", old not in c)
