package com.rtbridge.compat;

import com.rtbridge.RTBridgeMod;
import com.rtbridge.bvh.TLASManager;
import com.rtbridge.event.DirtyEventSystem;
import com.rtbridge.scene.SceneDatabase;
import com.rtbridge.scene.cache.EmissiveCache;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * SableCompat — RTBridge 与 Sable 飞艇子世界的集成。
 *
 * 使用反射访问 dev.ryanhcode.sable.api.SableCompanion，避免硬依赖。
 * 平台事件注册由 fabric/neoforge 各自的入口负责调用 tick()。
 *
 * 核心方法：
 *   projectOutOfSubLevel(Level, BlockPos) → Vec3
 *   将飞艇本地坐标转换为世界坐标
 */
public class SableCompat {

    // ── 反射缓存 ───────────────────────────────────────────────────────────────

    private static boolean methodChecked   = false;
    private static boolean methodAvailable = false;
    private static Object  sableInstance;
    private static Method  projectMethod;

    // ── 引用 ──────────────────────────────────────────────────────────────────

    private final DirtyEventSystem dirtyEvents;
    private final TLASManager      tlasManager;
    private final Map<Long, Long>  knownSubLevels = new HashMap<>();

    public SableCompat(DirtyEventSystem dirtyEvents, TLASManager tlasManager) {
        this.dirtyEvents = dirtyEvents;
        this.tlasManager = tlasManager;
    }

    // ── 可用性检测 ────────────────────────────────────────────────────────────

    public static boolean isLoaded() {
        try {
            Class.forName("dev.ryanhcode.sable.api.SableCompanion");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    public static boolean isMethodAvailable() {
        if (methodChecked) return methodAvailable;
        methodChecked = true;
        try {
            Class<?> cls    = Class.forName("dev.ryanhcode.sable.api.SableCompanion");
            Field instFld   = cls.getField("INSTANCE");
            sableInstance   = instFld.get(null);
            projectMethod   = cls.getMethod("projectOutOfSubLevel", Level.class, BlockPos.class);
            methodAvailable = true;
            RTBridgeMod.LOGGER.info("[SableCompat] Sable Companion API 加载成功");
        } catch (Exception e) {
            methodAvailable = false;
            RTBridgeMod.LOGGER.warn("[SableCompat] Sable Companion API 未找到，将使用原始坐标");
        }
        return methodAvailable;
    }

    // ── 坐标转换 ──────────────────────────────────────────────────────────────

    public static Vec3d toWorldPos(World level, BlockPos localPos) {
        if (!isMethodAvailable()) return Vec3d.ofCenter(localPos);
        try {
            return (Vec3d) projectMethod.invoke(sableInstance, level, localPos);
        } catch (Exception e) {
            return Vec3d.ofCenter(localPos);
        }
    }

    // ── 每帧 Tick（由平台入口调用）────────────────────────────────────────────

    public void tick(World level) {
        if (!isMethodAvailable()) return;
        updateShipLights(level);
    }

    private void updateShipLights(World level) {
        SceneDatabase db = RTBridgeMod.getSceneDatabase();
        if (db == null) return;

        db.writeLock();
        try {
            var toUpdate = new java.util.ArrayList<EmissiveCache.EmissiveEntry>();
            for (EmissiveCache.EmissiveEntry e : db.emissiveCache().all()) {
                if (e.isOnShip()) toUpdate.add(e);
            }
            for (EmissiveCache.EmissiveEntry e : toUpdate) {
                if (e.blockPos() == null) continue;
                Vec3d worldVec = toWorldPos(level, e.blockPos());
                Vector3f worldPos = new Vector3f(
                    (float) worldVec.x,
                    (float) worldVec.y,
                    (float) worldVec.z);
                db.emissiveCache().remove(e.id());
                db.emissiveCache().addShipLight(
                    e.blockPos(), worldPos,
                    e.color(), e.intensity(), e.radius(), e.shipId());
            }
        } finally {
            db.writeUnlock();
        }
    }

    // ── 飞艇生命周期 ──────────────────────────────────────────────────────────

    public void onShipCreate(long shipId, World level, BlockPos originPos) {
        RTBridgeMod.LOGGER.info("[SableCompat] 飞艇创建: id={}", shipId);
        knownSubLevels.put(shipId, 0L);
        dirtyEvents.postShipCreate(shipId);
        if (tlasManager != null) {
            Vec3 wPos = toWorldPos(level, originPos);
            Matrix4f transform = new Matrix4f().translate(
                (float) wPos.x, (float) wPos.y, (float) wPos.z);
            var placeholder = new com.rtbridge.vulkan.BLASEntry(-1L);
            tlasManager.addInstance(shipId, placeholder, transform);
        }
    }

    public void onShipDestroy(long shipId) {
        knownSubLevels.remove(shipId);
        if (tlasManager != null) tlasManager.removeInstance(shipId);
        dirtyEvents.postShipDestroy(shipId);
    }

    public void onShipMove(long shipId, World level, BlockPos newOrigin) {
        if (tlasManager != null) {
            Vec3 wPos = toWorldPos(level, newOrigin);
            Matrix4f transform = new Matrix4f().translate(
                (float) wPos.x, (float) wPos.y, (float) wPos.z);
            tlasManager.updateTransform(shipId, transform);
        }
        dirtyEvents.postShipMove(shipId);
    }

    // ── Fabric 平台注册（由 RTBridgeFabric 调用）─────────────────────────────

    public void register() {
        // 平台特定事件注册在 fabric/RTBridgeFabric 里完成
        RTBridgeMod.LOGGER.info("[SableCompat] 已注册，Sable 存在={}", isLoaded());
    }
}
