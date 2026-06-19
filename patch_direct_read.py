f = 'common/src/main/java/com/rtbridge/render/MCGBufferCapture.java'
c = open(f).read()

old = '''    public void captureFromDepth(float projNear, float projFar, float fovY, float aspectRatio) {
        if (!ready) return;

        // 1. 真实深度 blit：默认帧缓冲(0) → depthFbo
        glBindFramebuffer(GL_READ_FRAMEBUFFER, 0);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, depthFbo);
        glBlitFramebuffer(0, 0, width, height, 0, 0, width, height,
            GL_DEPTH_BUFFER_BIT, GL_NEAREST);

        // 2. 法线重建：从 depthTexId 采样，写入 normalFbo 的 normalTexId
        glBindFramebuffer(GL_FRAMEBUFFER, normalFbo);
        glViewport(0, 0, width, height);
        glDisable(GL_DEPTH_TEST);
        glDisable(GL_BLEND);
        NormalReconstructPass.run(depthTexId, projNear, projFar, fovY, aspectRatio, width, height);

        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glEnable(GL_DEPTH_TEST);
    }'''

new = '''    public void captureFromDepth(float projNear, float projFar, float fovY, float aspectRatio) {
        if (!ready) return;

        // 1. 直接从当前绑定的默认帧缓冲读取深度像素（跳过blit，排查blit本身是否有问题）
        depthReadback.clear();
        glReadPixels(0, 0, width, height, GL_DEPTH_COMPONENT, GL_FLOAT, depthReadback);
        depthReadback.rewind();

        // 2. CPU 上传深度数据进 depthTexId（供法线重建采样）
        glBindTexture(GL_TEXTURE_2D, depthTexId);
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width, height,
            GL_DEPTH_COMPONENT, GL_FLOAT, depthReadback);
        glBindTexture(GL_TEXTURE_2D, 0);
        depthReadback.rewind();

        // 3. 法线重建：从 depthTexId 采样，写入 normalFbo 的 normalTexId
        glBindFramebuffer(GL_FRAMEBUFFER, normalFbo);
        glViewport(0, 0, width, height);
        glDisable(GL_DEPTH_TEST);
        glDisable(GL_BLEND);
        NormalReconstructPass.run(depthTexId, projNear, projFar, fovY, aspectRatio, width, height);

        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glEnable(GL_DEPTH_TEST);
    }'''

c = c.replace(old, new)

# readDepthCPU 现在直接复用 captureFromDepth 里已读的数据，避免重复 glReadPixels
old2 = '''    public ByteBuffer readDepthCPU() {
        if (!ready) return null;
        glBindFramebuffer(GL_FRAMEBUFFER, depthFbo);
        depthReadback.clear();
        glReadPixels(0, 0, width, height, GL_DEPTH_COMPONENT, GL_FLOAT, depthReadback);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        depthReadback.rewind();
        return depthReadback;
    }'''

new2 = '''    public ByteBuffer readDepthCPU() {
        // 数据已在 captureFromDepth() 里读取，直接返回
        if (!ready) return null;
        return depthReadback;
    }'''

c = c.replace(old2, new2)
open(f, 'w').write(c)
print("Patched:", old not in c, old2 not in c)
