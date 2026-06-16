package com.rtbridge.render;

import com.rtbridge.RTBridgeMod;
import com.rtbridge.scene.SceneDatabase;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * AsyncRTScheduler — 异步 1 帧延迟调度器。
 *
 * 工作模式：
 *   帧N：GL 提交场景快照 → RT 线程异步开始渲染
 *   帧N：GL 直接用帧N-1 的 RT 结果合成（不等待）
 *   帧N+1：RT 结果就绪 → 下一帧合成
 *
 * 时间线：
 *   GL:  [渲染N] [合成N-1] [渲染N+1] [合成N] ...
 *   RT:       [RT N-1]         [RT N]         [RT N+1]
 *
 * 延迟：仅 1 帧（~16ms@60fps），视觉几乎无感。
 * 性能：GL 线程完全不等 RT，帧率翻倍。
 */
public class AsyncRTScheduler {

    // ── 帧状态 ───────────────────────────────────────────────────────────────

    public enum FrameState { IDLE, SUBMITTED, RENDERING, READY }

    public static class RTFrame {
        public final long         frameIndex;
        public final SceneDatabase scene;      // 场景快照（Middle buffer）
        public volatile FrameState state = FrameState.IDLE;

        // RT 结果 GL 纹理 ID（由 GLVulkanBridge 填写）
        public volatile int shadowTexId     = -1;
        public volatile int reflectionTexId = -1;
        public volatile int giTexId         = -1;

        public RTFrame(long idx, SceneDatabase scene) {
            this.frameIndex = idx;
            this.scene      = scene;
        }
    }

    // ── 状态 ─────────────────────────────────────────────────────────────────

    /** 当前正在 RT 渲染的帧 */
    private final AtomicReference<RTFrame> inFlight = new AtomicReference<>();

    /** 上一帧已就绪的 RT 结果（GL 当前帧使用） */
    private final AtomicReference<RTFrame> lastReady = new AtomicReference<>();

    /** 待 RT 渲染的帧队列（深度=1，新帧覆盖旧帧） */
    private final AtomicReference<RTFrame> pending = new AtomicReference<>();

    private final AtomicBoolean running    = new AtomicBoolean(true);
    private final AtomicLong    frameCount = new AtomicLong(0);

    private Thread rtThread;

    // ── 回调接口 ──────────────────────────────────────────────────────────────

    @FunctionalInterface
    public interface RTFrameRenderer {
        /** 在 RT 线程上渲染一帧，填写 frame 的纹理 ID */
        void render(RTFrame frame);
    }

    private final RTFrameRenderer renderer;

    // ── 构造 ──────────────────────────────────────────────────────────────────

    public AsyncRTScheduler(RTFrameRenderer renderer) {
        this.renderer = renderer;
    }

    // ── 生命周期 ──────────────────────────────────────────────────────────────

    public void start() {
        rtThread = new Thread(this::rtLoop, "RTBridge-RTRenderer");
        rtThread.setDaemon(true);
        rtThread.start();
        RTBridgeMod.LOGGER.info("[AsyncRT] 异步 RT 调度器启动");
    }

    public void stop() {
        running.set(false);
        if (rtThread != null) rtThread.interrupt();
    }

    // ── GL 线程调用 ───────────────────────────────────────────────────────────

    /**
     * GL 线程每帧末尾调用：提交新场景快照给 RT 线程。
     * 非阻塞，立即返回。
     *
     * @param scene 当前帧的 Middle SceneDatabase
     */
    public void submitFrame(SceneDatabase scene) {
        long idx = frameCount.incrementAndGet();
        RTFrame frame = new RTFrame(idx, scene);
        frame.state = FrameState.SUBMITTED;

        // 覆盖式提交：如果 RT 还没处理上一帧，直接丢弃（跳帧）
        RTFrame old = pending.getAndSet(frame);
        if (old != null && old.state == FrameState.SUBMITTED) {
            RTBridgeMod.LOGGER.debug("[AsyncRT] 跳帧: {}", old.frameIndex);
        }
    }

    /**
     * GL 线程获取最新可用的 RT 结果（上一帧）。
     * 非阻塞，没有结果时返回 null。
     */
    public RTFrame getLastReadyFrame() {
        return lastReady.get();
    }

    /** 是否有就绪的 RT 结果可用 */
    public boolean hasResult() {
        RTFrame f = lastReady.get();
        return f != null && f.state == FrameState.READY && f.shadowTexId >= 0;
    }

    // ── RT 线程主循环 ─────────────────────────────────────────────────────────

    private void rtLoop() {
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            // 等待新帧（自旋+睡眠，避免 busy wait）
            RTFrame frame = pending.getAndSet(null);
            if (frame == null) {
                try { Thread.sleep(1); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt(); break;
                }
                continue;
            }

            // 渲染
            frame.state = FrameState.RENDERING;
            inFlight.set(frame);

            try {
                long t0 = System.nanoTime();
                renderer.render(frame);
                long ms = (System.nanoTime() - t0) / 1_000_000;

                frame.state = FrameState.READY;
                lastReady.set(frame);

                RTBridgeMod.LOGGER.debug("[AsyncRT] 帧{} RT完成 {}ms", frame.frameIndex, ms);
            } catch (Throwable e) {
                RTBridgeMod.LOGGER.error("[AsyncRT] RT渲染失败 帧{}", frame.frameIndex, e);
                frame.state = FrameState.IDLE;
            } finally {
                inFlight.compareAndSet(frame, null);
            }
        }
        RTBridgeMod.LOGGER.info("[AsyncRT] RT 线程退出");
    }

    // ── 统计 ──────────────────────────────────────────────────────────────────

    public long getFrameCount()   { return frameCount.get(); }
    public boolean isRendering()  { return inFlight.get() != null; }
}
