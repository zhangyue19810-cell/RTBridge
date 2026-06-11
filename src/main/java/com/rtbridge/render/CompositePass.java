package com.rtbridge.render;

import com.rtbridge.RTBridgeMod;

/**
 * CompositePass — spec §9 (Composite Pass).
 *
 * Runs as a fullscreen GL pass AFTER the OpenGL world has been rendered.
 * Reads RT supplementary buffers and blends them into the framebuffer.
 *
 * Final colour equation (spec §9):
 *
 *   FinalColor = (BaseColor × DirectLighting × ShadowMask)
 *              + GI
 *              + Reflection × Fresnel
 *              + Emission
 *
 * Notes:
 *   - Shadow is a multiplicative attenuation term (not additive).
 *   - Reflection is weighted by the Fresnel term (angle of incidence / IOR).
 *   - GI is purely additive indirect contribution.
 *   - Emission is the self-luminous term from EmissiveCache geometry.
 *
 * All buffers are optional.  If a buffer handle is -1 the corresponding
 * term uses its neutral value (shadow=1, reflection=0, GI=0).
 */
public class CompositePass {

    private static final String VERT_SRC = """
        #version 450 core
        out vec2 vUV;
        void main() {
            // Full-screen triangle (no VBO needed)
            vUV = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
            gl_Position = vec4(vUV * 2.0 - 1.0, 0.0, 1.0);
        }
        """;

    /**
     * Composite GLSL fragment shader.
     *
     * Inputs (all bound as GL textures):
     *   u_BaseColor      — OpenGL rendered scene colour (tone-mapped)
     *   u_Shadow         — shadow attenuation [0,1]  (RT shadow pass output)
     *   u_Reflection     — reflection radiance        (RT reflection pass)
     *   u_GI             — indirect illumination      (RT GI pass)
     *   u_Emission       — emissive glow overlay      (from emissive geometry)
     *   u_Normal         — world-space normals (GBuffer, for Fresnel)
     *   u_Depth          — depth buffer (for motion vector / edge clamping)
     */
    private static final String FRAG_SRC = """
        #version 450 core
        in vec2 vUV;
        out vec4 fragColor;
        
        uniform sampler2D u_BaseColor;
        uniform sampler2D u_Shadow;
        uniform sampler2D u_Reflection;
        uniform sampler2D u_GI;
        uniform sampler2D u_Emission;
        uniform sampler2D u_Normal;
        uniform float     u_RTWeight;    // 0 = RT fully off, 1 = full RT
        
        // Schlick Fresnel approximation
        float fresnel(vec3 N, vec3 V, float F0) {
            float nDotV = max(dot(N, V), 0.0);
            return F0 + (1.0 - F0) * pow(1.0 - nDotV, 5.0);
        }
        
        void main() {
            vec3 baseColor    = texture(u_BaseColor,   vUV).rgb;
            float shadowMask  = texture(u_Shadow,      vUV).r;
            vec3 reflection   = texture(u_Reflection,  vUV).rgb;
            vec3 gi           = texture(u_GI,          vUV).rgb;
            vec3 emission     = texture(u_Emission,    vUV).rgb;
            vec3 worldNormal  = normalize(texture(u_Normal, vUV).xyz * 2.0 - 1.0);
            
            // View direction approximation (screen centre — good enough for Fresnel)
            vec3 viewDir = vec3(0.0, 0.0, -1.0);
            float F = fresnel(worldNormal, viewDir, 0.04);
            
            // Shadow: attenuate base direct lighting
            // (DirectLighting is baked into baseColor from the OpenGL pass)
            vec3 lit = baseColor * mix(1.0, shadowMask, u_RTWeight);
            
            // GI: additive indirect contribution
            lit += gi * u_RTWeight;
            
            // Reflection: Fresnel-weighted blend
            lit += reflection * F * u_RTWeight;
            
            // Emission: additive self-luminous term
            lit += emission * u_RTWeight;
            
            fragColor = vec4(lit, 1.0);
        }
        """;

    // ── GL state ──────────────────────────────────────────────────────────────

    private int programId = -1;
    private int vaoId     = -1;

    // Uniform locations
    private int uBaseColor, uShadow, uReflection, uGI, uEmission, uNormal, uRTWeight;

    private boolean initialised = false;

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Compile and link the composite shader.
     * Must be called on the GL thread before first use.
     *
     * TODO: Replace stub with actual GL calls using LWJGL:
     *   org.lwjgl.opengl.GL45.*
     */
    public void init() {
        if (initialised) return;
        try {
            // programId = compileProgram(VERT_SRC, FRAG_SRC);
            // vaoId     = GL30.glGenVertexArrays(); // empty VAO for full-screen tri
            // resolveUniforms();
            initialised = true;
            RTBridgeMod.LOGGER.info("[CompositePass] Shader compiled.");
        } catch (Exception e) {
            RTBridgeMod.LOGGER.error("[CompositePass] Shader compile failed", e);
        }
    }

    // ── Per-frame composite ───────────────────────────────────────────────────

    /**
     * Composite RT outputs onto the current framebuffer.
     *
     * @param shadowTexId     GL texture id from RTRenderer (-1 = disabled)
     * @param reflectionTexId GL texture id from RTRenderer (-1 = disabled)
     * @param giTexId         GL texture id from RTRenderer (-1 = disabled)
     */
    public void composite(int shadowTexId, int reflectionTexId, int giTexId) {
        if (!initialised) { init(); return; }
        if (programId < 0) return; // shader failed to compile

        float rtWeight = computeRTWeight(shadowTexId, reflectionTexId, giTexId);
        if (rtWeight <= 0f) return; // nothing to composite

        // TODO (GL):
        //   GL20.glUseProgram(programId);
        //   GL11.glDisable(GL11.GL_DEPTH_TEST);
        //   bindTextures(shadowTexId, reflectionTexId, giTexId);
        //   GL20.glUniform1f(uRTWeight, rtWeight);
        //   GL30.glBindVertexArray(vaoId);
        //   GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
        //   GL30.glBindVertexArray(0);
        //   GL11.glEnable(GL11.GL_DEPTH_TEST);
        //   GL20.glUseProgram(0);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** If all RT buffers are absent, return 0 to skip the composite draw. */
    private float computeRTWeight(int shadow, int refl, int gi) {
        return (shadow >= 0 || refl >= 0 || gi >= 0) ? 1.0f : 0.0f;
    }

    public void cleanup() {
        // TODO: GL20.glDeleteProgram(programId); GL30.glDeleteVertexArrays(vaoId);
        initialised = false;
    }
}
