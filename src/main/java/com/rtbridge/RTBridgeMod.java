package com.rtbridge;

import com.rtbridge.bvh.AsyncBLASBuilder;
import com.rtbridge.buffer.TripleBuffer;
import com.rtbridge.compat.SableCompat;
import com.rtbridge.event.DirtyEventSystem;
import com.rtbridge.render.CompositePass;
import com.rtbridge.render.RTRenderer;
import com.rtbridge.scene.SceneDatabase;
import com.rtbridge.scene.SceneExtractor;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RTBridgeMod implements ClientModInitializer {

    public static final String MOD_ID = "rtbridge";
    public static final Logger LOGGER  = LoggerFactory.getLogger(MOD_ID);

    private static DirtyEventSystem dirtyEventSystem;
    private static SceneDatabase    sceneDatabase;
    private static SceneExtractor   sceneExtractor;
    private static TripleBuffer     tripleBuffer;
    private static RTRenderer       rtRenderer;
    private static CompositePass    compositePass;
    private static SableCompat      sableCompat;

    @Override
    public void onInitializeClient() {
        LOGGER.info("[RTBridge] 初始化 v{}", getVersion());

        dirtyEventSystem = new DirtyEventSystem();
        sceneDatabase    = new SceneDatabase();
        tripleBuffer     = new TripleBuffer(sceneDatabase);
        sceneExtractor   = new SceneExtractor(dirtyEventSystem, sceneDatabase, tripleBuffer);

        AsyncBLASBuilder blasBuilder = new AsyncBLASBuilder(null);
        rtRenderer   = new RTRenderer(blasBuilder);
        compositePass = new CompositePass();

        dirtyEventSystem.registerFabricHooks();

        // Sable 兼容
        if (SableCompat.isLoaded()) {
            sableCompat = new SableCompat(dirtyEventSystem,
                rtRenderer.isAvailable() ? rtRenderer.getTLASManager() : null);
            sableCompat.register();
            LOGGER.info("[RTBridge] Sable 兼容层已启用");
        }

        // 渲染钩子
        WorldRenderEvents.END.register(ctx -> {
            tripleBuffer.advanceFrame();
            rtRenderer.submitFrame(tripleBuffer.getMiddle());
        });

        WorldRenderEvents.LAST.register(ctx -> {
            if (rtRenderer.hasResult()) {
                compositePass.composite(
                    rtRenderer.getShadowBuffer(),
                    rtRenderer.getReflectionBuffer(),
                    rtRenderer.getGIBuffer()
                );
            }
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.world != null) sceneExtractor.tick();
        });

        LOGGER.info("[RTBridge] 就绪。RT={}", rtRenderer.isAvailable() ? "Vulkan" : "已禁用");
    }

    public static DirtyEventSystem getDirtyEventSystem() { return dirtyEventSystem; }
    public static SceneDatabase    getSceneDatabase()    { return sceneDatabase; }
    public static RTRenderer       getRTRenderer()       { return rtRenderer; }

    private static String getVersion() {
        var v = RTBridgeMod.class.getPackage().getImplementationVersion();
        return v != null ? v : "dev";
    }
}
