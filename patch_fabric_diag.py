f = 'fabric/src/main/java/com/rtbridge/fabric/RTBridgeFabric.java'
c = open(f).read()

old = '''                // CPU 回读 + 上传给 Vulkan（供 ShadowPass 真实GBuffer采样）
                var rtR = RTBridgeMod.getRTRenderer();
                if (rtR != null) {
                    var depthData  = gb.readDepthCPU();
                    var normalData = gb.readNormalCPU();
                    rtR.uploadGBufferToVulkan(depthData, normalData);
                }'''

new = '''                // CPU 回读 + 上传给 Vulkan（供 ShadowPass 真实GBuffer采样）
                var rtR = RTBridgeMod.getRTRenderer();
                if (rtR != null) {
                    var depthData  = gb.readDepthCPU();
                    var normalData = gb.readNormalCPU();

                    // 诊断：每60帧打印深度/法线采样值
                    if (mc.world.getTime() % 60 == 0 && depthData != null && normalData != null) {
                        float dSum = 0; int dCount = 100;
                        for (int i = 0; i < dCount; i++) {
                            int idx = (int)((long) i * (depthData.remaining() / 4) / dCount) * 4;
                            dSum += depthData.getFloat(idx);
                        }
                        float nx=0, ny=0, nz=0; int nCount = 100;
                        for (int i = 0; i < nCount; i++) {
                            int idx = (int)((long) i * (normalData.remaining() / 8) / nCount) * 8;
                            nx += normalData.getShort(idx);
                            ny += normalData.getShort(idx + 2);
                            nz += normalData.getShort(idx + 4);
                        }
                        RTBridgeMod.LOGGER.info("[GBDiag] 深度均值={} 法线原始short均值=({},{},{})",
                            dSum / dCount, nx / nCount, ny / nCount, nz / nCount);
                    }

                    rtR.uploadGBufferToVulkan(depthData, normalData);
                }'''

c = c.replace(old, new)
open(f, 'w').write(c)
print("Patched:", old not in c)
