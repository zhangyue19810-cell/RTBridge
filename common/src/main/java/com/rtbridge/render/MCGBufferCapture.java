package com.rtbridge.render;

import com.rtbridge.RTBridgeMod;

import java.nio.ByteBuffer;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL30.*;

/**
 * MCGBufferCapture — 捕获 MC 真实深度 + 重建法线，供 Vulkan RT 使用。
 *
 * 架构：
 *   1. depthFbo: 只有 DEPTH_ATTACHMENT，用于从默认帧缓冲 blit 出真实深度
 *   2. normalFbo: 只有 COLOR_ATTACHMENT，用于跑 NormalReconstructPass 写法线
 *      （避免同一 FBO 同时读写深度导致的 feedback loop）
 *   3. CPU 回读：每帧 glReadPixels 深度+法线，交给 RTRenderer→Vulkan
 */
public class MCGBufferCapture {

    private int width = 1920, height = 1080;

    private int depthTexId  = -1; // DEPTH_COMPONENT24，用作 depthFbo 的深度附件
    private int normalTexId = -1; // RGBA16F，用作 normalFbo 的颜色附件

    private int depthFbo  = -1;
    private int normalFbo = -1;

    private boolean ready = false;

    // CPU 回读缓冲（直接内存，供 JNI/native 拷贝）
    private ByteBuffer depthReadback;  // R32F：width*height*4 bytes
    private ByteBuffer normalReadback; // RGBA16F：width*height*8 bytes (half float)

    // ── 初始化 ───────────────────────────────────────────────────────────────

    public void init(int w, int h) {
        if (ready && w == width && h == height) return;
        if (ready) cleanup();
        this.width = w; this.height = h;

        // 深度纹理（DEPTH_ATTACHMENT 专用）
        depthTexId = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, depthTexId);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_DEPTH_COMPONENT24, w, h, 0,
            GL_DEPTH_COMPONENT, GL_FLOAT, (ByteBuffer) null);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

        // 法线纹理（COLOR_ATTACHMENT 专用，RGBA16F 避免对齐问题）
        normalTexId = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, normalTexId);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA16F, w, h, 0,
            GL_RGBA, GL_FLOAT, (ByteBuffer) null);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glBindTexture(GL_TEXTURE_2D, 0);

        // depthFbo：只挂深度附件
        depthFbo = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, depthFbo);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT,
            GL_TEXTURE_2D, depthTexId, 0);
        int s1 = glCheckFramebufferStatus(GL_FRAMEBUFFER);

        // normalFbo：只挂颜色附件
        normalFbo = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, normalFbo);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0,
            GL_TEXTURE_2D, normalTexId, 0);
        int s2 = glCheckFramebufferStatus(GL_FRAMEBUFFER);

        glBindFramebuffer(GL_FRAMEBUFFER, 0);

        ready = (s1 == GL_FRAMEBUFFER_COMPLETE) && (s2 == GL_FRAMEBUFFER_COMPLETE);

        depthReadback  = ByteBuffer.allocateDirect(w * h * 4)
            .order(java.nio.ByteOrder.nativeOrder());      // R32F
        normalReadback = ByteBuffer.allocateDirect(w * h * 4 * 2)
            .order(java.nio.ByteOrder.nativeOrder());      // RGBA16F (half=2byte*4ch)

        if (ready) RTBridgeMod.LOGGER.info("[GBufferCapture] 初始化 {}x{} (depthFbo={} normalFbo={})",
            w, h, s1, s2);
        else RTBridgeMod.LOGGER.error("[GBufferCapture] FBO 不完整 depth={} normal={}", s1, s2);
    }

    // ── 每帧捕获 ──────────────────────────────────────────────────────────────

    /**
     * 在世界渲染完成后调用（GL 线程）：
     *   1. blit 默认帧缓冲的真实深度到 depthTexId
     *   2. 用 NormalReconstructPass 从深度差分计算法线，写入 normalTexId
     */
    public void captureFromDepth(float projNear, float projFar, float fovY, float aspectRatio,
                                  org.joml.Matrix3f invViewRot) {
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
        NormalReconstructPass.run(depthTexId, projNear, projFar, fovY, aspectRatio, width, height, invViewRot);

        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glEnable(GL_DEPTH_TEST);
    }

    // ── CPU 回读（GL 线程调用，结果传给 RTRenderer→Vulkan）────────────────────

    public ByteBuffer readDepthCPU() {
        // 数据已在 captureFromDepth() 里读取，直接返回
        if (!ready) return null;
        return depthReadback;
    }

    public ByteBuffer readNormalCPU() {
        if (!ready) return null;
        glBindFramebuffer(GL_FRAMEBUFFER, normalFbo);
        normalReadback.clear();
        // 用 HALF_FLOAT 读出，匹配 Vulkan 端 R16G16B16A16_SFLOAT
        glReadPixels(0, 0, width, height, GL_RGBA, GL_HALF_FLOAT, normalReadback);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        normalReadback.rewind();
        return normalReadback;
    }

    // ── Accessors ────────────────────────────────────────────────────────────

    public int getDepthTexId()  { return depthTexId;  }
    public int getNormalTexId() { return normalTexId; }
    public boolean isReady()    { return ready; }

    // ── 清理 ─────────────────────────────────────────────────────────────────

    public void cleanup() {
        if (depthFbo    >= 0) { glDeleteFramebuffers(depthFbo);  depthFbo    = -1; }
        if (normalFbo   >= 0) { glDeleteFramebuffers(normalFbo); normalFbo   = -1; }
        if (depthTexId  >= 0) { glDeleteTextures(depthTexId);    depthTexId  = -1; }
        if (normalTexId >= 0) { glDeleteTextures(normalTexId);   normalTexId = -1; }
        ready = false;
    }
}
