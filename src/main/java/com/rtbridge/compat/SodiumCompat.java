package com.rtbridge.compat;

import com.rtbridge.RTBridgeMod;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.math.ChunkPos;

/**
 * SodiumCompat — Sodium / Voxy mesh pipeline integration.
 *
 * Sodium replaces Minecraft's chunk rendering with a highly optimised
 * VBO-based pipeline.  RTBridge needs to intercept chunk mesh uploads
 * so it can copy the vertex data into BLAS-ready GPU buffers.
 *
 * Approach:
 *   1. Mixin into Sodium's ChunkRenderRegion or SectionRenderDataStorage
 *      to capture the vertex buffer handle after Sodium has uploaded it.
 *   2. RTBridge keeps a reference to each chunk's Sodium VBO and builds
 *      the BLAS from it (avoids duplicating tessellation work).
 *
 * Voxy integration:
 *   Voxy provides LOD chunk meshes for distant terrain.  RTBridge can use
 *   Voxy's coarse LOD meshes for distant BLAS geometry, saving memory and
 *   build time on far chunks.
 *
 * All methods are stubs.  The real integration requires Mixin hooks.
 * See MixinSodiumChunkRegion (TODO).
 */
public class SodiumCompat {

    public static boolean isSodiumLoaded() {
        return FabricLoader.getInstance().isModLoaded("sodium");
    }

    public static boolean isVoxyLoaded() {
        return FabricLoader.getInstance().isModLoaded("voxy");
    }

    /**
     * Called by MixinSodiumChunkRegion after Sodium uploads a chunk's VBO.
     *
     * @param chunkPos        the chunk that was just uploaded
     * @param sodiumVBOHandle the OpenGL/Vulkan buffer handle Sodium used
     * @param vertexCount     number of vertices in the buffer
     */
    public static void onChunkMeshUploaded(ChunkPos chunkPos, long sodiumVBOHandle, int vertexCount) {
        if (!isSodiumLoaded()) return;

        RTBridgeMod.getDirtyEventSystem().post(
            com.rtbridge.event.DirtyEvent.of(com.rtbridge.event.DirtyEventType.CHUNK_REMESH)
                .chunk(chunkPos)
                .build()
        );

        // Also directly update the static geometry cache with the VBO handle
        // so AsyncBLASBuilder can reference the buffer without re-tessellating.
        // TODO: access SceneExtractor / Back buffer to store sodiumVBOHandle
        RTBridgeMod.LOGGER.debug("[SodiumCompat] Chunk mesh uploaded: {} verts={}", chunkPos, vertexCount);
    }

    /**
     * Called by MixinSodiumChunkRegion when Sodium frees a chunk's VBO.
     */
    public static void onChunkMeshFreed(ChunkPos chunkPos) {
        if (!isSodiumLoaded()) return;
        RTBridgeMod.getDirtyEventSystem().post(
            com.rtbridge.event.DirtyEvent.of(com.rtbridge.event.DirtyEventType.CHUNK_UNLOAD)
                .chunk(chunkPos)
                .build()
        );
    }

    public static void init() {
        if (isSodiumLoaded()) RTBridgeMod.LOGGER.info("[RTBridge] Sodium detected — using Sodium VBO handles for BLAS.");
        if (isVoxyLoaded())   RTBridgeMod.LOGGER.info("[RTBridge] Voxy detected — LOD meshes available for distant BLAS.");
    }
}
