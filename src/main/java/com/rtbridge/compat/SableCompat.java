package com.rtbridge.compat;

import com.rtbridge.RTBridgeMod;
import com.rtbridge.bvh.TLASManager;
import com.rtbridge.event.DirtyEventSystem;
import com.rtbridge.scene.SceneDatabase;
import com.rtbridge.scene.cache.EmissiveCache;
import com.rtbridge.scene.cache.TransformCache;
import dev.ryanhcode.sable.companion.SableCompanion;
import dev.ryanhcode.sable.companion.SubLevelAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.joml.Matrix4f;
import org.joml.Quaterniond;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * SableCompat — RTBridge integration with Sable sub-levels.
 *
 * Sable uses "sub-levels" as moving block structures (airships, vehicles).
 * Each sub-level has a Pose3dc (position + rotation) updated every tick.
 *
 * RTBridge rules (from spec §11, adapted for Sable):
 *   SubLevel MOVE/ROTATE → TLAS Instance Transform only  (no BLAS rebuild)
 *   SubLevel LOAD        → async BLAS build + placeholder TLAS instance
 *   SubLevel UNLOAD      → remove BLAS + TLAS instance
 *   SubLevel MODIFIED    → queue BLAS rebuild
 *
 * Uses sable-companion (lightweight, no hard dep on full Sable).
 * Safe to load even when Sable is absent — companion provides no-op defaults.
 */
public class SableCompat {

    private final DirtyEventSystem dirtyEvents;
    private final TLASManager      tlasManager;

    // Track known sub-levels: subLevelId → last transform hash
    // Used to detect movement without a dedicated event
    private final Map<Long, Long> knownSubLevels = new HashMap<>();

    public SableCompat(DirtyEventSystem dirtyEvents, TLASManager tlasManager) {
        this.dirtyEvents = dirtyEvents;
        this.tlasManager = tlasManager;
    }

    // ── Registration ──────────────────────────────────────────────────────────

    public void register() {
        NeoForge.EVENT_BUS.addListener(this::onClientTick);
        RTBridgeMod.LOGGER.info("[SableCompat] Registered (Sable present={})", isLoaded());
    }

    // ── Per-tick scan ─────────────────────────────────────────────────────────

    /**
     * Called every client tick.
     * Companion has no push events, so we pull sub-level state each tick
     * and diff against last known state.
     */
    private void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        Level level = mc.level;
        Set<Long> currentIds = new HashSet<>();

        // Iterate all sub-levels via companion
        SableCompanion.INSTANCE.forEachSubLevel(level, subLevel -> {
            long id = subLevelId(subLevel);
            currentIds.add(id);

            if (!knownSubLevels.containsKey(id)) {
                // New sub-level discovered
                onSubLevelLoaded(id, subLevel);
            } else {
                // Check if transform changed
                long transformHash = hashPose(subLevel);
                if (knownSubLevels.get(id) != transformHash) {
                    onSubLevelMoved(id, subLevel);
                    knownSubLevels.put(id, transformHash);
                }
            }
        });

        // Find removed sub-levels
        Set<Long> removed = new HashSet<>(knownSubLevels.keySet());
        removed.removeAll(currentIds);
        for (long id : removed) {
            onSubLevelUnloaded(id);
        }
    }

    // ── Sub-level lifecycle ───────────────────────────────────────────────────

    private void onSubLevelLoaded(long id, SubLevelAccess subLevel) {
        RTBridgeMod.LOGGER.info("[SableCompat] SubLevel loaded: {}", id);
        knownSubLevels.put(id, hashPose(subLevel));

        // Register placeholder transform immediately
        Matrix4f transform = poseToMatrix(subLevel);
        tlasManager.addInstance(id, dummyBLAS(), transform);

        // Post event → SceneExtractor queues async BLAS build
        dirtyEvents.postShipCreate(id);
    }

    private void onSubLevelMoved(long id, SubLevelAccess subLevel) {
        // MOVE: only update TLAS transform, never rebuild BLAS
        Matrix4f transform = poseToMatrix(subLevel);
        tlasManager.updateTransform(id, transform);
        dirtyEvents.postShipMove(id);

        // Update emissive light world positions for this sub-level
        updateSubLevelLights(id, subLevel);
    }

    private void onSubLevelUnloaded(long id) {
        RTBridgeMod.LOGGER.info("[SableCompat] SubLevel unloaded: {}", id);
        knownSubLevels.remove(id);
        tlasManager.removeInstance(id);
        dirtyEvents.postShipDestroy(id);
    }

    // ── Emissive lights ───────────────────────────────────────────────────────

    /**
     * Re-project sub-level-local emissive positions to world space.
     * Called every time the sub-level moves.
     */
    private void updateSubLevelLights(long subLevelId, SubLevelAccess subLevel) {
        SceneDatabase back = RTBridgeMod.getSceneDatabase();
        if (back == null) return;

        back.writeLock();
        try {
            for (EmissiveCache.EmissiveEntry e : back.emissiveCache().all()) {
                if (!e.isOnShip() || e.shipId() != subLevelId) continue;

                // Transform local position → world space via sub-level pose
                org.joml.primitives.AABBd aabb = new org.joml.primitives.AABBd();
                var pose = subLevel.logicalPose();
                // pose.transformPosition returns a new Vec3d
                var worldPos = pose.transformPosition(
                    e.worldPos().x, e.worldPos().y, e.worldPos().z);

                // Re-add with updated world position
                // (EmissiveEntry is a record; remove + re-add)
                back.emissiveCache().remove(e.id());
                back.emissiveCache().addShipLight(
                    e.blockPos(),
                    new Vector3f((float) worldPos.x, (float) worldPos.y, (float) worldPos.z),
                    e.color(), e.intensity(), e.radius(), subLevelId);
            }
        } finally {
            back.writeUnlock();
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Convert Sable Pose3dc → JOML Matrix4f for TLAS instance transform.
     */
    private Matrix4f poseToMatrix(SubLevelAccess subLevel) {
        var pose = subLevel.logicalPose();

        // Sable Pose3dc: position() → Vector3dc, orientation() → Quaterniondc
        var pos  = pose.position();
        var rot  = pose.orientation();

        return new Matrix4f()
            .translate((float) pos.x(), (float) pos.y(), (float) pos.z())
            .rotate(new Quaternionf(
                (float) rot.x(), (float) rot.y(),
                (float) rot.z(), (float) rot.w()));
    }

    /**
     * Stable ID for a sub-level.
     * Companion exposes hashCode() — use it as a stable ID within a session.
     */
    private long subLevelId(SubLevelAccess subLevel) {
        return subLevel.id();
    }

    /**
     * Hash current pose for change detection.
     * XOR position and orientation components into a long.
     */
    private long hashPose(SubLevelAccess subLevel) {
        var pose = subLevel.logicalPose();
        var pos  = pose.position();
        var rot  = pose.orientation();
        long h   = Double.doubleToLongBits(pos.x())
                 ^ Double.doubleToLongBits(pos.y()) * 31L
                 ^ Double.doubleToLongBits(pos.z()) * 961L
                 ^ Double.doubleToLongBits(rot.w()) * 29791L;
        return h;
    }

    /** Placeholder BLAS entry until async build completes. */
    private com.rtbridge.vulkan.BLASEntry dummyBLAS() {
        var e = new com.rtbridge.vulkan.BLASEntry(-1L);
        e.deviceAddress = 0L;
        return e;
    }

    // ── Availability ──────────────────────────────────────────────────────────

    public static boolean isLoaded() {
        try {
            Class.forName("dev.ryanhcode.sable.companion.SableCompanion");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
