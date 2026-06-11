package com.rtbridge.scene.cache;

import java.util.HashMap;
import java.util.Map;

/**
 * DynamicGeometryCache — spec §3 (Dynamic Geometry).
 *
 * Covers: entities, block entities, Create contraptions, ship meshes.
 * Keyed by a stable long ID.  Ship mesh IDs use the Valkyrien Skies ship ID.
 */
public class DynamicGeometryCache {

    public enum DynamicType { ENTITY, BLOCK_ENTITY, SHIP, CREATE_CONTRAPTION, ANIMATED }

    public record DynamicMeshEntry(
        long        id,
        DynamicType type,
        long        gpuBufferHandle,
        int         vertexCount,
        int         blasHandle,      // -1 if not built yet
        boolean     needsBlasRebuild // set to true when geometry changes
    ) {}

    private final Map<Long, DynamicMeshEntry> meshes = new HashMap<>();

    public void put(long id, DynamicType type, long gpuHandle, int vertexCount) {
        meshes.put(id, new DynamicMeshEntry(id, type, gpuHandle, vertexCount, -1, true));
    }

    public void markBLASBuilt(long id, int blasHandle) {
        DynamicMeshEntry e = meshes.get(id);
        if (e != null) {
            meshes.put(id, new DynamicMeshEntry(e.id(), e.type(), e.gpuBufferHandle(),
                e.vertexCount(), blasHandle, false));
        }
    }

    public void markDirty(long id) {
        DynamicMeshEntry e = meshes.get(id);
        if (e != null) {
            meshes.put(id, new DynamicMeshEntry(e.id(), e.type(), e.gpuBufferHandle(),
                e.vertexCount(), e.blasHandle(), true));
        }
    }

    public void remove(long id) { meshes.remove(id); }

    public DynamicMeshEntry get(long id)          { return meshes.get(id); }
    public Iterable<DynamicMeshEntry> all()        { return meshes.values(); }
    public int size()                              { return meshes.size(); }

    public void copyFrom(DynamicGeometryCache src) {
        this.meshes.clear();
        this.meshes.putAll(src.meshes);
    }

    public void clear() { meshes.clear(); }
}
