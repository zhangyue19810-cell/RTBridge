package com.rtbridge.render;

import com.rtbridge.RTBridgeMod;
import org.lwjgl.opengl.*;

import java.nio.FloatBuffer;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL43.*;

/**
 * CompositePass — blends RT buffers onto the OpenGL frame.
 *
 * FinalColor = (BaseColor * ShadowMask)
 *            + GI
 *            + Reflection * Fresnel
 *            + Emission
 */
public class CompositePass {

    // ── Shader source ─────────────────────────────────────────────────────────

    private static final String VERT = """
        #version 330 core
        out vec2 vUV;
        void main() {
            vUV = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
            gl_Position = vec4(vUV * 2.0 - 1.0, 0.0, 1.0);
        }
        """;

    private static final String FRAG = """
        #version 330 core
        in  vec2 vUV;
        out vec4 fragColor;

        uniform sampler2D u_BaseColor;
        uniform sampler2D u_Shadow;
        uniform sampler2D u_Reflection;
        uniform sampler2D u_GI;
        uniform sampler2D u_Normal;
        uniform float     u_RTWeight;

        float schlick(vec3 N, vec3 V, float F0) {
            return F0 + (1.0 - F0) * pow(1.0 - max(dot(N, V), 0.0), 5.0);
        }

        void main() {
            vec3  base   = texture(u_BaseColor,  vUV).rgb;
            float shadow = texture(u_Shadow,     vUV).r;
            vec3  refl   = texture(u_Reflection, vUV).rgb;
            vec3  gi     = texture(u_GI,         vUV).rgb;
            vec3  N      = normalize(texture(u_Normal, vUV).xyz * 2.0 - 1.0);
            vec3  V      = vec3(0.0, 0.0, 1.0);
            float F      = schlick(N, V, 0.04);

            vec3 lit = base * mix(1.0, shadow, u_RTWeight);
            lit += gi   * u_RTWeight;
            lit += refl * F * u_RTWeight;

            fragColor = vec4(lit, 1.0);
        }
        """;

    // ── GL state ──────────────────────────────────────────────────────────────

    private int  programId    = -1;
    private int  vaoId        = -1;

    // Uniform locations
    private int uBaseColor, uShadow, uReflection, uGI, uNormal, uRTWeight;

    // 1x1 white/neutral fallback textures
    private int whiteTexId  = -1; // shadow fallback (fully lit)
    private int blackTexId  = -1; // reflection/GI fallback (no contribution)
    private int normalTexId = -1; // flat normal fallback (0.5, 0.5, 1.0)

    private boolean initialised = false;
    private boolean failed      = false;

    // ── Init ──────────────────────────────────────────────────────────────────

    public void init() {
        if (initialised || failed) return;
        try {
            programId = buildProgram(VERT, FRAG);
            vaoId     = glGenVertexArrays(); // empty VAO for full-screen triangle

            uBaseColor  = glGetUniformLocation(programId, "u_BaseColor");
            uShadow     = glGetUniformLocation(programId, "u_Shadow");
            uReflection = glGetUniformLocation(programId, "u_Reflection");
            uGI         = glGetUniformLocation(programId, "u_GI");
            uNormal     = glGetUniformLocation(programId, "u_Normal");
            uRTWeight   = glGetUniformLocation(programId, "u_RTWeight");

            // Set sampler bindings once
            glUseProgram(programId);
            glUniform1i(uBaseColor,  0);
            glUniform1i(uShadow,     1);
            glUniform1i(uReflection, 2);
            glUniform1i(uGI,         3);
            glUniform1i(uNormal,     4);
            glUseProgram(0);

            createFallbackTextures();

            initialised = true;
            RTBridgeMod.LOGGER.info("[CompositePass] Shader compiled OK");
        } catch (Exception e) {
            failed = true;
            RTBridgeMod.LOGGER.error("[CompositePass] Init failed", e);
        }
    }

    // ── Per-frame composite ───────────────────────────────────────────────────

