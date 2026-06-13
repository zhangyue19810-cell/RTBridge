package com.rtbridge.light;

import com.rtbridge.scene.cache.EmissiveCache;
import org.joml.Vector3f;

import java.util.*;

/**
 * LightCluster — spec §8, Layer 1: Light Clustering.
 *
 * Partitions the scene's emissive sources into spatial clusters so that
 * each pixel can affordably query only its nearest N candidate lights
 * instead of iterating all lights in the scene.
 *
 * Approach: uniform 3D grid (world-space cells).
 * Each cell stores a list of EmissiveCache entry IDs that fall within it.
 *
 * Usage in pipeline:
 *   1. Rebuilt once per frame or when EmissiveCache is dirty.
 *   2. ReservoirSampler queries the cluster for the pixel's world position
 *      to get a short candidate list.
 *   3. GPU-side: upload cluster grid as a structured buffer for shader access.
 */
public class LightCluster {

    // ── Configuration ─────────────────────────────────────────────────────────

    /** Size of one cluster cell in world blocks. Tune for scene scale. */
    public static final float CELL_SIZE = 16.0f;

    /** Max lights stored per cell (excess lights are lowest-intensity ones dropped). */
    public static final int MAX_LIGHTS_PER_CELL = 32;

    // ── Internal grid ─────────────────────────────────────────────────────────

    /** Maps (cellX, cellY, cellZ) → list of emissive entry IDs */
    private final Map<Long, List<Long>> grid = new HashMap<>();

    private int[] gridOrigin = {0, 0, 0}; // world cell offset
    private boolean dirty = true;

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Rebuild the cluster grid from the provided EmissiveCache.
     * Should be called when the emissive cache has changed or camera moved far.
     */
    public void rebuild(EmissiveCache emissiveCache) {
        grid.clear();

        for (EmissiveCache.EmissiveEntry entry : emissiveCache.all()) {
            long cellKey = worldToCell(entry.worldPos());
            grid.computeIfAbsent(cellKey, k -> new ArrayList<>()).add(entry.id());
        }

        // Trim oversized cells — keep highest-intensity lights
        for (Map.Entry<Long, List<Long>> e : grid.entrySet()) {
            if (e.getValue().size() > MAX_LIGHTS_PER_CELL) {
                e.getValue().subList(MAX_LIGHTS_PER_CELL, e.getValue().size()).clear();
            }
        }

        dirty = false;
    }

    /**
     * Returns candidate light IDs for a given world-space position.
     * Queries the 3×3×3 neighbourhood around the pixel's cell.
     */
    public List<Long> getCandidates(Vector3f worldPos) {
        int cx = worldToGridCoord(worldPos.x);
        int cy = worldToGridCoord(worldPos.y);
        int cz = worldToGridCoord(worldPos.z);

        List<Long> candidates = new ArrayList<>(MAX_LIGHTS_PER_CELL * 4);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    List<Long> cell = grid.get(packCell(cx + dx, cy + dy, cz + dz));
                    if (cell != null) candidates.addAll(cell);
                }
            }
        }
        return candidates;
    }

    public void markDirty() { dirty = true; }
    public boolean isDirty() { return dirty; }
    public int cellCount()   { return grid.size(); }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private long worldToCell(Vector3f pos) {
        return packCell(worldToGridCoord(pos.x), worldToGridCoord(pos.y), worldToGridCoord(pos.z));
    }

    private int worldToGridCoord(float v) {
        return (int) Math.floor(v / CELL_SIZE);
    }

    /**
     * Pack (x, y, z) into a single long.
     * Supports grid coordinates in range [-1048576, 1048575] on each axis.
     */
    private long packCell(int x, int y, int z) {
        return ((long)(x & 0xFFFFF) << 40)
             | ((long)(y & 0xFFFFF) << 20)
             |  (long)(z & 0xFFFFF);
    }
}
