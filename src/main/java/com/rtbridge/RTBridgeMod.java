package com.rtbridge;

import com.rtbridge.bvh.AsyncBLASBuilder;
import com.rtbridge.buffer.TripleBuffer;
import com.rtbridge.event.DirtyEventSystem;
import com.rtbridge.render.RTRenderer;
import com.rtbridge.render.CompositePass;
import com.rtbridge.scene.SceneDatabase;
import com.rtbridge.scene.SceneExtractor;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * RTBridge — Minecraft OpenGL + Vulkan RT dual-branch renderer.
 *
 * Pipeline overview:
 *   World → DirtyEventSystem → SceneExtractor → SceneDatabase (TripleBuffer)
 *       → [OpenGL branch stays untouched]
 *       → [RT branch: AsyncBLASBuilder → TLAS → VulkanRTXBackend]
 *       → CompositePass → FinalFrame
 *
 * Key design goals:
 *   - Zero full-world scans per frame; purely event-driven updates
 *   - OpenGL rendering is NEVER hijacked
 *   - Async BLAS build so ShipMove never stalls the main thread
 *   - Same-frame composite (no 2-frame lag / temporal tearing)
 */
public class RTBridgeMod implements ClientModInitializer {

    public static final String MOD_ID = "rtbridge";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    // ── Singletons ────────────────────────────────────────────────────────────
    private static DirtyEventSystem dirtyEventSystem;
    private static SceneDatabase    sceneDatabase;
    private static SceneExtractor   sceneExtractor;
    private static TripleBuffer     tripleBuffer;
    private static AsyncBLASBuilder blasBuilder;
    private static RTRenderer       rtRenderer;
    private static CompositePass    compositePass;

    @Override
    public void onInitializeClient() {
        LOGGER.info("[RTBridge] Initialising v{}", getVersion());

        // 1. Core data layer
        dirtyEventSystem = new DirtyEventSystem();
        sceneDatabase    = new SceneDatabase();
        tripleBuffer     = new TripleBuffer(sceneDatabase);
        sceneExtractor   = new SceneExtractor(dirtyEventSystem, sceneDatabase, tripleBuffer);

        // 2. RT pipeline
        blasBuilder  = new AsyncBLASBuilder();
        rtRenderer   = new RTRenderer(blasBuilder);
        compositePass = new CompositePass();

        // 3. Register world-event hooks
        dirtyEventSystem.registerFabricHooks();

        // 4. Inject into render loop (after OpenGL has drawn its frame)
        WorldRenderEvents.END.register(context -> {
            // Rotate triple buffer: Back → Middle, signal RT thread
            tripleBuffer.advanceFrame();
            rtRenderer.submitFrame(tripleBuffer.getMiddle());
        });

        // 5. Composite RT results onto the final framebuffer
        WorldRenderEvents.LAST.register(context -> {
            if (rtRenderer.hasResult()) {
                compositePass.composite(
                    rtRenderer.getShadowBuffer(),
                    rtRenderer.getReflectionBuffer(),
                    rtRenderer.getGIBuffer()
                );
            }
        });

        // 6. Per-tick maintenance (light cluster rebuild, etc.)
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.level != null) {
                sceneExtractor.tick();
            }
        });

        LOGGER.info("[RTBridge] Ready. RT={}", rtRenderer.isAvailable() ? "Vulkan" : "disabled");
    }

    // ── Static accessors ──────────────────────────────────────────────────────
    public static DirtyEventSystem getDirtyEventSystem() { return dirtyEventSystem; }
    public static SceneDatabase    getSceneDatabase()    { return sceneDatabase; }
    public static SceneExtractor   getSceneExtractor()   { return sceneExtractor; }
    public static RTRenderer       getRTRenderer()       { return rtRenderer; }

    private static String getVersion() {
        return RTBridgeMod.class.getPackage().getImplementationVersion() != null
            ? RTBridgeMod.class.getPackage().getImplementationVersion()
            : "dev";
    }
}