    /**
     * @param shadowTexId     GL tex id, -1 = use white fallback (fully lit)
     * @param reflectionTexId GL tex id, -1 = no reflection
     * @param giTexId         GL tex id, -1 = no GI
     */
    public void composite(int shadowTexId, int reflectionTexId, int giTexId) {
        if (!initialised) { init(); return; }
        if (failed) return;

        float rtWeight = (shadowTexId >= 0 || reflectionTexId >= 0 || giTexId >= 0)
            ? 1.0f : 0.0f;
        if (rtWeight == 0f) return;

        // Save GL state
        boolean depthTest = glIsEnabled(GL_DEPTH_TEST);
        glDisable(GL_DEPTH_TEST);
        glDisable(GL_BLEND);

        glUseProgram(programId);
        glUniform1f(uRTWeight, rtWeight);

        // Bind textures
        // slot 0: base color is the current framebuffer → read via sampler
        // (caller must have blitted to a texture before calling composite)
        glActiveTexture(GL_TEXTURE0); // base color (caller responsibility)

        glActiveTexture(GL_TEXTURE1);
        glBindTexture(GL_TEXTURE_2D, shadowTexId     >= 0 ? shadowTexId     : whiteTexId);

        glActiveTexture(GL_TEXTURE2);
        glBindTexture(GL_TEXTURE_2D, reflectionTexId >= 0 ? reflectionTexId : blackTexId);

        glActiveTexture(GL_TEXTURE3);
        glBindTexture(GL_TEXTURE_2D, giTexId         >= 0 ? giTexId         : blackTexId);

        glActiveTexture(GL_TEXTURE4);
        glBindTexture(GL_TEXTURE_2D, normalTexId); // GBuffer normal (TODO: real)

        glBindVertexArray(vaoId);
        glDrawArrays(GL_TRIANGLES, 0, 3);
        glBindVertexArray(0);

        glUseProgram(0);

        // Restore
        if (depthTest) glEnable(GL_DEPTH_TEST);
    }

    // ── Shader compile ────────────────────────────────────────────────────────

    private int buildProgram(String vertSrc, String fragSrc) {
        int vert = compileShader(GL_VERTEX_SHADER,   vertSrc, "vert");
        int frag = compileShader(GL_FRAGMENT_SHADER, fragSrc, "frag");

        int prog = glCreateProgram();
        glAttachShader(prog, vert);
        glAttachShader(prog, frag);
        glLinkProgram(prog);

        if (glGetProgrami(prog, GL_LINK_STATUS) == GL_FALSE) {
            String log = glGetProgramInfoLog(prog);
            glDeleteProgram(prog);
            throw new RuntimeException("Program link failed: " + log);
        }
        glDeleteShader(vert);
        glDeleteShader(frag);
        return prog;
    }

    private int compileShader(int type, String src, String name) {
        int shader = glCreateShader(type);
        glShaderSource(shader, src);
        glCompileShader(shader);
        if (glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE) {
            String log = glGetShaderInfoLog(shader);
            glDeleteShader(shader);
            throw new RuntimeException("Shader compile failed [" + name + "]: " + log);
        }
        return shader;
    }

    // ── Fallback textures ─────────────────────────────────────────────────────

    private void createFallbackTextures() {
        whiteTexId  = createTex1x1(1.0f, 1.0f, 1.0f, 1.0f);
        blackTexId  = createTex1x1(0.0f, 0.0f, 0.0f, 1.0f);
        normalTexId = createTex1x1(0.5f, 0.5f, 1.0f, 1.0f);
    }

    private int createTex1x1(float r, float g, float b, float a) {
        int tex = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, tex);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        FloatBuffer px = org.lwjgl.BufferUtils.createFloatBuffer(4);
        px.put(r).put(g).put(b).put(a).flip();
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA32F, 1, 1, 0,
            GL_RGBA, GL_FLOAT, px);
        glBindTexture(GL_TEXTURE_2D, 0);
        return tex;
    }

    // ── Cleanup ───────────────────────────────────────────────────────────────

    public void cleanup() {
        if (programId    >= 0) glDeleteProgram(programId);
        if (vaoId        >= 0) glDeleteVertexArrays(vaoId);
        if (whiteTexId   >= 0) glDeleteTextures(whiteTexId);
        if (blackTexId   >= 0) glDeleteTextures(blackTexId);
        if (normalTexId  >= 0) glDeleteTextures(normalTexId);
        initialised = false;
    }
}
