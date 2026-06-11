package com.rtbridge.scene.cache;

import net.minecraft.util.math.ChunkPos;

import java.util.HashMap;
import java.util.Map;

/**
 * StaticGeometryCache — spec §3 (Static Geometry).
 *
 * Holds chunk mesh handles.  Each entry maps a ChunkPos to an opaque
 * GPU buffer handle (VkBuffer ID / GL VBO ID).  The actual vertex data
 * lives on the GPU; we only track the handle and dirty state here.
 */
public class StaticGeometryCache {

    public record ChunkMeshEntry(
        ChunkPos chunkPos,
        long     gpuBufferHandle,  // Vulkan VkBuffer or GL VBO id
        int      vertexCount,
        int      blasHandle,       // -1 = not yet built
        boolean  dirty
    ) {}

    private final Map<ChunkPos, ChunkMeshEntry> chunks = new HashMap<>();

    public void put(ChunkPos pos, long bufHandle, int vertexCount) {
        chunks.put(pos, new ChunkMeshEntry(pos, bufHandle, vertexCount, -1, true));
    }

    public void markBLASBuilt(ChunkPos pos, int blasHandle) {
        ChunkMeshEntry e = chunks.get(pos);
        if (e != null) {
            chunks.put(pos, new ChunkMeshEntry(e.chunkPos(), e.gpuBufferHandle(),
                e.vertexCount(), blasHandle, false));
        }
    }

    public void remove(ChunkPos pos) { chunks.remove(pos); }

    public ChunkMeshEntry get(ChunkPos pos) { return chunks.get(pos); }
    public Iterable<ChunkMeshEntry> all()   { return chunks.values(); }
    public int size()                        { return chunks.size(); }

    public void copyFrom(StaticGeometryCache src) {
        this.chunks.clear();
        this.chunks.putAll(src.chunks);
    }

    public void clear() { chunks.clear(); }
}
