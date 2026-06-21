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

    /** 异步 RT 调度器 — 1帧延迟，GL 线程不阻塞 */
    private AsyncRTScheduler asyncScheduler;

    /** GPU 显存场景缓存 */
    private com.rtbridge.vulkan.cache.GPUSceneCache gpuCache;

    /** Snapshot of the scene passed from the main thread each frame. */
    private final AtomicReference<SceneDatabase> pendingScene = new AtomicReference<>();

    private long currentFrameIndex = 0L;

    // ── Constructor ───────────────────────────────────────────────────────────

    public RTRenderer(AsyncBLASBuilder blasBuilder) {
        this.blasBuilder = blasBuilder;
        // Vulkan 延迟到第一帧初始化，确保 LWJGL 原生库已就绪
    }

    private boolean initAttempted   = false;
    private boolean externalInited  = false;

    /** 第一帧渲染时调用，此时 OpenGL/LWJGL 已完全初始化 */
    public void initOnFirstFrame() {
        if (initAttempted) return;
        initAttempted = true;
        initVulkan();
    }

    /** GL 线程调用：初始化 GL-Vulkan 共享图像 */
    public void initExternalImagesOnGLThread() {
        if (externalInited || rtImages == null || !rtAvailable.get()) return;
        externalInited = true;

        // 提前做一次 GL 函数指针修补（确保在 ExternalImage 之前生效）
        try {
            var caps = org.lwjgl.opengl.GL.getCapabilities();
            com.rtbridge.vulkan.ExternalImage.patchGLFunctionPointers(caps);
        } catch (Throwable e) {
            RTBridgeMod.LOGGER.warn("[RTRenderer] 提前GL修补失败: {}", e.getMessage());
        }

        rtImages.initExternalImages();
        if (rtImages.externalReady) {
            shadowBufferId     = rtImages.getShadowGLTex();
            reflectionBufferId = rtImages.getReflectionGLTex();
            gIBufferId         = rtImages.getGIGLTex();
            RTBridgeMod.LOGGER.info("[RTRenderer] GL-Vulkan 共享图像接入完成");
        } else {
            // Win32 zero-copy 失败，创建普通 GL 纹理用于 CPU 回读上传
            shadowBufferId = createPlainGLTexture(renderWidth, renderHeight);
            RTBridgeMod.LOGGER.info("[RTRenderer] 使用 CPU 回读模式，普通 GL 纹理={}", shadowBufferId);
        }
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

                // blasBuilder 用 null ctx 创建，现在 Vulkan 就绪后 reinit
                if (blasBuilder != null) {
                    blasBuilder.reinit(vulkanCtx);
                }

                // 启动异步调度器
                asyncScheduler = new AsyncRTScheduler(frame -> {
                    dispatchShadowPass(frame.scene, frame.frameIndex);
                    dispatchReflectionPass(frame.scene, frame.frameIndex);
                    dispatchGIPass(frame.scene, frame.frameIndex);
                    frame.shadowTexId     = shadowBufferId;
                    frame.reflectionTexId = reflectionBufferId;
                    frame.giTexId         = gIBufferId;

                    // CPU 回读：拷贝 staging buffer 数据，供 GL 线程上传
                    if (rtImages != null && rtImages.shadowStagingPtr != 0) {
                        java.nio.ByteBuffer copy =
                            java.nio.ByteBuffer.allocateDirect((int) rtImages.shadowStagingSize);
                        org.lwjgl.system.MemoryUtil.memCopy(
                            rtImages.shadowStagingPtr,
                            org.lwjgl.system.MemoryUtil.memAddress(copy),
                            rtImages.shadowStagingSize);
                        frame.shadowPixels = copy;

                        if (frame.frameIndex % 60 == 0) {
                            long sum = 0; int sampleCount = 1000;
                            for (int i = 0; i < sampleCount; i++) {
                                int idx = (int)((long) i * copy.capacity() / sampleCount);
                                sum += (copy.get(idx) & 0xFF);
                            }
                            RTBridgeMod.LOGGER.info("[RTDiag] Shadow像素采样均值={} (0=黑,255=白) capacity={}",
                                sum / sampleCount, copy.capacity());
                        }
                    }
                });
                asyncScheduler.start();

                // 初始化 GPU 场景缓存（1.5GB）
                gpuCache = new com.rtbridge.vulkan.cache.GPUSceneCache(vulkanCtx);
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
        if (!rtAvailable.get() || asyncScheduler == null) return;
        asyncScheduler.submitFrame(middleScene);
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
    private com.rtbridge.vulkan.GLVulkanBridge glBridge;
    private com.rtbridge.vulkan.RTImageSet rtImages;
    private int renderWidth = 1920, renderHeight = 1080; // 默认分辨率

    // 真实相机状态（每帧由 GL 线程更新，RT 线程读取）
    private final org.joml.Matrix4f cachedInvView = new org.joml.Matrix4f();
    private final org.joml.Matrix4f cachedInvProj  = new org.joml.Matrix4f();
    private final org.joml.Vector3f cachedLightDir = new org.joml.Vector3f(-0.5f, -1f, -0.3f).normalize();
    private volatile boolean cameraDataValid = false;

    /**
     * GL 线程每帧调用：传入当前 MC 相机的视图矩阵和投影矩阵。
     * RT 线程下一次 dispatch 时会用这份数据。
     */
    public void updateCamera(org.joml.Matrix4f view, org.joml.Matrix4f proj,
                             org.joml.Vector3f sunDirection) {
        synchronized (cachedInvView) {
            view.invert(cachedInvView);
            proj.invert(cachedInvProj);
            cachedLightDir.set(sunDirection).normalize();
            cameraDataValid = true;
        }
    }

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

        if (!cameraDataValid) {
            if (frameIdx % 60 == 0)
                RTBridgeMod.LOGGER.info("[RTDiag] 相机数据未就绪，跳过 dispatch frame={}", frameIdx);
            return;
        }
        if (frameIdx % 60 == 0)
            RTBridgeMod.LOGGER.info("[RTDiag] dispatchShadowPass 执行 frame={}", frameIdx);

        org.joml.Matrix4f invView, invProj;
        org.joml.Vector3f lightDir;
        synchronized (cachedInvView) {
            invView  = new org.joml.Matrix4f(cachedInvView);
            invProj  = new org.joml.Matrix4f(cachedInvProj);
            lightDir = new org.joml.Vector3f(cachedLightDir);
        }

        shadowPass.dispatch(invView, invProj, lightDir);
        // 注意：shadowBufferId 不能在此处覆盖！
        // 它应该一直是 GL 纹理 ID（由 initExternalImagesOnGLThread 设置），
        // 不能被 Vulkan 的 VkImageView 句柄覆盖（之前这里有个 bug 导致 composite 失败）。
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
    public boolean hasResult() {
        if (asyncScheduler != null) return asyncScheduler.hasResult();
        return resultReady.get();
    }

    /** GL 线程调用：把 CPU 回读的像素数据上传到 GL 纹理 */
    private long uploadCount = 0;
    public void uploadPendingReadbacks() {
        if (asyncScheduler == null || !asyncScheduler.hasResult()) {
            if (uploadCount++ % 120 == 0)
                RTBridgeMod.LOGGER.info("[RTDiag] uploadPendingReadbacks: 无结果 hasResult={}",
                    asyncScheduler != null && asyncScheduler.hasResult());
            return;
        }
        var frame = asyncScheduler.getLastReadyFrame();
        if (frame == null || frame.shadowPixels == null) {
            if (uploadCount++ % 120 == 0)
                RTBridgeMod.LOGGER.info("[RTDiag] uploadPendingReadbacks: frame或pixels为null");
            return;
        }
        if (shadowBufferId < 0) return;
        if (uploadCount++ % 120 == 0)
            RTBridgeMod.LOGGER.info("[RTDiag] 上传 shadowBufferId={} size={}",
                shadowBufferId, frame.shadowPixels.capacity());

        try {
            org.lwjgl.opengl.GL11.glBindTexture(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, shadowBufferId);
            org.lwjgl.opengl.GL11.glTexSubImage2D(
                org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 0, 0, 0,
                renderWidth, renderHeight,
                org.lwjgl.opengl.GL11.GL_RGBA, org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE,
                frame.shadowPixels);
            org.lwjgl.opengl.GL11.glBindTexture(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 0);
        } catch (Throwable e) {
            RTBridgeMod.LOGGER.error("[RTRenderer] Shadow 回读上传失败: {}", e.getMessage());
        }
        frame.shadowPixels = null; // 消费后清空，避免重复上传
    }

    public int getShadowBuffer() {
        if (asyncScheduler != null && asyncScheduler.hasResult())
            return asyncScheduler.getLastReadyFrame().shadowTexId;
        return shadowBufferId;
    }
    public int getReflectionBuffer() {
        if (asyncScheduler != null && asyncScheduler.hasResult())
            return asyncScheduler.getLastReadyFrame().reflectionTexId;
        return reflectionBufferId;
    }
    public int getGIBuffer() {
        if (asyncScheduler != null && asyncScheduler.hasResult())
            return asyncScheduler.getLastReadyFrame().giTexId;
        return gIBufferId;
    }

    public TLASInstanceBuffer getTLASBuffer()   { return tlasBuffer; }
    public com.rtbridge.bvh.TLASManager getTLASManager() { return tlasManager; }
    public com.rtbridge.vulkan.VulkanContext getVulkanContext() { return vulkanCtx; }
    public AsyncBLASBuilder   getBLASBuilder()  { return blasBuilder; }

    /**
     * GL 线程调用：把 MC 真实深度/法线 CPU 回读数据拷贝进 Vulkan staging buffer。
     * 这些数据将在下次 ShadowPass.dispatch() 时被拷贝进 gDepthImage/gNormalImage。
     */
    public void uploadGBufferToVulkan(java.nio.ByteBuffer depthData, java.nio.ByteBuffer normalData) {
        if (rtImages == null) return;
        try {
            if (depthData != null && rtImages.gDepthUploadPtr != 0) {
                long copySize = Math.min(depthData.remaining(), rtImages.gDepthUploadSize);
                org.lwjgl.system.MemoryUtil.memCopy(
                    org.lwjgl.system.MemoryUtil.memAddress(depthData),
                    rtImages.gDepthUploadPtr, copySize);
            }
            if (normalData != null && rtImages.gNormalUploadPtr != 0) {
                long copySize = Math.min(normalData.remaining(), rtImages.gNormalUploadSize);
                org.lwjgl.system.MemoryUtil.memCopy(
                    org.lwjgl.system.MemoryUtil.memAddress(normalData),
                    rtImages.gNormalUploadPtr, copySize);
            }
        } catch (Throwable e) {
            RTBridgeMod.LOGGER.error("[RTRenderer] GBuffer 上传失败: {}", e.getMessage());
        }
    }

    private int createPlainGLTexture(int w, int h) {
        int tex = org.lwjgl.opengl.GL11.glGenTextures();
        org.lwjgl.opengl.GL11.glBindTexture(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, tex);
        org.lwjgl.opengl.GL11.glTexImage2D(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 0,
            org.lwjgl.opengl.GL11.GL_RGBA, w, h, 0,
            org.lwjgl.opengl.GL11.GL_RGBA, org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE,
            (java.nio.ByteBuffer) null);
        org.lwjgl.opengl.GL11.glTexParameteri(org.lwjgl.opengl.GL11.GL_TEXTURE_2D,
            org.lwjgl.opengl.GL11.GL_TEXTURE_MIN_FILTER, org.lwjgl.opengl.GL11.GL_LINEAR);
        org.lwjgl.opengl.GL11.glTexParameteri(org.lwjgl.opengl.GL11.GL_TEXTURE_2D,
            org.lwjgl.opengl.GL11.GL_TEXTURE_MAG_FILTER, org.lwjgl.opengl.GL11.GL_LINEAR);
        org.lwjgl.opengl.GL11.glBindTexture(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 0);
        return tex;
    }

    public void shutdown() {
        if (asyncScheduler != null) asyncScheduler.stop();
        if (gpuCache        != null) gpuCache.close();
        if (blasBuilder     != null) blasBuilder.shutdown();
    }
}
