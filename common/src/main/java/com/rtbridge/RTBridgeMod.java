package com.rtbridge;

import com.rtbridge.bvh.AsyncBLASBuilder;
import com.rtbridge.buffer.TripleBuffer;
import com.rtbridge.compat.SableCompat;
import com.rtbridge.event.DirtyEventSystem;
import com.rtbridge.render.CompositePass;
import com.rtbridge.render.RTRenderer;
import com.rtbridge.scene.SceneDatabase;
import com.rtbridge.scene.SceneExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 平台无关核心 — Fabric/NeoForge 各自的入口调用 init()
 */
public class RTBridgeMod {

    public static final String MOD_ID = "rtbridge";
    public static final Logger LOGGER  = LoggerFactory.getLogger(MOD_ID);

    private static DirtyEventSystem dirtyEventSystem;
    private static SceneDatabase    sceneDatabase;
    private static SceneExtractor   sceneExtractor;
    private static TripleBuffer     tripleBuffer;
    private static RTRenderer       rtRenderer;
    private static CompositePass    compositePass;
    private static SableCompat      sableCompat;
    private static com.rtbridge.render.MCGBufferCapture gBufferCapture;
    private static com.rtbridge.render.FrameCapture frameCapture;

    public static void init() {
        LOGGER.info("[RTBridge] 核心初始化");

        dirtyEventSystem = new DirtyEventSystem();
        sceneDatabase    = new SceneDatabase();
        tripleBuffer     = new TripleBuffer(sceneDatabase);
        sceneExtractor   = new SceneExtractor(dirtyEventSystem, sceneDatabase, tripleBuffer);

        AsyncBLASBuilder blasBuilder;
        try {
            blasBuilder = new AsyncBLASBuilder(null);
        } catch (Throwable e) {
            LOGGER.warn("[RTBridge] AsyncBLASBuilder 初始化失败，RT 禁用: {}", e.getMessage());
            blasBuilder = null;
        }
        rtRenderer   = new RTRenderer(blasBuilder);
        compositePass = new CompositePass();

        // GBuffer 捕获器（GL 线程初始化）
        gBufferCapture = new com.rtbridge.render.MCGBufferCapture();
        frameCapture   = new com.rtbridge.render.FrameCapture();

        if (SableCompat.isLoaded()) {
            sableCompat = new SableCompat(dirtyEventSystem,
                rtRenderer.isAvailable() ? rtRenderer.getTLASManager() : null);
            sableCompat.register();
        }

        LOGGER.info("[RTBridge] 就绪。RT={}", rtRenderer.isAvailable() ? "Vulkan" : "已禁用");
    }

    public static DirtyEventSystem getDirtyEventSystem() { return dirtyEventSystem; }
    public static SceneDatabase    getSceneDatabase()    { return sceneDatabase; }
    public static RTRenderer       getRTRenderer()       { return rtRenderer; }
    public static com.rtbridge.render.MCGBufferCapture getGBufferCapture() { return gBufferCapture; }
    public static com.rtbridge.render.FrameCapture          getFrameCapture()   { return frameCapture; }
    public static TripleBuffer     getTripleBuffer()     { return tripleBuffer; }
    public static CompositePass    getCompositePass()    { return compositePass; }
    public static SceneExtractor   getSceneExtractor()   { return sceneExtractor; }
    public static SableCompat      getSableCompat()      { return sableCompat; }
}
