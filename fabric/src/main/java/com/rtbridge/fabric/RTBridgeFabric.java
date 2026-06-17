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

        // 渲染钩子
        WorldRenderEvents.END.register(ctx -> {
            // 第一帧：初始化 Vulkan（此时 LWJGL 已就绪）
            RTBridgeMod.getRTRenderer().initOnFirstFrame();
            // GL 线程：初始化 GL-Vulkan 共享图像
            RTBridgeMod.getRTRenderer().initExternalImagesOnGLThread();

            // 每帧更新真实相机矩阵
            var mc = net.minecraft.client.MinecraftClient.getInstance();
            if (mc.gameRenderer != null && mc.gameRenderer.getCamera() != null) {
                var camera = mc.gameRenderer.getCamera();

                // MC 视图矩阵：相机旋转 (yaw/pitch)
                org.joml.Matrix4f viewMat = new org.joml.Matrix4f();
                viewMat.rotate(org.joml.Math.toRadians(camera.getYaw() + 180f),
                    new org.joml.Vector3f(0, 1, 0));
                viewMat.rotate(org.joml.Math.toRadians(camera.getPitch()),
                    new org.joml.Vector3f(1, 0, 0));

                var pos = camera.getPos();
                viewMat.translate(-(float) pos.x, -(float) pos.y, -(float) pos.z);

                // MC 投影矩阵：从 GameRenderer 取 FOV
                float fovDeg = (float) mc.options.getFov().getValue();
                float aspect = (float) mc.getWindow().getFramebufferWidth()
                             / mc.getWindow().getFramebufferHeight();
                org.joml.Matrix4f projMat = new org.joml.Matrix4f()
                    .perspective(org.joml.Math.toRadians(fovDeg), aspect, 0.05f, 512f);

                // 太阳方向：根据世界时间计算（简化：白天朝上偏一点角度）
                float dayTime = mc.world != null
                    ? (mc.world.getTimeOfDay() % 24000) / 24000f : 0.25f;
                float sunAngle = dayTime * 6.2831853f - 1.5707963f; // -90° 偏移让正午太阳在天顶
                org.joml.Vector3f sunDir = new org.joml.Vector3f(
                    (float) Math.cos(sunAngle), -(float) Math.sin(sunAngle) - 0.2f, 0.3f
                ).normalize();

                RTBridgeMod.getRTRenderer().updateCamera(viewMat, projMat, sunDir);
            }
            RTBridgeMod.getTripleBuffer().advanceFrame();
            RTBridgeMod.getRTRenderer().submitFrame(
                RTBridgeMod.getTripleBuffer().getMiddle());
        });

        WorldRenderEvents.LAST.register(ctx -> {
            // 捕获 MC 深度+法线（渲染完世界之后）
            var gb = RTBridgeMod.getGBufferCapture();
            if (gb != null) {
                var win = net.minecraft.client.MinecraftClient.getInstance().getWindow();
                if (!gb.isReady()
    || gb.getDepthTexId() < 0
    || gb.getNormalTexId() < 0) {

    gb.init(
        win.getFramebufferWidth(),
        win.getFramebufferHeight()
    );
}
                // 从 MC 默认帧缓冲读取深度纹理
                // MC Sodium/Iris 的深度纹理通过 Mixin 注入
                gb.captureFromDepth(-1, -1, 0.05f, 512f,
                    (float) Math.toRadians(
                        net.minecraft.client.MinecraftClient.getInstance().options.getFov().getValue()),
                    (float) win.getFramebufferWidth() / win.getFramebufferHeight());
            }
            var rt = RTBridgeMod.getRTRenderer();
            var fc2 = RTBridgeMod.getFrameCapture();
            if (rt.hasResult()) {
                RTBridgeMod.getCompositePass().composite(
                    fc2 != null && fc2.isReady() ? fc2.getColorTexId() : -1,
                    rt.getShadowBuffer(),
                    rt.getReflectionBuffer(),
                    rt.getGIBuffer());
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
