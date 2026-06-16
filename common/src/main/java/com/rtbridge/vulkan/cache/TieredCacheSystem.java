package com.rtbridge.vulkan.cache;

import com.rtbridge.RTBridgeMod;
import com.rtbridge.vulkan.VulkanBuffer;
import com.rtbridge.vulkan.VulkanContext;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * TieredCacheSystem — Hot / Warm / Cold 三层显存缓存。
 *
 * 分层策略：
 *
 *   Hot  (快速访问，固定驻留)
 *     - 当前相机周围 N 个区块的 BLAS
 *     - 当前活跃飞艇的 BLAS
 *     - 容量：512MB
 *     - 驱逐策略：不驱逐，除非降级到 Warm
 *
 *   Warm (近期使用，LRU 管理)
 *     - 最近 200 帧内访问过的 BLAS
 *     - 容量：768MB
 *     - 驱逐策略：LRU，降级到 Cold 或直接释放
 *
 *   Cold (延迟销毁，TTL 保护)
 *     - 刚被驱逐出 Warm 的条目
 *     - TTL：300 帧（~5秒@60fps）
 *     - 容量：256MB
 *     - 作用：避免区块卸载后立即重建造成卡顿
 *            玩家回头时直接从 Cold 提升到 Warm
 *
 * 总容量：512 + 768 + 256 = 1536MB ≈ 1.5GB
 *
 * 提升路径：Cold → Warm → Hot
 * 降级路径：Hot → Warm → Cold → 释放
 */
public class TieredCacheSystem implements AutoCloseable {

    // ── 容量配置 ──────────────────────────────────────────────────────────────

    public static final long HOT_MAX  = 512L  * 1024 * 1024; // 512MB
    public static final long WARM_MAX = 768L  * 1024 * 1024; // 768MB
    public static final long COLD_MAX = 256L  * 1024 * 1024; // 256MB

    public static final int  COLD_TTL_FRAMES = 300; // ~5秒
    public static final int  HOT_RADIUS      = 8;   // 相机周围 8 格区块固定 Hot
    public static final int  WARM_THRESHOLD  = 200; // 200帧内访问过 = Warm

    // ── 条目 ──────────────────────────────────────────────────────────────────

    public enum Tier { HOT, WARM, COLD }

    public static class TieredEntry {
        public final long        key;
        public final String      tag;
        public VulkanBuffer      buffer;
        public Tier              tier;
        public long              lastAccessFrame;
        public long              coldSinceFrame;   // 进入 Cold 的帧
        public boolean           pinned;           // Hot 固定，不降级
        public final long        sizeBytes;

        public TieredEntry(long key, String tag, VulkanBuffer buf,
                           Tier tier, long frame) {
            this.key             = key;
            this.tag             = tag;
            this.buffer          = buf;
            this.tier            = tier;
            this.lastAccessFrame = frame;
            this.sizeBytes       = buf.size;
        }
    }

    // ── 状态 ──────────────────────────────────────────────────────────────────

    private final VulkanContext ctx;

    // 三层分别用 LinkedHashMap 保持插入/访问顺序
    private final Map<Long, TieredEntry> hot  = new LinkedHashMap<>();
    private final Map<Long, TieredEntry> warm = new LinkedHashMap<>();
    private final Map<Long, TieredEntry> cold = new LinkedHashMap<>();

    // 全局索引：key → tier（快速查找）
    private final ConcurrentHashMap<Long, Tier> index = new ConcurrentHashMap<>();

    private long hotUsed  = 0;
    private long warmUsed = 0;
    private long coldUsed = 0;

    private long currentFrame = 0;

    // 相机位置（用于 Hot 半径判断）
    private int camChunkX = 0, camChunkZ = 0;

    // 统计
    private long hits = 0, misses = 0, promotions = 0, demotions = 0;

    // ── 构造 ──────────────────────────────────────────────────────────────────

    public TieredCacheSystem(VulkanContext ctx) {
        this.ctx = ctx;
        RTBridgeMod.LOGGER.info("[TieredCache] 初始化 Hot:{}MB Warm:{}MB Cold:{}MB",
            HOT_MAX/1024/1024, WARM_MAX/1024/1024, COLD_MAX/1024/1024);
    }

    // ── 帧更新 ────────────────────────────────────────────────────────────────

    /**
     * 每帧开始时调用。
     * 执行：Cold TTL 清理、Warm→Hot 提升、Hot→Warm 降级。
     */
    public void tick(long frame, int camChunkX, int camChunkZ) {
        this.currentFrame = frame;
        this.camChunkX    = camChunkX;
        this.camChunkZ    = camChunkZ;

        evictExpiredCold();
        promoteWarmToHot();
        demoteHotToWarm();
    }

