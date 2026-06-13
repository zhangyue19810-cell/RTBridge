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
            RTBridgeMod.getTripleBuffer().advanceFrame();
            RTBridgeMod.getRTRenderer().submitFrame(
                RTBridgeMod.getTripleBuffer().getMiddle());
        });

        WorldRenderEvents.LAST.register(ctx -> {
            var rt = RTBridgeMod.getRTRenderer();
            if (rt.hasResult()) {
                RTBridgeMod.getCompositePass().composite(
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
