package com.rtbridge.vulkan.cache;

import com.rtbridge.RTBridgeMod;
import com.rtbridge.vulkan.VulkanBuffer;
import com.rtbridge.vulkan.VulkanContext;

import java.util.*;

import static org.lwjgl.vulkan.VK12.*;

public class GPUSceneCache implements AutoCloseable {

    /** 三层缓存系统（Hot/Warm/Cold） */
    private TieredCacheSystem tiered;
    private boolean tieredReady = false;

    public static final long DEFAULT_MAX_BYTES = 1536L * 1024 * 1024; // 1.5GB

    private final VulkanContext ctx;
    private final long maxBytes;
    private long usedBytes = 0;
    private long currentFrame = 0;
    private long totalHits = 0, totalMisses = 0, totalUploads = 0;

    public static class CacheEntry {
        public final long key;
        public final String tag;
        public VulkanBuffer buffer;
        public long lastUsedFrame;
        public final long sizeBytes;

        public CacheEntry(long key, String tag, VulkanBuffer buf, long frame) {
            this.key = key; this.tag = tag; this.buffer = buf;
            this.lastUsedFrame = frame; this.sizeBytes = buf.size;
        }
    }

    private final LinkedHashMap<Long, CacheEntry> chunkBLAS = new LinkedHashMap<>();
    private final LinkedHashMap<Long, CacheEntry> shipBLAS  = new LinkedHashMap<>();
    private final Deque<VulkanBuffer> stagingPool = new ArrayDeque<>();

    public GPUSceneCache(VulkanContext ctx, long maxBytes) {
        this.ctx = ctx; this.maxBytes = maxBytes;
        RTBridgeMod.LOGGER.info("[GPUCache] 初始化 容量:{}MB", maxBytes / 1024 / 1024);
        try {
            tiered = new TieredCacheSystem(ctx);
            tieredReady = true;
        } catch (Throwable e) {
            RTBridgeMod.LOGGER.warn("[GPUCache] 三层缓存初始化失败，使用基础缓存: {}", e.getMessage());
        }
    }

    public GPUSceneCache(VulkanContext ctx) { this(ctx, DEFAULT_MAX_BYTES); }

    public void beginFrame(long frame, int camChunkX, int camChunkZ) {
        this.currentFrame = frame;
        if (tieredReady) tiered.tick(frame, camChunkX, camChunkZ);
    }

    public void beginFrame(long frame) { beginFrame(frame, 0, 0); }

    // ── Chunk BLAS ────────────────────────────────────────────────────────────

    public VulkanBuffer getChunkBLAS(long key) {
        if (tieredReady) {
            VulkanBuffer b = tiered.get(key);
            if (b != null) { totalHits++; return b; }
            totalMisses++; return null;
        }
        CacheEntry e = chunkBLAS.get(key);
        if (e != null) { e.lastUsedFrame = currentFrame; totalHits++; return e.buffer; }
        totalMisses++; return null;
    }

    public void putChunkBLAS(long key, float[] verts, int[] idx) {
        long size = (long) verts.length * 4 + (long) idx.length * 4;
        VulkanBuffer buf = upload(verts, size);
        if (buf == null) return;
        if (tieredReady) {
            tiered.put(key, buf, "chunk:" + key);
        } else {
            if (chunkBLAS.containsKey(key)) { buf.close(); return; }
            evict(size);
            if (usedBytes + size > maxBytes) { buf.close(); return; }
            chunkBLAS.put(key, new CacheEntry(key, "chunk", buf, currentFrame));
            usedBytes += size;
        }
        totalUploads++;
    }

    public void invalidateChunk(long key) {
        if (tieredReady) { tiered.invalidate(key); return; }
        CacheEntry e = chunkBLAS.remove(key);
        if (e != null) { usedBytes -= e.sizeBytes; e.buffer.close(); }
    }

    // ── Ship BLAS ─────────────────────────────────────────────────────────────

