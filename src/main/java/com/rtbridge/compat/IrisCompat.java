package com.rtbridge.compat;

import com.rtbridge.RTBridgeMod;
import net.fabricmc.loader.api.FabricLoader;

/**
 * IrisCompat — coexistence with Iris shaders.
 *
 * RTBridge does NOT replace Iris.  Instead:
 *   - Iris handles its own deferred/forward shading pipeline.
 *   - RTBridge generates supplementary RT buffers (shadow, reflection, GI).
 *   - The CompositePass blends RT results on top of Iris's output.
 *
 * Interaction points:
 *   1. GBuffer sharing: if Iris exposes a GBuffer (world normals, depth),
 *      RTBridge reads it to drive its RT passes rather than duplicating work.
 *   2. Shadow: if Iris has shadow maps, RTBridge's RT shadow can complement
 *      or replace them selectively (e.g. RT shadows for ships, shadow maps
 *      for terrain).
 *   3. Composite order: RTBridge composite runs after Iris's final pass.
 *
 * Currently all methods are stubs.
 * TODO: Hook into Iris's public API when available, or use Mixin into
 *       net.irisshaders.iris.pipeline.WorldRenderingPipeline.
 */
public class IrisCompat {

    private static boolean irisActive = false;

    public static boolean isLoaded() {
        return FabricLoader.getInstance().isModLoaded("iris");
    }

    /**
     * Detect whether Iris has loaded a shader pack that provides a GBuffer
     * RTBridge can read.
     */
    public static boolean hasGBuffer() {
        if (!isLoaded()) return false;
        // TODO: check Iris API for active pack + gbuffer availability
        return false;
    }

    /**
     * Returns the GL texture ID of Iris's world-normal GBuffer, or -1.
     * RTBridge uses this to avoid running its own normal pre-pass.
     */
    public static int getNormalGBufferTexId() {
        if (!hasGBuffer()) return -1;
        // TODO: call Iris reflection API to get texture handle
        return -1;
    }

    /**
     * Returns the GL depth texture ID from Iris's pipeline, or -1.
     */
    public static int getDepthTexId() {
        if (!isLoaded()) return -1;
        // TODO
        return -1;
    }

    /**
     * Notify Iris compat that a new frame is starting.
     * Used to synchronise GBuffer reads with the correct frame boundary.
     */
    public static void onFrameStart() {
        if (!isLoaded()) return;
        // TODO: synchronise with Iris frame timing if needed
    }

    public static void init() {
        if (isLoaded()) {
            RTBridgeMod.LOGGER.info("[RTBridge] Iris detected — GBuffer sharing mode active.");
        }
    }
}
