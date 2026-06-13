package com.rtbridge.render;

import org.joml.Matrix4f;
import org.joml.Vector2f;

/**
 * MotionVectorBuffer — spec §10 (Motion Vectors and Temporal Reprojection).
 *
 * Stores per-pixel 2D screen-space motion vectors that describe how each
 * pixel has moved since the previous frame.
 *
 * Role in the pipeline (from spec):
 *   - COMPENSATION tool, not the main workflow.
 *   - Used when the RT thread falls behind and we need to reproject last
 *     frame's RT result onto the current frame without a 2-frame lag.
 *   - Same-frame composite is always preferred; motion vectors are only
 *     applied when RT hasn't finished in time.
 *
 * Sources of motion:
 *   - Camera movement (most pixels, easy to compute from view matrix delta)
 *   - Dynamic objects / entities (per-object velocity)
 *   - Ship movement (transform delta from TransformCache)
 *
 * Format: R16G16_SFLOAT texture in NDC space (range roughly [-2, 2]).
 */
public class MotionVectorBuffer {

    private int width;
    private int height;
    private int texId = -1; // GL texture handle

    // Previous frame matrices for camera motion
    private final Matrix4f prevViewProj = new Matrix4f();
    private final Matrix4f currViewProj = new Matrix4f();
    private boolean hasPrevFrame = false;

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    public void init(int width, int height) {
        this.width  = width;
        this.height = height;
        // TODO: allocate GL_RG16F texture
        // texId = GL11.glGenTextures();
        // GL11.glBindTexture(GL11.GL_TEXTURE_2D, texId);
        // GL11.glTexImage2D(... GL30.GL_RG16F ...)
    }

    public void resize(int newWidth, int newHeight) {
        cleanup();
        init(newWidth, newHeight);
    }

    // ── Per-frame update ──────────────────────────────────────────────────────

    /**
     * Called at the start of each frame with the current combined view-projection matrix.
     * Saves the current matrix as "previous" and stores the new one as "current".
     *
     * The GPU-side motion vector generation shader then computes:
     *
     *   vec4 prevClip = u_PrevViewProj * vec4(worldPos, 1.0);
     *   vec4 currClip = u_CurrViewProj * vec4(worldPos, 1.0);
     *   vec2 prevUV   = (prevClip.xy / prevClip.w) * 0.5 + 0.5;
     *   vec2 currUV   = (currClip.xy / currClip.w) * 0.5 + 0.5;
     *   motionVec     = currUV - prevUV;
     */
    public void update(Matrix4f viewProjMatrix) {
        if (hasPrevFrame) {
            prevViewProj.set(currViewProj);
        }
        currViewProj.set(viewProjMatrix);
        hasPrevFrame = true;
    }

    // ── Reprojection query ────────────────────────────────────────────────────

    /**
     * Compute the motion vector for a single pixel (CPU path, for testing).
     * In production this runs as a compute shader over all pixels at once.
     *
     * @param ndcPos   pixel position in NDC [-1, 1]
     * @param worldPos reconstructed world position at this pixel (from depth)
     * @return motion vector in NDC space, or (0,0) if no previous frame
     */
    public Vector2f computeMotionVector(Vector2f ndcPos, org.joml.Vector3f worldPos) {
        if (!hasPrevFrame) return new Vector2f(0f);

        org.joml.Vector4f curr4 = new org.joml.Vector4f(worldPos, 1f).mul(currViewProj);
        org.joml.Vector4f prev4 = new org.joml.Vector4f(worldPos, 1f).mul(prevViewProj);

        if (Math.abs(curr4.w) < 1e-6f || Math.abs(prev4.w) < 1e-6f) return new Vector2f(0f);

        Vector2f currNDC = new Vector2f(curr4.x / curr4.w, curr4.y / curr4.w);
        Vector2f prevNDC = new Vector2f(prev4.x / prev4.w, prev4.y / prev4.w);

        return currNDC.sub(prevNDC);
    }

    /**
     * Reproject last frame's RT result using the motion vector texture.
     *
     * This is the "compensation" path (spec §10).  Only invoked when:
     *   RTRenderer.hasResult() == false for the current frame.
     *
     * TODO: dispatch a fullscreen compute shader:
     *   - For each pixel, read motionVector
     *   - Sample previous RT result at (uv - motionVector)
     *   - Write to current RT result buffer
     */
    public void reprojectLastFrame(int prevRTTexId, int outTexId) {
        if (!hasPrevFrame || prevRTTexId < 0) return;
        // TODO: bind compute shader, dispatch GL43.glDispatchCompute(...)
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public int     getTexId()     { return texId; }
    public boolean hasPrevFrame() { return hasPrevFrame; }

    // ── Cleanup ───────────────────────────────────────────────────────────────

    public void cleanup() {
        if (texId >= 0) {
            // TODO: GL11.glDeleteTextures(texId);
            texId = -1;
        }
        hasPrevFrame = false;
    }
}
