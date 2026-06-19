f = 'fabric/src/main/java/com/rtbridge/fabric/RTBridgeFabric.java'
c = open(f).read()

old = '''        WorldRenderEvents.AFTER_TRANSLUCENT.register(ctx -> {
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
                gb.captureFromDepth(0.05f, 512f, fovRad, aspect);'''

new = '''        WorldRenderEvents.AFTER_TRANSLUCENT.register(ctx -> {
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

                gb.captureFromDepth(0.05f, 512f, fovRad, aspect, invViewRot);'''

c = c.replace(old, new)
open(f, 'w').write(c)
print("Fabric patched:", old not in c)
