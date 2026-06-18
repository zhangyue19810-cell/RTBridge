package com.rtbridge.fabric;

import com.rtbridge.RTBridgeMod;
import com.rtbridge.compat.SableCompat;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;

public class RTBridgeFabric implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        RTBridgeMod.init();

        // 渲染钩子：每帧末尾
        WorldRenderEvents.END.register(ctx -> {
            // 第一帧：初始化 Vulkan（此时 LWJGL 已就绪）
            RTBridgeMod.getRTRenderer().initOnFirstFrame();
            // GL 线程：初始化 GL-Vulkan 共享图像（或CPU回读fallback）
            RTBridgeMod.getRTRenderer().initExternalImagesOnGLThread();

            // 每帧更新真实相机矩阵
            var mc = net.minecraft.client.MinecraftClient.getInstance();
            if (mc.gameRenderer != null && mc.gameRenderer.getCamera() != null) {
                var camera = mc.gameRenderer.getCamera();

                org.joml.Matrix4f viewMat = new org.joml.Matrix4f();
                viewMat.rotate(org.joml.Math.toRadians(camera.getYaw() + 180f),
                    new org.joml.Vector3f(0, 1, 0));
                viewMat.rotate(org.joml.Math.toRadians(camera.getPitch()),
                    new org.joml.Vector3f(1, 0, 0));

                var pos = camera.getPos();
                viewMat.translate(-(float) pos.x, -(float) pos.y, -(float) pos.z);

                float fovDeg = (float) mc.options.getFov().getValue();
                float aspect = (float) mc.getWindow().getFramebufferWidth()
                             / mc.getWindow().getFramebufferHeight();
                org.joml.Matrix4f projMat = new org.joml.Matrix4f()
                    .perspective(org.joml.Math.toRadians(fovDeg), aspect, 0.05f, 512f);

                float dayTime = mc.world != null
                    ? (mc.world.getTimeOfDay() % 24000) / 24000f : 0.25f;
                float sunAngle = dayTime * 6.2831853f - 1.5707963f;
                org.joml.Vector3f sunDir = new org.joml.Vector3f(
                    (float) Math.cos(sunAngle), -(float) Math.sin(sunAngle) - 0.2f, 0.3f
                ).normalize();

                RTBridgeMod.getRTRenderer().updateCamera(viewMat, projMat, sunDir);
            }

            RTBridgeMod.getTripleBuffer().advanceFrame();
            RTBridgeMod.getRTRenderer().submitFrame(
                RTBridgeMod.getTripleBuffer().getMiddle());
        });

        // 渲染钩子：世界渲染完成后（合成 RT 结果到屏幕）
        WorldRenderEvents.LAST.register(ctx -> {
            var mc = net.minecraft.client.MinecraftClient.getInstance();
            if (mc.world == null) return;
            var win = mc.getWindow();
            int W = win.getFramebufferWidth();
            int H = win.getFramebufferHeight();

            // 1. GBuffer 捕获（深度/法线，目前用占位）
            var gb = RTBridgeMod.getGBufferCapture();
            if (gb != null) {
                if (!gb.isReady() || gb.getDepthTexId() < 0 || gb.getNormalTexId() < 0) {
                    gb.init(W, H);
                }
                gb.captureFromDepth(-1, -1, 0.05f, 512f,
                    (float) Math.toRadians(mc.options.getFov().getValue()),
                    (float) W / H);
            }

            // 2. 捕获当前帧画面作为 BaseColor（这一步之前丢失了！）
            var fc = RTBridgeMod.getFrameCapture();
            if (fc != null) {
                if (!fc.isReady()) {
                    fc.init(W, H);
                }
                fc.captureCurrentFrame();
            }

            // 3. 上传 CPU 回读的 shadow 像素到 GL 纹理（这一步之前丢失了！）
            var rt = RTBridgeMod.getRTRenderer();
            if (rt != null) {
                rt.uploadPendingReadbacks();
            }

            // 4. 合成 RT 结果叠加到画面上
            if (rt != null && rt.hasResult()) {
                int baseColor = (fc != null && fc.isReady()) ? fc.getColorTexId() : -1;
                try {
                    RTBridgeMod.getCompositePass().composite(
                        baseColor,
                        rt.getShadowBuffer(),
                        rt.getReflectionBuffer(),
                        rt.getGIBuffer());
                } catch (Throwable e) {
                    RTBridgeMod.LOGGER.error("[RTBridge] Composite 失败: {}", e.getMessage());
                }
            }
        });

        // Tick
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.world == null) return;
            RTBridgeMod.getSceneExtractor().tick();
            SableCompat sable = RTBridgeMod.getSableCompat();
            if (sable != null) sable.tick(client.world);
        });
    }
}
