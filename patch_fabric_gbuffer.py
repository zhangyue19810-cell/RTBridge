f = 'fabric/src/main/java/com/rtbridge/fabric/RTBridgeFabric.java'
c = open(f).read()

old = '''            // 1. GBuffer 捕获（深度/法线，目前用占位）
            var gb = RTBridgeMod.getGBufferCapture();
            if (gb != null) {
                if (!gb.isReady() || gb.getDepthTexId() < 0 || gb.getNormalTexId() < 0) {
                    gb.init(W, H);
                }
                gb.captureFromDepth(-1, -1, 0.05f, 512f,
                    (float) Math.toRadians(mc.options.getFov().getValue()),
                    (float) W / H);
            }'''

new = '''            // 1. GBuffer 捕获：blit真实深度 + 重建法线 + CPU回读 + 上传Vulkan
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
                    rtR.uploadGBufferToVulkan(depthData, normalData);
                }
            }'''

c = c.replace(old, new)
open(f, 'w').write(c)
print("Patched:", old not in c)
