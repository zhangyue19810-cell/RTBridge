f = 'fabric/src/main/java/com/rtbridge/fabric/RTBridgeFabric.java'
c = open(f).read()

# 把 GBuffer 捕获 + 上传逻辑从 LAST 移到 AFTER_TRANSLUCENT
old = '''        WorldRenderEvents.LAST.register(ctx -> {
            var mc = net.minecraft.client.MinecraftClient.getInstance();
            if (mc.world == null) return;
            var win = mc.getWindow();
            int W = win.getFramebufferWidth();
            int H = win.getFramebufferHeight();

            // 1. GBuffer 捕获：blit真实深度 + 重建法线 + CPU回读 + 上传Vulkan
            var gb = RTBridgeMod.getGBufferCapture();
            if (gb != null) {
                if (!gb.isReady()) {
                    gb.init(W, H);
                }
                float fovRad = (float) Math.toRadians(mc.options.getFov().getValue());
                float aspect = (float) W / H;
                gb.captureFromDepth(0.05f, 512f, fovRad, aspect);

                // CPU 回读 + 上传给 Vulkan（供 ShadowPass 真实GBuffer采样）
                var rtR = RTBridgeMod.getRTRenderer();
                if (rtR != null) {
                    var depthData  = gb.readDepthCPU();
                    var normalData = gb.readNormalCPU();

                    // 诊断：每60帧打印深度/法线采样值
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

            // 2. 捕获当前帧画面作为 BaseColor（这一步之前丢失了！）'''

new = '''        // GBuffer 捕获改到 AFTER_TRANSLUCENT（世界几何体画完，比 LAST 更早，
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
                gb.captureFromDepth(0.05f, 512f, fovRad, aspect);

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

            // 捕获当前帧画面作为 BaseColor'''

c = c.replace(old, new)
open(f, 'w').write(c)
print("Patched:", old not in c)