    // ── 查询 ──────────────────────────────────────────────────────────────────

    /**
     * 查询缓存。
     * 命中时自动提升：Cold→Warm，Warm→Hot（如在半径内）。
     */
    public VulkanBuffer get(long key) {
        Tier tier = index.get(key);
        if (tier == null) { misses++; return null; }

        hits++;
        TieredEntry entry = getFromTier(tier, key);
        if (entry == null) { index.remove(key); misses++; return null; }

        entry.lastAccessFrame = currentFrame;

        // 自动提升
        if (tier == Tier.COLD) {
            promote(entry, Tier.WARM);
        } else if (tier == Tier.WARM && isInHotRadius(entry.key)) {
            promote(entry, Tier.HOT);
        }

        return entry.buffer;
    }

    /**
     * 写入缓存，自动分配到合适层级。
     */
    public void put(long key, VulkanBuffer buffer, String tag) {
        if (index.containsKey(key)) return;

        Tier targetTier = isInHotRadius(key) ? Tier.HOT : Tier.WARM;
        long size = buffer.size;

        // 确保目标层有空间
        ensureSpace(targetTier, size);

        TieredEntry entry = new TieredEntry(key, tag, buffer, targetTier, currentFrame);

        switch (targetTier) {
            case HOT  -> { hot.put(key, entry);  hotUsed  += size; }
            case WARM -> { warm.put(key, entry); warmUsed += size; }
            default   -> { warm.put(key, entry); warmUsed += size; }
        }
        index.put(key, targetTier);
    }

    /**
     * 固定到 Hot 层（飞艇、玩家附近区块）。
     * 固定的条目不会被自动降级。
     */
    public void pin(long key) {
        Tier tier = index.get(key);
        if (tier == null) return;
        TieredEntry entry = getFromTier(tier, key);
        if (entry == null) return;
        if (tier != Tier.HOT) promote(entry, Tier.HOT);
        entry.pinned = true;
    }

    public void unpin(long key) {
        TieredEntry entry = hot.get(key);
        if (entry != null) entry.pinned = false;
    }

    /**
     * 无效化条目（区块卸载、飞艇销毁）。
     * 不立即释放——放入 Cold 层等待 TTL。
     */
    public void invalidate(long key) {
        Tier tier = index.get(key);
        if (tier == null) return;

        TieredEntry entry = getFromTier(tier, key);
        if (entry == null) { index.remove(key); return; }

        // 从当前层移除
        removeFromTier(tier, key, entry.sizeBytes);

        // 放入 Cold（TTL 延迟销毁）
        if (coldUsed + entry.sizeBytes <= COLD_MAX) {
            entry.tier           = Tier.COLD;
            entry.coldSinceFrame = currentFrame;
            cold.put(key, entry);
            coldUsed += entry.sizeBytes;
            index.put(key, Tier.COLD);
            RTBridgeMod.LOGGER.debug("[TieredCache] {}→Cold (TTL)", entry.tag);
        } else {
            // Cold 也满了，直接释放
            entry.buffer.close();
            index.remove(key);
        }
    }

    // ── 内部：提升/降级 ───────────────────────────────────────────────────────

    private void promote(TieredEntry entry, Tier target) {
        if (entry.tier == target) return;
        Tier src = entry.tier;

        removeFromTier(src, entry.key, entry.sizeBytes);
        ensureSpace(target, entry.sizeBytes);

        entry.tier = target;
        switch (target) {
            case HOT  -> { hot.put(entry.key, entry);  hotUsed  += entry.sizeBytes; }
            case WARM -> { warm.put(entry.key, entry); warmUsed += entry.sizeBytes; }
            default   -> {}
        }
        index.put(entry.key, target);
        promotions++;
        RTBridgeMod.LOGGER.debug("[TieredCache] {} {}→{}", entry.tag, src, target);
    }

    private void demote(TieredEntry entry, Tier target) {
        if (entry.tier == target || entry.pinned) return;
        Tier src = entry.tier;

        removeFromTier(src, entry.key, entry.sizeBytes);

        entry.tier = target;
        switch (target) {
            case WARM -> { warm.put(entry.key, entry); warmUsed += entry.sizeBytes; }
            case COLD -> {
                entry.coldSinceFrame = currentFrame;
                cold.put(entry.key, entry);
                coldUsed += entry.sizeBytes;
            }
            default -> {}
        }
        index.put(entry.key, target);
        demotions++;
        RTBridgeMod.LOGGER.debug("[TieredCache] {} {}→{}", entry.tag, src, target);
    }

    // ── 内部：定期维护 ────────────────────────────────────────────────────────

