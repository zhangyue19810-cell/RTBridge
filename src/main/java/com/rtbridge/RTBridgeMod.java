package com.rtbridge;

import com.rtbridge.bvh.AsyncBLASBuilder;
import com.rtbridge.buffer.TripleBuffer;
import com.rtbridge.event.DirtyEventSystem;
import com.rtbridge.render.CompositePass;
import com.rtbridge.render.RTRenderer;
import com.rtbridge.scene.SceneDatabase;
import com.rtbridge.scene.SceneExtractor;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(value = RTBridgeMod.MOD_ID, dist = Dist.CLIENT)
public class RTBridgeMod {

    public static final String MOD_ID = "rtbridge";
    public static final Logger LOGGER  = LoggerFactory.getLogger(MOD_ID);

    private static DirtyEventSystem dirtyEventSystem;
    private static SceneDatabase    sceneDatabase;
    private static SceneExtractor   sceneExtractor;
    private static TripleBuffer     tripleBuffer;
    private static RTRenderer       rtRenderer;
    private static CompositePass    compositePass;

    public RTBridgeMod(IEventBus modBus) {
        modBus.addListener(this::clientSetup);
    }

    private void clientSetup(FMLClientSetupEvent event) {
        LOGGER.info("[RTBridge] Client setup start");

        dirtyEventSystem = new DirtyEventSystem();
        sceneDatabase    = new SceneDatabase();
        tripleBuffer     = new TripleBuffer(sceneDatabase);
        sceneExtractor   = new SceneExtractor(dirtyEventSystem, sceneDatabase, tripleBuffer);

        AsyncBLASBuilder blasBuilder = new AsyncBLASBuilder(null); // VulkanContext injected after init
        rtRenderer   = new RTRenderer(blasBuilder);
        compositePass = new CompositePass();

        dirtyEventSystem.registerNeoForgeHooks();

        // Render hooks
        NeoForge.EVENT_BUS.addListener(this::onRenderLevelStage);

        LOGGER.info("[RTBridge] Ready. RT={}", rtRenderer.isAvailable() ? "Vulkan" : "disabled");
    }

    private void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_LEVEL) {
            tripleBuffer.advanceFrame();
            rtRenderer.submitFrame(tripleBuffer.getMiddle());
        }
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            if (rtRenderer.hasResult()) {
                compositePass.composite(
                    rtRenderer.getShadowBuffer(),
                    rtRenderer.getReflectionBuffer(),
                    rtRenderer.getGIBuffer()
                );
            }
        }
    }

    public static DirtyEventSystem getDirtyEventSystem() { return dirtyEventSystem; }
    public static SceneDatabase    getSceneDatabase()    { return sceneDatabase; }
    public static RTRenderer       getRTRenderer()       { return rtRenderer; }
}
