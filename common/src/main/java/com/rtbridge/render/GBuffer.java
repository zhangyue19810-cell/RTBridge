package com.rtbridge.render;

import com.rtbridge.RTBridgeMod;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL14.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

/**
 * GBuffer — 捕获 OpenGL 渲染的深度和法线，供 RT pass 使用。
 *
 * 附加到 Minecraft 主 FBO 之后：
 *   attachment 0 = 颜色（MC 原有）
 *   attachment 1 = 世界空间法线 (RGB16F)
 *   depth        = 深度 (DEPTH24)
 *
 * RT pass 绑定这两张贴图作为输入。
 */
public class GBuffer {

    private int fbo          = -1;
    private int normalTexId  = -1;
    private int depthTexId   = -1;
    private int width, height;

    private boolean initialised = false;

    // ── 初始化 ────────────────────────────────────────────────────────────────

    public void init(int w, int h) {
        this.width = w; this.height = h;

        // 法线贴图
        normalTexId = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, normalTexId);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGB16F, w, h, 0, GL_RGB, GL_FLOAT, (java.nio.ByteBuffer) null);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);

        // 深度贴图
        depthTexId = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, depthTexId);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_DEPTH_COMPONENT24, w, h, 0,
            GL_DEPTH_COMPONENT, GL_FLOAT, (java.nio.ByteBuffer) null);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);

        // FBO
        fbo = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT1,
            GL_TEXTURE_2D, normalTexId, 0);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT,
            GL_TEXTURE_2D, depthTexId, 0);

        int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);

        if (status == GL_FRAMEBUFFER_COMPLETE) {
            initialised = true;
            RTBridgeMod.LOGGER.info("[GBuffer] 初始化 {}x{} OK", w, h);
        } else {
            RTBridgeMod.LOGGER.error("[GBuffer] FBO 不完整: {}", status);
        }
    }

    public void resize(int w, int h) {
        if (w == width && h == height) return;
        cleanup();
        init(w, h);
    }

    // ── 绑定/解绑 ─────────────────────────────────────────────────────────────

    /** 渲染前绑定，让 MC 同时写入法线附件 */
    public void bind() {
        if (!initialised) return;
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, fbo);
        glDrawBuffers(new int[]{ GL_COLOR_ATTACHMENT0, GL_COLOR_ATTACHMENT1 });
    }

    public void unbind() {
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public int  getNormalTexId() { return normalTexId; }
    public int  getDepthTexId()  { return depthTexId; }
    public boolean isReady()     { return initialised; }

    // ── 清理 ──────────────────────────────────────────────────────────────────

    public void cleanup() {
        if (fbo         >= 0) glDeleteFramebuffers(fbo);
        if (normalTexId >= 0) glDeleteTextures(normalTexId);
        if (depthTexId  >= 0) glDeleteTextures(depthTexId);
        fbo = normalTexId = depthTexId = -1;
        initialised = false;
    }
}