    /** 清理 Cold 层中 TTL 过期的条目 */
    private void evictExpiredCold() {
        Iterator<Map.Entry<Long, TieredEntry>> it = cold.entrySet().iterator();
        while (it.hasNext()) {
            TieredEntry e = it.next().getValue();
            if (currentFrame - e.coldSinceFrame > COLD_TTL_FRAMES) {
                it.remove();
                coldUsed -= e.sizeBytes;
                index.remove(e.key);
                e.buffer.close();
                RTBridgeMod.LOGGER.debug("[TieredCache] Cold TTL 过期释放: {}", e.tag);
            }
        }
    }

    /** Warm 中在 Hot 半径内的条目提升到 Hot */
    private void promoteWarmToHot() {
        for (TieredEntry entry : new ArrayList<>(warm.values())) {
            if (isInHotRadius(entry.key)
                    && hotUsed + entry.sizeBytes <= HOT_MAX) {
                promote(entry, Tier.HOT);
            }
        }
    }

    /** Hot 中离相机太远的条目降级到 Warm */
    private void demoteHotToWarm() {
        for (TieredEntry entry : new ArrayList<>(hot.values())) {
            if (!entry.pinned && !isInHotRadius(entry.key)) {
                demote(entry, Tier.WARM);
            }
        }
    }

    // ── 容量管理 ──────────────────────────────────────────────────────────────

    private void ensureSpace(Tier tier, long needed) {
        switch (tier) {
            case HOT  -> evictLRU(hot,  hotUsed,  HOT_MAX,  needed, Tier.HOT,  Tier.WARM);
            case WARM -> evictLRU(warm, warmUsed, WARM_MAX, needed, Tier.WARM, Tier.COLD);
            case COLD -> evictLRU(cold, coldUsed, COLD_MAX, needed, Tier.COLD, null);
        }
    }

    private void evictLRU(Map<Long, TieredEntry> layer, long used, long max,
                           long needed, Tier fromTier, Tier toTier) {
        if (used + needed <= max) return;

        List<TieredEntry> sorted = new ArrayList<>(layer.values());
        sorted.sort(Comparator.comparingLong(e -> e.lastAccessFrame));

        for (TieredEntry entry : sorted) {
            if (used + needed <= max) break;
            if (entry.pinned) continue;

            if (toTier != null) {
                demote(entry, toTier);
            } else {
                layer.remove(entry.key);
                coldUsed -= entry.sizeBytes;
                index.remove(entry.key);
                entry.buffer.close();
            }
        }
    }

    // ── 工具 ──────────────────────────────────────────────────────────────────

    /**
     * 判断区块是否在 Hot 半径内。
     * key 编码为 chunkX << 32 | chunkZ（长整型）。
     */
    private boolean isInHotRadius(long key) {
        int cx = (int)(key >> 32);
        int cz = (int)(key & 0xFFFFFFFFL);
        int dx = Math.abs(cx - camChunkX);
        int dz = Math.abs(cz - camChunkZ);
        return dx <= HOT_RADIUS && dz <= HOT_RADIUS;
    }

    private TieredEntry getFromTier(Tier tier, long key) {
        return switch (tier) {
            case HOT  -> hot.get(key);
            case WARM -> warm.get(key);
            case COLD -> cold.get(key);
        };
    }

    private void removeFromTier(Tier tier, long key, long size) {
        switch (tier) {
            case HOT  -> { hot.remove(key);  hotUsed  -= size; }
            case WARM -> { warm.remove(key); warmUsed -= size; }
            case COLD -> { cold.remove(key); coldUsed -= size; }
        }
        index.remove(key);
    }

    // ── 统计 ──────────────────────────────────────────────────────────────────

    public void printStats() {
        long total = hits + misses;
        RTBridgeMod.LOGGER.info(
            "[TieredCache] Hot:{}MB/{}MB Warm:{}MB/{}MB Cold:{}MB/{}MB | " +
            "命中率:{}% 提升:{} 降级:{}",
            hotUsed/1024/1024,  HOT_MAX/1024/1024,
            warmUsed/1024/1024, WARM_MAX/1024/1024,
            coldUsed/1024/1024, COLD_MAX/1024/1024,
            total > 0 ? hits*100/total : 0,
            promotions, demotions);
    }

    public long getHotUsed()  { return hotUsed;  }
    public long getWarmUsed() { return warmUsed; }
    public long getColdUsed() { return coldUsed; }
    public long getTotalUsed(){ return hotUsed + warmUsed + coldUsed; }

    @Override
    public void close() {
        hot.values().forEach(e  -> e.buffer.close());
        warm.values().forEach(e -> e.buffer.close());
        cold.values().forEach(e -> e.buffer.close());
        hot.clear(); warm.clear(); cold.clear(); index.clear();
        RTBridgeMod.LOGGER.info("[TieredCache] 三层缓存已释放");
    }
}
