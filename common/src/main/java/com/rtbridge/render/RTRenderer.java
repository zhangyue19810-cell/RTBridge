package com.rtbridge.render;

import com.rtbridge.RTBridgeMod;
import com.rtbridge.vulkan.VulkanContext;
import com.rtbridge.bvh.TLASManager;
import com.rtbridge.bvh.AsyncBLASBuilder;
import com.rtbridge.bvh.TLASInstanceBuffer;
import com.rtbridge.light.LightCluster;
import com.rtbridge.light.ReservoirSampler;
import com.rtbridge.scene.SceneDatabase;
import com.rtbridge.scene.cache.EmissiveCache;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * RTRenderer — spec §6 (RT Renderer).
 *
 * Responsible for producing the three supplementary buffers that are
 * composited on top of the OpenGL main pass:
 *
 *   ShadowBuffer     — binary / soft shadow factor  (multiplied in composite)
 *   ReflectionBuffer — reflection radiance           (Fresnel-blended)
 *   GIBuffer         — indirect illumination         (additive)
 *
 * Key design rules:
 *   - This is a SUPPLEMENT to OpenGL, not a replacement.
 *   - If Vulkan RT is unavailable, all buffers are set to "neutral" defaults
 *     so the CompositePass degrades gracefully.
 *   - RT work runs on a dedicated thread to avoid blocking the main thread.
 *   - Results are considered "ready" only for the same frame they were
 *     produced (same-frame composite — spec §4 / §10 goal).
 */
public class RTRenderer {

    // ── Output buffer handles (GPU resource IDs / textures) ──────────────────
    // In real implementation these would be VkImage / GL texture handles.

    private volatile int shadowBufferId     = -1;
    private volatile int reflectionBufferId = -1;
    private volatile int gIBufferId         = -1;

    private final AtomicBoolean resultReady  = new AtomicBoolean(false);
    private final AtomicBoolean rtAvailable  = new AtomicBoolean(false);

    // ── Sub-systems ───────────────────────────────────────────────────────────

    private final AsyncBLASBuilder   blasBuilder;
    private final TLASInstanceBuffer tlasBuffer  = new TLASInstanceBuffer();
    private final LightCluster       lightCluster = new LightCluster();
    private final ReservoirSampler   reservoirSampler = new ReservoirSampler(lightCluster);

