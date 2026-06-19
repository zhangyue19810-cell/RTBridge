package com.rtbridge.fabric;

import com.rtbridge.RTBridgeMod;
import com.rtbridge.compat.SableCompat;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;

public class RTBridgeFabric implements ClientModInitializer {

    // IEEE754 half-float (16bit) → float 转换
    private static float halfToFloat(short half) {
        int h = half & 0xFFFF;
        int sign = (h >> 15) & 1;
        int exp  = (h >> 10) & 0x1F;
        int mant = h & 0x3FF;
        float val;
        if (exp == 0) {
            val = (float) (mant * Math.pow(2, -24));
        } else if (exp == 31) {
            val = mant == 0 ? Float.POSITIVE_INFINITY : Float.NaN;
        } else {
            val = (float) ((1 + mant / 1024.0) * Math.pow(2, exp - 15));
        }
        return sign == 1 ? -val : val;
    }
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
        // GBuffer 捕获改到 AFTER_TRANSLUCENT（世界几何体画完，比 LAST 更早，
        // 避开 Sodium/Veil 等渲染优化mod在 LAST 之前可能已经清空/替换深度附件的问题）
        WorldRenderEvents.AFTER_TRANSLUCENT.register(ctx -> {
            var mc = net.minecraft.client.MinecraftClient.getInstance();
            if (mc.world == null) return;
            var win = mc.getWindow();
            int W = win.getFramebufferWidth();
            int H = win.getFramebufferHeight();

            var gb = RTBridgeMod.getGBufferCapture();
            if (gb != null) {
                if (!gb.isReady()) {
                    gb.init(W, H);
                }
                float fovRad = (float) Math.toRadians(mc.options.getFov().getValue());
                float aspect = (float) W / H;

                // 计算当前帧相机的"视空间→世界空间"旋转矩阵（与渲染所用一致）
                org.joml.Matrix3f invViewRot = new org.joml.Matrix3f();
                if (mc.gameRenderer != null && mc.gameRenderer.getCamera() != null) {
                    var camera = mc.gameRenderer.getCamera();
                    org.joml.Matrix4f viewMat = new org.joml.Matrix4f();
                    viewMat.rotate(org.joml.Math.toRadians(camera.getYaw() + 180f),
                        new org.joml.Vector3f(0, 1, 0));
                    viewMat.rotate(org.joml.Math.toRadians(camera.getPitch()),
                        new org.joml.Vector3f(1, 0, 0));
                    // 只取旋转部分（不含平移），求逆得到 视空间→世界空间 的旋转
                    viewMat.normal(invViewRot); // normal矩阵 = inverse-transpose的3x3，纯旋转时等价于直接转置
                }

                gb.captureFromDepth(0.05f, 512f, fovRad, aspect, invViewRot);

                var rtR = RTBridgeMod.getRTRenderer();
                if (rtR != null) {
                    var depthData  = gb.readDepthCPU();
                    var normalData = gb.readNormalCPU();

                    if (mc.world.getTime() % 60 == 0 && depthData != null && normalData != null) {
                        depthData.order(java.nio.ByteOrder.nativeOrder());
                        normalData.order(java.nio.ByteOrder.nativeOrder());
                        float dSum = 0; int dCount = 100;
                        float dMin = Float.MAX_VALUE, dMax = -Float.MAX_VALUE;
                        for (int i = 0; i < dCount; i++) {
                            int idx = (int)((long) i * (depthData.remaining() / 4) / dCount) * 4;
                            float v = depthData.getFloat(idx);
                            dSum += v; dMin = Math.min(dMin, v); dMax = Math.max(dMax, v);
                        }
                        float nx=0, ny=0, nz=0; int nCount = 100;
                        for (int i = 0; i < nCount; i++) {
                            int idx = (int)((long) i * (normalData.remaining() / 8) / nCount) * 8;
                            nx += halfToFloat(normalData.getShort(idx));
                            ny += halfToFloat(normalData.getShort(idx + 2));
                            nz += halfToFloat(normalData.getShort(idx + 4));
                        }
                        RTBridgeMod.LOGGER.info("[GBDiag] 深度均值={} min={} max={} 法线均值=({},{},{})",
                            dSum / dCount, dMin, dMax, nx / nCount, ny / nCount, nz / nCount);
                    }

                    rtR.uploadGBufferToVulkan(depthData, normalData);
                }
            }
        });

        WorldRenderEvents.LAST.register(ctx -> {
            var mc = net.minecraft.client.MinecraftClient.getInstance();
            if (mc.world == null) return;
            var win = mc.getWindow();
            int W = win.getFramebufferWidth();
            int H = win.getFramebufferHeight();

            // 捕获当前帧画面作为 BaseColor
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
