f = 'fabric/src/main/java/com/rtbridge/fabric/RTBridgeFabric.java'
c = open(f).read()

old = '''                    if (mc.world.getTime() % 60 == 0 && depthData != null && normalData != null) {
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
                    }'''

new = '''                    if (mc.world.getTime() % 60 == 0 && depthData != null && normalData != null) {
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
                    }'''

c = c.replace(old, new)

# 加 halfToFloat 工具函数
old2 = 'public class RTBridgeFabric implements ClientModInitializer {'
new2 = '''public class RTBridgeFabric implements ClientModInitializer {

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
    }'''

c = c.replace(old2, new2)
open(f, 'w').write(c)
print("Diag patched:", old not in c, old2 not in c)
