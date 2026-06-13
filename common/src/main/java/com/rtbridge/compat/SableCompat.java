package com.rtbridge.compat;

import com.rtbridge.RTBridgeMod;
import com.rtbridge.bvh.TLASManager;
import com.rtbridge.event.DirtyEventSystem;
import com.rtbridge.scene.SceneDatabase;
import com.rtbridge.scene.cache.EmissiveCache;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;



import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * SableCompat — RTBridge 与 Sable 飞艇子世界的集成。
 *
 * Sable 真实 API 包名：dev.rew1nd.sable.api.SableCompanion
 * 参考 AirshipCockpit Mod 的做法，使用反射访问，避免硬依赖。
 *
 * 核心方法：
 *   projectOutOfSubLevel(Level, BlockPos) → Vec3
 *   将飞艇本地坐标转换为世界坐标
 *
 * RTBridge 规则（spec §11）：
 *   子世界 MOVE/ROTATE → 只更新 TLAS Instance Transform（不重建 BLAS）
 *   子世界 LOAD        → 异步 BLAS 构建 + 占位 TLAS 实例
 *   子世界 UNLOAD      → 移除 BLAS + TLAS 实例
 */
public class SableCompat {

    // ── 反射缓存 ───────────────────────────────────────────────────────────────

    private static boolean methodChecked   = false;
    private static boolean methodAvailable = false;

    private static Object  sableInstance; // SableCompanion.INSTANCE
    private static Method  projectMethod;  // projectOutOfSubLevel(Level, BlockPos)
    private static Class<?>  sableClass;

    // ── RTBridge 引用 ──────────────────────────────────────────────────────────

    private final DirtyEventSystem dirtyEvents;
    private final TLASManager      tlasManager;

    // 已知子世界：id → 上一帧位置哈希（用于检测移动）
    private final Map<Long, Long> knownSubLevels = new HashMap<>();

    public SableCompat(DirtyEventSystem dirtyEvents, TLASManager tlasManager) {
        this.dirtyEvents = dirtyEvents;
        this.tlasManager = tlasManager;
    }

    // ── 注册 ──────────────────────────────────────────────────────────────────

    public void register() {
        NeoForge.EVENT_BUS.addListener(this::onClientTick);
        RTBridgeMod.LOGGER.info("[SableCompat] 已注册，Sable 存在={}", isLoaded());
    }

    // ── 可用性检测 ────────────────────────────────────────────────────────────

    public static boolean isLoaded() {
        return ModList.get().isLoaded("sable");
    }

    /**
     * 检测 Sable Companion API 是否可通过反射访问。
     * 与 AirshipCockpit 一致：用 Class.forName 探测。
     */
    public static boolean isMethodAvailable() {
        if (methodChecked) return methodAvailable;
        methodChecked = true;
        try {
            sableClass    = Class.forName("dev.rew1nd.sable.api.SableCompanion");
            Field instFld = sableClass.getField("INSTANCE");
            sableInstance = instFld.get(null);
            projectMethod = sableClass.getMethod("projectOutOfSubLevel",
                Level.class, BlockPos.class);
            methodAvailable = true;
            RTBridgeMod.LOGGER.info("[SableCompat] Sable Companion API 加载成功");
        } catch (Exception e) {
            methodAvailable = false;
            RTBridgeMod.LOGGER.warn("[SableCompat] Sable Companion API 未找到，将使用原始坐标");
        }
        return methodAvailable;
    }

    // ── 坐标转换（核心方法）──────────────────────────────────────────────────

    /**
     * 将飞艇本地坐标转换为世界坐标。
     * 等同于 SableCompanion.INSTANCE.projectOutOfSubLevel(level, localPos)
     *
     * @param level    当前客户端 Level
     * @param localPos 飞艇内的方块本地坐标
     * @return 世界坐标 Vec3，失败时返回方块中心坐标
     */
    public static Vec3 toWorldPos(Level level, BlockPos localPos) {
        if (!isMethodAvailable()) {
            return Vec3.atCenterOf(localPos);
        }
        try {
            return (Vec3) projectMethod.invoke(sableInstance, level, localPos);
        } catch (Exception e) {
            RTBridgeMod.LOGGER.debug("[SableCompat] projectOutOfSubLevel 调用失败: {}", e.getMessage());
            return Vec3.atCenterOf(localPos);
        }
    }

