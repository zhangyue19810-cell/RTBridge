package com.rtbridge.render;

import com.rtbridge.RTBridgeMod;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.*;
import static org.lwjgl.opengl.GL30.*;

/**
 * FrameCapture — 把 MC 当前帧缓冲 blit 到纹理，
 * 供 CompositePass 作为 BaseColor 输入。
 */
public class FrameCapture {

    private int fbo      = -1;
    private int colorTex = -1;
    private int width, height;
    private boolean ready = false;

    public void init(int w, int h) {
        if (w == width && h == height && ready) return;
        cleanup();
        this.width = w; this.height = h;

        colorTex = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, colorTex);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0,
            GL_RGBA, GL_UNSIGNED_BYTE, (java.nio.ByteBuffer) null);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glBindTexture(GL_TEXTURE_2D, 0);

        fbo = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0,
            GL_TEXTURE_2D, colorTex, 0);
        int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);

        ready = status == GL_FRAMEBUFFER_COMPLETE;
        if (ready) RTBridgeMod.LOGGER.info("[FrameCapture] 初始化 {}x{}", w, h);
        else RTBridgeMod.LOGGER.error("[FrameCapture] FBO 不完整: {}", status);
    }

    /** 把默认帧缓冲的颜色 blit 到 colorTex */
    public void captureCurrentFrame() {
        if (!ready) return;
        glBindFramebuffer(GL_READ_FRAMEBUFFER, 0);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, fbo);
        glBlitFramebuffer(0, 0, width, height,
                          0, 0, width, height,
                          GL_COLOR_BUFFER_BIT, GL_NEAREST);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }

    public int  getColorTexId() { return colorTex; }
    public boolean isReady()    { return ready; }

    public void cleanup() {
        if (fbo      >= 0) { glDeleteFramebuffers(fbo);  fbo      = -1; }
        if (colorTex >= 0) { glDeleteTextures(colorTex); colorTex = -1; }
        ready = false;
    }
}
