package com.rtbridge.render;

import com.rtbridge.RTBridgeMod;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL32.*;

/**
 * MCGBufferCapture — 在 MC OpenGL 渲染管线中捕获深度和法线。
 *
 * 实现方式：
 *   在 MC 的主 FBO 上附加额外的颜色附件（法线），
 *   深度直接读 MC 自带的深度缓冲。
 *
 * 使用时序：
 *   1. beginCapture()  — 在 MC 渲染世界前调用（附加 FBO 附件）
 *   2. endCapture()    — 在 MC 渲染世界后调用（解绑，恢复默认）
 *   3. getDepthTexId() — 获取深度纹理 ID（供 Vulkan shadow pass 读取）
 *   4. getNormalTexId()— 获取法线纹理 ID
 */
public class MCGBufferCapture {

    private int width  = 1920;
    private int height = 1080;

    // 我们附加的法线纹理
    private int normalTexId = -1;
    // MC 主 FBO 的深度纹理（读取 MC 自己创建的）
    private int depthTexId  = -1;

    // 我们创建的辅助 FBO（只用来写法线）
    private int auxFbo = -1;

    // MC 主 FBO id（从 Mixin 注入）
    private int mcMainFbo = 0; // 0 = 默认帧缓冲

    private boolean capturing = false;
    private boolean ready     = false;

    // ── 初始化 ───────────────────────────────────────────────────────────────

    public void init(int width, int height) {
        this.width  = width;
        this.height = height;
        cleanup();
        createTextures();
        ready = true;
        RTBridgeMod.LOGGER.info("[GBufferCapture] 初始化 {}x{}", width, height);
    }

    public void resize(int w, int h) {
        if (w == width && h == height) return;
        init(w, h);
    }

    private void createTextures() {
        // 法线纹理 RGB16F
        normalTexId = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, normalTexId);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGB16F, width, height, 0,
            GL_RGB, GL_FLOAT, (java.nio.ByteBuffer) null);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

        // 深度纹理 DEPTH24
        depthTexId = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, depthTexId);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_DEPTH_COMPONENT24, width, height, 0,
            GL_DEPTH_COMPONENT, GL_FLOAT, (java.nio.ByteBuffer) null);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

        glBindTexture(GL_TEXTURE_2D, 0);

        // 辅助 FBO：深度 + 法线同时写入
        auxFbo = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, auxFbo);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0,
            GL_TEXTURE_2D, normalTexId, 0);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT,
            GL_TEXTURE_2D, depthTexId, 0);

        int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);

        if (status != GL_FRAMEBUFFER_COMPLETE) {
            RTBridgeMod.LOGGER.error("[GBufferCapture] FBO 不完整: {}", status);
            ready = false;
        }
    }

    // ── 法线写入 Pass ─────────────────────────────────────────────────────────

    /**
     * 在 MC 世界渲染完成后，用一个简单的 fullscreen pass
     * 从深度重建世界法线，写入 normalTexId。
     *
     * 这比截获 MC 渲染管线更稳定：
     *   - 不修改 MC 的渲染状态
     *   - 从深度图重建近似法线（对 RT shadow 够用）
     *   - 精确法线等 Iris GBuffer 接入后再替换
     */
    public void captureFromDepth(int mcDepthTex, int mcColorTex,
                                 float projNear, float projFar,
                                 float fovY, float aspectRatio) {
        if (!ready || auxFbo < 0) return;

        // 绑定辅助 FBO
        glBindFramebuffer(GL_FRAMEBUFFER, auxFbo);
        glViewport(0, 0, width, height);
        glDisable(GL_DEPTH_TEST);
        glDisable(GL_BLEND);

        // TODO: NormalReconstructPass 暂时跳过，等 GBuffer 稳定后启用
        // NormalReconstructPass.run(mcDepthTex, projNear, projFar, fovY, aspectRatio, width, height);

        // 把 MC 的深度 blit 到我们的深度纹理
        if (mcDepthTex > 0) {
            glBindFramebuffer(GL_READ_FRAMEBUFFER, 0); // 读默认帧缓冲
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, auxFbo);
            glBlitFramebuffer(0, 0, width, height,
                0, 0, width, height,
                GL_DEPTH_BUFFER_BIT, GL_NEAREST);
        }

        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glEnable(GL_DEPTH_TEST);
        capturing = false;
    }

    // ── Accessors ────────────────────────────────────────────────────────────

    public int  getNormalTexId() { return normalTexId; }
    public int  getDepthTexId()  { return depthTexId;  }
    public boolean isReady()     { return ready; }

    // ── 设置 MC 深度纹理（由 Iris/Sodium Mixin 注入）────────────────────────

    public void setMCDepthTex(int texId) {
        // 直接用 MC 的深度纹理，不需要 blit
        this.depthTexId = texId;
    }

    // ── 清理 ─────────────────────────────────────────────────────────────────

    public void cleanup() {
        if (auxFbo      >= 0) { glDeleteFramebuffers(auxFbo);   auxFbo      = -1; }
        if (normalTexId >= 0) { glDeleteTextures(normalTexId);  normalTexId = -1; }
        if (depthTexId  >= 0) { glDeleteTextures(depthTexId);   depthTexId  = -1; }
        ready = false;
    }
}