    // ── 每帧 Tick ─────────────────────────────────────────────────────────────

    private void onClientTick(ClientTickEvent.Post event) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.level == null || !isMethodAvailable()) return;

        // 由于 Sable 没有推送式事件，每帧轮询子世界列表
        // TODO: 通过 SableCompanion 获取所有活跃子世界列表
        // 目前通过 RTBridgeMod 的 SceneDatabase 里已注册的 ship 来轮询
        updateKnownSubLevels(mc.level);
    }

    /**
     * 对每个已知飞艇：检测是否移动，更新 TLAS Transform 和发光光源世界坐标。
     */
    private void updateKnownSubLevels(Level level) {
        // TODO: 当 Sable 提供 getAllSubLevels() 接口时替换下方逻辑
        // 目前：遍历 SceneDatabase 中标记为 isOnShip 的光源
        // 以此推断活跃飞艇 ID，并重新投影光源位置

        SceneDatabase db = RTBridgeMod.getSceneDatabase();
        if (db == null) return;

        Set<Long> activeShipIds = new HashSet<>();
        db.readLock();
        try {
            for (EmissiveCache.EmissiveEntry e : db.emissiveCache().all()) {
                if (e.isOnShip()) activeShipIds.add(e.shipId());
            }
        } finally {
            db.readUnlock();
        }

        for (long shipId : activeShipIds) {
            reprojectShipLights(shipId, level, db);
        }
    }

    /**
     * 重新投影某艘飞艇上所有发光光源的世界坐标。
     * 每次飞艇移动/旋转后调用。
     */
    private void reprojectShipLights(long shipId, Level level, SceneDatabase db) {
        db.writeLock();
        try {
            // 收集需要更新的光源
            var toUpdate = new java.util.ArrayList<EmissiveCache.EmissiveEntry>();
            for (EmissiveCache.EmissiveEntry e : db.emissiveCache().all()) {
                if (e.isOnShip() && e.shipId() == shipId) toUpdate.add(e);
            }

            for (EmissiveCache.EmissiveEntry e : toUpdate) {
                if (e.blockPos() == null) continue;

                // 用 Sable API 将本地 BlockPos 投影到世界坐标
                Vec3 worldVec = toWorldPos(level, e.blockPos());
                Vector3f worldPos = new Vector3f(
                    (float) worldVec.x,
                    (float) worldVec.y,
                    (float) worldVec.z);

                // 移除旧条目，用新世界坐标重新注册
                db.emissiveCache().remove(e.id());
                db.emissiveCache().addShipLight(
                    e.blockPos(), worldPos,
                    e.color(), e.intensity(), e.radius(), shipId);
            }
        } finally {
            db.writeUnlock();
        }
    }

    // ── 飞艇生命周期（供 DirtyEventSystem 调用）──────────────────────────────

    public void onShipCreate(long shipId, Level level, BlockPos originPos) {
        RTBridgeMod.LOGGER.info("[SableCompat] 飞艇创建: id={}", shipId);
        knownSubLevels.put(shipId, 0L);
        dirtyEvents.postShipCreate(shipId);

        // TLAS 占位实例
        if (tlasManager != null) {
            Vec3 wPos = toWorldPos(level, originPos);
            Matrix4f transform = new Matrix4f().translate(
                (float) wPos.x, (float) wPos.y, (float) wPos.z);
            var placeholder = new com.rtbridge.vulkan.BLASEntry(-1L);
            tlasManager.addInstance(shipId, placeholder, transform);
        }
    }

    public void onShipDestroy(long shipId) {
        RTBridgeMod.LOGGER.info("[SableCompat] 飞艇销毁: id={}", shipId);
        knownSubLevels.remove(shipId);
        if (tlasManager != null) tlasManager.removeInstance(shipId);
        dirtyEvents.postShipDestroy(shipId);
    }

    /**
     * 飞艇移动/旋转 — 只更新 TLAS Transform，不重建 BLAS。
     */
    public void onShipMove(long shipId, Level level, BlockPos newOrigin) {
        if (tlasManager != null) {
            Vec3 wPos = toWorldPos(level, newOrigin);
            Matrix4f transform = new Matrix4f().translate(
                (float) wPos.x, (float) wPos.y, (float) wPos.z);
            tlasManager.updateTransform(shipId, transform);
        }
        dirtyEvents.postShipMove(shipId);
    }
}