    /** Dedicated RT thread — all Vulkan RT calls happen here. */
    private final ExecutorService rtThread =
        Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "RTBridge-RTRenderer");
            t.setDaemon(true);
            return t;
        });

    /** Snapshot of the scene passed from the main thread each frame. */
    private final AtomicReference<SceneDatabase> pendingScene = new AtomicReference<>();

    private long currentFrameIndex = 0L;

    // ── Constructor ───────────────────────────────────────────────────────────

    public RTRenderer(AsyncBLASBuilder blasBuilder) {
        this.blasBuilder = blasBuilder;
        // Vulkan 延迟到第一帧初始化，确保 LWJGL 原生库已就绪
    }

    private boolean initAttempted = false;

    /** 第一帧渲染时调用，此时 OpenGL/LWJGL 已完全初始化 */
    public void initOnFirstFrame() {
        if (initAttempted) return;
        initAttempted = true;
        initVulkan();
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Attempt to initialise the Vulkan RT backend.
     * Safe to call even if Vulkan is unavailable — sets rtAvailable=false.
     *
     * TODO: Replace stub with actual LWJGL Vulkan initialisation:
     *   - vkCreateInstance
     *   - VK_KHR_ray_tracing_pipeline + VK_KHR_acceleration_structure
     *   - Swapchain sharing with MC's existing GL context via VK_KHR_external_memory
     */
    private void initVulkan() {
        try {
            // 先检查 LWJGL Vulkan RT 类是否可用
            Class.forName("org.lwjgl.vulkan.VkInstance");
            Class.forName("org.lwjgl.vulkan.VkAccelerationStructureCreateInfoKHR");

            boolean vulkanSupported = checkVulkanSupport();
            rtAvailable.set(vulkanSupported);
            if (vulkanSupported) {
                RTBridgeMod.LOGGER.info("[RTRenderer] Vulkan RT 初始化完成");
                allocateOutputBuffers();
            } else {
                RTBridgeMod.LOGGER.warn("[RTRenderer] 当前 GPU 不支持 RT，RT 已禁用");
            }
        } catch (ClassNotFoundException | NoClassDefFoundError e) {
            RTBridgeMod.LOGGER.warn("[RTRenderer] LWJGL Vulkan KHR 扩展不可用，RT 已禁用: {}", e.getMessage());
            rtAvailable.set(false);
        } catch (Throwable e) {
            RTBridgeMod.LOGGER.error("[RTRenderer] Vulkan 初始化失败", e);
            rtAvailable.set(false);
        }
    }

    private VulkanContext vulkanCtx;
    private TLASManager tlasManager;

    private boolean checkVulkanSupport() {
        // TODO: org.lwjgl.vulkan.VK10.vkEnumerateInstanceExtensionProperties(...)
        // Check for VK_KHR_acceleration_structure and VK_KHR_ray_tracing_pipeline
        vulkanCtx = new com.rtbridge.vulkan.VulkanContext();
        boolean ok = vulkanCtx.init();
        if (ok) tlasManager = new com.rtbridge.bvh.TLASManager(vulkanCtx);
        return ok;
    }

    private void allocateOutputBuffers() {
        // TODO: allocate VkImage (VK_FORMAT_R16G16B16A16_SFLOAT) for each buffer
        // and create shared GL texture via VK_KHR_external_memory_win32 / fd
        RTBridgeMod.LOGGER.debug("[RTRenderer] Output buffers allocated (stub).");
    }

    // ── Frame submission ──────────────────────────────────────────────────────

    /**
     * Called by the main thread at the end of each OpenGL frame.
     * Hands off the current Middle scene snapshot to the RT thread.
     */
    public void submitFrame(SceneDatabase middleScene) {
        if (!rtAvailable.get()) return;

        resultReady.set(false);
        pendingScene.set(middleScene);
        rtThread.submit(this::renderRT);
    }

    // ── RT frame (runs on rtThread) ───────────────────────────────────────────

    private void renderRT() {
        SceneDatabase scene = pendingScene.getAndSet(null);
        if (scene == null) return;

        try {
            scene.readLock();
            try {
                long frameIdx = ++currentFrameIndex;

                // Step 1 — TLAS update
                updateTLAS(scene);

                // Step 2 — Light clustering
                if (lightCluster.isDirty()) {
                    lightCluster.rebuild(scene.emissiveCache());
                }

                // Step 3 — Dispatch RT pipelines
                dispatchShadowPass(scene, frameIdx);
                dispatchReflectionPass(scene, frameIdx);
                dispatchGIPass(scene, frameIdx);

            } finally {
                scene.readUnlock();
            }

            resultReady.set(true);

        } catch (Exception e) {
            RTBridgeMod.LOGGER.error("[RTRenderer] RT frame failed", e);
        }
    }

    // ── TLAS update ───────────────────────────────────────────────────────────

    private void updateTLAS(SceneDatabase scene) {
        if (tlasBuffer.needsStructuralRebuild()) {
            rebuildTLASFull();
        } else if (tlasBuffer.needsTransformUpload()) {
            uploadDirtyTransforms();
        }
        tlasBuffer.clearDirtyFlags();
    }

    /**
     * Full TLAS rebuild — called when instances are added/removed.
     * TODO: vkCmdBuildAccelerationStructuresKHR (TLAS, UPDATE=false)
     */
    private void rebuildTLASFull() {
        RTBridgeMod.LOGGER.debug("[RTRenderer] TLAS structural rebuild ({} instances)",
            tlasBuffer.size());
        // Populate VkAccelerationStructureInstanceKHR array from tlasBuffer.all()
        // then call vkCmdBuildAccelerationStructuresKHR
    }

    /**
     * Cheap transform-only TLAS update (no structural change).
     * TODO: vkCmdBuildAccelerationStructuresKHR (TLAS, UPDATE=true / patch only)
     */
    private void uploadDirtyTransforms() {
        RTBridgeMod.LOGGER.debug("[RTRenderer] TLAS transform patch ({} dirty)",
            tlasBuffer.dirtyTransforms().size());
        // Patch only the dirty instance transforms in the instance buffer
    }

    // ── RT pass dispatches ────────────────────────────────────────────────────

    /**
     * Shadow pass — produces a shadow attenuation factor per pixel.
     *
     * TODO: vkCmdTraceRaysKHR with:
     *   - RayGen: shoot shadow ray toward each sampled light
     *   - AnyHit: alpha-test for transparent geometry
     *   - Miss: light is visible → factor = 1.0
     *   - ClosestHit: blocked → factor = 0.0 (or soft shadow accumulation)
     *
     * Output: shadowBufferId (R8 or R16F texture)
     */
    private ShadowPass shadowPass;
    private com.rtbridge.vulkan.RTImageSet rtImages;
    private int renderWidth = 1920, renderHeight = 1080; // 默认分辨率

    private void dispatchShadowPass(SceneDatabase scene, long frameIdx) {
        if (vulkanCtx == null || tlasManager == null) return;

        // Lazy init
        if (shadowPass == null) {
            rtImages = new com.rtbridge.vulkan.RTImageSet(vulkanCtx);
            rtImages.allocate(renderWidth, renderHeight);

            shadowPass = new ShadowPass(vulkanCtx, tlasManager,
                rtImages, renderWidth, renderHeight);
            if (!shadowPass.init()) {
                RTBridgeMod.LOGGER.error("[RTRenderer] ShadowPass 初始化失败");
                shadowPass = null;
                return;
            }
        }

        // 太阳光方向（固定，后续从 MC 世界时间读取）
        org.joml.Vector3f lightDir = new org.joml.Vector3f(-0.5f, -1f, -0.3f).normalize();
        org.joml.Matrix4f invView  = new org.joml.Matrix4f(); // TODO: 从 MC camera 读取
        org.joml.Matrix4f invProj  = new org.joml.Matrix4f(); // TODO: 从 MC camera 读取

        shadowPass.dispatch(invView, invProj, lightDir);
        shadowBufferId = (int) rtImages.shadowView; // 供 CompositePass 使用
        RTBridgeMod.LOGGER.debug("[RTRenderer] Shadow pass dispatched frame={}", frameIdx);
    }

    /**
     * Reflection pass — screen-space RT reflections.
     *
     * TODO: vkCmdTraceRaysKHR with:
     *   - RayGen: read GBuffer normal + roughness, compute reflection ray
     *   - ClosestHit: shade hit surface (use material cache)
     *   - Miss: sample sky / environment map
     *
     * Output: reflectionBufferId (RGB16F texture)
     */
    private void dispatchReflectionPass(SceneDatabase scene, long frameIdx) {
        // TODO: bind GBuffer textures from OpenGL via shared memory
    }

    /**
     * GI pass — one-bounce indirect illumination via reservoir sampling.
     *
     * TODO: vkCmdTraceRaysKHR with ReSTIR DI / ReSTIR GI pipeline.
     *   - RayGen: sample light from reservoir, trace visibility ray
     *   - Apply temporal + spatial reuse across N neighbour pixels
     *
     * Output: gIBufferId (RGB16F texture)
     */
    private void dispatchGIPass(SceneDatabase scene, long frameIdx) {
        // TODO: bind reservoir buffer from previous frame (temporal reuse)
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public boolean isAvailable()  { return rtAvailable.get(); }
    public boolean hasResult()    { return resultReady.get(); }

    public int getShadowBuffer()     { return shadowBufferId; }
    public int getReflectionBuffer() { return reflectionBufferId; }
    public int getGIBuffer()         { return gIBufferId; }

    public TLASInstanceBuffer getTLASBuffer()   { return tlasBuffer; }
    public com.rtbridge.bvh.TLASManager getTLASManager() { return tlasManager; }
    public AsyncBLASBuilder   getBLASBuilder()  { return blasBuilder; }

    public void shutdown() {
        rtThread.shutdown();
        blasBuilder.shutdown();
    }
}
