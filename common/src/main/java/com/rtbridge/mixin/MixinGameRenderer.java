package com.rtbridge.mixin;

import com.rtbridge.RTBridgeMod;
import com.rtbridge.render.MotionVectorBuffer;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.util.math.MatrixStack;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * MixinGameRenderer — spec §5 (OpenGL Renderer) / §10 (Motion Vectors).
 *
 * Hooks into Minecraft's GameRenderer to:
 *   1. Capture the combined view-projection matrix at the start of each frame
 *      so MotionVectorBuffer can compute per-pixel motion vectors.
 *   2. (Future) Hook the renderWorld end to kick CompositePass.
 *
 * IMPORTANT: We do NOT replace or redirect any render calls.
 *            All existing OpenGL rendering proceeds untouched.
 */
@Mixin(GameRenderer.class)
public class MixinGameRenderer {

    private static final MotionVectorBuffer motionVec = new MotionVectorBuffer();

    /**
     * Capture view-projection matrix at the very start of renderWorld.
     * The matrix is constructed from the camera's projection + view.
     */
    @Inject(
        method  = "renderWorld(FJLnet/minecraft/client/util/math/MatrixStack;)V",
        at      = @At("HEAD")
    )
    private void rtbridge$onRenderWorldStart(float tickDelta, long limitTime,
                                              MatrixStack matrices,
                                              CallbackInfo ci) {
        // TODO: extract actual combined VP matrix from GameRenderer's camera
        // Matrix4f vp = new Matrix4f(projMatrix).mul(viewMatrix);
        // motionVec.update(vp);
    }

    /**
     * At the tail of renderWorld, trigger RT frame submission and composite.
     * This runs AFTER all vanilla OpenGL world rendering is complete.
     */
    @Inject(
        method  = "renderWorld(FJLnet/minecraft/client/util/math/MatrixStack;)V",
        at      = @At("TAIL")
    )
    private void rtbridge$onRenderWorldEnd(float tickDelta, long limitTime,
                                            MatrixStack matrices,
                                            CallbackInfo ci) {
        // Triple buffer advance + RT submission is driven by WorldRenderEvents.END
        // registered in RTBridgeMod.  This injection is a fallback hook point.
    }
}