    public VulkanBuffer getShipBLAS(long shipId) {
        CacheEntry e = shipBLAS.get(shipId);
        if (e != null) { e.lastUsedFrame = currentFrame; totalHits++; return e.buffer; }
        totalMisses++; return null;
    }

    public void putShipBLAS(long shipId, float[] verts, int[] idx) {
        long size = (long) verts.length * 4 + (long) idx.length * 4;
        evict(size);
        VulkanBuffer buf = upload(verts, size);
        if (buf == null) return;
        shipBLAS.put(shipId, new CacheEntry(shipId, "ship", buf, currentFrame));
        usedBytes += size; totalUploads++;
    }

    public void invalidateShip(long shipId) {
        CacheEntry e = shipBLAS.remove(shipId);
        if (e != null) { usedBytes -= e.sizeBytes; e.buffer.close(); }
    }

    // ── LRU 淘汰 ─────────────────────────────────────────────────────────────

    private void evict(long needed) {
        if (usedBytes + needed <= maxBytes) return;
        long toFree = (usedBytes + needed) - maxBytes;
        long freed  = 0;

        List<Map.Entry<Long, CacheEntry>> sorted = new ArrayList<>(chunkBLAS.entrySet());
        sorted.sort(Comparator.comparingLong(e -> e.getValue().lastUsedFrame));

        for (Map.Entry<Long, CacheEntry> entry : sorted) {
            if (freed >= toFree) break;
            CacheEntry ce = chunkBLAS.remove(entry.getKey());
            if (ce != null) {
                freed += ce.sizeBytes; usedBytes -= ce.sizeBytes; ce.buffer.close();
            }
        }
    }

    // ── 上传 ─────────────────────────────────────────────────────────────────

    private VulkanBuffer upload(float[] verts, long size) {
        try {
            VulkanBuffer staging = acquireStaging(size);
            staging.upload(verts);
            VulkanBuffer device  = VulkanBuffer.deviceAS(ctx.physDevice, ctx.device, size);
            // TODO: vkCmdCopyBuffer
            releaseStaging(staging);
            return device;
        } catch (Throwable e) {
            RTBridgeMod.LOGGER.error("[GPUCache] 上传失败: {}", e.getMessage());
            return null;
        }
    }

    private VulkanBuffer acquireStaging(long min) {
        for (Iterator<VulkanBuffer> it = stagingPool.iterator(); it.hasNext();) {
            VulkanBuffer b = it.next();
            if (b.size >= min) { it.remove(); return b; }
        }
        return VulkanBuffer.staging(ctx.physDevice, ctx.device,
            Math.max(min, 4L * 1024 * 1024));
    }

    private void releaseStaging(VulkanBuffer b) {
        if (stagingPool.size() < 4) stagingPool.push(b); else b.close();
    }

    // ── 统计 ──────────────────────────────────────────────────────────────────

    public void printStats() {
        if (tieredReady) tiered.printStats();
        long rate = (totalHits + totalMisses) > 0
            ? totalHits * 100 / (totalHits + totalMisses) : 0;
        RTBridgeMod.LOGGER.info("[GPUCache] {}MB/{}MB 命中率:{}% 上传:{} 命中:{} 未命中:{}",
            usedBytes/1024/1024, maxBytes/1024/1024, rate, totalUploads, totalHits, totalMisses);
    }

    public long getUsedBytes()  { return usedBytes; }
    public long getMaxBytes()   { return maxBytes; }
    public int  getChunkCount() { return chunkBLAS.size(); }

    @Override
    public void close() {
        chunkBLAS.values().forEach(e -> e.buffer.close());
        shipBLAS.values().forEach(e -> e.buffer.close());
        stagingPool.forEach(VulkanBuffer::close);
        if (tieredReady) tiered.close();
        RTBridgeMod.LOGGER.info("[GPUCache] 显存缓存已释放");
    }
}
